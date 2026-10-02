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
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class CloudSqlService {

    private static final Logger LOG = Logger.getLogger(CloudSqlService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final String NO_SECONDARY_ZONE = "no_secondary_zone";

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
    private final boolean strictLocations;

    @Inject
    public CloudSqlService(StorageFactory storageFactory,
                           ServiceRegistry serviceRegistry,
                           EmulatorConfig config,
                           ObjectMapper objectMapper,
                           CloudSqlEngineDataPlane dataPlane) {
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
        this.dataPlaneEnabled = !config.services().cloudsql().mock();
        this.strictLocations = config.locations().strict();
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
        this(instanceStore, databaseStore, userStore, operationStore, objectMapper, baseUrl,
                dataPlane, dataPlaneEnabled, false);
    }

    CloudSqlService(StorageBackend<String, Map<String, Object>> instanceStore,
                    StorageBackend<String, Map<String, Object>> databaseStore,
                    StorageBackend<String, Map<String, Object>> userStore,
                    StorageBackend<String, Map<String, Object>> operationStore,
                    ObjectMapper objectMapper,
                    String baseUrl,
                    CloudSqlDataPlane dataPlane,
                    boolean dataPlaneEnabled,
                    boolean strictLocations) {
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
        this.strictLocations = strictLocations;
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
        if (config.services().cloudsql().enabled()) {
            backfillBuiltInUsers();
        }
        if (config.services().cloudsql().enabled() && dataPlaneEnabled) {
            restartPersistedInstances();
        }
    }

    /**
     * Instances persisted by a build that did not list the engine's built-in user gain it on
     * their first startup after the upgrade, so {@code users.list} answers the same before and
     * after. Each instance is seeded once, tracked by a marker, never by whether the user record
     * happens to be present: {@code root@%} can be deleted through the API (the Terraform provider
     * does so right after creating every MySQL instance), and a presence check would bring it
     * back on every restart.
     */
    void backfillBuiltInUsers() {
        for (Map<String, Object> instance : allInstances()) {
            String project = stringValue(instance.get("project"));
            String name = stringValue(instance.get("name"));
            if (project == null || name == null) {
                continue;
            }
            try {
                seedBuiltInUser(project, name, engineOf(instance));
            } catch (GcpException e) {
                LOG.warnf("Skipping built-in user backfill project=%s instance=%s: %s", project, name, e.getMessage());
            }
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
        CloudSqlEngine engine = CloudSqlEngine.fromDatabaseVersion(stringValue(request.get("databaseVersion")));
        if (instanceStore.get(instanceKey(instance)).isPresent()) {
            throw GcpException.alreadyExists("Cloud SQL instance already exists: " + instance);
        }

        Map<String, Object> stored = normalizeInstance(project, instance, request);
        if (dataPlaneEnabled) {
            stored = dataPlane.startInstance(project, instance, stored);
        }
        putInstance(project, instance, stored);
        createSystemDatabases(project, instance, engine);
        seedBuiltInUser(project, instance, engine);

        LOG.infof("create Cloud SQL %s instance project=%s instance=%s", engine, project, instance);
        return createOperation(project, "CREATE", instance, stored);
    }

    public Map<String, Object> patchInstance(String project, String instance, Map<String, Object> body) {
        Map<String, Object> existing = getInstance(project, instance);
        Map<String, Object> patch = copy(body);
        rejectEngineChange(existing, patch);
        // rootPassword is write-only in the Admin API; a PATCH must not persist it either.
        patch.remove("rootPassword");
        merge(existing, patch);
        applyLocationPreference(existing);
        existing.put("kind", "sql#instance");
        existing.put("name", instance);
        existing.put("project", project);
        existing.put("connectionName", connectionName(project, existing, instance));
        existing.put("selfLink", instanceSelfLink(project, instance));
        instanceStore.put(instanceKey(instance), existing);
        LOG.infof("patch Cloud SQL instance project=%s instance=%s", project, instance);
        return createOperation(project, "UPDATE", instance, existing);
    }

    public Map<String, Object> updateInstance(String project, String instance, Map<String, Object> body) {
        Map<String, Object> existing = getInstance(project, instance);
        Map<String, Object> update = copy(body);
        rejectEngineChange(existing, update);
        merge(existing, update);
        Map<String, Object> stored = normalizeInstance(project, instance, existing);
        instanceStore.put(instanceKey(instance), stored);
        LOG.infof("update Cloud SQL instance project=%s instance=%s", project, instance);
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
        instanceStore.delete(builtInUserSeededKey(instance));
        deleteByPrefix(databaseStore, databasePrefix(instance));
        deleteByPrefix(userStore, userPrefix(instance));
        LOG.infof("delete Cloud SQL instance project=%s instance=%s", project, instance);
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
                                "POSTGRES_15", "POSTGRES_16", "POSTGRES_17", "POSTGRES_18"),
                        flag("max_connections", "INTEGER", false, "MYSQL_8_0", "MYSQL_8_4"),
                        flag("sql_mode", "STRING", false, "MYSQL_8_0", "MYSQL_8_4"),
                        flag("character_set_server", "STRING", true, "MYSQL_8_0", "MYSQL_8_4")));
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
        Map<String, Object> stored = normalizeDatabase(project, instance, engineOf(instanceMetadata), database, request);
        if (dataPlaneEnabled) {
            dataPlane.createDatabase(instanceMetadata, database,
                    stringValue(stored.get("charset")), stringValue(stored.get("collation")));
            for (Map<String, Object> user : users(instance)) {
                dataPlane.grantDatabaseAccess(instanceMetadata, database,
                        stringValue(user.get("name")), stringValue(user.get("host")));
            }
        }
        databaseStore.put(key, stored);
        LOG.infof("create Cloud SQL database project=%s instance=%s database=%s",
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
        Map<String, Object> stored = normalizeDatabase(project, instance,
                engineOf(getInstance(project, instance)), database, existing);
        databaseStore.put(databaseKey(instance, database), stored);
        LOG.infof("update Cloud SQL database project=%s instance=%s database=%s",
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
        if (engineOf(instanceMetadata).isSystemDatabase(database)) {
            throw GcpException.failedPrecondition("System database cannot be deleted: " + database);
        }
        Map<String, Object> existing = getDatabase(project, instance, database);
        if (dataPlaneEnabled) {
            dataPlane.deleteDatabase(instanceMetadata, database);
        }
        databaseStore.delete(databaseKey(instance, database));
        LOG.infof("delete Cloud SQL database project=%s instance=%s database=%s",
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
        CloudSqlEngine engine = engineOf(instanceMetadata);
        String host = engine.normalizeHost(stringValue(request.get("host")));
        String key = userKey(instance, user, host);
        if (userStore.get(key).isPresent()) {
            throw GcpException.alreadyExists("Cloud SQL user already exists: " + user);
        }
        if (engine.isReservedIdentity(user, host)) {
            throw GcpException.invalidArgument("User identity is reserved for the instance administrator: "
                    + user + (host == null ? "" : "@" + host));
        }
        if (dataPlaneEnabled) {
            dataPlane.createOrUpdateUser(instanceMetadata, user, host, stringValue(request.get("password")));
            for (String database : databaseNames(instance)) {
                dataPlane.grantDatabaseAccess(instanceMetadata, database, user, host);
            }
        }
        Map<String, Object> stored = normalizeUser(project, instance, user, host, request);
        userStore.put(key, stored);
        LOG.infof("create Cloud SQL user project=%s instance=%s user=%s", project, instance, user);
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
        Map<String, Object> instanceMetadata = getInstance(project, instance);
        validateUserName(user);
        host = engineOf(instanceMetadata).normalizeHost(host);
        return userStore.get(userKey(instance, user, host))
                .map(this::copy)
                .orElseThrow(() -> GcpException.notFound("Cloud SQL user not found: " + user));
    }

    public Map<String, Object> updateUser(String project, String instance, String user,
                                          String host, Map<String, Object> body) {
        Map<String, Object> instanceMetadata = getInstance(project, instance);
        host = engineOf(instanceMetadata).normalizeHost(host);
        Map<String, Object> existing = getUser(project, instance, user, host);
        Map<String, Object> update = copy(body);
        if (dataPlaneEnabled && update.containsKey("password")) {
            dataPlane.createOrUpdateUser(instanceMetadata, user, host, stringValue(update.get("password")));
            for (String database : databaseNames(instance)) {
                dataPlane.grantDatabaseAccess(instanceMetadata, database, user, host);
            }
        }
        merge(existing, update);
        Map<String, Object> stored = normalizeUser(project, instance, user, host, existing);
        userStore.put(userKey(instance, user, host), stored);
        LOG.infof("update Cloud SQL user project=%s instance=%s user=%s",
                project, instance, user);
        return createOperation(project, "UPDATE_USER", instance, stored);
    }

    public Map<String, Object> deleteUser(String project, String instance, String user, String host) {
        Map<String, Object> instanceMetadata = getInstance(project, instance);
        validateUserName(user);
        CloudSqlEngine engine = engineOf(instanceMetadata);
        host = engine.normalizeHost(host);
        String key = userKey(instance, user, host);
        Map<String, Object> existing = userStore.get(key)
                .orElseThrow(() -> GcpException.notFound("Cloud SQL user not found: " + user));
        if (engine.isReservedIdentity(user, host)) {
            // The data plane's own admin login; dropping it would strand every later DDL call.
            throw GcpException.failedPrecondition("Built-in user cannot be deleted: " + user);
        }
        if (dataPlaneEnabled) {
            dataPlane.deleteUser(instanceMetadata, user, host, databaseNames(instance));
        }
        userStore.delete(key);
        LOG.infof("delete Cloud SQL user project=%s instance=%s user=%s", project, instance, user);
        return createOperation(project, "DELETE_USER", instance, existing);
    }

    private Map<String, Object> normalizeInstance(String project, String instance, Map<String, Object> request) {
        Map<String, Object> stored = copy(request);
        // rootPassword is write-only in the Admin API (instances.insert only) and never echoed back.
        stored.remove("rootPassword");
        putDefault(stored, "kind", "sql#instance");
        stored.put("name", instance);
        stored.put("project", project);
        putDefault(stored, "backendType", "SECOND_GEN");
        putDefault(stored, "instanceType", "CLOUD_SQL_INSTANCE");
        putDefault(stored, "state", "RUNNABLE");
        putDefault(stored, "settings", defaultSettings());
        putDefault(stored, "region", regionOfZone(preferredZone(stored)).orElse("us-central1"));
        applyLocationPreference(stored);
        putDefault(stored, "etag", UUID.randomUUID().toString());
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
                LOG.warnf("Cloud SQL data plane was not restored project=%s instance=%s: %s",
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

    /** The databases a fresh instance already has, so {@code databases.list} matches the engine. */
    private void createSystemDatabases(String project, String instance, CloudSqlEngine engine) {
        for (String database : engine.systemDatabases()) {
            String key = databaseKey(instance, database);
            if (databaseStore.get(key).isEmpty()) {
                databaseStore.put(key, normalizeDatabase(project, instance, engine, database, Map.of("name", database)));
            }
        }
    }

    /**
     * Lists the account the data plane is provisioned with ({@code root@%} on MySQL,
     * {@code postgres} on PostgreSQL) like real Cloud SQL does, once per instance. The marker is a
     * sibling key in the instance store rather than a field on the instance record, so it never
     * shows up in {@code instances.get}; {@link #deleteInstance} removes it with the instance.
     */
    private void seedBuiltInUser(String project, String instance, CloudSqlEngine engine) {
        String marker = builtInUserSeededKey(instance);
        if (getForProject(instanceStore, project, marker).isPresent()) {
            return;
        }
        String user = engine.builtInUser();
        if (user != null) {
            String host = engine.normalizeHost(null);
            String key = userKey(instance, user, host);
            if (getForProject(userStore, project, key).isEmpty()) {
                putForProject(userStore, project, key, normalizeUser(project, instance, user, host, Map.of("name", user)));
            }
        }
        putForProject(instanceStore, project, marker, mapOf("instance", instance));
    }

    /**
     * The engine is fixed by the container an instance was provisioned with; the data plane
     * dispatches on {@code databaseVersion}, so letting it cross engines would run the MySQL
     * client against a PostgreSQL container (or restart the wrong image on the retained
     * volume). Real Cloud SQL has no cross-engine update either. Same-engine version changes
     * are left as they were.
     */
    private void rejectEngineChange(Map<String, Object> existing, Map<String, Object> request) {
        String requested = stringValue(request.get("databaseVersion"));
        if (requested != null && CloudSqlEngine.fromDatabaseVersion(requested) != engineOf(existing)) {
            throw GcpException.invalidArgument("databaseVersion cannot change the instance engine from "
                    + engineOf(existing) + " to " + CloudSqlEngine.fromDatabaseVersion(requested));
        }
    }

    @SuppressWarnings("unchecked")
    private static Optional<Map<String, Object>> getForProject(
            StorageBackend<String, Map<String, Object>> store, String project, String key) {
        if (store instanceof ProjectAwareStorageBackend<?> projectAware) {
            return ((ProjectAwareStorageBackend<Map<String, Object>>) projectAware).getForProject(project, key);
        }
        return store.get(key);
    }

    @SuppressWarnings("unchecked")
    private static void putForProject(StorageBackend<String, Map<String, Object>> store, String project,
                                      String key, Map<String, Object> value) {
        if (store instanceof ProjectAwareStorageBackend<?> projectAware) {
            ((ProjectAwareStorageBackend<Map<String, Object>>) projectAware).putForProject(project, key, value);
            return;
        }
        store.put(key, value);
    }

    private CloudSqlEngine engineOf(Map<String, Object> instanceMetadata) {
        return CloudSqlEngine.fromDatabaseVersion(stringValue(instanceMetadata.get("databaseVersion")));
    }

    private Map<String, Object> normalizeDatabase(String project, String instance, CloudSqlEngine engine,
                                                  String database, Map<String, Object> request) {
        Map<String, Object> stored = copy(request);
        putDefault(stored, "kind", "sql#database");
        stored.put("name", database);
        stored.put("project", project);
        stored.put("instance", instance);
        // Charset and collation are a pair: a request naming only one of them gets nothing
        // defaulted for the other, so the DDL never combines a caller's charset with the
        // engine-default collation of a different charset. The engine defaults apply only when
        // the request names neither.
        if (isBlank(stored.get("charset")) && isBlank(stored.get("collation"))) {
            stored.put("charset", engine.defaultCharset());
            stored.put("collation", engine.defaultCollation());
        }
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

    private List<Map<String, Object>> users(String instance) {
        return userStore.scan(k -> k.startsWith(userPrefix(instance))).stream()
                .filter(user -> stringValue(user.get("name")) != null && !stringValue(user.get("name")).isBlank())
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

    /**
     * {@code gceZone} and {@code secondaryGceZone} are output fields reporting where the instance
     * serves from; they follow {@code settings.locationPreference}. The secondary zone only exists
     * for {@code REGIONAL} instances, and {@code no_secondary_zone} clears it.
     */
    @SuppressWarnings("unchecked")
    private void applyLocationPreference(Map<String, Object> instance) {
        String region = stringValue(instance.get("region"));
        Map<String, Object> preference = null;
        if (instance.get("settings") instanceof Map<?, ?> settings
                && settings.get("locationPreference") instanceof Map<?, ?> value) {
            preference = (Map<String, Object>) value;
        }
        String zone = preference == null ? null : stringValue(preference.get("zone"));
        String secondaryZone = preference == null ? null : stringValue(preference.get("secondaryZone"));
        boolean hasSecondary = !isBlank(secondaryZone) && !NO_SECONDARY_ZONE.equals(secondaryZone);
        if (strictLocations) {
            requireZoneInRegion("settings.locationPreference.zone", zone, region);
            if (hasSecondary) {
                requireZoneInRegion("settings.locationPreference.secondaryZone", secondaryZone, region);
            }
        }
        if (preference != null) {
            putDefault(preference, "kind", "sql#locationPreference");
        }
        if (!isBlank(zone)) {
            instance.put("gceZone", zone);
        } else {
            putDefault(instance, "gceZone", region + "-a");
        }
        boolean regional = instance.get("settings") instanceof Map<?, ?> settings
                && "REGIONAL".equals(stringValue(settings.get("availabilityType")));
        if (regional && hasSecondary) {
            instance.put("secondaryGceZone", secondaryZone);
        } else if (!regional || NO_SECONDARY_ZONE.equals(secondaryZone)) {
            instance.remove("secondaryGceZone");
        }
    }

    private static void requireZoneInRegion(String field, String zone, String region) {
        if (!isBlank(zone) && (region == null || !zone.startsWith(region + "-"))) {
            throw GcpException.invalidArgument("Invalid request: " + field + " " + zone
                    + " is not in region " + region + ".");
        }
    }

    private static String preferredZone(Map<String, Object> instance) {
        if (instance.get("settings") instanceof Map<?, ?> settings
                && settings.get("locationPreference") instanceof Map<?, ?> preference) {
            return stringValue(preference.get("zone"));
        }
        return null;
    }

    private static Optional<String> regionOfZone(String zone) {
        if (isBlank(zone) || zone.lastIndexOf('-') <= 0) {
            return Optional.empty();
        }
        return Optional.of(zone.substring(0, zone.lastIndexOf('-')));
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

    private static boolean isBlank(Object value) {
        return value == null || value.toString().isBlank();
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

    /** Outside the {@code instances/} prefix, so instance scans never see it. */
    private static String builtInUserSeededKey(String instance) {
        return "built-in-user-seeded/" + instance;
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

    /**
     * {@code <encoded host>/<user>}: the host is percent-encoded so it can never contain the
     * {@code /} separator (MySQL hosts may be CIDR ranges), which keeps the key unambiguous for
     * any user name. PostgreSQL users have no host and keep their pre-existing {@code /user} key.
     */
    private static String userKey(String instance, String user, String host) {
        String encodedHost = host == null ? "" : java.net.URLEncoder.encode(host, java.nio.charset.StandardCharsets.UTF_8);
        return userPrefix(instance) + encodedHost + "/" + user;
    }

    private static String operationKey(String operation) {
        return "operations/" + operation;
    }
}
