package io.floci.gcp.services.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.LocationCatalog;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ProjectNumbers;
import io.floci.gcp.core.common.ProjectRecord;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The IAM Workload Identity Pools API ({@code projects.locations.workloadIdentityPools} and
 * {@code ...providers}).
 *
 * <p>Pools and providers are soft-deleted with a 30-day {@code expireTime}, as GCP does. The
 * emulator does not permanently purge them; a soft-deleted resource stays visible through
 * {@code get} and through {@code list} with {@code showDeleted=true}. Operations are synchronous
 * and use the same empty {@code response} GCP returns; their names are nested under the pool or
 * provider, which is why they have dedicated routes rather than the shared
 * {@code locations/{location}/operations} route.</p>
 */
@ApplicationScoped
public class IamWorkloadIdentityPoolService {

    static final String POOL_ID_RULE =
            "Invalid WorkloadIdentityPool ID. It can only consist of digits [0-9], lowercase "
                    + "characters [a-z], dashes [-], and must be between 4 and 32 characters in length.";
    static final String POOL_ID_RESERVED =
            "Invalid WorkloadIdentityPool ID. The \"gcp-\" prefix is reserved for use by Google.";
    static final String PROVIDER_ID_RULE =
            "Invalid WorkloadIdentityPoolProvider ID. It can only consist of digits [0-9], lowercase "
                    + "characters [a-z], dashes [-], and must be between 4 and 32 characters in length.";
    static final String PROVIDER_ID_RESERVED =
            "Invalid WorkloadIdentityPoolProvider ID. The \"gcp-\" prefix is reserved for use by Google.";
    static final String ENTITY_NOT_FOUND = "Requested entity was not found.";
    static final String ENTITY_ALREADY_EXISTS = "Requested entity already exists";
    static final String PARENT_INVALID = "The parent resource is invalid.";
    static final String UPDATE_MASK_REQUIRED = "A FieldMask with at least one path must be specified.";
    static final String OIDC_MAPPINGS_REQUIRED = "Attribute mappings are required for OIDC providers.";
    static final String SUBJECT_MAPPING_REQUIRED =
            "Must specify at minimum the following attribute mappings: [google.subject]";
    static final String IMMUTABLE_FIELD = "Attempted to update an immutable field.";
    static final String AWS_ACCOUNT_INVALID =
            "Invalid AWS Account ID. The AWS account ID is a 12-digit number.";
    static final String POOL_NOT_DELETED =
            "Failed to undelete WorkloadIdentityPool. The WorkloadIdentityPool is not in a deleted state.";
    static final String PROVIDER_NOT_DELETED =
            "Failed to undelete WorkloadIdentityPoolProvider. "
                    + "The WorkloadIdentityPoolProvider is not in a deleted state.";

    private static final Pattern ID = Pattern.compile("[a-z0-9-]{4,32}");
    private static final int LOCK_COUNT = 64;
    private static final Object[] LOCKS = locks();

    private final StorageBackend<String, String> pools;
    private final StorageBackend<String, String> providers;
    private final StorageBackend<String, String> operations;
    private final ObjectMapper mapper;
    private final Instance<IamService> iamService;
    private final ProjectRegistry projects;
    private final LocationCatalog locations;

    @Inject
    public IamWorkloadIdentityPoolService(StorageFactory storageFactory, ObjectMapper mapper,
            Instance<IamService> iamService, ProjectRegistry projects, LocationCatalog locations) {
        this(storageFactory.createGlobal("iam-workload-identity-pools", "iam-workload-identity-pools.json",
                        new TypeReference<Map<String, String>>() {}),
                storageFactory.createGlobal("iam-workload-identity-providers", "iam-workload-identity-providers.json",
                        new TypeReference<Map<String, String>>() {}),
                storageFactory.createGlobal("iam-workload-identity-operations", "iam-workload-identity-operations.json",
                        new TypeReference<Map<String, String>>() {}),
                mapper, iamService, projects, locations);
    }

    IamWorkloadIdentityPoolService(StorageBackend<String, String> pools,
            StorageBackend<String, String> providers, StorageBackend<String, String> operations,
            ObjectMapper mapper, Instance<IamService> iamService, ProjectRegistry projects,
            LocationCatalog locations) {
        this.pools = pools;
        this.providers = providers;
        this.operations = operations;
        this.mapper = mapper;
        this.iamService = iamService;
        this.projects = projects;
        this.locations = locations;
        registerPolicyResolver();
    }

    void onStart(@Observes StartupEvent event) {
        registerPolicyResolver();
    }

    private void registerPolicyResolver() {
        IamService iam = iamService == null ? null : iamService.get();
        if (iam != null) {
            iam.registerPolicyResourceResolver("projects/*/locations/*/workloadIdentityPools/*",
                    this::requirePoolExists);
        }
    }

    // ── Pools ─────────────────────────────────────────────────────────────────

    public ObjectNode createPool(String project, String location, String poolId, ObjectNode body) {
        String projectId = projectId(project);
        requireGlobal(location);
        validateId(poolId, false);
        String name = IamWorkloadIdentityPoolNames.pool(projectId, poolId);
        String policy = IamWorkloadIdentityPoolNames.policyPool(projectId, poolId);
        IamService iam = iamService == null ? null : iamService.get();
        if (iam == null) {
            return createPoolUncoordinated(name, body);
        }
        // Hold the policy lock across cleanup and publication. Otherwise a setIamPolicy that
        // sees the pool between the store write and the deletePolicy can have its grant erased.
        return iam.createResourceAndPolicy(policy, null,
                establishPolicy -> createPoolAndPolicy(name, body, establishPolicy));
    }

    private ObjectNode createPoolAndPolicy(String name, ObjectNode body, Runnable establishPolicy) {
        synchronized (lock(name)) {
            if (pools.get(name).isPresent()) {
                throw GcpException.alreadyExists(ENTITY_ALREADY_EXISTS);
            }
            ObjectNode pool = poolBody(name, body);
            pools.put(name, pool.toString());
            try {
                establishPolicy.run();
                return operation(name);
            } catch (RuntimeException | Error failure) {
                pools.delete(name);
                throw failure;
            }
        }
    }

    private ObjectNode createPoolUncoordinated(String name, ObjectNode body) {
        synchronized (lock(name)) {
            if (pools.get(name).isPresent()) {
                throw GcpException.alreadyExists(ENTITY_ALREADY_EXISTS);
            }
            ObjectNode pool = poolBody(name, body);
            pools.put(name, pool.toString());
            return operation(name);
        }
    }

    private ObjectNode poolBody(String name, ObjectNode body) {
        ObjectNode pool = resourceBody(body, "workloadIdentityPool");
        removeOutputFields(pool);
        pool.put("name", name);
        pool.put("state", "ACTIVE");
        return pool;
    }

    public ObjectNode getPool(String project, String location, String poolId) {
        String projectId = projectId(project);
        requireGlobal(location);
        return read(pools, IamWorkloadIdentityPoolNames.pool(projectId, poolId));
    }

    public ObjectNode listAttestationRules(String project, String location, String poolId) {
        String projectId = projectId(project);
        requireGlobal(location);
        active(pools, IamWorkloadIdentityPoolNames.pool(projectId, poolId));
        return mapper.createObjectNode();
    }

    public ObjectNode listPools(String project, String location, boolean showDeleted,
            int pageSize, String pageToken) {
        String projectId = projectId(project);
        requireGlobal(location);
        String prefix = IamWorkloadIdentityPoolNames.pool(projectId, "");
        List<ObjectNode> all = pools.scan(key -> key.startsWith(prefix)).stream()
                .map(this::parse)
                .filter(pool -> showDeleted || !"DELETED".equals(pool.path("state").asText()))
                .sorted(Comparator.comparing(pool -> pool.path("name").asText()))
                .toList();
        PageToken.Page<ObjectNode> page = PageToken.paginate(all, pageSize, pageToken);
        ObjectNode response = mapper.createObjectNode();
        if (!page.items().isEmpty()) {
            ArrayNode list = response.putArray("workloadIdentityPools");
            page.items().forEach(list::add);
        }
        if (page.nextPageToken() != null) {
            response.put("nextPageToken", page.nextPageToken());
        }
        return response;
    }

    public ObjectNode patchPool(String project, String location, String poolId,
            String queryMask, ObjectNode body) {
        String projectId = projectId(project);
        requireGlobal(location);
        String name = IamWorkloadIdentityPoolNames.pool(projectId, poolId);
        synchronized (lock(name)) {
            ObjectNode pool = active(pools, name);
            ObjectNode patch = resourceBody(body, "workloadIdentityPool");
            Set<String> mask = maskPaths(queryMask, patch);
            for (String path : mask) {
                switch (path) {
                    case "displayName", "description", "disabled" -> apply(pool, patch, path);
                    case "mode" -> throw GcpException.invalidArgument(IMMUTABLE_FIELD);
                    default -> throw GcpException.invalidArgument("Unsupported field mask path: " + path);
                }
            }
            pools.put(name, pool.toString());
            return operation(name);
        }
    }

    public ObjectNode deletePool(String project, String location, String poolId) {
        String projectId = projectId(project);
        requireGlobal(location);
        String name = IamWorkloadIdentityPoolNames.pool(projectId, poolId);
        synchronized (lock(name)) {
            ObjectNode pool = active(pools, name);
            pool.put("state", "DELETED");
            pool.put("expireTime", expireTime());
            pools.put(name, pool.toString());
            return operation(name);
        }
    }

    public ObjectNode undeletePool(String project, String location, String poolId) {
        String projectId = projectId(project);
        requireGlobal(location);
        String name = IamWorkloadIdentityPoolNames.pool(projectId, poolId);
        synchronized (lock(name)) {
            ObjectNode pool = read(pools, name);
            if (!"DELETED".equals(pool.path("state").asText())) {
                throw GcpException.invalidArgument(POOL_NOT_DELETED);
            }
            pool.put("state", "ACTIVE");
            pool.remove("expireTime");
            pools.put(name, pool.toString());
            return operation(name);
        }
    }

    public StoredPolicy getPoolPolicy(String project, String location, String poolId) {
        return requireIam().getPolicy(policyPool(project, location, poolId));
    }

    public StoredPolicy setPoolPolicy(String project, String location, String poolId,
            Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> policy = body == null ? null : (Map<String, Object>) body.get("policy");
        StoredPolicy stored = IamPolicyCodec.fromJsonMap(policy);
        return requireIam().setPolicy(policyPool(project, location, poolId), stored);
    }

    public List<String> testPoolPermissions(String project, String location, String poolId,
            List<String> permissions) {
        return requireIam().testPermissions(policyPool(project, location, poolId),
                permissions == null ? List.of() : permissions);
    }

    // ── Providers ─────────────────────────────────────────────────────────────

    public ObjectNode createProvider(String project, String location, String poolId,
            String providerId, ObjectNode body) {
        String projectId = projectId(project);
        requireGlobal(location);
        validateId(poolId, false);
        requireActivePool(projectId, poolId);
        validateId(providerId, true);
        String name = IamWorkloadIdentityPoolNames.provider(projectId, poolId, providerId);
        synchronized (lock(name)) {
            if (providers.get(name).isPresent()) {
                throw GcpException.alreadyExists(ENTITY_ALREADY_EXISTS);
            }
            ObjectNode provider = resourceBody(body, "workloadIdentityPoolProvider");
            removeOutputFields(provider);
            provider.put("name", name);
            provider.put("state", "ACTIVE");
            validateProvider(provider);
            providers.put(name, provider.toString());
            return operation(name);
        }
    }

    public ObjectNode getProvider(String project, String location, String poolId, String providerId) {
        String projectId = projectId(project);
        requireGlobal(location);
        requireActivePool(projectId, poolId);
        return read(providers, IamWorkloadIdentityPoolNames.provider(projectId, poolId, providerId));
    }

    public ObjectNode listProviders(String project, String location, String poolId,
            boolean showDeleted, int pageSize, String pageToken) {
        String projectId = projectId(project);
        requireGlobal(location);
        requireActivePool(projectId, poolId);
        String prefix = IamWorkloadIdentityPoolNames.provider(projectId, poolId, "");
        List<ObjectNode> all = providers.scan(key -> key.startsWith(prefix)).stream()
                .map(this::parse)
                .filter(provider -> showDeleted || !"DELETED".equals(provider.path("state").asText()))
                .sorted(Comparator.comparing(provider -> provider.path("name").asText()))
                .toList();
        PageToken.Page<ObjectNode> page = PageToken.paginate(all, pageSize, pageToken);
        ObjectNode response = mapper.createObjectNode();
        if (!page.items().isEmpty()) {
            ArrayNode list = response.putArray("workloadIdentityPoolProviders");
            page.items().forEach(list::add);
        }
        if (page.nextPageToken() != null) {
            response.put("nextPageToken", page.nextPageToken());
        }
        return response;
    }

    public ObjectNode patchProvider(String project, String location, String poolId,
            String providerId, String queryMask, ObjectNode body) {
        String projectId = projectId(project);
        requireGlobal(location);
        String name = IamWorkloadIdentityPoolNames.provider(projectId, poolId, providerId);
        synchronized (lock(name)) {
            requireActivePool(projectId, poolId);
            ObjectNode provider = active(providers, name);
            ObjectNode patch = resourceBody(body, "workloadIdentityPoolProvider");
            Set<String> mask = maskPaths(queryMask, patch);
            for (String path : mask) {
                switch (path) {
                    case "displayName", "description", "attributeMapping", "attributeCondition", "disabled",
                            "oidc", "aws", "saml", "x509", "oidc.allowedAudiences", "oidc.issuerUri",
                            "oidc.discoveryUri", "oidc.jwksJson", "aws.accountId" -> applyPath(provider, patch, path);
                    default -> throw GcpException.invalidArgument("Unsupported field mask path: " + path);
                }
            }
            validateProvider(provider);
            providers.put(name, provider.toString());
            return operation(name);
        }
    }

    public ObjectNode deleteProvider(String project, String location, String poolId, String providerId) {
        String projectId = projectId(project);
        requireGlobal(location);
        requireActivePool(projectId, poolId);
        String name = IamWorkloadIdentityPoolNames.provider(projectId, poolId, providerId);
        synchronized (lock(name)) {
            ObjectNode provider = active(providers, name);
            provider.put("state", "DELETED");
            provider.put("expireTime", expireTime());
            providers.put(name, provider.toString());
            return operation(name);
        }
    }

    public ObjectNode undeleteProvider(String project, String location, String poolId, String providerId) {
        String projectId = projectId(project);
        requireGlobal(location);
        requireActivePool(projectId, poolId);
        String name = IamWorkloadIdentityPoolNames.provider(projectId, poolId, providerId);
        synchronized (lock(name)) {
            ObjectNode provider = read(providers, name);
            if (!"DELETED".equals(provider.path("state").asText())) {
                throw GcpException.invalidArgument(PROVIDER_NOT_DELETED);
            }
            provider.put("state", "ACTIVE");
            provider.remove("expireTime");
            providers.put(name, provider.toString());
            return operation(name);
        }
    }

    // ── Operations ────────────────────────────────────────────────────────────

    public ObjectNode getPoolOperation(String project, String location, String poolId, String operationId) {
        String projectId = projectId(project);
        requireGlobal(location);
        return read(operations, IamWorkloadIdentityPoolNames.pool(projectId, poolId) + "/operations/" + operationId);
    }

    public ObjectNode getProviderOperation(String project, String location, String poolId,
            String providerId, String operationId) {
        String projectId = projectId(project);
        requireGlobal(location);
        return read(operations, IamWorkloadIdentityPoolNames.provider(projectId, poolId, providerId)
                + "/operations/" + operationId);
    }

    // ── STS lookup ───────────────────────────────────────────────────────────

    /**
     * Resolves an active, non-disabled Workload Identity Pool and Provider for STS token exchange.
     * Does not implicitly register unknown projects.
     */
    public Optional<ActiveProvider> findActiveProvider(String projectIdOrNumber, String poolId, String providerId) {
        if (projectIdOrNumber == null || projectIdOrNumber.isBlank()
                || poolId == null || poolId.isBlank()
                || providerId == null || providerId.isBlank()) {
            return Optional.empty();
        }
        String projectNumber = resolveProjectNumber(projectIdOrNumber);
        if (projectNumber == null) {
            return Optional.empty();
        }
        String poolName = IamWorkloadIdentityPoolNames.pool(projectNumber, poolId);
        Optional<ObjectNode> pool = pools.get(poolName).map(this::parse);
        if (pool.isEmpty()
                || !"ACTIVE".equals(pool.get().path("state").asText())
                || pool.get().path("disabled").asBoolean(false)) {
            return Optional.empty();
        }
        String providerName = IamWorkloadIdentityPoolNames.provider(projectNumber, poolId, providerId);
        Optional<ObjectNode> provider = providers.get(providerName).map(this::parse);
        if (provider.isEmpty()
                || !"ACTIVE".equals(provider.get().path("state").asText())
                || provider.get().path("disabled").asBoolean(false)) {
            return Optional.empty();
        }
        return Optional.of(new ActiveProvider(projectNumber, poolId, providerId,
                poolName, providerName, pool.get(), provider.get()));
    }

    private String resolveProjectNumber(String projectIdOrNumber) {
        if (projects != null) {
            Optional<ProjectRecord> record = projects.find(projectIdOrNumber);
            if (record.isPresent()) {
                return record.get().getProjectNumber();
            }
        }
        if (ProjectRegistry.isNumber(projectIdOrNumber)) {
            return projectIdOrNumber;
        }
        return ProjectNumbers.of(projectIdOrNumber);
    }

    public record ActiveProvider(
            String projectNumber,
            String poolId,
            String providerId,
            String poolName,
            String providerName,
            ObjectNode pool,
            ObjectNode provider) {
    }

    // ── Policy resolver ──────────────────────────────────────────────────────

    /**
     * Called by {@link IamService} before a policy read or write. The resource spelling may use the
     * project ID or number; both resolve to the stored number-form pool.
     */
    void requirePoolExists(String resource) {
        String[] parts = resource.split("/", -1);
        if (parts.length != 6 || !"projects".equals(parts[0]) || !"locations".equals(parts[2])
                || !IamWorkloadIdentityPoolNames.POOLS.equals(parts[4])) {
            return;
        }
        String projectId = projectId(parts[1]);
        active(pools, IamWorkloadIdentityPoolNames.pool(projectId, parts[5]));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String projectId(String project) {
        if (project == null || project.isBlank()) {
            throw GcpException.invalidArgument(PARENT_INVALID);
        }
        return projects == null ? project : projects.require(project).getProjectId();
    }

    private void requireGlobal(String location) {
        if (locations != null) {
            locations.requireLocation(location, LocationCatalog.Kind.GLOBAL);
        }
        if (!IamWorkloadIdentityPoolNames.LOCATION.equals(location)) {
            throw GcpException.invalidArgument(PARENT_INVALID);
        }
    }

    private static void validateId(String id, boolean provider) {
        if (id == null || !ID.matcher(id).matches()) {
            throw GcpException.invalidArgument(provider ? PROVIDER_ID_RULE : POOL_ID_RULE);
        }
        if (id.startsWith("gcp-")) {
            throw GcpException.invalidArgument(provider ? PROVIDER_ID_RESERVED : POOL_ID_RESERVED);
        }
    }

    private void requireActivePool(String projectId, String poolId) {
        active(pools, IamWorkloadIdentityPoolNames.pool(projectId, poolId));
    }

    private ObjectNode active(StorageBackend<String, String> store, String name) {
        ObjectNode resource = read(store, name);
        if ("DELETED".equals(resource.path("state").asText())) {
            throw notFound();
        }
        return resource;
    }

    private IamService requireIam() {
        if (iamService == null || iamService.get() == null) {
            throw GcpException.internal("IAM service is unavailable");
        }
        return iamService.get();
    }

    private String policyPool(String project, String location, String poolId) {
        requireGlobal(location);
        return IamWorkloadIdentityPoolNames.policyPool(projectId(project), poolId);
    }

    private ObjectNode read(StorageBackend<String, String> store, String name) {
        return store.get(name).map(this::parse).orElseThrow(IamWorkloadIdentityPoolService::notFound);
    }

    private ObjectNode parse(String json) {
        try {
            return (ObjectNode) mapper.readTree(json);
        } catch (JsonProcessingException | ClassCastException e) {
            throw GcpException.internal("Stored workload identity resource is invalid");
        }
    }

    private static ObjectNode resourceBody(ObjectNode body, String wrapper) {
        if (body == null) {
            return JsonNodeFactory.instance.objectNode();
        }
        JsonNode nested = body.get(wrapper);
        ObjectNode source = nested instanceof ObjectNode object ? object : body;
        return source.deepCopy();
    }

    private static void removeOutputFields(ObjectNode resource) {
        resource.remove("name");
        resource.remove("state");
        resource.remove("expireTime");
    }

    private ObjectNode operation(String target) {
        String name = target + "/operations/" + UUID.randomUUID().toString().replace("-", "");
        ObjectNode operation = mapper.createObjectNode();
        operation.put("name", name);
        operation.put("done", true);
        operation.putObject("response");
        operations.put(name, operation.toString());
        return operation;
    }

    private static Set<String> maskPaths(String queryMask, ObjectNode body) {
        String mask = queryMask;
        if ((mask == null || mask.isBlank()) && body != null && body.path("updateMask").isTextual()) {
            mask = body.get("updateMask").asText();
        }
        if (mask == null || mask.isBlank()) {
            throw GcpException.invalidArgument(UPDATE_MASK_REQUIRED);
        }
        Set<String> paths = new LinkedHashSet<>();
        for (String path : mask.split(",")) {
            if (!path.isBlank()) {
                paths.add(normalizePath(path.trim()));
            }
        }
        if (paths.isEmpty()) {
            throw GcpException.invalidArgument(UPDATE_MASK_REQUIRED);
        }
        return paths;
    }

    private static String normalizePath(String path) {
        return switch (path) {
            case "display_name" -> "displayName";
            case "attribute_mapping" -> "attributeMapping";
            case "attribute_condition" -> "attributeCondition";
            case "oidc.allowed_audiences" -> "oidc.allowedAudiences";
            case "oidc.issuer_uri" -> "oidc.issuerUri";
            case "oidc.discovery_uri" -> "oidc.discoveryUri";
            case "oidc.jwks_json" -> "oidc.jwksJson";
            case "aws.account_id" -> "aws.accountId";
            default -> path;
        };
    }

    private static void apply(ObjectNode target, ObjectNode source, String field) {
        if (source.has(field)) {
            target.set(field, source.get(field).deepCopy());
        } else {
            target.remove(field);
        }
    }

    private static void applyPath(ObjectNode target, ObjectNode source, String path) {
        int dot = path.indexOf('.');
        if (dot < 0) {
            apply(target, source, path);
            return;
        }
        String parent = path.substring(0, dot);
        String field = path.substring(dot + 1);
        ObjectNode targetParent;
        if (target.get(parent) instanceof ObjectNode object) {
            targetParent = object;
        } else {
            targetParent = target.putObject(parent);
        }
        JsonNode sourceParent = source.get(parent);
        if (sourceParent instanceof ObjectNode sourceObject && sourceObject.has(field)) {
            targetParent.set(field, sourceObject.get(field).deepCopy());
        } else {
            targetParent.remove(field);
        }
    }

    private void validateProvider(ObjectNode provider) {
        List<String> configured = new ArrayList<>();
        for (String type : List.of("oidc", "aws", "saml", "x509")) {
            if (provider.hasNonNull(type)) {
                configured.add(type);
            }
        }
        if (configured.isEmpty()) {
            throw GcpException.invalidArgument(
                    "Invalid WorkloadIdentityPoolProvider IDP configuration: PROVIDERCONFIG_NOT_SET.");
        }
        if (configured.size() > 1) {
            throw GcpException.invalidArgument("Invalid value at 'workload_identity_pool_provider' (oneof), "
                    + "oneof field 'provider_config' is already set. Cannot set '" + configured.get(1) + "'");
        }
        JsonNode mapping = provider.get("attributeMapping");
        if (configured.getFirst().equals("oidc")) {
            JsonNode oidc = provider.get("oidc");
            if (oidc == null || oidc.path("issuerUri").asText().isBlank()) {
                throw GcpException.invalidArgument("Missing OIDC issuer URI.");
            }
            if (mapping == null || !mapping.isObject() || mapping.isEmpty()) {
                throw GcpException.invalidArgument(OIDC_MAPPINGS_REQUIRED);
            }
        } else if (configured.getFirst().equals("aws")) {
            JsonNode aws = provider.get("aws");
            if (aws == null || !aws.path("accountId").asText().matches("\\d{12}")) {
                throw GcpException.invalidArgument(AWS_ACCOUNT_INVALID);
            }
        }
        if (mapping != null && mapping.isObject() && !mapping.isEmpty()
                && !mapping.has("google.subject")) {
            throw GcpException.invalidArgument(SUBJECT_MAPPING_REQUIRED);
        }
    }

    private static String expireTime() {
        return Instant.now().plus(30, ChronoUnit.DAYS).toString();
    }

    private static GcpException notFound() {
        return GcpException.notFound(ENTITY_NOT_FOUND);
    }

    private static Object[] locks() {
        Object[] locks = new Object[LOCK_COUNT];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    private static Object lock(String key) {
        return LOCKS[Math.floorMod(key.hashCode(), LOCKS.length)];
    }
}
