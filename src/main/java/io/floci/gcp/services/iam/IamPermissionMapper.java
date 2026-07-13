package io.floci.gcp.services.iam;

import io.floci.gcp.services.cloudrun.CloudRunUrlService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps REST requests to GCP IAM permission strings for CTF enforcement.
 */
@ApplicationScoped
public class IamPermissionMapper {

    private final CloudRunUrlService cloudRunUrlService;

    @Inject
    public IamPermissionMapper(CloudRunUrlService cloudRunUrlService) {
        this.cloudRunUrlService = cloudRunUrlService;
    }

    private static final Pattern SERVICE_ACCOUNTS = Pattern.compile(
            "^/?v1/projects/[^/]+/serviceAccounts(?:/([^/]+))?/?$");

    private static final Pattern SERVICE_ACCOUNT_KEYS = Pattern.compile(
            "^/?v1/projects/[^/]+/serviceAccounts/[^/]+/keys(?:/([^/]+))?/?$");

    private static final Pattern SECRET_COLLECTION = Pattern.compile(
            "^/?v1/projects/[^/]+/secrets/?$");

    private static final Pattern SECRET_RESOURCE = Pattern.compile(
            "^/?v1/projects/[^/]+/secrets/([^/]+)/?$");

    private static final Pattern SECRET_VERSIONS_COLLECTION = Pattern.compile(
            "^/?v1/projects/[^/]+/secrets/[^/]+/versions/?$");

    private static final Pattern SECRET_VERSION_RESOURCE = Pattern.compile(
            "^/?v1/projects/[^/]+/secrets/[^/]+/versions/([^/]+)/?$");

    /** JSON API: /storage/v1/b and /storage/v1/b/{bucket}[/...] */
    private static final Pattern STORAGE_BUCKETS = Pattern.compile(
            "^/?storage/v1/b(?:/([^/]+)(?:/(.*))?)?/?$");

    /** Upload API: /upload/storage/v1/b/{bucket}/o */
    private static final Pattern STORAGE_UPLOAD = Pattern.compile(
            "^/?upload/storage/v1/b/[^/]+/o/?$");

    /** Download API: /download/storage/v1/b/{bucket}/o/{object} */
    private static final Pattern STORAGE_DOWNLOAD = Pattern.compile(
            "^/?download/storage/v1/b/[^/]+/o/.+$");

    private static final Pattern PUBSUB_TOPIC_COLLECTION = Pattern.compile(
            "^/?v1/projects/[^/]+/topics/?$");

    private static final Pattern PUBSUB_TOPIC_RESOURCE = Pattern.compile(
            "^/?v1/projects/[^/]+/topics/([^/]+)/?$");

    private static final Pattern PUBSUB_SUBSCRIPTION_COLLECTION = Pattern.compile(
            "^/?v1/projects/[^/]+/subscriptions/?$");

    private static final Pattern PUBSUB_SUBSCRIPTION_RESOURCE = Pattern.compile(
            "^/?v1/projects/[^/]+/subscriptions/([^/]+)/?$");

    /** Location resource used by {@code :generateRandomBytes}. */
    private static final Pattern KMS_LOCATION = Pattern.compile(
            "^/?v1/projects/[^/]+/locations/[^/]+/?$");

    private static final Pattern KMS_KEY_RINGS = Pattern.compile(
            "^/?v1/projects/[^/]+/locations/[^/]+/keyRings/?$");

    private static final Pattern KMS_KEY_RING = Pattern.compile(
            "^/?v1/projects/[^/]+/locations/[^/]+/keyRings/[^/]+/?$");

    private static final Pattern KMS_CRYPTO_KEYS = Pattern.compile(
            "^/?v1/projects/[^/]+/locations/[^/]+/keyRings/[^/]+/cryptoKeys/?$");

    private static final Pattern KMS_CRYPTO_KEY = Pattern.compile(
            "^/?v1/projects/[^/]+/locations/[^/]+/keyRings/[^/]+/cryptoKeys/[^/]+/?$");

    private static final Pattern KMS_CRYPTO_KEY_VERSIONS = Pattern.compile(
            "^/?v1/projects/[^/]+/locations/[^/]+/keyRings/[^/]+/cryptoKeys/[^/]+/cryptoKeyVersions/?$");

    private static final Pattern KMS_CRYPTO_KEY_VERSION = Pattern.compile(
            "^/?v1/projects/[^/]+/locations/[^/]+/keyRings/[^/]+/cryptoKeys/[^/]+/cryptoKeyVersions/[^/]+/?$");

    private static final Pattern KMS_PUBLIC_KEY = Pattern.compile(
            "^/?v1/projects/[^/]+/locations/[^/]+/keyRings/[^/]+/cryptoKeys/[^/]+"
                    + "/cryptoKeyVersions/[^/]+/publicKey/?$");

    private static final Pattern SCHEDULER_JOBS = Pattern.compile(
            "^/?v1/projects/[^/]+/locations/[^/]+/jobs/?$");

    private static final Pattern SCHEDULER_JOB = Pattern.compile(
            "^/?v1/projects/[^/]+/locations/[^/]+/jobs/([^/]+)/?$");

    /** Cloud SQL Admin API under /v1, /v1beta4, or legacy /sql/v1beta4. */
    private static final String CLOUDSQL_PREFIX = "^/?(?:sql/)?(?:v1|v1beta4)";

    private static final Pattern CLOUDSQL_INSTANCES = Pattern.compile(
            CLOUDSQL_PREFIX + "/projects/[^/]+/instances/?$");

    private static final Pattern CLOUDSQL_INSTANCE = Pattern.compile(
            CLOUDSQL_PREFIX + "/projects/[^/]+/instances/([^/]+)/?$");

    private static final Pattern CLOUDSQL_CONNECT = Pattern.compile(
            CLOUDSQL_PREFIX + "/projects/[^/]+/instances/[^/]+/connectSettings/?$");

    private static final Pattern CLOUDSQL_DATABASES = Pattern.compile(
            CLOUDSQL_PREFIX + "/projects/[^/]+/instances/[^/]+/databases/?$");

    private static final Pattern CLOUDSQL_DATABASE = Pattern.compile(
            CLOUDSQL_PREFIX + "/projects/[^/]+/instances/[^/]+/databases/([^/]+)/?$");

    private static final Pattern CLOUDSQL_USERS = Pattern.compile(
            CLOUDSQL_PREFIX + "/projects/[^/]+/instances/[^/]+/users(?:/([^/]+))?/?$");

    private static final Pattern CLOUDSQL_TIERS = Pattern.compile(
            CLOUDSQL_PREFIX + "/projects/[^/]+/tiers/?$");

    private static final Pattern CLOUDSQL_OPERATIONS = Pattern.compile(
            CLOUDSQL_PREFIX + "/projects/[^/]+/operations(?:/([^/]+))?/?$");

    private static final Pattern CLOUDSQL_FLAGS = Pattern.compile(
            CLOUDSQL_PREFIX + "/flags/?$");

    private static final Pattern KAFKA_CLUSTERS = Pattern.compile(
            "^/?v1/projects/[^/]+/locations/[^/]+/clusters/?$");

    private static final Pattern KAFKA_CLUSTER = Pattern.compile(
            "^/?v1/projects/[^/]+/locations/[^/]+/clusters/([^/]+)/?$");

    private static final Pattern KAFKA_TOPICS = Pattern.compile(
            "^/?v1/projects/[^/]+/locations/[^/]+/clusters/[^/]+/topics/?$");

    private static final Pattern KAFKA_TOPIC = Pattern.compile(
            "^/?v1/projects/[^/]+/locations/[^/]+/clusters/[^/]+/topics/([^/]+)/?$");

    private static final Pattern KAFKA_CONSUMER_GROUPS = Pattern.compile(
            "^/?v1/projects/[^/]+/locations/[^/]+/clusters/[^/]+/consumerGroups/?$");

    private static final Pattern KAFKA_CONSUMER_GROUP = Pattern.compile(
            "^/?v1/projects/[^/]+/locations/[^/]+/clusters/[^/]+/consumerGroups/([^/]+)/?$");

    /** Bare project resource: Resource Manager GET or Datastore / CRM custom methods. */
    private static final Pattern PROJECT_ONLY = Pattern.compile(
            "^/?v1/projects/[^/]+/?$");

    private static final Set<String> DATASTORE_CUSTOM_METHODS = Set.of(
            "lookup",
            "commit",
            "runQuery",
            "runAggregationQuery",
            "beginTransaction",
            "rollback",
            "allocateIds",
            "reserveIds");

    private static final Pattern EVENTARC_TRIGGERS = Pattern.compile(
            "^/?v1/projects/[^/]+/locations/[^/]+/triggers/?$");

    private static final Pattern EVENTARC_TRIGGER = Pattern.compile(
            "^/?v1/projects/[^/]+/locations/[^/]+/triggers/[^/]+/?$");

    private static final Pattern EVENTARC_CHANNELS = Pattern.compile(
            "^/?v1/projects/[^/]+/locations/[^/]+/channels/?$");

    private static final Pattern EVENTARC_CHANNEL = Pattern.compile(
            "^/?v1/projects/[^/]+/locations/[^/]+/channels/[^/]+/?$");

    private static final Pattern EVENTARC_PROVIDERS = Pattern.compile(
            "^/?v1/projects/[^/]+/locations/[^/]+/providers/?$");

    private static final Pattern EVENTARC_PROVIDER = Pattern.compile(
            "^/?v1/projects/[^/]+/locations/[^/]+/providers/[^/]+/?$");

    private static final Pattern CLOUD_FUNCTIONS_COLLECTION = Pattern.compile(
            "^/?v2/projects/[^/]+/locations/[^/]+/functions/?$");

    private static final Pattern CLOUD_FUNCTIONS_RESOURCE = Pattern.compile(
            "^/?v2/projects/[^/]+/locations/[^/]+/functions/[^/]+/?$");

    private static final Pattern CLOUD_RUN_SERVICES = Pattern.compile(
            "^/?v2/projects/[^/]+/locations/[^/]+/services/?$");

    private static final Pattern CLOUD_RUN_SERVICE = Pattern.compile(
            "^/?v2/projects/[^/]+/locations/[^/]+/services/[^/]+/?$");

    private static final Pattern CLOUD_RUN_REVISIONS = Pattern.compile(
            "^/?v2/projects/[^/]+/locations/[^/]+/services/[^/]+/revisions/?$");

    private static final Pattern CLOUD_RUN_REVISION = Pattern.compile(
            "^/?v2/projects/[^/]+/locations/[^/]+/services/[^/]+/revisions/[^/]+/?$");

    private static final Pattern CLOUD_RUN_JOBS = Pattern.compile(
            "^/?v2/projects/[^/]+/locations/[^/]+/jobs/?$");

    private static final Pattern CLOUD_RUN_JOB = Pattern.compile(
            "^/?v2/projects/[^/]+/locations/[^/]+/jobs/[^/]+/?$");

    /** Legacy prefixed invocation proxy path. */
    private static final Pattern CLOUD_RUN_INVOKE = Pattern.compile(
            "^/?run/v2/projects/[^/]+/locations/[^/]+/services/[^/]+(?:/.*)?/?$");

    private static final Pattern LOGGING_ENTRIES = Pattern.compile("^/?v2/entries/?$");

    private static final Pattern LOGGING_LOGS = Pattern.compile("^/?v2/projects/[^/]+/logs/?$");

    private static final Pattern LOGGING_LOG = Pattern.compile("^/?v2/projects/[^/]+/logs/[^/]+/?$");

    private static final Pattern MONITORING_TIME_SERIES = Pattern.compile(
            "^/?v3/projects/[^/]+/timeSeries/?$");

    private static final Pattern MONITORING_METRIC_DESCRIPTORS = Pattern.compile(
            "^/?v3/projects/[^/]+/metricDescriptors/?$");

    private static final Pattern MONITORING_METRIC_DESCRIPTOR = Pattern.compile(
            "^/?v3/projects/[^/]+/metricDescriptors/.+$");

    private static final Pattern MONITORING_MRD_COLLECTION = Pattern.compile(
            "^/?v3/projects/[^/]+/monitoredResourceDescriptors/?$");

    private static final Pattern MONITORING_MRD_RESOURCE = Pattern.compile(
            "^/?v3/projects/[^/]+/monitoredResourceDescriptors/.+$");

    private static final Pattern CLOUDTASKS_QUEUES = Pattern.compile(
            "^/?v2/projects/[^/]+/locations/[^/]+/queues/?$");

    private static final Pattern CLOUDTASKS_QUEUE = Pattern.compile(
            "^/?v2/projects/[^/]+/locations/[^/]+/queues/[^/]+/?$");

    private static final Pattern CLOUDTASKS_TASKS = Pattern.compile(
            "^/?v2/projects/[^/]+/locations/[^/]+/queues/[^/]+/tasks/?$");

    private static final Pattern CLOUDTASKS_TASK = Pattern.compile(
            "^/?v2/projects/[^/]+/locations/[^/]+/queues/[^/]+/tasks/[^/]+/?$");

    private static final Pattern BQ_DATASETS = Pattern.compile(
            "^/?bigquery/v2/projects/[^/]+/datasets/?$");

    private static final Pattern BQ_DATASET = Pattern.compile(
            "^/?bigquery/v2/projects/[^/]+/datasets/[^/]+/?$");

    private static final Pattern BQ_TABLES = Pattern.compile(
            "^/?bigquery/v2/projects/[^/]+/datasets/[^/]+/tables/?$");

    private static final Pattern BQ_TABLE = Pattern.compile(
            "^/?bigquery/v2/projects/[^/]+/datasets/[^/]+/tables/[^/]+/?$");

    private static final Pattern BQ_INSERT_ALL = Pattern.compile(
            "^/?bigquery/v2/projects/[^/]+/datasets/[^/]+/tables/[^/]+/insertAll/?$");

    private static final Pattern BQ_TABLE_DATA = Pattern.compile(
            "^/?bigquery/v2/projects/[^/]+/datasets/[^/]+/tables/[^/]+/data/?$");

    private static final Pattern BQ_QUERIES = Pattern.compile(
            "^/?bigquery/v2/projects/[^/]+/queries/?$");

    private static final Pattern BQ_QUERY_RESULTS = Pattern.compile(
            "^/?bigquery/v2/projects/[^/]+/queries/[^/]+/?$");

    private static final Pattern BQ_JOBS = Pattern.compile(
            "^/?bigquery/v2/projects/[^/]+/jobs/?$");

    private static final Pattern BQ_JOB = Pattern.compile(
            "^/?bigquery/v2/projects/[^/]+/jobs/[^/]+/?$");

    private static final Pattern BQ_JOB_CANCEL = Pattern.compile(
            "^/?bigquery/v2/projects/[^/]+/jobs/[^/]+/cancel/?$");

    private static final Pattern BQ_JOB_DELETE = Pattern.compile(
            "^/?bigquery/v2/projects/[^/]+/jobs/[^/]+/delete/?$");

    private static final Pattern FIREBASE_CLIENT_ACCOUNTS = Pattern.compile(
            "^/?identitytoolkit\\.googleapis\\.com/v1/accounts/?$");

    private static final Pattern FIREBASE_ADMIN_ACCOUNTS = Pattern.compile(
            "^/?identitytoolkit\\.googleapis\\.com/v1/projects/[^/]+/accounts/?$");

    private static final Pattern FIREBASE_SECURE_TOKEN = Pattern.compile(
            "^/?securetoken\\.googleapis\\.com/v1/token/?$");

    private static final Pattern FIREBASE_EMULATOR_ACCOUNTS = Pattern.compile(
            "^/?emulator/v1/projects/[^/]+/accounts/?$");

    private static final Pattern SERVICEUSAGE_COLLECTION = Pattern.compile(
            "^/?v1/projects/[^/]+/services/?$");

    private static final Pattern SERVICEUSAGE_RESOURCE = Pattern.compile(
            "^/?v1/projects/[^/]+/services/[^/]+/?$");

    public Optional<String> map(ContainerRequestContext ctx) {
        if (ctx == null) {
            return Optional.empty();
        }
        UriInfo uriInfo = ctx.getUriInfo();
        if (uriInfo == null) {
            return Optional.empty();
        }
        String rawPath = uriInfo.getPath();
        if (rawPath == null || rawPath.isBlank()) {
            return Optional.empty();
        }
        String method = ctx.getMethod();
        if (method == null) {
            return Optional.empty();
        }

        String customMethod = null;
        String path = rawPath;
        int colon = rawPath.indexOf(':');
        if (colon >= 0) {
            customMethod = rawPath.substring(colon + 1);
            path = rawPath.substring(0, colon);
        }

        String httpMethod = method.toUpperCase();

        if (SERVICE_ACCOUNTS.matcher(path).matches()
                || SERVICE_ACCOUNT_KEYS.matcher(path).matches()) {
            return mapServiceAccounts(path, httpMethod, customMethod);
        }
        if (isSecretManagerPath(path)) {
            return mapSecretManager(path, httpMethod, customMethod);
        }
        if (isPubSubPath(path)) {
            return mapPubSub(path, httpMethod, customMethod);
        }
        if (isCloudKmsPath(path)) {
            return mapCloudKms(path, httpMethod, customMethod);
        }
        if (isCloudSchedulerPath(path)) {
            return mapCloudScheduler(path, httpMethod, customMethod);
        }
        if (isCloudSqlPath(path)) {
            return mapCloudSql(path, httpMethod);
        }
        if (isManagedKafkaPath(path)) {
            return mapManagedKafka(path, httpMethod);
        }
        if (isCloudFunctionsPath(path)) {
            return mapCloudFunctions(path, httpMethod, customMethod);
        }
        if (isCloudRunPath(path)) {
            return mapCloudRun(path, httpMethod, customMethod);
        }
        if (isEventarcPath(path)) {
            return mapEventarc(path, httpMethod, customMethod);
        }
        if (isLoggingPath(path)) {
            return mapLogging(path, httpMethod, customMethod);
        }
        if (isMonitoringPath(path)) {
            return mapMonitoring(path, httpMethod);
        }
        if (isCloudTasksPath(path)) {
            return mapCloudTasks(path, httpMethod, customMethod);
        }
        if (isBigQueryPath(path)) {
            return mapBigQuery(path, httpMethod);
        }
        if (isFirebaseAuthPath(path)) {
            return mapFirebaseAuth(path, httpMethod, customMethod);
        }
        if (isServiceUsagePath(path)) {
            return mapServiceUsage(path, httpMethod, customMethod);
        }
        if (PROJECT_ONLY.matcher(path).matches()) {
            return mapProjectOnly(httpMethod, customMethod);
        }
        Optional<String> gcs = mapGcs(path, httpMethod);
        if (gcs.isPresent()) {
            return gcs;
        }
        if (isCloudRunInvocationHost(ctx)) {
            return Optional.of("run.routes.invoke");
        }
        return Optional.empty();
    }

    private boolean isCloudRunInvocationHost(ContainerRequestContext ctx) {
        if (cloudRunUrlService == null) {
            return false;
        }
        String host = ctx.getHeaderString(HttpHeaders.HOST);
        if (host == null || host.isBlank()) {
            return false;
        }
        return cloudRunUrlService.parseHost(host).isPresent();
    }

    /**
     * Maps {@code /v1/projects/{project}} GET (Resource Manager) and
     * {@code /v1/projects/{project}:{method}} for Datastore HTTP and CRM IAM custom methods.
     * Firestore has no REST surface in floci-gcp (gRPC only).
     */
    private static Optional<String> mapProjectOnly(String method, String customMethod) {
        if (customMethod != null && !customMethod.isBlank()) {
            if (DATASTORE_CUSTOM_METHODS.contains(customMethod)) {
                return mapDatastoreCustomMethod(customMethod);
            }
            return switch (customMethod) {
                case "getIamPolicy" -> Optional.of("resourcemanager.projects.getIamPolicy");
                case "setIamPolicy" -> Optional.of("resourcemanager.projects.setIamPolicy");
                default -> Optional.empty();
            };
        }
        return switch (method) {
            case "GET" -> Optional.of("resourcemanager.projects.get");
            default -> Optional.empty();
        };
    }

    private static Optional<String> mapDatastoreCustomMethod(String customMethod) {
        return switch (customMethod) {
            case "lookup", "beginTransaction", "rollback" -> Optional.of("datastore.entities.get");
            case "runQuery", "runAggregationQuery" -> Optional.of("datastore.entities.list");
            case "commit" -> Optional.of("datastore.entities.create");
            case "allocateIds", "reserveIds" -> Optional.of("datastore.entities.allocateIds");
            default -> Optional.empty();
        };
    }

    private static boolean isEventarcPath(String path) {
        return EVENTARC_TRIGGERS.matcher(path).matches()
                || EVENTARC_TRIGGER.matcher(path).matches()
                || EVENTARC_CHANNELS.matcher(path).matches()
                || EVENTARC_CHANNEL.matcher(path).matches()
                || EVENTARC_PROVIDERS.matcher(path).matches()
                || EVENTARC_PROVIDER.matcher(path).matches();
    }

    private static Optional<String> mapEventarc(String path, String method, String customMethod) {
        if (customMethod != null && !customMethod.isBlank()) {
            return switch (customMethod) {
                case "getIamPolicy" -> Optional.of("eventarc.triggers.getIamPolicy");
                case "setIamPolicy" -> Optional.of("eventarc.triggers.setIamPolicy");
                default -> Optional.empty();
            };
        }

        if (EVENTARC_TRIGGERS.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("eventarc.triggers.list");
                case "POST" -> Optional.of("eventarc.triggers.create");
                default -> Optional.empty();
            };
        }

        if (EVENTARC_TRIGGER.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("eventarc.triggers.get");
                case "PATCH" -> Optional.of("eventarc.triggers.update");
                case "DELETE" -> Optional.of("eventarc.triggers.delete");
                default -> Optional.empty();
            };
        }

        if (EVENTARC_CHANNELS.matcher(path).matches()) {
            return "GET".equals(method) ? Optional.of("eventarc.channels.list") : Optional.empty();
        }

        if (EVENTARC_CHANNEL.matcher(path).matches()) {
            return "GET".equals(method) ? Optional.of("eventarc.channels.get") : Optional.empty();
        }

        if (EVENTARC_PROVIDERS.matcher(path).matches()) {
            return "GET".equals(method) ? Optional.of("eventarc.providers.list") : Optional.empty();
        }

        if (EVENTARC_PROVIDER.matcher(path).matches()) {
            return "GET".equals(method) ? Optional.of("eventarc.providers.get") : Optional.empty();
        }

        return Optional.empty();
    }

    private static Optional<String> mapServiceAccounts(String path, String method, String customMethod) {
        if (customMethod != null && !customMethod.isBlank()) {
            return switch (customMethod) {
                case "signBlob" -> Optional.of("iam.serviceAccounts.signBlob");
                default -> Optional.empty();
            };
        }

        Matcher keys = SERVICE_ACCOUNT_KEYS.matcher(path);
        if (keys.matches()) {
            String keyId = keys.group(1);
            boolean hasKeyId = keyId != null && !keyId.isBlank();
            return switch (method) {
                case "GET" -> hasKeyId
                        ? Optional.empty()
                        : Optional.of("iam.serviceAccountKeys.list");
                case "POST" -> hasKeyId
                        ? Optional.empty()
                        : Optional.of("iam.serviceAccountKeys.create");
                case "DELETE" -> hasKeyId
                        ? Optional.of("iam.serviceAccountKeys.delete")
                        : Optional.empty();
                default -> Optional.empty();
            };
        }

        Matcher m = SERVICE_ACCOUNTS.matcher(path);
        if (!m.matches()) {
            return Optional.empty();
        }
        String emailOrId = m.group(1);
        boolean hasId = emailOrId != null && !emailOrId.isBlank();

        return switch (method) {
            case "GET" -> Optional.of(hasId ? "iam.serviceAccounts.get" : "iam.serviceAccounts.list");
            case "POST" -> hasId ? Optional.empty() : Optional.of("iam.serviceAccounts.create");
            default -> Optional.empty();
        };
    }

    private static boolean isSecretManagerPath(String path) {
        return SECRET_COLLECTION.matcher(path).matches()
                || SECRET_RESOURCE.matcher(path).matches()
                || SECRET_VERSIONS_COLLECTION.matcher(path).matches()
                || SECRET_VERSION_RESOURCE.matcher(path).matches();
    }

    private static Optional<String> mapSecretManager(String path, String method, String customMethod) {
        if (customMethod != null && !customMethod.isBlank()) {
            return switch (customMethod) {
                case "addVersion" -> Optional.of("secretmanager.versions.add");
                case "access" -> Optional.of("secretmanager.versions.access");
                case "destroy" -> Optional.of("secretmanager.versions.destroy");
                case "disable" -> Optional.of("secretmanager.versions.disable");
                case "enable" -> Optional.of("secretmanager.versions.enable");
                case "getIamPolicy" -> Optional.of("secretmanager.secrets.getIamPolicy");
                case "setIamPolicy" -> Optional.of("secretmanager.secrets.setIamPolicy");
                default -> Optional.empty();
            };
        }

        if (SECRET_COLLECTION.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("secretmanager.secrets.list");
                case "POST" -> Optional.of("secretmanager.secrets.create");
                default -> Optional.empty();
            };
        }

        if (SECRET_VERSION_RESOURCE.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("secretmanager.versions.get");
                default -> Optional.empty();
            };
        }

        if (SECRET_VERSIONS_COLLECTION.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("secretmanager.versions.list");
                default -> Optional.empty();
            };
        }

        if (SECRET_RESOURCE.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("secretmanager.secrets.get");
                case "PATCH" -> Optional.of("secretmanager.secrets.update");
                case "DELETE" -> Optional.of("secretmanager.secrets.delete");
                default -> Optional.empty();
            };
        }

        return Optional.empty();
    }

    private static boolean isPubSubPath(String path) {
        return PUBSUB_TOPIC_COLLECTION.matcher(path).matches()
                || PUBSUB_TOPIC_RESOURCE.matcher(path).matches()
                || PUBSUB_SUBSCRIPTION_COLLECTION.matcher(path).matches()
                || PUBSUB_SUBSCRIPTION_RESOURCE.matcher(path).matches();
    }

    private static Optional<String> mapPubSub(String path, String method, String customMethod) {
        if (customMethod != null && !customMethod.isBlank()) {
            return switch (customMethod) {
                case "publish" -> Optional.of("pubsub.topics.publish");
                case "pull", "acknowledge" -> Optional.of("pubsub.subscriptions.consume");
                default -> Optional.empty();
            };
        }

        if (PUBSUB_TOPIC_COLLECTION.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("pubsub.topics.list");
                default -> Optional.empty();
            };
        }

        if (PUBSUB_TOPIC_RESOURCE.matcher(path).matches()) {
            return switch (method) {
                case "PUT" -> Optional.of("pubsub.topics.create");
                case "GET" -> Optional.of("pubsub.topics.get");
                case "PATCH" -> Optional.of("pubsub.topics.update");
                case "DELETE" -> Optional.of("pubsub.topics.delete");
                default -> Optional.empty();
            };
        }

        if (PUBSUB_SUBSCRIPTION_COLLECTION.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("pubsub.subscriptions.list");
                default -> Optional.empty();
            };
        }

        if (PUBSUB_SUBSCRIPTION_RESOURCE.matcher(path).matches()) {
            return switch (method) {
                case "PUT" -> Optional.of("pubsub.subscriptions.create");
                case "GET" -> Optional.of("pubsub.subscriptions.get");
                case "PATCH" -> Optional.of("pubsub.subscriptions.update");
                case "DELETE" -> Optional.of("pubsub.subscriptions.delete");
                default -> Optional.empty();
            };
        }

        return Optional.empty();
    }

    private static boolean isCloudKmsPath(String path) {
        return KMS_LOCATION.matcher(path).matches()
                || KMS_KEY_RINGS.matcher(path).matches()
                || KMS_KEY_RING.matcher(path).matches()
                || KMS_CRYPTO_KEYS.matcher(path).matches()
                || KMS_CRYPTO_KEY.matcher(path).matches()
                || KMS_CRYPTO_KEY_VERSIONS.matcher(path).matches()
                || KMS_CRYPTO_KEY_VERSION.matcher(path).matches()
                || KMS_PUBLIC_KEY.matcher(path).matches();
    }

    private static Optional<String> mapCloudKms(String path, String method, String customMethod) {
        if (customMethod != null && !customMethod.isBlank()) {
            return switch (customMethod) {
                case "encrypt" -> Optional.of("cloudkms.cryptoKeyVersions.useToEncrypt");
                case "decrypt", "asymmetricDecrypt" -> Optional.of("cloudkms.cryptoKeyVersions.useToDecrypt");
                case "asymmetricSign" -> Optional.of("cloudkms.cryptoKeyVersions.useToSign");
                case "updatePrimaryVersion" -> Optional.of("cloudkms.cryptoKeys.update");
                case "destroy" -> Optional.of("cloudkms.cryptoKeyVersions.destroy");
                case "restore" -> Optional.of("cloudkms.cryptoKeyVersions.restore");
                case "generateRandomBytes" -> Optional.of("cloudkms.locations.generateRandomBytes");
                default -> Optional.empty();
            };
        }

        if (KMS_PUBLIC_KEY.matcher(path).matches()) {
            return "GET".equals(method)
                    ? Optional.of("cloudkms.cryptoKeyVersions.viewPublicKey")
                    : Optional.empty();
        }

        if (KMS_KEY_RINGS.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("cloudkms.keyRings.list");
                case "POST" -> Optional.of("cloudkms.keyRings.create");
                default -> Optional.empty();
            };
        }

        if (KMS_KEY_RING.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("cloudkms.keyRings.get");
                default -> Optional.empty();
            };
        }

        if (KMS_CRYPTO_KEYS.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("cloudkms.cryptoKeys.list");
                case "POST" -> Optional.of("cloudkms.cryptoKeys.create");
                default -> Optional.empty();
            };
        }

        if (KMS_CRYPTO_KEY.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("cloudkms.cryptoKeys.get");
                case "PATCH" -> Optional.of("cloudkms.cryptoKeys.update");
                default -> Optional.empty();
            };
        }

        if (KMS_CRYPTO_KEY_VERSIONS.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("cloudkms.cryptoKeyVersions.list");
                case "POST" -> Optional.of("cloudkms.cryptoKeyVersions.create");
                default -> Optional.empty();
            };
        }

        if (KMS_CRYPTO_KEY_VERSION.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("cloudkms.cryptoKeyVersions.get");
                case "PATCH" -> Optional.of("cloudkms.cryptoKeyVersions.update");
                default -> Optional.empty();
            };
        }

        return Optional.empty();
    }

    private static boolean isCloudSchedulerPath(String path) {
        return SCHEDULER_JOBS.matcher(path).matches()
                || SCHEDULER_JOB.matcher(path).matches();
    }

    private static Optional<String> mapCloudScheduler(String path, String method, String customMethod) {
        if (customMethod != null && !customMethod.isBlank()) {
            return switch (customMethod) {
                case "pause" -> Optional.of("cloudscheduler.jobs.pause");
                case "resume" -> Optional.of("cloudscheduler.jobs.enable");
                case "run" -> Optional.of("cloudscheduler.jobs.run");
                default -> Optional.empty();
            };
        }

        if (SCHEDULER_JOBS.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("cloudscheduler.jobs.list");
                case "POST" -> Optional.of("cloudscheduler.jobs.create");
                default -> Optional.empty();
            };
        }

        if (SCHEDULER_JOB.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("cloudscheduler.jobs.get");
                case "PATCH" -> Optional.of("cloudscheduler.jobs.update");
                case "DELETE" -> Optional.of("cloudscheduler.jobs.delete");
                default -> Optional.empty();
            };
        }

        return Optional.empty();
    }

    private static boolean isCloudSqlPath(String path) {
        return CLOUDSQL_INSTANCES.matcher(path).matches()
                || CLOUDSQL_INSTANCE.matcher(path).matches()
                || CLOUDSQL_CONNECT.matcher(path).matches()
                || CLOUDSQL_DATABASES.matcher(path).matches()
                || CLOUDSQL_DATABASE.matcher(path).matches()
                || CLOUDSQL_USERS.matcher(path).matches()
                || CLOUDSQL_TIERS.matcher(path).matches()
                || CLOUDSQL_OPERATIONS.matcher(path).matches()
                || CLOUDSQL_FLAGS.matcher(path).matches();
    }

    private static Optional<String> mapCloudSql(String path, String method) {
        if (CLOUDSQL_FLAGS.matcher(path).matches() || CLOUDSQL_TIERS.matcher(path).matches()) {
            return "GET".equals(method) ? Optional.of("cloudsql.instances.list") : Optional.empty();
        }

        if (CLOUDSQL_OPERATIONS.matcher(path).matches()) {
            return "GET".equals(method) ? Optional.of("cloudsql.instances.get") : Optional.empty();
        }

        if (CLOUDSQL_CONNECT.matcher(path).matches()) {
            return "GET".equals(method) ? Optional.of("cloudsql.instances.get") : Optional.empty();
        }

        if (CLOUDSQL_INSTANCES.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("cloudsql.instances.list");
                case "POST" -> Optional.of("cloudsql.instances.create");
                default -> Optional.empty();
            };
        }

        if (CLOUDSQL_DATABASES.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("cloudsql.databases.list");
                case "POST" -> Optional.of("cloudsql.databases.create");
                default -> Optional.empty();
            };
        }

        if (CLOUDSQL_DATABASE.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("cloudsql.databases.get");
                case "PUT", "PATCH" -> Optional.of("cloudsql.databases.update");
                case "DELETE" -> Optional.of("cloudsql.databases.delete");
                default -> Optional.empty();
            };
        }

        if (CLOUDSQL_USERS.matcher(path).matches()) {
            Matcher m = CLOUDSQL_USERS.matcher(path);
            if (!m.matches()) {
                return Optional.empty();
            }
            String userName = m.group(1);
            boolean hasUser = userName != null && !userName.isBlank();
            return switch (method) {
                case "GET" -> Optional.of(hasUser ? "cloudsql.users.get" : "cloudsql.users.list");
                case "POST" -> Optional.of("cloudsql.users.create");
                case "PUT" -> Optional.of("cloudsql.users.update");
                case "DELETE" -> Optional.of("cloudsql.users.delete");
                default -> Optional.empty();
            };
        }

        if (CLOUDSQL_INSTANCE.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("cloudsql.instances.get");
                case "PATCH", "PUT" -> Optional.of("cloudsql.instances.update");
                case "DELETE" -> Optional.of("cloudsql.instances.delete");
                default -> Optional.empty();
            };
        }

        return Optional.empty();
    }

    private static boolean isManagedKafkaPath(String path) {
        return KAFKA_CLUSTERS.matcher(path).matches()
                || KAFKA_CLUSTER.matcher(path).matches()
                || KAFKA_TOPICS.matcher(path).matches()
                || KAFKA_TOPIC.matcher(path).matches()
                || KAFKA_CONSUMER_GROUPS.matcher(path).matches()
                || KAFKA_CONSUMER_GROUP.matcher(path).matches();
    }

    private static Optional<String> mapManagedKafka(String path, String method) {
        if (KAFKA_CLUSTERS.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("managedkafka.clusters.list");
                case "POST" -> Optional.of("managedkafka.clusters.create");
                default -> Optional.empty();
            };
        }

        if (KAFKA_TOPICS.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("managedkafka.topics.list");
                case "POST" -> Optional.of("managedkafka.topics.create");
                default -> Optional.empty();
            };
        }

        if (KAFKA_TOPIC.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("managedkafka.topics.get");
                case "PATCH" -> Optional.of("managedkafka.topics.update");
                case "DELETE" -> Optional.of("managedkafka.topics.delete");
                default -> Optional.empty();
            };
        }

        if (KAFKA_CONSUMER_GROUPS.matcher(path).matches()) {
            return "GET".equals(method)
                    ? Optional.of("managedkafka.consumerGroups.list")
                    : Optional.empty();
        }

        if (KAFKA_CONSUMER_GROUP.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("managedkafka.consumerGroups.get");
                case "PATCH" -> Optional.of("managedkafka.consumerGroups.update");
                case "DELETE" -> Optional.of("managedkafka.consumerGroups.delete");
                default -> Optional.empty();
            };
        }

        if (KAFKA_CLUSTER.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("managedkafka.clusters.get");
                case "PATCH" -> Optional.of("managedkafka.clusters.update");
                case "DELETE" -> Optional.of("managedkafka.clusters.delete");
                default -> Optional.empty();
            };
        }

        return Optional.empty();
    }

    private static boolean isCloudFunctionsPath(String path) {
        return CLOUD_FUNCTIONS_COLLECTION.matcher(path).matches()
                || CLOUD_FUNCTIONS_RESOURCE.matcher(path).matches();
    }

    private static Optional<String> mapCloudFunctions(String path, String method, String customMethod) {
        if (customMethod != null && !customMethod.isBlank()) {
            return switch (customMethod) {
                case "generateUploadUrl" -> Optional.of("cloudfunctions.functions.sourceCodeSet");
                default -> Optional.empty();
            };
        }

        if (CLOUD_FUNCTIONS_COLLECTION.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("cloudfunctions.functions.list");
                case "POST" -> Optional.of("cloudfunctions.functions.create");
                default -> Optional.empty();
            };
        }

        if (CLOUD_FUNCTIONS_RESOURCE.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("cloudfunctions.functions.get");
                case "DELETE" -> Optional.of("cloudfunctions.functions.delete");
                default -> Optional.empty();
            };
        }

        return Optional.empty();
    }

    private static boolean isCloudRunPath(String path) {
        return CLOUD_RUN_SERVICES.matcher(path).matches()
                || CLOUD_RUN_SERVICE.matcher(path).matches()
                || CLOUD_RUN_REVISIONS.matcher(path).matches()
                || CLOUD_RUN_REVISION.matcher(path).matches()
                || CLOUD_RUN_JOBS.matcher(path).matches()
                || CLOUD_RUN_JOB.matcher(path).matches()
                || CLOUD_RUN_INVOKE.matcher(path).matches();
    }

    private static Optional<String> mapCloudRun(String path, String method, String customMethod) {
        if (CLOUD_RUN_INVOKE.matcher(path).matches()) {
            return Optional.of("run.routes.invoke");
        }

        if (customMethod != null && !customMethod.isBlank()) {
            if (CLOUD_RUN_JOB.matcher(path).matches()) {
                return switch (customMethod) {
                    case "getIamPolicy" -> Optional.of("run.jobs.getIamPolicy");
                    case "setIamPolicy" -> Optional.of("run.jobs.setIamPolicy");
                    case "run" -> Optional.of("run.jobs.run");
                    default -> Optional.empty();
                };
            }
            if (CLOUD_RUN_SERVICE.matcher(path).matches()) {
                return switch (customMethod) {
                    case "getIamPolicy" -> Optional.of("run.services.getIamPolicy");
                    case "setIamPolicy" -> Optional.of("run.services.setIamPolicy");
                    default -> Optional.empty();
                };
            }
            return Optional.empty();
        }

        if (CLOUD_RUN_REVISIONS.matcher(path).matches()) {
            return "GET".equals(method) ? Optional.of("run.revisions.list") : Optional.empty();
        }

        if (CLOUD_RUN_REVISION.matcher(path).matches()) {
            return "GET".equals(method) ? Optional.of("run.revisions.get") : Optional.empty();
        }

        if (CLOUD_RUN_SERVICES.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("run.services.list");
                case "POST" -> Optional.of("run.services.create");
                default -> Optional.empty();
            };
        }

        if (CLOUD_RUN_SERVICE.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("run.services.get");
                case "PATCH" -> Optional.of("run.services.update");
                case "DELETE" -> Optional.of("run.services.delete");
                default -> Optional.empty();
            };
        }

        if (CLOUD_RUN_JOBS.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("run.jobs.list");
                case "POST" -> Optional.of("run.jobs.create");
                default -> Optional.empty();
            };
        }

        if (CLOUD_RUN_JOB.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("run.jobs.get");
                case "PATCH" -> Optional.of("run.jobs.update");
                case "DELETE" -> Optional.of("run.jobs.delete");
                default -> Optional.empty();
            };
        }

        return Optional.empty();
    }

    private static boolean isLoggingPath(String path) {
        return LOGGING_ENTRIES.matcher(path).matches()
                || LOGGING_LOGS.matcher(path).matches()
                || LOGGING_LOG.matcher(path).matches();
    }

    private static Optional<String> mapLogging(String path, String method, String customMethod) {
        if (LOGGING_ENTRIES.matcher(path).matches()) {
            if (customMethod == null || customMethod.isBlank()) {
                return Optional.empty();
            }
            return switch (customMethod) {
                case "write" -> Optional.of("logging.logEntries.create");
                case "list" -> Optional.of("logging.logEntries.list");
                default -> Optional.empty();
            };
        }
        if (LOGGING_LOGS.matcher(path).matches()) {
            return "GET".equals(method)
                    ? Optional.of("logging.logs.list")
                    : Optional.empty();
        }
        if (LOGGING_LOG.matcher(path).matches()) {
            return "DELETE".equals(method)
                    ? Optional.of("logging.logs.delete")
                    : Optional.empty();
        }
        return Optional.empty();
    }

    private static boolean isMonitoringPath(String path) {
        return MONITORING_TIME_SERIES.matcher(path).matches()
                || MONITORING_METRIC_DESCRIPTORS.matcher(path).matches()
                || MONITORING_METRIC_DESCRIPTOR.matcher(path).matches()
                || MONITORING_MRD_COLLECTION.matcher(path).matches()
                || MONITORING_MRD_RESOURCE.matcher(path).matches();
    }

    private static Optional<String> mapMonitoring(String path, String method) {
        if (MONITORING_TIME_SERIES.matcher(path).matches()) {
            return switch (method) {
                case "POST" -> Optional.of("monitoring.timeSeries.create");
                case "GET" -> Optional.of("monitoring.timeSeries.list");
                default -> Optional.empty();
            };
        }
        if (MONITORING_METRIC_DESCRIPTORS.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("monitoring.metricDescriptors.list");
                case "POST" -> Optional.of("monitoring.metricDescriptors.create");
                default -> Optional.empty();
            };
        }
        if (MONITORING_METRIC_DESCRIPTOR.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("monitoring.metricDescriptors.get");
                case "DELETE" -> Optional.of("monitoring.metricDescriptors.delete");
                default -> Optional.empty();
            };
        }
        if (MONITORING_MRD_COLLECTION.matcher(path).matches()) {
            return "GET".equals(method)
                    ? Optional.of("monitoring.monitoredResourceDescriptors.list")
                    : Optional.empty();
        }
        if (MONITORING_MRD_RESOURCE.matcher(path).matches()) {
            return "GET".equals(method)
                    ? Optional.of("monitoring.monitoredResourceDescriptors.get")
                    : Optional.empty();
        }
        return Optional.empty();
    }

    private static boolean isCloudTasksPath(String path) {
        return CLOUDTASKS_QUEUES.matcher(path).matches()
                || CLOUDTASKS_QUEUE.matcher(path).matches()
                || CLOUDTASKS_TASKS.matcher(path).matches()
                || CLOUDTASKS_TASK.matcher(path).matches();
    }

    private static Optional<String> mapCloudTasks(String path, String method, String customMethod) {
        if (customMethod != null && !customMethod.isBlank()) {
            return switch (customMethod) {
                case "pause" -> Optional.of("cloudtasks.queues.pause");
                case "resume" -> Optional.of("cloudtasks.queues.resume");
                case "purge" -> Optional.of("cloudtasks.queues.purge");
                case "run" -> Optional.of("cloudtasks.tasks.run");
                default -> Optional.empty();
            };
        }

        if (CLOUDTASKS_QUEUES.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("cloudtasks.queues.list");
                case "POST" -> Optional.of("cloudtasks.queues.create");
                default -> Optional.empty();
            };
        }

        if (CLOUDTASKS_QUEUE.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("cloudtasks.queues.get");
                case "PATCH" -> Optional.of("cloudtasks.queues.update");
                case "DELETE" -> Optional.of("cloudtasks.queues.delete");
                default -> Optional.empty();
            };
        }

        if (CLOUDTASKS_TASKS.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("cloudtasks.tasks.list");
                case "POST" -> Optional.of("cloudtasks.tasks.create");
                default -> Optional.empty();
            };
        }

        if (CLOUDTASKS_TASK.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("cloudtasks.tasks.get");
                case "DELETE" -> Optional.of("cloudtasks.tasks.delete");
                default -> Optional.empty();
            };
        }

        return Optional.empty();
    }


    private static boolean isBigQueryPath(String path) {
        return BQ_DATASETS.matcher(path).matches()
                || BQ_DATASET.matcher(path).matches()
                || BQ_TABLES.matcher(path).matches()
                || BQ_TABLE.matcher(path).matches()
                || BQ_INSERT_ALL.matcher(path).matches()
                || BQ_TABLE_DATA.matcher(path).matches()
                || BQ_QUERIES.matcher(path).matches()
                || BQ_QUERY_RESULTS.matcher(path).matches()
                || BQ_JOBS.matcher(path).matches()
                || BQ_JOB.matcher(path).matches()
                || BQ_JOB_CANCEL.matcher(path).matches()
                || BQ_JOB_DELETE.matcher(path).matches();
    }

    private static Optional<String> mapBigQuery(String path, String method) {
        if (BQ_INSERT_ALL.matcher(path).matches()) {
            return "POST".equals(method)
                    ? Optional.of("bigquery.tables.updateData")
                    : Optional.empty();
        }
        if (BQ_TABLE_DATA.matcher(path).matches()) {
            return "GET".equals(method)
                    ? Optional.of("bigquery.tables.getData")
                    : Optional.empty();
        }
        if (BQ_JOB_CANCEL.matcher(path).matches()) {
            return "POST".equals(method)
                    ? Optional.of("bigquery.jobs.update")
                    : Optional.empty();
        }
        if (BQ_JOB_DELETE.matcher(path).matches()) {
            return "DELETE".equals(method)
                    ? Optional.of("bigquery.jobs.delete")
                    : Optional.empty();
        }
        if (BQ_QUERIES.matcher(path).matches()) {
            return "POST".equals(method)
                    ? Optional.of("bigquery.jobs.create")
                    : Optional.empty();
        }
        if (BQ_QUERY_RESULTS.matcher(path).matches()) {
            return "GET".equals(method)
                    ? Optional.of("bigquery.jobs.get")
                    : Optional.empty();
        }
        if (BQ_JOBS.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("bigquery.jobs.list");
                case "POST" -> Optional.of("bigquery.jobs.create");
                default -> Optional.empty();
            };
        }
        if (BQ_JOB.matcher(path).matches()) {
            return "GET".equals(method)
                    ? Optional.of("bigquery.jobs.get")
                    : Optional.empty();
        }
        if (BQ_TABLES.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("bigquery.tables.list");
                case "POST" -> Optional.of("bigquery.tables.create");
                default -> Optional.empty();
            };
        }
        if (BQ_TABLE.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("bigquery.tables.get");
                case "PATCH", "PUT", "POST" -> Optional.of("bigquery.tables.update");
                case "DELETE" -> Optional.of("bigquery.tables.delete");
                default -> Optional.empty();
            };
        }
        if (BQ_DATASETS.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("bigquery.datasets.list");
                case "POST" -> Optional.of("bigquery.datasets.create");
                default -> Optional.empty();
            };
        }
        if (BQ_DATASET.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Optional.of("bigquery.datasets.get");
                case "PATCH", "PUT", "POST" -> Optional.of("bigquery.datasets.update");
                case "DELETE" -> Optional.of("bigquery.datasets.delete");
                default -> Optional.empty();
            };
        }
        return Optional.empty();
    }

    private static boolean isFirebaseAuthPath(String path) {
        return FIREBASE_CLIENT_ACCOUNTS.matcher(path).matches()
                || FIREBASE_ADMIN_ACCOUNTS.matcher(path).matches()
                || FIREBASE_SECURE_TOKEN.matcher(path).matches()
                || FIREBASE_EMULATOR_ACCOUNTS.matcher(path).matches();
    }

    private static Optional<String> mapFirebaseAuth(String path, String method, String customMethod) {
        if (FIREBASE_SECURE_TOKEN.matcher(path).matches()) {
            return "POST".equals(method)
                    ? Optional.of("firebaseauth.users.createSession")
                    : Optional.empty();
        }
        if (FIREBASE_EMULATOR_ACCOUNTS.matcher(path).matches()) {
            return "DELETE".equals(method)
                    ? Optional.of("firebaseauth.users.delete")
                    : Optional.empty();
        }
        if (FIREBASE_CLIENT_ACCOUNTS.matcher(path).matches()
                || FIREBASE_ADMIN_ACCOUNTS.matcher(path).matches()) {
            if (customMethod != null && !customMethod.isBlank()) {
                return switch (customMethod) {
                    case "signUp" -> Optional.of("firebaseauth.users.create");
                    case "signInWithPassword", "signInWithCustomToken" ->
                            Optional.of("firebaseauth.users.createSession");
                    case "lookup", "batchGet" -> Optional.of("firebaseauth.users.get");
                    case "update" -> Optional.of("firebaseauth.users.update");
                    case "delete", "batchDelete" -> Optional.of("firebaseauth.users.delete");
                    default -> Optional.empty();
                };
            }
            if (FIREBASE_ADMIN_ACCOUNTS.matcher(path).matches() && "POST".equals(method)) {
                return Optional.of("firebaseauth.users.create");
            }
        }
        return Optional.empty();
    }

    private static boolean isServiceUsagePath(String path) {
        return SERVICEUSAGE_COLLECTION.matcher(path).matches()
                || SERVICEUSAGE_RESOURCE.matcher(path).matches();
    }

    private static Optional<String> mapServiceUsage(String path, String method, String customMethod) {
        if (customMethod != null && !customMethod.isBlank()) {
            return switch (customMethod) {
                case "enable", "batchEnable" -> Optional.of("serviceusage.services.enable");
                case "disable" -> Optional.of("serviceusage.services.disable");
                case "batchGet" -> Optional.of("serviceusage.services.get");
                default -> Optional.empty();
            };
        }
        if (SERVICEUSAGE_COLLECTION.matcher(path).matches()) {
            return "GET".equals(method)
                    ? Optional.of("serviceusage.services.list")
                    : Optional.empty();
        }
        if (SERVICEUSAGE_RESOURCE.matcher(path).matches()) {
            return "GET".equals(method)
                    ? Optional.of("serviceusage.services.get")
                    : Optional.empty();
        }
        return Optional.empty();
    }

    private static Optional<String> mapGcs(String path, String method) {
        if (STORAGE_UPLOAD.matcher(path).matches()) {
            return switch (method) {
                case "POST", "PUT" -> Optional.of("storage.objects.create");
                default -> Optional.empty();
            };
        }
        if (STORAGE_DOWNLOAD.matcher(path).matches()) {
            return "GET".equals(method) ? Optional.of("storage.objects.get") : Optional.empty();
        }

        Matcher m = STORAGE_BUCKETS.matcher(path);
        if (!m.matches()) {
            return Optional.empty();
        }
        String bucket = m.group(1);
        String rest = m.group(2);

        if (bucket == null || bucket.isBlank()) {
            return mapBucketCollection(method);
        }
        if (rest == null || rest.isBlank()) {
            return mapBucketResource(method);
        }
        if ("iam".equals(rest)) {
            return mapBucketIam(method);
        }
        if ("o".equals(rest)) {
            return mapObjectCollection(method);
        }
        if (rest.startsWith("o/")) {
            return mapObjectResource(method);
        }
        return Optional.empty();
    }

    private static Optional<String> mapBucketCollection(String method) {
        return switch (method) {
            case "GET" -> Optional.of("storage.buckets.list");
            case "POST" -> Optional.of("storage.buckets.create");
            default -> Optional.empty();
        };
    }

    private static Optional<String> mapBucketResource(String method) {
        return switch (method) {
            case "GET" -> Optional.of("storage.buckets.get");
            default -> Optional.empty();
        };
    }

    private static Optional<String> mapBucketIam(String method) {
        return switch (method) {
            case "GET" -> Optional.of("storage.buckets.getIamPolicy");
            case "PUT" -> Optional.of("storage.buckets.setIamPolicy");
            default -> Optional.empty();
        };
    }

    private static Optional<String> mapObjectCollection(String method) {
        return switch (method) {
            case "GET" -> Optional.of("storage.objects.list");
            default -> Optional.empty();
        };
    }

    private static Optional<String> mapObjectResource(String method) {
        return switch (method) {
            case "GET" -> Optional.of("storage.objects.get");
            case "DELETE" -> Optional.of("storage.objects.delete");
            case "POST", "PUT" -> Optional.of("storage.objects.create");
            case "PATCH" -> Optional.of("storage.objects.update");
            default -> Optional.empty();
        };
    }
}
