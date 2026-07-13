package io.floci.gcp.services.iam;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates GCP-style IAM allow bindings against a principal and permission (CTF Stage 0+).
 */
@ApplicationScoped
public class IamPolicyEvaluator {

    private static final String ROLE_OWNER = "roles/owner";
    private static final String ROLE_SA_ADMIN = "roles/iam.serviceAccountAdmin";
    private static final String ROLE_SECRET_ACCESSOR = "roles/secretmanager.secretAccessor";
    private static final String ROLE_SECRET_ADMIN = "roles/secretmanager.admin";
    private static final String ROLE_STORAGE_OBJECT_VIEWER = "roles/storage.objectViewer";
    private static final String ROLE_STORAGE_OBJECT_ADMIN = "roles/storage.objectAdmin";
    private static final String ROLE_STORAGE_ADMIN = "roles/storage.admin";
    private static final String ROLE_PUBSUB_PUBLISHER = "roles/pubsub.publisher";
    private static final String ROLE_PUBSUB_SUBSCRIBER = "roles/pubsub.subscriber";
    private static final String ROLE_PUBSUB_ADMIN = "roles/pubsub.admin";
    private static final String ROLE_CLOUDKMS_ENCRYPTER_DECRYPTER = "roles/cloudkms.cryptoKeyEncrypterDecrypter";
    private static final String ROLE_CLOUDKMS_ADMIN = "roles/cloudkms.admin";
    private static final String ROLE_LOGGING_VIEWER = "roles/logging.viewer";
    private static final String ROLE_LOGGING_LOG_WRITER = "roles/logging.logWriter";
    private static final String ROLE_LOGGING_ADMIN = "roles/logging.admin";
    private static final String ROLE_MONITORING_VIEWER = "roles/monitoring.viewer";
    private static final String ROLE_MONITORING_METRIC_WRITER = "roles/monitoring.metricWriter";
    private static final String ROLE_MONITORING_ADMIN = "roles/monitoring.admin";
    private static final String ROLE_CLOUDTASKS_VIEWER = "roles/cloudtasks.viewer";
    private static final String ROLE_CLOUDTASKS_ADMIN = "roles/cloudtasks.admin";
    private static final String ROLE_CLOUDFUNCTIONS_DEVELOPER = "roles/cloudfunctions.developer";
    private static final String ROLE_CLOUDFUNCTIONS_ADMIN = "roles/cloudfunctions.admin";
    private static final String ROLE_RUN_ADMIN = "roles/run.admin";
    private static final String ROLE_RUN_DEVELOPER = "roles/run.developer";
    private static final String ROLE_RUN_INVOKER = "roles/run.invoker";
    private static final String ROLE_BIGQUERY_ADMIN = "roles/bigquery.admin";
    private static final String ROLE_BIGQUERY_DATA_VIEWER = "roles/bigquery.dataViewer";
    private static final String ROLE_BIGQUERY_JOB_USER = "roles/bigquery.jobUser";
    private static final String ROLE_FIREBASEAUTH_ADMIN = "roles/firebaseauth.admin";
    private static final String ROLE_IDENTITYTOOLKIT_VIEWER = "roles/identitytoolkit.viewer";
    private static final String ROLE_SERVICEUSAGE_ADMIN = "roles/serviceusage.serviceUsageAdmin";
    private static final String ROLE_SERVICEUSAGE_CONSUMER = "roles/serviceusage.serviceUsageConsumer";
    private static final String ROLE_CLOUDSCHEDULER_ADMIN = "roles/cloudscheduler.admin";
    private static final String ROLE_CLOUDSCHEDULER_VIEWER = "roles/cloudscheduler.viewer";
    private static final String ROLE_CLOUDSQL_ADMIN = "roles/cloudsql.admin";
    private static final String ROLE_CLOUDSQL_VIEWER = "roles/cloudsql.viewer";
    private static final String ROLE_MANAGEDKAFKA_ADMIN = "roles/managedkafka.admin";
    private static final String ROLE_MANAGEDKAFKA_VIEWER = "roles/managedkafka.viewer";
    private static final String ROLE_MANAGEDKAFKA_CLUSTER_EDITOR = "roles/managedkafka.clusterEditor";
    private static final String ROLE_DATASTORE_VIEWER = "roles/datastore.viewer";
    private static final String ROLE_DATASTORE_USER = "roles/datastore.user";
    private static final String ROLE_EVENTARC_VIEWER = "roles/eventarc.viewer";
    private static final String ROLE_EVENTARC_ADMIN = "roles/eventarc.admin";
    private static final String ROLE_BROWSER = "roles/browser";
    private static final String ROLE_PROJECT_IAM_ADMIN = "roles/resourcemanager.projectIamAdmin";

    private static final Set<String> SA_ADMIN_PERMISSIONS = Set.of(
            "iam.serviceAccounts.list",
            "iam.serviceAccounts.create",
            "iam.serviceAccounts.get",
            "iam.serviceAccounts.signBlob",
            "iam.serviceAccountKeys.list",
            "iam.serviceAccountKeys.create",
            "iam.serviceAccountKeys.delete");

    private static final Set<String> SECRET_ACCESSOR_PERMISSIONS = Set.of(
            "secretmanager.versions.access");

    /** Permissions emitted by {@link IamPermissionMapper} for Secret Manager REST. */
    private static final Set<String> SECRET_ADMIN_PERMISSIONS = Set.of(
            "secretmanager.secrets.create",
            "secretmanager.secrets.get",
            "secretmanager.secrets.list",
            "secretmanager.secrets.update",
            "secretmanager.secrets.delete",
            "secretmanager.secrets.getIamPolicy",
            "secretmanager.secrets.setIamPolicy",
            "secretmanager.versions.add",
            "secretmanager.versions.access",
            "secretmanager.versions.destroy",
            "secretmanager.versions.disable",
            "secretmanager.versions.enable",
            "secretmanager.versions.get",
            "secretmanager.versions.list");

    private static final Set<String> STORAGE_OBJECT_VIEWER_PERMISSIONS = Set.of(
            "storage.objects.get",
            "storage.objects.list");

    private static final Set<String> STORAGE_OBJECT_ADMIN_PERMISSIONS = Set.of(
            "storage.objects.create",
            "storage.objects.get",
            "storage.objects.update",
            "storage.objects.delete",
            "storage.objects.list");

    private static final Set<String> STORAGE_ADMIN_PERMISSIONS = Set.of(
            "storage.buckets.create",
            "storage.buckets.get",
            "storage.buckets.list",
            "storage.buckets.getIamPolicy",
            "storage.buckets.setIamPolicy",
            "storage.objects.create",
            "storage.objects.get",
            "storage.objects.update",
            "storage.objects.delete",
            "storage.objects.list");

    private static final Set<String> PUBSUB_PUBLISHER_PERMISSIONS = Set.of(
            "pubsub.topics.publish");

    private static final Set<String> PUBSUB_SUBSCRIBER_PERMISSIONS = Set.of(
            "pubsub.subscriptions.consume");

    /** Permissions emitted by REST and gRPC Pub/Sub mappers. */
    private static final Set<String> PUBSUB_ADMIN_PERMISSIONS = Set.of(
            "pubsub.topics.create",
            "pubsub.topics.get",
            "pubsub.topics.list",
            "pubsub.topics.update",
            "pubsub.topics.delete",
            "pubsub.topics.publish",
            "pubsub.topics.detachSubscription",
            "pubsub.subscriptions.create",
            "pubsub.subscriptions.get",
            "pubsub.subscriptions.list",
            "pubsub.subscriptions.update",
            "pubsub.subscriptions.delete",
            "pubsub.subscriptions.consume",
            "pubsub.snapshots.create",
            "pubsub.snapshots.get",
            "pubsub.snapshots.list",
            "pubsub.snapshots.update",
            "pubsub.snapshots.delete");

    /** Encrypt/decrypt only, matching GCP {@code roles/cloudkms.cryptoKeyEncrypterDecrypter}. */
    private static final Set<String> CLOUDKMS_ENCRYPTER_DECRYPTER_PERMISSIONS = Set.of(
            "cloudkms.cryptoKeyVersions.useToEncrypt",
            "cloudkms.cryptoKeyVersions.useToDecrypt");

    /**
     * Management permissions for Cloud KMS REST (no crypto ops), matching GCP
     * {@code roles/cloudkms.admin}.
     */
    private static final Set<String> CLOUDKMS_ADMIN_PERMISSIONS = Set.of(
            "cloudkms.keyRings.create",
            "cloudkms.keyRings.get",
            "cloudkms.keyRings.list",
            "cloudkms.cryptoKeys.create",
            "cloudkms.cryptoKeys.get",
            "cloudkms.cryptoKeys.list",
            "cloudkms.cryptoKeys.update",
            "cloudkms.cryptoKeyVersions.create",
            "cloudkms.cryptoKeyVersions.get",
            "cloudkms.cryptoKeyVersions.list",
            "cloudkms.cryptoKeyVersions.update",
            "cloudkms.cryptoKeyVersions.destroy",
            "cloudkms.cryptoKeyVersions.restore");

    /** Read permissions for Cloud Logging REST, matching {@code roles/logging.viewer}. */
    private static final Set<String> LOGGING_VIEWER_PERMISSIONS = Set.of(
            "logging.logEntries.list",
            "logging.logs.list");

    /** Write-only log ingest, matching {@code roles/logging.logWriter}. */
    private static final Set<String> LOGGING_LOG_WRITER_PERMISSIONS = Set.of(
            "logging.logEntries.create");

    /** Permissions emitted by {@link IamPermissionMapper} for Cloud Logging REST. */
    private static final Set<String> LOGGING_ADMIN_PERMISSIONS = Set.of(
            "logging.logEntries.create",
            "logging.logEntries.list",
            "logging.logs.list",
            "logging.logs.delete");

    /** Read permissions for Monitoring REST, matching {@code roles/monitoring.viewer}. */
    private static final Set<String> MONITORING_VIEWER_PERMISSIONS = Set.of(
            "monitoring.timeSeries.list",
            "monitoring.metricDescriptors.get",
            "monitoring.metricDescriptors.list",
            "monitoring.monitoredResourceDescriptors.get",
            "monitoring.monitoredResourceDescriptors.list");

    /**
     * Metric ingest role matching {@code roles/monitoring.metricWriter}
     * (create time series and descriptor catalog write/list, not list time series).
     */
    private static final Set<String> MONITORING_METRIC_WRITER_PERMISSIONS = Set.of(
            "monitoring.timeSeries.create",
            "monitoring.metricDescriptors.create",
            "monitoring.metricDescriptors.get",
            "monitoring.metricDescriptors.list",
            "monitoring.monitoredResourceDescriptors.get",
            "monitoring.monitoredResourceDescriptors.list");

    /** Permissions emitted by {@link IamPermissionMapper} for Cloud Monitoring REST. */
    private static final Set<String> MONITORING_ADMIN_PERMISSIONS = Set.of(
            "monitoring.timeSeries.create",
            "monitoring.timeSeries.list",
            "monitoring.metricDescriptors.create",
            "monitoring.metricDescriptors.get",
            "monitoring.metricDescriptors.list",
            "monitoring.metricDescriptors.delete",
            "monitoring.monitoredResourceDescriptors.get",
            "monitoring.monitoredResourceDescriptors.list");

    /** Read permissions for Cloud Tasks REST, matching {@code roles/cloudtasks.viewer}. */
    private static final Set<String> CLOUDTASKS_VIEWER_PERMISSIONS = Set.of(
            "cloudtasks.queues.get",
            "cloudtasks.queues.list",
            "cloudtasks.tasks.get",
            "cloudtasks.tasks.list");

    /** Permissions emitted by {@link IamPermissionMapper} for Cloud Tasks REST. */
    private static final Set<String> CLOUDTASKS_ADMIN_PERMISSIONS = Set.of(
            "cloudtasks.queues.create",
            "cloudtasks.queues.get",
            "cloudtasks.queues.list",
            "cloudtasks.queues.update",
            "cloudtasks.queues.delete",
            "cloudtasks.queues.pause",
            "cloudtasks.queues.resume",
            "cloudtasks.queues.purge",
            "cloudtasks.tasks.create",
            "cloudtasks.tasks.get",
            "cloudtasks.tasks.list",
            "cloudtasks.tasks.delete",
            "cloudtasks.tasks.run");

    /**
     * Control-plane permissions emitted by {@link IamPermissionMapper} for Cloud Functions REST.
     * Shared by {@code roles/cloudfunctions.developer} and {@code roles/cloudfunctions.admin}
     * for the Stage 0 mapped surface (create, get, list, delete, sourceCodeSet).
     */
    private static final Set<String> CLOUDFUNCTIONS_CONTROL_PLANE_PERMISSIONS = Set.of(
            "cloudfunctions.functions.create",
            "cloudfunctions.functions.get",
            "cloudfunctions.functions.list",
            "cloudfunctions.functions.delete",
            "cloudfunctions.functions.sourceCodeSet");

    /**
     * Permissions emitted by {@link IamPermissionMapper} for Cloud Run REST (services, revisions,
     * jobs) plus legacy invoke. Matches the GCP {@code roles/run.admin} subset used in CTF.
     */
    private static final Set<String> RUN_ADMIN_PERMISSIONS = Set.of(
            "run.services.create",
            "run.services.get",
            "run.services.list",
            "run.services.update",
            "run.services.delete",
            "run.services.getIamPolicy",
            "run.services.setIamPolicy",
            "run.revisions.get",
            "run.revisions.list",
            "run.jobs.create",
            "run.jobs.get",
            "run.jobs.list",
            "run.jobs.update",
            "run.jobs.delete",
            "run.jobs.getIamPolicy",
            "run.jobs.setIamPolicy",
            "run.jobs.run",
            "run.routes.invoke");

    /**
     * GCP {@code roles/run.developer} mapped subset: manage services/jobs/revisions but not
     * setIamPolicy, invoke, or run jobs (those stay on invoker/admin).
     */
    private static final Set<String> RUN_DEVELOPER_PERMISSIONS = Set.of(
            "run.services.create",
            "run.services.get",
            "run.services.list",
            "run.services.update",
            "run.services.delete",
            "run.services.getIamPolicy",
            "run.revisions.get",
            "run.revisions.list",
            "run.jobs.create",
            "run.jobs.get",
            "run.jobs.list",
            "run.jobs.update",
            "run.jobs.delete",
            "run.jobs.getIamPolicy");

    /** GCP {@code roles/run.invoker}: invoke services and run jobs. */
    private static final Set<String> RUN_INVOKER_PERMISSIONS = Set.of(
            "run.routes.invoke",
            "run.jobs.run");

    /** Permissions emitted by {@link IamPermissionMapper} for Cloud Scheduler REST. */
    private static final Set<String> CLOUDSCHEDULER_ADMIN_PERMISSIONS = Set.of(
            "cloudscheduler.jobs.create",
            "cloudscheduler.jobs.get",
            "cloudscheduler.jobs.list",
            "cloudscheduler.jobs.update",
            "cloudscheduler.jobs.delete",
            "cloudscheduler.jobs.pause",
            "cloudscheduler.jobs.enable",
            "cloudscheduler.jobs.run");

    private static final Set<String> CLOUDSCHEDULER_VIEWER_PERMISSIONS = Set.of(
            "cloudscheduler.jobs.get",
            "cloudscheduler.jobs.list");

    /** Permissions emitted by {@link IamPermissionMapper} for Cloud SQL Admin REST. */
    private static final Set<String> CLOUDSQL_ADMIN_PERMISSIONS = Set.of(
            "cloudsql.instances.create",
            "cloudsql.instances.get",
            "cloudsql.instances.list",
            "cloudsql.instances.update",
            "cloudsql.instances.delete",
            "cloudsql.databases.create",
            "cloudsql.databases.get",
            "cloudsql.databases.list",
            "cloudsql.databases.update",
            "cloudsql.databases.delete",
            "cloudsql.users.create",
            "cloudsql.users.get",
            "cloudsql.users.list",
            "cloudsql.users.update",
            "cloudsql.users.delete");

    private static final Set<String> CLOUDSQL_VIEWER_PERMISSIONS = Set.of(
            "cloudsql.instances.get",
            "cloudsql.instances.list",
            "cloudsql.databases.get",
            "cloudsql.databases.list",
            "cloudsql.users.get",
            "cloudsql.users.list");

    /** Permissions emitted by {@link IamPermissionMapper} for Managed Kafka REST. */
    private static final Set<String> MANAGEDKAFKA_ADMIN_PERMISSIONS = Set.of(
            "managedkafka.clusters.create",
            "managedkafka.clusters.get",
            "managedkafka.clusters.list",
            "managedkafka.clusters.update",
            "managedkafka.clusters.delete",
            "managedkafka.topics.create",
            "managedkafka.topics.get",
            "managedkafka.topics.list",
            "managedkafka.topics.update",
            "managedkafka.topics.delete",
            "managedkafka.consumerGroups.get",
            "managedkafka.consumerGroups.list",
            "managedkafka.consumerGroups.update",
            "managedkafka.consumerGroups.delete");

    private static final Set<String> MANAGEDKAFKA_VIEWER_PERMISSIONS = Set.of(
            "managedkafka.clusters.get",
            "managedkafka.clusters.list",
            "managedkafka.topics.get",
            "managedkafka.topics.list",
            "managedkafka.consumerGroups.get",
            "managedkafka.consumerGroups.list");

    /**
     * Cluster CRUD plus read of topics/consumer groups, matching GCP
     * {@code roles/managedkafka.clusterEditor} (no topic write).
     */
    private static final Set<String> MANAGEDKAFKA_CLUSTER_EDITOR_PERMISSIONS = Set.of(
            "managedkafka.clusters.create",
            "managedkafka.clusters.get",
            "managedkafka.clusters.list",
            "managedkafka.clusters.update",
            "managedkafka.clusters.delete",
            "managedkafka.topics.get",
            "managedkafka.topics.list",
            "managedkafka.consumerGroups.get",
            "managedkafka.consumerGroups.list");

    /** Read-only Datastore entity permissions (GCP {@code roles/datastore.viewer} subset). */
    private static final Set<String> DATASTORE_VIEWER_PERMISSIONS = Set.of(
            "datastore.entities.get",
            "datastore.entities.list");

    /** Entity read/write permissions for Datastore HTTP (GCP {@code roles/datastore.user} subset). */
    private static final Set<String> DATASTORE_USER_PERMISSIONS = Set.of(
            "datastore.entities.get",
            "datastore.entities.list",
            "datastore.entities.create",
            "datastore.entities.update",
            "datastore.entities.delete",
            "datastore.entities.allocateIds");

    private static final Set<String> EVENTARC_VIEWER_PERMISSIONS = Set.of(
            "eventarc.triggers.get",
            "eventarc.triggers.list",
            "eventarc.channels.get",
            "eventarc.channels.list",
            "eventarc.providers.get",
            "eventarc.providers.list");

    /** Permissions emitted by {@link IamPermissionMapper} for Eventarc REST. */
    private static final Set<String> EVENTARC_ADMIN_PERMISSIONS = Set.of(
            "eventarc.triggers.create",
            "eventarc.triggers.get",
            "eventarc.triggers.list",
            "eventarc.triggers.update",
            "eventarc.triggers.delete",
            "eventarc.triggers.getIamPolicy",
            "eventarc.triggers.setIamPolicy",
            "eventarc.channels.get",
            "eventarc.channels.list",
            "eventarc.providers.get",
            "eventarc.providers.list");

    private static final Set<String> BROWSER_PERMISSIONS = Set.of(
            "resourcemanager.projects.get");

    private static final Set<String> PROJECT_IAM_ADMIN_PERMISSIONS = Set.of(
            "resourcemanager.projects.getIamPolicy",
            "resourcemanager.projects.setIamPolicy");

    /**
     * Returns true when a binding grants {@code permission} to {@code principalEmail}.
     * Member form: {@code serviceAccount:{email}}.
     * {@code roles/owner} allows any permission for Stage 0.
     */

    /** Permissions emitted by {@link IamPermissionMapper} for BigQuery REST. */
    private static final Set<String> BIGQUERY_ADMIN_PERMISSIONS = Set.of(
            "bigquery.datasets.create",
            "bigquery.datasets.get",
            "bigquery.datasets.list",
            "bigquery.datasets.update",
            "bigquery.datasets.delete",
            "bigquery.tables.create",
            "bigquery.tables.get",
            "bigquery.tables.list",
            "bigquery.tables.update",
            "bigquery.tables.delete",
            "bigquery.tables.getData",
            "bigquery.tables.updateData",
            "bigquery.jobs.create",
            "bigquery.jobs.get",
            "bigquery.jobs.list",
            "bigquery.jobs.update",
            "bigquery.jobs.delete");

    private static final Set<String> BIGQUERY_DATA_VIEWER_PERMISSIONS = Set.of(
            "bigquery.datasets.get",
            "bigquery.datasets.list",
            "bigquery.tables.get",
            "bigquery.tables.list",
            "bigquery.tables.getData");

    private static final Set<String> BIGQUERY_JOB_USER_PERMISSIONS = Set.of(
            "bigquery.jobs.create");

    /** Permissions emitted by {@link IamPermissionMapper} for Firebase Auth / Identity Toolkit REST. */
    private static final Set<String> FIREBASEAUTH_ADMIN_PERMISSIONS = Set.of(
            "firebaseauth.users.create",
            "firebaseauth.users.createSession",
            "firebaseauth.users.get",
            "firebaseauth.users.update",
            "firebaseauth.users.delete");

    private static final Set<String> IDENTITYTOOLKIT_VIEWER_PERMISSIONS = Set.of(
            "firebaseauth.users.get");

    /** Permissions emitted by {@link IamPermissionMapper} for Service Usage REST. */
    private static final Set<String> SERVICEUSAGE_ADMIN_PERMISSIONS = Set.of(
            "serviceusage.services.enable",
            "serviceusage.services.disable",
            "serviceusage.services.get",
            "serviceusage.services.list");

    private static final Set<String> SERVICEUSAGE_CONSUMER_PERMISSIONS = Set.of(
            "serviceusage.services.get",
            "serviceusage.services.list");

    public boolean isAllowed(List<Map<String, Object>> bindings,
                             String principalEmail,
                             String permission) {
        if (bindings == null || bindings.isEmpty()
                || principalEmail == null || principalEmail.isBlank()
                || permission == null || permission.isBlank()) {
            return false;
        }

        String member = "serviceAccount:" + principalEmail;
        for (Map<String, Object> binding : bindings) {
            if (binding == null) {
                continue;
            }
            Object roleObj = binding.get("role");
            if (!(roleObj instanceof String role) || role.isBlank()) {
                continue;
            }
            if (!memberInBinding(binding, member)) {
                continue;
            }
            if (ROLE_OWNER.equals(role)) {
                return true;
            }
            if (ROLE_SA_ADMIN.equals(role) && SA_ADMIN_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_SECRET_ACCESSOR.equals(role) && SECRET_ACCESSOR_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_SECRET_ADMIN.equals(role) && SECRET_ADMIN_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_STORAGE_OBJECT_VIEWER.equals(role)
                    && STORAGE_OBJECT_VIEWER_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_STORAGE_OBJECT_ADMIN.equals(role)
                    && STORAGE_OBJECT_ADMIN_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_STORAGE_ADMIN.equals(role)
                    && STORAGE_ADMIN_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_PUBSUB_PUBLISHER.equals(role)
                    && PUBSUB_PUBLISHER_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_PUBSUB_SUBSCRIBER.equals(role)
                    && PUBSUB_SUBSCRIBER_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_PUBSUB_ADMIN.equals(role)
                    && PUBSUB_ADMIN_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_CLOUDKMS_ENCRYPTER_DECRYPTER.equals(role)
                    && CLOUDKMS_ENCRYPTER_DECRYPTER_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_CLOUDKMS_ADMIN.equals(role)
                    && CLOUDKMS_ADMIN_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_LOGGING_VIEWER.equals(role)
                    && LOGGING_VIEWER_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_LOGGING_LOG_WRITER.equals(role)
                    && LOGGING_LOG_WRITER_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_LOGGING_ADMIN.equals(role)
                    && LOGGING_ADMIN_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_MONITORING_VIEWER.equals(role)
                    && MONITORING_VIEWER_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_MONITORING_METRIC_WRITER.equals(role)
                    && MONITORING_METRIC_WRITER_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_MONITORING_ADMIN.equals(role)
                    && MONITORING_ADMIN_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_CLOUDTASKS_VIEWER.equals(role)
                    && CLOUDTASKS_VIEWER_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_CLOUDTASKS_ADMIN.equals(role)
                    && CLOUDTASKS_ADMIN_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_CLOUDFUNCTIONS_DEVELOPER.equals(role)
                    && CLOUDFUNCTIONS_CONTROL_PLANE_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_CLOUDFUNCTIONS_ADMIN.equals(role)
                    && CLOUDFUNCTIONS_CONTROL_PLANE_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_RUN_ADMIN.equals(role)
                    && RUN_ADMIN_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_RUN_DEVELOPER.equals(role)
                    && RUN_DEVELOPER_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_RUN_INVOKER.equals(role)
                    && RUN_INVOKER_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_CLOUDSCHEDULER_ADMIN.equals(role)
                    && CLOUDSCHEDULER_ADMIN_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_CLOUDSCHEDULER_VIEWER.equals(role)
                    && CLOUDSCHEDULER_VIEWER_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_CLOUDSQL_ADMIN.equals(role)
                    && CLOUDSQL_ADMIN_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_CLOUDSQL_VIEWER.equals(role)
                    && CLOUDSQL_VIEWER_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_MANAGEDKAFKA_ADMIN.equals(role)
                    && MANAGEDKAFKA_ADMIN_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_MANAGEDKAFKA_VIEWER.equals(role)
                    && MANAGEDKAFKA_VIEWER_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_MANAGEDKAFKA_CLUSTER_EDITOR.equals(role)
                    && MANAGEDKAFKA_CLUSTER_EDITOR_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_DATASTORE_VIEWER.equals(role)
                    && DATASTORE_VIEWER_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_DATASTORE_USER.equals(role)
                    && DATASTORE_USER_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_EVENTARC_VIEWER.equals(role)
                    && EVENTARC_VIEWER_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_EVENTARC_ADMIN.equals(role)
                    && EVENTARC_ADMIN_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_BROWSER.equals(role)
                    && BROWSER_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_PROJECT_IAM_ADMIN.equals(role)
                    && PROJECT_IAM_ADMIN_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_BIGQUERY_ADMIN.equals(role)
                    && BIGQUERY_ADMIN_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_BIGQUERY_DATA_VIEWER.equals(role)
                    && BIGQUERY_DATA_VIEWER_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_BIGQUERY_JOB_USER.equals(role)
                    && BIGQUERY_JOB_USER_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_FIREBASEAUTH_ADMIN.equals(role)
                    && FIREBASEAUTH_ADMIN_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_IDENTITYTOOLKIT_VIEWER.equals(role)
                    && IDENTITYTOOLKIT_VIEWER_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_SERVICEUSAGE_ADMIN.equals(role)
                    && SERVICEUSAGE_ADMIN_PERMISSIONS.contains(permission)) {
                return true;
            }
            if (ROLE_SERVICEUSAGE_CONSUMER.equals(role)
                    && SERVICEUSAGE_CONSUMER_PERMISSIONS.contains(permission)) {
                return true;
            }
        }
        return false;
    }

    private static boolean memberInBinding(Map<String, Object> binding, String member) {
        Object membersObj = binding.get("members");
        if (!(membersObj instanceof List<?> members)) {
            return false;
        }
        for (Object m : members) {
            if (member.equals(m)) {
                return true;
            }
        }
        return false;
    }
}
