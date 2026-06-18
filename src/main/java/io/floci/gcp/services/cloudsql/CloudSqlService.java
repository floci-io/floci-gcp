package io.floci.gcp.services.cloudsql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.ProjectAwareStorageBackend;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class CloudSqlService {

    private static final Logger LOG = Logger.getLogger(CloudSqlService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final StorageBackend<String, Map<String, Object>> instanceStore;
    private final StorageBackend<String, Map<String, Object>> databaseStore;
    private final StorageBackend<String, Map<String, Object>> userStore;
    private final StorageBackend<String, Map<String, Object>> operationStore;
    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final CloudSqlDataPlane dataPlane;
    private final boolean dataPlaneEnabled;

    @Inject
    public CloudSqlService(StorageFactory storageFactory,
                           ServiceRegistry serviceRegistry,
                           EmulatorConfig config,
                           ObjectMapper objectMapper,
                           CloudSqlPostgresDataPlane dataPlane) {
        this.instanceStore = storageFactory.create("cloudsql", "cloudsql-instances.json",
                new TypeReference<Map<String, Map<String, Object>>>() {});
        this.databaseStore = storageFactory.create("cloudsql", "cloudsql-databases.json",
                new TypeReference<Map<String, Map<String, Object>>>() {});
        this.userStore = storageFactory.create("cloudsql", "cloudsql-users.json",
                new TypeReference<Map<String, Map<String, Object>>>() {});
        this.operationStore = storageFactory.create("cloudsql", "cloudsql-operations.json",
                new TypeReference<Map<String, Map<String, Object>>>() {});
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.objectMapper = objectMapper;
        this.baseUrl = config.effectiveBaseUrl();
        this.dataPlane = dataPlane;
        this.dataPlaneEnabled = config.services().cloudsql().dataPlaneEnabled();
    }

    CloudSqlService(StorageBackend<String, Map<String, Object>> instanceStore,
                    StorageBackend<String, Map<String, Object>> databaseStore,
                    StorageBackend<String, Map<String, Object>> userStore,
                    StorageBackend<String, Map<String, Object>> operationStore,
                    ObjectMapper objectMapper,
                    String baseUrl) {
        this(instanceStore, databaseStore, userStore, operationStore, objectMapper, baseUrl,
                CloudSqlDataPlane.noop(), false);
    }

    CloudSqlService(StorageBackend<String, Map<String, Object>> instanceStore,
                    StorageBackend<String, Map<String, Object>> databaseStore,
                    StorageBackend<String, Map<String, Object>> userStore,
                    StorageBackend<String, Map<String, Object>> operationStore,
                    ObjectMapper objectMapper,
                    String baseUrl,
                    CloudSqlDataPlane dataPlane,
                    boolean dataPlaneEnabled) {
        this.instanceStore = instanceStore;
        this.databaseStore = databaseStore;
        this.userStore = userStore;
        this.operationStore = operationStore;
        this.serviceRegistry = null;
        this.config = null;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.dataPlane = dataPlane;
        this.dataPlaneEnabled = dataPlaneEnabled;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("cloudsql")
                .enabled(config.services().cloudsql().enabled())
                .storageKey("cloudsql")
                .protocol(ServiceProtocol.REST)
                .resourceClasses(CloudSqlController.class, CloudSqlV1Beta4Controller.class,
                        CloudSqlLegacyController.class, CloudSqlGlobalController.class,
                        CloudSqlV1Beta4GlobalController.class, CloudSqlLegacyGlobalController.class)
                .build());
        if (config.services().cloudsql().enabled() && dataPlaneEnabled) {
            restartPersistedInstances();
        }
    }

    @PreDestroy
    public void shutdown() {
        if (!dataPlaneEnabled) {
            return;
        }
        for (Map<String, Object> instance : allInstances()) {
            String project = stringValue(instance.get("project"));
            String name = stringValue(instance.get("name"));
            if (project != null && name != null) {
                dataPlane.stopInstance(project, name, instance, false);
            }
        }
    }

    public Map<String, Object> createInstance(String project, Map<String, Object> body) {
        Map<String, Object> request = copy(body);
        String instance = stringValue(request.get("name"));
        if (instance == null || instance.isBlank()) {
            throw GcpException.invalidArgument("Instance name is required");
        }
        String databaseVersion = stringValue(request.get("databaseVersion"));
        if (databaseVersion == null || !databaseVersion.startsWith("POSTGRES_")) {
            throw GcpException.invalidArgument("Only PostgreSQL Cloud SQL instances are supported");
        }
        if (instanceStore.get(instanceKey(instance)).isPresent()) {
            throw GcpException.alreadyExists("Cloud SQL instance already exists: " + instance);
        }

        Map<String, Object> stored = normalizeInstance(project, instance, request);
        if (dataPlaneEnabled) {
            stored = dataPlane.startInstance(project, instance, stored);
        }
        putInstance(project, instance, stored);
        createDefaultDatabase(project, instance);

        LOG.infof("create Cloud SQL PostgreSQL instance project=%s instance=%s", project, instance);
        return createOperation(project, "CREATE", instance, stored);
    }

    public Map<String, Object> patchInstance(String project, String instance, Map<String, Object> body) {
        Map<String, Object> existing = getInstance(project, instance);
        Map<String, Object> patch = copy(body);
        merge(existing, patch);
        existing.put("kind", "sql#instance");
        existing.put("name", instance);
        existing.put("project", project);
        existing.put("connectionName", connectionName(project, existing, instance));
        existing.put("selfLink", instanceSelfLink(project, instance));
        instanceStore.put(instanceKey(instance), existing);
        LOG.infof("patch Cloud SQL PostgreSQL instance project=%s instance=%s", project, instance);
        return createOperation(project, "UPDATE", instance, existing);
    }

    public Map<String, Object> updateInstance(String project, String instance, Map<String, Object> body) {
        Map<String, Object> existing = getInstance(project, instance);
        Map<String, Object> update = copy(body);
        merge(existing, update);
        Map<String, Object> stored = normalizeInstance(project, instance, existing);
        instanceStore.put(instanceKey(instance), stored);
        LOG.infof("update Cloud SQL PostgreSQL instance project=%s instance=%s", project, instance);
        return createOperation(project, "UPDATE", instance, stored);
    }

    public Map<String, Object> getInstance(String project, String instance) {
        return instanceStore.get(instanceKey(instance))
                .map(this::copy)
                .orElseThrow(() -> GcpException.notFound("Cloud SQL instance not found: " + instance));
    }

    public Map<String, Object> listInstances(int maxResults, String pageToken) {
        List<Map<String, Object>> instances = instanceStore.scan(k -> k.startsWith("instances/")).stream()
                .map(this::copy)
                .sorted(Comparator.comparing(m -> stringValue(m.get("name"))))
                .toList();
        PageToken.Page<Map<String, Object>> page = PageToken.paginate(instances, normalizedPageSize(maxResults), pageToken);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("kind", "sql#instancesList");
        response.put("items", page.items());
        if (page.nextPageToken() != null) {
            response.put("nextPageToken", page.nextPageToken());
        }
        return response;
    }

    public Map<String, Object> deleteInstance(String project, String instance) {
        Map<String, Object> existing = getInstance(project, instance);
        if (dataPlaneEnabled) {
            dataPlane.stopInstance(project, instance, existing, true);
        }
        instanceStore.delete(instanceKey(instance));
        deleteByPrefix(databaseStore, databasePrefix(instance));
        deleteByPrefix(userStore, userPrefix(instance));
        LOG.infof("delete Cloud SQL PostgreSQL instance project=%s instance=%s", project, instance);
        return createOperation(project, "DELETE", instance, existing);
    }

    public Map<String, Object> listTiers(String project) {
        return mapOf(
                "kind", "sql#tiersList",
                "items", List.of(
                        tier("db-custom-1-3840", "3840"),
                        tier("db-custom-2-7680", "7680"),
                        tier("db-custom-4-15360", "15360")));
    }

    public Map<String, Object> listFlags() {
        return mapOf(
                "kind", "sql#flagsList",
                "items", List.of(
                        flag("max_connections", "INTEGER", true,
                                "POSTGRES_15", "POSTGRES_16", "POSTGRES_17", "POSTGRES_18"),
                        flag("cloudsql.iam_authentication", "BOOLEAN", true,
                                "POSTGRES_15", "POSTGRES_16", "POSTGRES_17", "POSTGRES_18"),
                        flag("log_min_duration_statement", "INTEGER", false,
                                "POSTGRES_15", "POSTGRES_16", "POSTGRES_17", "POSTGRES_18")));
    }

    public Map<String, Object> getConnectSettings(String project, String instance) {
        Map<String, Object> stored = getInstance(project, instance);
        return mapOf(
                "kind", "sql#connectSettings",
                "backendType", stored.get("backendType"),
                "instanceType", stored.get("instanceType"),
                "ipAddresses", stored.getOrDefault("ipAddresses", List.of()),
                "serverCaCert", stored.get("serverCaCert"),
                "region", stored.get("region"),
                "dnsName", stored.getOrDefault("dnsName", ""),
                "pscEnabled", false);
    }

    public Map<String, Object> getOperation(String operation) {
        return operationStore.get(operationKey(operation))
                .map(this::copy)
                .orElseThrow(() -> GcpException.notFound("Cloud SQL operation not found: " + operation));
    }

    public Map<String, Object> listOperations(int maxResults, String pageToken) {
        List<Map<String, Object>> operations = operationStore.scan(k -> k.startsWith("operations/")).stream()
                .map(this::copy)
                .sorted(Comparator.comparing(m -> stringValue(m.get("name"))))
                .toList();
        PageToken.Page<Map<String, Object>> page = PageToken.paginate(operations, normalizedPageSize(maxResults), pageToken);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("kind", "sql#operationsList");
        response.put("items", page.items());
        if (page.nextPageToken() != null) {
            response.put("nextPageToken", page.nextPageToken());
        }
        return response;
    }

    public Map<String, Object> createDatabase(String project, String instance, Map<String, Object> body) {
        Map<String, Object> instanceMetadata = getInstance(project, instance);
        Map<String, Object> request = copy(body);
        String database = stringValue(request.get("name"));
        if (database == null || database.isBlank()) {
            throw GcpException.invalidArgument("Database name is required");
        }
        String key = databaseKey(instance, database);
        if (databaseStore.get(key).isPresent()) {
            throw GcpException.alreadyExists("Cloud SQL database already exists: " + database);
        }
        Map<String, Object> stored = normalizeDatabase(project, instance, database, request);
        if (dataPlaneEnabled) {
            dataPlane.createDatabase(instanceMetadata, database);
            for (String user : userNames(instance)) {
                dataPlane.grantDatabaseAccess(instanceMetadata, database, user);
            }
        }
        databaseStore.put(key, stored);
        LOG.infof("create Cloud SQL PostgreSQL database project=%s instance=%s database=%s",
                project, instance, database);
        return createOperation(project, "CREATE_DATABASE", instance, stored);
    }

    public Map<String, Object> getDatabase(String project, String instance, String database) {
        getInstance(project, instance);
        return databaseStore.get(databaseKey(instance, database))
                .map(this::copy)
                .orElseThrow(() -> GcpException.notFound("Cloud SQL database not found: " + database));
    }

    public Map<String, Object> updateDatabase(String project, String instance,
                                              String database, Map<String, Object> body) {
        Map<String, Object> existing = getDatabase(project, instance, database);
        Map<String, Object> update = copy(body);
        merge(existing, update);
        Map<String, Object> stored = normalizeDatabase(project, instance, database, existing);
        databaseStore.put(databaseKey(instance, database), stored);
        LOG.infof("update Cloud SQL PostgreSQL database project=%s instance=%s database=%s",
                project, instance, database);
        return createOperation(project, "UPDATE_DATABASE", instance, stored);
    }

    public Map<String, Object> patchDatabase(String project, String instance,
                                             String database, Map<String, Object> body) {
        return updateDatabase(project, instance, database, body);
    }

    public Map<String, Object> listDatabases(String project, String instance) {
        getInstance(project, instance);
        List<Map<String, Object>> databases = databaseStore.scan(k -> k.startsWith(databasePrefix(instance))).stream()
                .map(this::copy)
                .sorted(Comparator.comparing(m -> stringValue(m.get("name"))))
                .toList();
        return mapOf(
                "kind", "sql#databasesList",
                "items", databases);
    }

    public Map<String, Object> deleteDatabase(String project, String instance, String database) {
        Map<String, Object> instanceMetadata = getInstance(project, instance);
        if ("postgres".equals(database)) {
            throw GcpException.failedPrecondition("Default PostgreSQL database cannot be deleted: postgres");
        }
        Map<String, Object> existing = getDatabase(project, instance, database);
        if (dataPlaneEnabled) {
            dataPlane.deleteDatabase(instanceMetadata, database);
        }
        databaseStore.delete(databaseKey(instance, database));
        LOG.infof("delete Cloud SQL PostgreSQL database project=%s instance=%s database=%s",
                project, instance, database);
        return createOperation(project, "DELETE_DATABASE", instance, existing);
    }

    public Map<String, Object> createUser(String project, String instance, Map<String, Object> body) {
        Map<String, Object> instanceMetadata = getInstance(project, instance);
        Map<String, Object> request = copy(body);
        String user = stringValue(request.get("name"));
        if (user == null || user.isBlank()) {
            throw GcpException.invalidArgument("User name is required");
        }
        String host = stringValue(request.get("host"));
        validatePostgresHost(host);
        String key = userKey(instance, user, host);
        if (userStore.get(key).isPresent()) {
            throw GcpException.alreadyExists("Cloud SQL user already exists: " + user);
        }
        if (dataPlaneEnabled) {
            dataPlane.createOrUpdateUser(instanceMetadata, user, stringValue(request.get("password")));
            for (String database : databaseNames(instance)) {
                dataPlane.grantDatabaseAccess(instanceMetadata, database, user);
            }
        }
        Map<String, Object> stored = normalizeUser(project, instance, user, host, request);
        userStore.put(key, stored);
        LOG.infof("create Cloud SQL PostgreSQL user project=%s instance=%s user=%s", project, instance, user);
        return createOperation(project, "CREATE_USER", instance, stored);
    }

    public Map<String, Object> listUsers(String project, String instance) {
        getInstance(project, instance);
        List<Map<String, Object>> users = userStore.scan(k -> k.startsWith(userPrefix(instance))).stream()
                .map(this::copy)
                .sorted(Comparator.comparing(m -> stringValue(m.get("name"))))
                .toList();
        return mapOf(
                "kind", "sql#usersList",
                "items", users);
    }

    public Map<String, Object> getUser(String project, String instance, String user, String host) {
        getInstance(project, instance);
        validateUserName(user);
        validatePostgresHost(host);
        return userStore.get(userKey(instance, user, host))
                .map(this::copy)
                .orElseThrow(() -> GcpException.notFound("Cloud SQL user not found: " + user));
    }

    public Map<String, Object> updateUser(String project, String instance, String user,
                                          String host, Map<String, Object> body) {
        Map<String, Object> existing = getUser(project, instance, user, host);
        Map<String, Object> update = copy(body);
        if (dataPlaneEnabled && update.containsKey("password")) {
            Map<String, Object> instanceMetadata = getInstance(project, instance);
            dataPlane.createOrUpdateUser(instanceMetadata, user, stringValue(update.get("password")));
            for (String database : databaseNames(instance)) {
                dataPlane.grantDatabaseAccess(instanceMetadata, database, user);
            }
        }
        merge(existing, update);
        Map<String, Object> stored = normalizeUser(project, instance, user, host, existing);
        userStore.put(userKey(instance, user, host), stored);
        LOG.infof("update Cloud SQL PostgreSQL user project=%s instance=%s user=%s",
                project, instance, user);
        return createOperation(project, "UPDATE_USER", instance, stored);
    }

    public Map<String, Object> deleteUser(String project, String instance, String user, String host) {
        Map<String, Object> instanceMetadata = getInstance(project, instance);
        validateUserName(user);
        validatePostgresHost(host);
        String key = userKey(instance, user, host);
        Map<String, Object> existing = userStore.get(key)
                .orElseThrow(() -> GcpException.notFound("Cloud SQL user not found: " + user));
        if (dataPlaneEnabled) {
            dataPlane.deleteUser(instanceMetadata, user, databaseNames(instance));
        }
        userStore.delete(key);
        LOG.infof("delete Cloud SQL PostgreSQL user project=%s instance=%s user=%s", project, instance, user);
        return createOperation(project, "DELETE_USER", instance, existing);
    }

    private Map<String, Object> normalizeInstance(String project, String instance, Map<String, Object> request) {
        Map<String, Object> stored = copy(request);
        putDefault(stored, "kind", "sql#instance");
        stored.put("name", instance);
        stored.put("project", project);
        putDefault(stored, "backendType", "SECOND_GEN");
        putDefault(stored, "instanceType", "CLOUD_SQL_INSTANCE");
        putDefault(stored, "state", "RUNNABLE");
        putDefault(stored, "region", "us-central1");
        putDefault(stored, "gceZone", stored.get("region") + "-a");
        putDefault(stored, "etag", UUID.randomUUID().toString());
        putDefault(stored, "settings", defaultSettings());
        putDefault(stored, "ipAddresses", List.of());
        putDefault(stored, "serverCaCert", serverCaCert(instance));
        stored.put("connectionName", connectionName(project, stored, instance));
        stored.put("selfLink", instanceSelfLink(project, instance));
        return stored;
    }

    private void restartPersistedInstances() {
        for (Map<String, Object> instance : allInstances()) {
            String project = stringValue(instance.get("project"));
            String name = stringValue(instance.get("name"));
            if (project == null || name == null) {
                continue;
            }
            try {
                Map<String, Object> updated = dataPlane.ensureInstance(project, name, instance);
                putInstance(project, name, updated);
            } catch (GcpException e) {
                LOG.warnf("Cloud SQL PostgreSQL data plane was not restored project=%s instance=%s: %s",
                        project, name, e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> allInstances() {
        if (instanceStore instanceof ProjectAwareStorageBackend<?> projectAware) {
            return projectAware.scanAllProjects(k -> k.startsWith("instances/")).stream()
                    .map(v -> copy((Map<String, Object>) v))
                    .toList();
        }
        return instanceStore.scan(k -> k.startsWith("instances/")).stream()
                .map(this::copy)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private void putInstance(String project, String instance, Map<String, Object> stored) {
        if (instanceStore instanceof ProjectAwareStorageBackend<?> projectAware) {
            ((ProjectAwareStorageBackend<Map<String, Object>>) projectAware)
                    .putForProject(project, instanceKey(instance), stored);
            return;
        }
        instanceStore.put(instanceKey(instance), stored);
    }

    private void createDefaultDatabase(String project, String instance) {
        String key = databaseKey(instance, "postgres");
        if (databaseStore.get(key).isEmpty()) {
            databaseStore.put(key, normalizeDatabase(project, instance, "postgres", Map.of("name", "postgres")));
        }
    }

    private Map<String, Object> normalizeDatabase(String project, String instance,
                                                  String database, Map<String, Object> request) {
        Map<String, Object> stored = copy(request);
        putDefault(stored, "kind", "sql#database");
        stored.put("name", database);
        stored.put("project", project);
        stored.put("instance", instance);
        putDefault(stored, "charset", "UTF8");
        putDefault(stored, "collation", "en_US.UTF8");
        stored.put("selfLink", effectiveBaseUrl() + "/v1/projects/" + project
                + "/instances/" + instance + "/databases/" + database);
        return stored;
    }

    private Map<String, Object> normalizeUser(String project, String instance, String user,
                                              String host, Map<String, Object> request) {
        Map<String, Object> stored = copy(request);
        stored.remove("password");
        putDefault(stored, "kind", "sql#user");
        stored.put("name", user);
        stored.put("project", project);
        stored.put("instance", instance);
        if (host != null) {
            stored.put("host", host);
        }
        putDefault(stored, "type", "BUILT_IN");
        return stored;
    }

    private List<String> databaseNames(String instance) {
        return databaseStore.scan(k -> k.startsWith(databasePrefix(instance))).stream()
                .map(database -> stringValue(database.get("name")))
                .filter(name -> name != null && !name.isBlank())
                .toList();
    }

    private List<String> userNames(String instance) {
        return userStore.scan(k -> k.startsWith(userPrefix(instance))).stream()
                .map(user -> stringValue(user.get("name")))
                .filter(name -> name != null && !name.isBlank())
                .toList();
    }

    private Map<String, Object> createOperation(String project, String operationType,
                                                String instance, Map<String, Object> target) {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("kind", "sql#operation");
        operation.put("name", id);
        operation.put("targetId", instance);
        operation.put("targetProject", project);
        operation.put("targetLink", target.getOrDefault("selfLink", instanceSelfLink(project, instance)));
        operation.put("status", "DONE");
        operation.put("operationType", operationType);
        operation.put("insertTime", now);
        operation.put("startTime", now);
        operation.put("endTime", now);
        operation.put("selfLink", effectiveBaseUrl() + "/v1/projects/" + project + "/operations/" + id);
        operationStore.put(operationKey(id), operation);
        return copy(operation);
    }

    private Map<String, Object> tier(String tier, String ramMb) {
        return mapOf(
                "kind", "sql#tier",
                "tier", tier,
                "RAM", ramMb,
                "DiskQuota", "0",
                "region", List.of("us-central1", "us-east1", "europe-west1"));
    }

    private Map<String, Object> flag(String name, String type, boolean requiresRestart, String... appliesTo) {
        return mapOf(
                "kind", "sql#flag",
                "name", name,
                "type", type,
                "requiresRestart", requiresRestart,
                "appliesTo", List.of(appliesTo));
    }

    private Map<String, Object> defaultSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("kind", "sql#settings");
        settings.put("tier", "db-custom-1-3840");
        settings.put("activationPolicy", "ALWAYS");
        settings.put("dataDiskType", "PD_SSD");
        settings.put("dataDiskSizeGb", "10");
        settings.put("availabilityType", "ZONAL");
        return settings;
    }

    private Map<String, Object> serverCaCert(String instance) {
        return mapOf(
                "kind", "sql#sslCert",
                "certSerialNumber", UUID.nameUUIDFromBytes(instance.getBytes()).toString(),
                "commonName", "C=US,O=Google,Inc,CN=Google Cloud SQL Server CA",
                "sha1Fingerprint", UUID.nameUUIDFromBytes(("sha1:" + instance).getBytes()).toString(),
                "instance", instance);
    }

    private String connectionName(String project, Map<String, Object> instance, String name) {
        return project + ":" + stringValue(instance.get("region")) + ":" + name;
    }

    private String instanceSelfLink(String project, String instance) {
        return effectiveBaseUrl() + "/v1/projects/" + project + "/instances/" + instance;
    }

    private String effectiveBaseUrl() {
        return baseUrl;
    }

    private int normalizedPageSize(int maxResults) {
        if (maxResults <= 0) {
            return 500;
        }
        return Math.min(maxResults, 1000);
    }

    private void deleteByPrefix(StorageBackend<String, Map<String, Object>> store, String prefix) {
        new ArrayList<>(store.keys()).stream()
                .filter(k -> k.startsWith(prefix))
                .forEach(store::delete);
    }

    @SuppressWarnings("unchecked")
    private void merge(Map<String, Object> target, Map<String, Object> patch) {
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> patchMap
                    && target.get(entry.getKey()) instanceof Map<?, ?> targetMap) {
                Map<String, Object> mutableTarget = copy((Map<String, Object>) targetMap);
                merge(mutableTarget, (Map<String, Object>) patchMap);
                target.put(entry.getKey(), mutableTarget);
            } else {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private Map<String, Object> copy(Map<String, Object> value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(value, MAP_TYPE);
    }

    private void putDefault(Map<String, Object> map, String key, Object value) {
        if (!map.containsKey(key) || map.get(key) == null) {
            map.put(key, value);
        }
    }

    private void validateUserName(String user) {
        if (user == null || user.isBlank()) {
            throw GcpException.invalidArgument("User name is required");
        }
    }

    private void validatePostgresHost(String host) {
        if (host != null && !host.isBlank()) {
            throw GcpException.invalidArgument("PostgreSQL Cloud SQL users do not support host-qualified identities");
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put((String) entries[i], entries[i + 1]);
        }
        return map;
    }

    private static String instanceKey(String instance) {
        return "instances/" + instance;
    }

    private static String databasePrefix(String instance) {
        return "instances/" + instance + "/databases/";
    }

    private static String databaseKey(String instance, String database) {
        return databasePrefix(instance) + database;
    }

    private static String userPrefix(String instance) {
        return "instances/" + instance + "/users/";
    }

    private static String userKey(String instance, String user, String host) {
        return userPrefix(instance) + (host == null ? "" : host) + "/" + user;
    }

    private static String operationKey(String operation) {
        return "operations/" + operation;
    }
}
