package io.floci.gcp.core.common;

import com.google.api.AnnotationsProto;
import com.google.api.HttpRule;
import com.google.api.ResourceDescriptor;
import com.google.api.ResourceProto;
import com.google.api.ResourceReference;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import io.floci.gcp.services.iam.authorization.IamAuthorizationAdapter;
import io.floci.gcp.services.iam.authorization.IamAuthorizationRegistry;
import io.grpc.BindableService;
import io.grpc.protobuf.ProtoServiceDescriptorSupplier;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every registered service carries the tenancy touchpoints the gates rely on, or an exemption below
 * with a reason. Forgetting one does not fail any request test: the service is silently ungated
 * ({@link ServiceUsageGate#gates} needs an API name, {@code GrpcServerManager.bind} and
 * {@link ServiceEnabledFilter} look classes up in the registry), gates the default project instead
 * of the caller's, or skips strict location checks.
 */
@QuarkusTest
class ServiceRegistryConsistencyTest {

    private static final java.nio.file.Path CLASSES = java.nio.file.Path.of("target/classes");
    private static final java.nio.file.Path SOURCES = java.nio.file.Path.of("src/main/java");
    private static final String ROOT_PACKAGE = "io.floci.gcp";

    /** Classes that serve requests but belong to no descriptor, so no Service Usage gate or enabled flag applies. */
    private static final Map<String, String> UNREGISTERED_ENDPOINTS = Map.ofEntries(
            Map.entry("EmulatorInfoController", "emulator control endpoints under /_floci-gcp, not a Google API"),
            Map.entry("GkeTokenWebhookController", "TokenReview webhook called by the k3s API server, not by GCP clients"),
            Map.entry("HealthController", "emulator health probe, not a Google API"),
            Map.entry("IamPolicyGrpcController", "google.iam.v1.IAMPolicy mixin shared by every API, so it names no single API"),
            Map.entry("LocationsGrpcController", "google.cloud.location.Locations mixin shared by every regional API"),
            Map.entry("LocationsV1GetController", "Locations mixin; the API is only known from the request host"),
            Map.entry("LocationsV1ListController", "Locations mixin; the API is only known from the request host"),
            Map.entry("LocationsV2GetController", "Locations mixin; the API is only known from the request host"),
            Map.entry("LocationsV2ListController", "Locations mixin; the API is only known from the request host"),
            Map.entry("LongRunningOperationsController",
                    "google.longrunning.Operations under /v2 shared by Cloud Run, Cloud Functions and Eventarc; "
                            + "the API is only known from the request host"),
            Map.entry("OAuthTokenController", "OAuth 2.0 token endpoint, which Service Usage does not gate"));

    /** Descriptors registered without an API name. Their requests are never gated. */
    private static final Map<String, String> NO_API_NAME = Map.of();

    /**
     * gRPC services, on a gated descriptor with the generic usage adapter, that the generic resolution does
     * not cover: a method whose request names no project in a field the gate reads.
     */
    private static final Map<String, String> GRPC_PROJECT_RESOLUTION = Map.of(
            "BigQueryReadController", "bound with bindForApi under bigquerystorage.googleapis.com and its own adapter",
            "BigQueryWriteController", "bound with bindForApi under bigquerystorage.googleapis.com and its own adapter");

    /** Descriptors that serve location-scoped resources without a strict location check. */
    private static final Map<String, String> NO_LOCATION_CHECK = Map.of(
            "logging", "serves log entries and sinks only; the location-scoped buckets, views and links are not implemented",
            "secretmanager", "rejects regional secrets (projects/*/locations/*) as UNIMPLEMENTED, so no location is accepted");

    private static final Set<String> TARGET_FIELDS = Set.of("name", "parent", "resource");
    private static final Set<String> PROJECT_FIELDS = Set.of("project", "project_id");
    private static final Set<String> COMMON_DOMAINS =
            Set.of("cloudresourcemanager.googleapis.com", "locations.googleapis.com");
    private static final int MAX_DEPTH = 4;
    private static final Pattern ROUTED_PROJECT = Pattern.compile("\\{[a-z_.]+=projects/");
    private static final Pattern REQUIRES_LOCATION = Pattern.compile("\\.require(List|Parent)?Location\\(");

    /** gRPC services written against a hand-built descriptor, because their stubs are not on the classpath. */
    private static final Map<String, com.google.protobuf.Descriptors.ServiceDescriptor> LOCAL_SERVICES = Map.of(
            "DatastoreController", com.google.datastore.v1.DatastoreProto.getDescriptor()
                    .findServiceByName("Datastore"),
            "LocationsGrpcController", com.google.cloud.location.LocationsProto.getDescriptor()
                    .findServiceByName("Locations"));

    @Inject
    ServiceRegistry registry;

    @Inject
    IamAuthorizationRegistry iamAuthorization;

    @Test
    void everyDescriptorNamesItsApi() {
        Set<String> missing = new TreeSet<>();
        for (ServiceDescriptor descriptor : descriptors()) {
            if (blank(descriptor.apiName()) || blank(descriptor.apiTitle())) {
                missing.add(descriptor.name());
            }
        }
        missing.removeAll(NO_API_NAME.keySet());

        assertTrue(missing.isEmpty(), "Descriptors with no .api(name, title), so Service Usage never gates them: "
                + missing + ". Add .api(\"<svc>.googleapis.com\", \"<API title>\") to the descriptor, "
                + "or an entry with a reason to NO_API_NAME.");
    }

    @Test
    void everyGrpcServiceBelongsToADescriptor() {
        Set<String> missing = new TreeSet<>();
        for (Class<?> service : grpcServices()) {
            if (registry.byResourceClass(service).isEmpty()) {
                missing.add(service.getSimpleName());
            }
        }
        missing.removeAll(UNREGISTERED_ENDPOINTS.keySet());

        assertTrue(missing.isEmpty(), "gRPC services in no descriptor's resourceClasses, so GrpcServerManager.bind "
                + "binds them ungated: " + missing + ". Add them to .resourceClasses(...), "
                + "or an entry with a reason to UNREGISTERED_ENDPOINTS.");
    }

    @Test
    void everyRestResourceBelongsToADescriptor() {
        Set<String> missing = new TreeSet<>();
        for (Class<?> resource : restResources()) {
            if (registry.byApiResourceClass(resource).isEmpty()) {
                missing.add(resource.getSimpleName());
            }
        }
        missing.removeAll(UNREGISTERED_ENDPOINTS.keySet());

        assertTrue(missing.isEmpty(), "REST resources in no descriptor, so neither the enabled flag nor Service Usage "
                + "applies: " + missing + ". Add them to .resourceClasses(...) (or to .api(name, title, extra...) "
                + "for API-only resources), or an entry with a reason to UNREGISTERED_ENDPOINTS.");
    }

    @Test
    void everyGatedGrpcMethodNamesItsProject() {
        Set<String> missing = new TreeSet<>();
        for (ServiceDescriptor descriptor : gatedWithGenericAdapter()) {
            for (Class<?> service : descriptor.resourceClasses()) {
                if (!BindableService.class.isAssignableFrom(service)
                        || GRPC_PROJECT_RESOLUTION.containsKey(service.getSimpleName())) {
                    continue;
                }
                Set<String> domains = Set.of(descriptor.apiName());
                for (MethodDescriptor method : protoService(service).getMethods()) {
                    if (!routesProject(method) && !namesProject(method.getInputType(), domains, true, MAX_DEPTH)) {
                        missing.add(service.getSimpleName() + "." + method.getName());
                    }
                }
            }
        }

        assertTrue(missing.isEmpty(), "gRPC methods of gated services whose request names no project in a field "
                + "the generic resolution reads, so the default project is gated instead of the caller's: " + missing
                + ". Register a ServiceUsageAdapter on the descriptor (.usageAdapter(...)), "
                + "or an entry with a reason to GRPC_PROJECT_RESOLUTION.");
    }

    @Test
    void everyLocationScopedServiceChecksLocations() {
        Set<String> missing = new TreeSet<>();
        for (ServiceDescriptor descriptor : descriptors()) {
            if (servesLocations(descriptor) && !callsLocationCatalog(descriptor)) {
                missing.add(descriptor.name());
            }
        }
        missing.removeAll(NO_LOCATION_CHECK.keySet());

        assertTrue(missing.isEmpty(), "Descriptors that serve projects/*/locations/* resources but whose package never "
                + "calls LocationCatalog.requireLocation, requireListLocation or requireParentLocation, so "
                + "floci-gcp.locations.strict is not applied: " + missing
                + ". Inject LocationCatalog and check the location, or an entry with a reason to NO_LOCATION_CHECK.");
    }

    @Test
    void everyIamAdapterMapsRegisteredEndpoints() {
        Set<String> grpcNames = new HashSet<>();
        for (Class<?> service : grpcServices()) {
            grpcNames.add(protoService(service).getFullName());
        }
        Set<String> unknown = new TreeSet<>();
        for (IamAuthorizationAdapter adapter : iamAuthorization.adapters()) {
            for (Class<?> controller : adapter.restControllers()) {
                if (registry.byApiResourceClass(controller).isEmpty()) {
                    unknown.add(adapter.serviceName() + " REST " + controller.getSimpleName());
                }
            }
            for (String grpcService : adapter.grpcServices()) {
                if (!grpcNames.contains(grpcService)) {
                    unknown.add(adapter.serviceName() + " gRPC " + grpcService);
                }
            }
        }

        assertTrue(unknown.isEmpty(), "IAM adapters map endpoints that no descriptor registers or no controller "
                + "serves, so their requests are never authorized: " + unknown);
    }

    @Test
    void exemptionsAreNotStale() {
        Set<String> stale = new TreeSet<>();
        Set<String> endpoints = new HashSet<>();
        grpcServices().forEach(c -> endpoints.add(c.getSimpleName()));
        restResources().forEach(c -> endpoints.add(c.getSimpleName()));
        Set<String> registered = new HashSet<>();
        for (Class<?> c : allClasses()) {
            if (registry.byApiResourceClass(c).isPresent()) {
                registered.add(c.getSimpleName());
            }
        }
        for (String name : UNREGISTERED_ENDPOINTS.keySet()) {
            if (!endpoints.contains(name) || registered.contains(name)) {
                stale.add("UNREGISTERED_ENDPOINTS " + name);
            }
        }
        for (String name : NO_API_NAME.keySet()) {
            if (descriptors().stream().noneMatch(d -> d.name().equals(name) && blank(d.apiName()))) {
                stale.add("NO_API_NAME " + name);
            }
        }
        for (String name : NO_LOCATION_CHECK.keySet()) {
            if (descriptors().stream().noneMatch(d -> d.name().equals(name)
                    && servesLocations(d) && !callsLocationCatalog(d))) {
                stale.add("NO_LOCATION_CHECK " + name);
            }
        }
        for (String name : GRPC_PROJECT_RESOLUTION.keySet()) {
            if (!endpoints.contains(name)) {
                stale.add("GRPC_PROJECT_RESOLUTION " + name);
            }
        }

        assertTrue(stale.isEmpty(), "Exemptions that no longer apply; remove them: " + stale);
    }

    private List<ServiceDescriptor> descriptors() {
        List<ServiceDescriptor> descriptors = registry.descriptors();
        assertTrue(descriptors.size() > 20, "the registry holds almost no descriptors: " + descriptors.size());
        return descriptors;
    }

    private List<ServiceDescriptor> gatedWithGenericAdapter() {
        return descriptors().stream()
                .filter(d -> !blank(d.apiName()) && !ServiceUsageGate.EXEMPT_APIS.contains(d.apiName()))
                .filter(d -> d.usageAdapter() == ServiceUsageAdapter.GENERIC)
                .toList();
    }

    /**
     * Whether the method's HTTP rule binds a request field to a {@code projects/...} path segment. Google's SDKs
     * send that field in the {@code x-goog-request-params} header, which the gate reads first.
     */
    private static boolean routesProject(MethodDescriptor method) {
        if (!method.getOptions().hasExtension(AnnotationsProto.http)) {
            return false;
        }
        HttpRule rule = method.getOptions().getExtension(AnnotationsProto.http);
        List<HttpRule> rules = new ArrayList<>(List.of(rule));
        rules.addAll(rule.getAdditionalBindingsList());
        for (HttpRule binding : rules) {
            String path = switch (binding.getPatternCase()) {
                case GET -> binding.getGet();
                case PUT -> binding.getPut();
                case POST -> binding.getPost();
                case DELETE -> binding.getDelete();
                case PATCH -> binding.getPatch();
                case CUSTOM -> binding.getCustom().getPath();
                default -> "";
            };
            if (ROUTED_PROJECT.matcher(path).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Mirrors {@link ProtoResourceFields} and {@link ServiceUsageGrpcInterceptor}: an annotated resource name
     * of this API (or a common one), the request's own name, parent or resource, a project or project_id
     * field, or a repeated string on the request.
     */
    private static boolean namesProject(Descriptor message, Set<String> domains, boolean top, int depth) {
        ResourceDescriptor resource = message.getOptions().hasExtension(ResourceProto.resource)
                ? message.getOptions().getExtension(ResourceProto.resource) : null;
        String nameField = resource == null ? null
                : resource.getNameField().isEmpty() ? "name" : resource.getNameField();
        for (FieldDescriptor field : message.getFields()) {
            if (field.isMapField()) {
                continue;
            }
            if (field.getJavaType() == FieldDescriptor.JavaType.STRING) {
                String type = null;
                if (field.getOptions().hasExtension(ResourceProto.resourceReference)) {
                    ResourceReference reference = field.getOptions().getExtension(ResourceProto.resourceReference);
                    type = reference.getType().isEmpty() ? reference.getChildType() : reference.getType();
                } else if (field.getName().equals(nameField)) {
                    type = resource.getType();
                }
                if (type != null) {
                    if ((top && TARGET_FIELDS.contains(field.getName())) || gatesOn(type, domains)) {
                        return true;
                    }
                } else if (top && (TARGET_FIELDS.contains(field.getName())
                        || PROJECT_FIELDS.contains(field.getName()) || field.isRepeated())) {
                    return true;
                }
            } else if (field.getJavaType() == FieldDescriptor.JavaType.MESSAGE && depth > 1
                    && namesProject(field.getMessageType(), domains, false, depth - 1)) {
                return true;
            }
        }
        return false;
    }

    private static boolean gatesOn(String type, Set<String> domains) {
        if (type.equals("*")) {
            return true;
        }
        String domain = type.substring(0, Math.max(0, type.indexOf('/')));
        return COMMON_DOMAINS.contains(domain) || domains.contains(domain);
    }

    private static boolean servesLocations(ServiceDescriptor descriptor) {
        Set<Class<?>> classes = new LinkedHashSet<>(descriptor.resourceClasses());
        classes.addAll(descriptor.apiResourceClasses());
        for (Class<?> c : classes) {
            if (BindableService.class.isAssignableFrom(c)) {
                if (declaresLocationResources(protoService(c).getFile(), descriptor.apiName())) {
                    return true;
                }
            } else if (restPaths(c).stream().anyMatch(p -> p.contains("/locations/{"))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> restPaths(Class<?> resource) {
        Path classPath = resource.getAnnotation(Path.class);
        String base = classPath == null ? "" : classPath.value();
        List<String> paths = new ArrayList<>(List.of(base));
        for (Method method : resource.getDeclaredMethods()) {
            Path methodPath = method.getAnnotation(Path.class);
            if (methodPath != null) {
                paths.add(base + (methodPath.value().startsWith("/") ? "" : "/") + methodPath.value());
            }
        }
        return paths;
    }

    /** Whether the service's proto package declares a resource of its own API named under projects/*&#47;locations/*. */
    private static boolean declaresLocationResources(FileDescriptor serviceFile, String apiName) {
        Deque<FileDescriptor> pending = new ArrayDeque<>(List.of(serviceFile));
        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            FileDescriptor file = pending.pop();
            if (!seen.add(file.getFullName())) {
                continue;
            }
            for (ResourceDescriptor definition : file.getOptions().getExtension(ResourceProto.resourceDefinition)) {
                if (locational(definition, apiName)) {
                    return true;
                }
            }
            Deque<Descriptor> messages = new ArrayDeque<>(file.getMessageTypes());
            while (!messages.isEmpty()) {
                Descriptor message = messages.pop();
                if (message.getOptions().hasExtension(ResourceProto.resource)
                        && locational(message.getOptions().getExtension(ResourceProto.resource), apiName)) {
                    return true;
                }
                messages.addAll(message.getNestedTypes());
            }
            for (FileDescriptor dependency : file.getDependencies()) {
                if (dependency.getPackage().equals(serviceFile.getPackage())) {
                    pending.push(dependency);
                }
            }
        }
        return false;
    }

    private static boolean locational(ResourceDescriptor resource, String apiName) {
        return resource.getType().startsWith(apiName + "/") && resource.getPatternList().stream().anyMatch(p -> p.contains("/locations/{"));
    }

    private static boolean callsLocationCatalog(ServiceDescriptor descriptor) {
        Set<String> packages = new TreeSet<>();
        descriptor.resourceClasses().forEach(c -> packages.add(c.getPackageName()));
        descriptor.apiResourceClasses().forEach(c -> packages.add(c.getPackageName()));
        for (String pkg : packages) {
            java.nio.file.Path dir = SOURCES.resolve(pkg.replace('.', '/'));
            try (Stream<java.nio.file.Path> files = Files.walk(dir)) {
                for (java.nio.file.Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    if (REQUIRES_LOCATION.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
                        return true;
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return false;
    }

    private static com.google.protobuf.Descriptors.ServiceDescriptor protoService(Class<?> service) {
        for (Class<?> c = service.getSuperclass(); c != null; c = c.getSuperclass()) {
            Class<?> stubs = c.getEnclosingClass();
            if (stubs == null) {
                continue;
            }
            try {
                io.grpc.ServiceDescriptor grpc = (io.grpc.ServiceDescriptor)
                        stubs.getMethod("getServiceDescriptor").invoke(null);
                if (grpc.getSchemaDescriptor() instanceof ProtoServiceDescriptorSupplier schema) {
                    return schema.getServiceDescriptor();
                }
            } catch (NoSuchMethodException e) {
                // Not a generated *Grpc holder; keep walking up.
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return Optional.ofNullable(LOCAL_SERVICES.get(service.getSimpleName()))
                .orElseThrow(() -> new AssertionError("No proto service found for " + service.getName()));
    }

    private static List<Class<?>> grpcServices() {
        return allClasses().stream()
                .filter(BindableService.class::isAssignableFrom)
                .filter(c -> !c.isInterface() && !Modifier.isAbstract(c.getModifiers()))
                .toList();
    }

    private static List<Class<?>> restResources() {
        return allClasses().stream().filter(c -> c.isAnnotationPresent(Path.class)).toList();
    }

    private static List<Class<?>> allClasses() {
        List<Class<?>> classes = new ArrayList<>();
        java.nio.file.Path root = CLASSES.resolve(ROOT_PACKAGE.replace('.', '/'));
        ClassLoader loader = ServiceRegistryConsistencyTest.class.getClassLoader();
        try (Stream<java.nio.file.Path> files = Files.walk(root)) {
            for (java.nio.file.Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = CLASSES.relativize(file).toString().replace('/', '.').replace(".class", "");
                if (name.contains("$")) {
                    continue;
                }
                try {
                    classes.add(Class.forName(name, false, loader));
                } catch (ClassNotFoundException | LinkageError e) {
                    // A class that cannot load cannot serve requests either.
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertTrue(classes.size() > 200, "the class scan found almost no classes; is " + CLASSES + " the right path?");
        return classes;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
