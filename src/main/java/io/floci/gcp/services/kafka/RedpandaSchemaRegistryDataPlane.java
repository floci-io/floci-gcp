package io.floci.gcp.services.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.GcpResourceNames;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerDetector;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.gcp.core.common.docker.ContainerStorageHelper;
import io.floci.gcp.services.kafka.model.StoredSchemaRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves each schema registry from its own Redpanda container, whose built-in Schema Registry
 * listener speaks the Confluent API. Compatibility checks, schema validation and normalization
 * are Redpanda's. A registry is not tied to a Kafka cluster in GCP, so it gets its own broker:
 * Redpanda keeps the schemas in that broker's {@code _schemas} topic, on the registry's volume.
 *
 * <p>The container is started on create and again on first use after floci restarts.
 */
@ApplicationScoped
public class RedpandaSchemaRegistryDataPlane implements SchemaRegistryDataPlane {

    private static final Logger LOG = Logger.getLogger(RedpandaSchemaRegistryDataPlane.class);
    static final String SERVICE = "kafka-schema-registry";
    private static final int SCHEMA_REGISTRY_PORT = 8081;
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(90);
    private static final String CONTENT_TYPE = "application/vnd.schemaregistry.v1+json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    /** Running containers by registry name. */
    private final Map<String, Running> running = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    private record Running(String containerId, String baseUrl) {}

    @Inject
    public RedpandaSchemaRegistryDataPlane(ContainerBuilder containerBuilder,
                                           ContainerLifecycleManager lifecycleManager,
                                           ContainerDetector containerDetector,
                                           EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.config = config;
    }

    // ── Container lifecycle ───────────────────────────────────────────────────

    @Override
    public void ensureRunning(StoredSchemaRegistry registry) {
        running(registry);
    }

    @Override
    public void stop(StoredSchemaRegistry registry) {
        synchronized (lock(registry)) {
            Running r = running.remove(registry.getName());
            if (r != null) {
                lifecycleManager.stopAndRemove(r.containerId(), null);
                LOG.infof("Redpanda container for schema registry %s stopped", registry.getName());
            }
        }
    }

    @Override
    public void delete(StoredSchemaRegistry registry) {
        synchronized (lock(registry)) {
            stop(registry);
            lifecycleManager.removeIfExists(containerName(registry));
            ContainerStorageHelper.removeStorage(config, lifecycleManager, registry.getVolumeName());
        }
    }

    private Running running(StoredSchemaRegistry registry) {
        Running r = running.get(registry.getName());
        if (r != null) {
            return r;
        }
        synchronized (lock(registry)) {
            r = running.get(registry.getName());
            if (r == null) {
                r = start(registry);
                running.put(registry.getName(), r);
            }
            return r;
        }
    }

    private Running start(StoredSchemaRegistry registry) {
        String image = config.services().kafka().defaultImage();
        String containerName = containerName(registry);
        LOG.infof("Starting Redpanda for schema registry %s using image %s", registry.getName(), image);
        lifecycleManager.removeIfExists(containerName);

        ContainerBuilder.Builder spec = containerBuilder.newContainer(image)
                .withName(containerName)
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(SERVICE, registry.registryId(),
                        GcpResourceNames.parseProject(registry.getName()),
                        GcpResourceNames.parseLocation(registry.getName())))
                .withLogRotation()
                .withDockerNetwork(config.services().kafka().dockerNetwork())
                .withCmd(List.of("redpanda", "start", "--overprovisioned", "--smp", "1",
                        "--memory", "512M", "--reserve-memory", "0M",
                        "--schema-registry-addr", "0.0.0.0:" + SCHEMA_REGISTRY_PORT));
        if (containerDetector.isRunningInContainer()) {
            spec.withExposedPort(SCHEMA_REGISTRY_PORT);
        } else {
            // The schema registry API is unauthenticated and only the emulator should drive it, so
            // the published port listens on loopback, which is where the emulator reaches it.
            spec.withLoopbackDynamicPort(SCHEMA_REGISTRY_PORT);
        }
        if (ContainerStorageHelper.isNamedVolumeMode(config)) {
            ContainerStorageHelper.applyStorage(spec, lifecycleManager, registry.getVolumeName(),
                    "/var/lib/redpanda/data");
        } else {
            String hostPath = ContainerStorageHelper.hostResourcePath(config, SERVICE, registry.getVolumeName())
                    .toAbsolutePath().toString();
            ContainerStorageHelper.ensureHostDir(hostPath);
            spec.withBind(hostPath, "/var/lib/redpanda/data");
        }

        ContainerInfo info = lifecycleManager.createAndStart(spec.build());
        try {
            String baseUrl = "http://" + info.getEndpoint(SCHEMA_REGISTRY_PORT);
            awaitReady(registry, baseUrl);
            return new Running(info.containerId(), baseUrl);
        } catch (RuntimeException e) {
            // Not tracked in running yet, so stop() would never reach it. The volume keeps the schemas.
            lifecycleManager.stopAndRemove(info.containerId(), null);
            throw e;
        }
    }

    private void awaitReady(StoredSchemaRegistry registry, String baseUrl) {
        long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/subjects"))
                        .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    LOG.infof("Schema registry %s is ready at %s", registry.getName(), baseUrl);
                    return;
                }
            } catch (IOException ignored) {
                // Redpanda is not listening yet; polled again until the deadline.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw GcpException.unavailable("Schema registry " + registry.getName() + " did not become ready");
    }

    private Object lock(StoredSchemaRegistry registry) {
        return locks.computeIfAbsent(registry.getName(), k -> new Object());
    }

    private String containerName(StoredSchemaRegistry registry) {
        return ContainerStorageHelper.dockerName(config,
                "kafka-sr-" + registry.registryId() + "-" + registry.getVolumeId());
    }

    // ── Confluent API ─────────────────────────────────────────────────────────

    @Override
    public JsonNode listContexts(StoredSchemaRegistry registry) {
        return send(new Scope(registry, null), "GET", "/contexts", List.of(), null);
    }

    @Override
    public JsonNode listSubjects(Scope scope, String subjectPrefix, boolean deleted) {
        List<String> query = new ArrayList<>();
        if (subjectPrefix != null) {
            query.add("subjectPrefix=" + encode(subjectPrefix));
        }
        query.add("deleted=" + deleted);
        return send(scope, "GET", "/subjects", query, null);
    }

    @Override
    public JsonNode deleteSubject(Scope scope, String subject, boolean permanent) {
        return send(scope, "DELETE", subjectPath(subject), List.of("permanent=" + permanent), null);
    }

    @Override
    public JsonNode lookupVersion(Scope scope, String subject, ObjectNode schema, boolean normalize,
            boolean deleted) {
        return send(scope, "POST", subjectPath(subject), List.of("normalize=" + normalize, "deleted=" + deleted),
                schema);
    }

    @Override
    public JsonNode getVersion(Scope scope, String subject, String version, boolean deleted) {
        return send(scope, "GET", versionPath(subject, version), List.of("deleted=" + deleted), null);
    }

    @Override
    public String getRawSchemaVersion(Scope scope, String subject, String version, boolean deleted) {
        return exchange(scope, "GET", versionPath(subject, version) + "/schema", List.of("deleted=" + deleted), null)
                .body();
    }

    @Override
    public JsonNode listVersions(Scope scope, String subject, boolean deleted) {
        return send(scope, "GET", subjectPath(subject) + "/versions", List.of("deleted=" + deleted), null);
    }

    @Override
    public JsonNode createVersion(Scope scope, String subject, ObjectNode schema, boolean normalize) {
        return send(scope, "POST", subjectPath(subject) + "/versions", List.of("normalize=" + normalize), schema);
    }

    @Override
    public JsonNode deleteVersion(Scope scope, String subject, String version, boolean permanent) {
        return send(scope, "DELETE", versionPath(subject, version), List.of("permanent=" + permanent), null);
    }

    @Override
    public JsonNode listReferencedSchemas(Scope scope, String subject, String version) {
        return send(scope, "GET", versionPath(subject, version) + "/referencedby", List.of(), null);
    }

    @Override
    public JsonNode getSchema(Scope scope, int id, String subject) {
        return send(scope, "GET", "/schemas/ids/" + id, subjectQuery(subject), null);
    }

    @Override
    public String getRawSchema(Scope scope, int id, String subject) {
        return exchange(scope, "GET", "/schemas/ids/" + id + "/schema", subjectQuery(subject), null).body();
    }

    @Override
    public JsonNode listSchemaVersions(Scope scope, int id, String subject, boolean deleted) {
        List<String> query = new ArrayList<>(subjectQuery(subject));
        query.add("deleted=" + deleted);
        return send(scope, "GET", "/schemas/ids/" + id + "/versions", query, null);
    }

    @Override
    public JsonNode listSubjectsBySchemaId(Scope scope, int id, String subject, boolean deleted) {
        List<String> query = new ArrayList<>(subjectQuery(subject));
        query.add("deleted=" + deleted);
        return send(scope, "GET", "/schemas/ids/" + id + "/subjects", query, null);
    }

    @Override
    public JsonNode listSchemaTypes(Scope scope) {
        return send(scope, "GET", "/schemas/types", List.of(), null);
    }

    @Override
    public JsonNode checkCompatibility(Scope scope, String subject, String version, ObjectNode schema,
            boolean verbose) {
        return send(scope, "POST", "/compatibility" + versionPath(subject, version), List.of("verbose=" + verbose),
                schema);
    }

    @Override
    public JsonNode getConfig(Scope scope, String subject, boolean defaultToGlobal) {
        if (subject == null) {
            return send(scope, "GET", "/config", List.of(), null);
        }
        return send(scope, "GET", "/config/" + encode(subject), List.of("defaultToGlobal=" + defaultToGlobal), null);
    }

    @Override
    public JsonNode updateConfig(Scope scope, String subject, String compatibility) {
        return send(scope, "PUT", subject == null ? "/config" : "/config/" + encode(subject), List.of(),
                MAPPER.createObjectNode().put("compatibility", compatibility));
    }

    @Override
    public JsonNode deleteConfig(Scope scope, String subject) {
        return send(scope, "DELETE", "/config/" + encode(subject), List.of(), null);
    }

    @Override
    public JsonNode getMode(Scope scope, String subject) {
        return send(scope, "GET", subject == null ? "/mode" : "/mode/" + encode(subject), List.of(), null);
    }

    @Override
    public JsonNode updateMode(Scope scope, String subject, String mode) {
        return send(scope, "PUT", subject == null ? "/mode" : "/mode/" + encode(subject), List.of(),
                MAPPER.createObjectNode().put("mode", mode));
    }

    @Override
    public JsonNode deleteMode(Scope scope, String subject) {
        return send(scope, "DELETE", "/mode/" + encode(subject), List.of(), null);
    }

    private static String subjectPath(String subject) {
        return "/subjects/" + encode(subject);
    }

    private static String versionPath(String subject, String version) {
        return subjectPath(subject) + "/versions/" + encode(version);
    }

    private static List<String> subjectQuery(String subject) {
        return subject != null ? List.of("subject=" + encode(subject)) : List.of();
    }

    /**
     * Redpanda serves a context's API under {@code /contexts/{context}}; the default context
     * ({@code null} or {@code "."}) is the root.
     */
    private static String contextPrefix(Scope scope) {
        String context = scope.context();
        return context == null || context.equals(".") ? "" : "/contexts/" + encode(context);
    }

    private JsonNode send(Scope scope, String method, String path, List<String> query, JsonNode body) {
        HttpResponse<String> response = exchange(scope, method, path, query, body);
        JsonNode json = parse(scope.registry(), method, path, response);
        return json != null ? json : MAPPER.nullNode();
    }

    /** Sends the request and returns a successful response; a Confluent error is thrown. */
    private HttpResponse<String> exchange(Scope scope, String method, String path, List<String> query,
            JsonNode body) {
        StoredSchemaRegistry registry = scope.registry();
        Running target = running(registry);
        String url = target.baseUrl() + contextPrefix(scope) + path
                + (query.isEmpty() ? "" : "?" + String.join("&", query));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", CONTENT_TYPE);
        if (body != null) {
            request.header("Content-Type", CONTENT_TYPE)
                    .method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            forgetIfStopped(registry, target);
            throw GcpException.unavailable("Schema registry " + registry.getName() + " is not reachable: "
                    + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw GcpException.unavailable("Interrupted calling schema registry " + registry.getName());
        }
        if (response.statusCode() >= 400) {
            JsonNode json = parse(registry, method, path, response);
            int errorCode = json != null ? json.path("error_code").asInt(response.statusCode()) : response.statusCode();
            String message = json != null && json.hasNonNull("message") ? json.get("message").asText()
                    : "Schema registry returned HTTP " + response.statusCode();
            throw new SchemaRegistryException(response.statusCode(), errorCode, message);
        }
        return response;
    }

    /**
     * Stops tracking the container only if it is no longer running, so the next call starts a
     * new one. A request that failed against a running container (a timeout, say) leaves it in
     * place for the requests still using it.
     */
    private void forgetIfStopped(StoredSchemaRegistry registry, Running target) {
        if (!lifecycleManager.isContainerRunning(target.containerId())) {
            LOG.warnf("Redpanda container for schema registry %s is no longer running", registry.getName());
            running.remove(registry.getName(), target);
        }
    }

    private static JsonNode parse(StoredSchemaRegistry registry, String method, String path,
            HttpResponse<String> response) {
        String body = response.body();
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            LOG.warnf("Schema registry %s answered %s %s with HTTP %s and a body that is not JSON: %s",
                    registry.getName(), method, path, response.statusCode(),
                    body.length() > 200 ? body.substring(0, 200) + "..." : body);
            return null;
        }
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
