package io.floci.gcp.services.iam;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Optional;

/**
 * Maps Firestore, Datastore, Pub/Sub, Secret Manager, Cloud Tasks, Cloud KMS,
 * Cloud Scheduler, Cloud Logging, and Cloud Monitoring gRPC full method names to
 * IAM permission strings. Native-mode Firestore uses {@code datastore.entities.*}
 * (same as Datastore REST). Other services mirror the REST mapper.
 */
@ApplicationScoped
public class IamGrpcPermissionMapper {

    private static final String DATASTORE_PREFIX = "google.datastore.v1.Datastore/";
    private static final String FIRESTORE_PREFIX = "google.firestore.v1.Firestore/";
    private static final String PUBSUB_PUBLISHER_PREFIX = "google.pubsub.v1.Publisher/";
    private static final String PUBSUB_SUBSCRIBER_PREFIX = "google.pubsub.v1.Subscriber/";
    private static final String SECRET_MANAGER_PREFIX =
            "google.cloud.secretmanager.v1.SecretManagerService/";
    private static final String CLOUD_TASKS_PREFIX = "google.cloud.tasks.v2.CloudTasks/";
    private static final String CLOUD_KMS_PREFIX = "google.cloud.kms.v1.KeyManagementService/";
    private static final String CLOUD_SCHEDULER_PREFIX = "google.cloud.scheduler.v1.CloudScheduler/";
    private static final String LOGGING_PREFIX = "google.logging.v2.LoggingServiceV2/";
    private static final String MONITORING_PREFIX = "google.monitoring.v3.MetricService/";

    private static final Map<String, String> DATASTORE_METHODS = Map.of(
            "Lookup", "datastore.entities.get",
            "BeginTransaction", "datastore.entities.get",
            "Rollback", "datastore.entities.get",
            "RunQuery", "datastore.entities.list",
            "RunAggregationQuery", "datastore.entities.list",
            "Commit", "datastore.entities.create",
            "AllocateIds", "datastore.entities.allocateIds",
            "ReserveIds", "datastore.entities.allocateIds");

    private static final Map<String, String> FIRESTORE_METHODS = Map.ofEntries(
            Map.entry("GetDocument", "datastore.entities.get"),
            Map.entry("BatchGetDocuments", "datastore.entities.get"),
            Map.entry("BeginTransaction", "datastore.entities.get"),
            Map.entry("Rollback", "datastore.entities.get"),
            Map.entry("Listen", "datastore.entities.get"),
            Map.entry("RunQuery", "datastore.entities.list"),
            Map.entry("RunAggregationQuery", "datastore.entities.list"),
            Map.entry("ListDocuments", "datastore.entities.list"),
            Map.entry("ListCollectionIds", "datastore.entities.list"),
            Map.entry("PartitionQuery", "datastore.entities.list"),
            Map.entry("Commit", "datastore.entities.create"),
            Map.entry("BatchWrite", "datastore.entities.create"),
            Map.entry("CreateDocument", "datastore.entities.create"),
            Map.entry("Write", "datastore.entities.create"),
            Map.entry("UpdateDocument", "datastore.entities.update"),
            Map.entry("DeleteDocument", "datastore.entities.delete"));

    private static final Map<String, String> PUBSUB_PUBLISHER_METHODS = Map.ofEntries(
            Map.entry("CreateTopic", "pubsub.topics.create"),
            Map.entry("GetTopic", "pubsub.topics.get"),
            Map.entry("ListTopics", "pubsub.topics.list"),
            Map.entry("UpdateTopic", "pubsub.topics.update"),
            Map.entry("DeleteTopic", "pubsub.topics.delete"),
            Map.entry("Publish", "pubsub.topics.publish"),
            Map.entry("ListTopicSubscriptions", "pubsub.topics.get"),
            Map.entry("DetachSubscription", "pubsub.topics.detachSubscription"));

    private static final Map<String, String> PUBSUB_SUBSCRIBER_METHODS = Map.ofEntries(
            Map.entry("CreateSubscription", "pubsub.subscriptions.create"),
            Map.entry("GetSubscription", "pubsub.subscriptions.get"),
            Map.entry("ListSubscriptions", "pubsub.subscriptions.list"),
            Map.entry("UpdateSubscription", "pubsub.subscriptions.update"),
            Map.entry("DeleteSubscription", "pubsub.subscriptions.delete"),
            Map.entry("Pull", "pubsub.subscriptions.consume"),
            Map.entry("Acknowledge", "pubsub.subscriptions.consume"),
            Map.entry("StreamingPull", "pubsub.subscriptions.consume"),
            Map.entry("ModifyAckDeadline", "pubsub.subscriptions.consume"),
            Map.entry("ModifyPushConfig", "pubsub.subscriptions.update"),
            Map.entry("CreateSnapshot", "pubsub.snapshots.create"),
            Map.entry("GetSnapshot", "pubsub.snapshots.get"),
            Map.entry("ListSnapshots", "pubsub.snapshots.list"),
            Map.entry("UpdateSnapshot", "pubsub.snapshots.update"),
            Map.entry("DeleteSnapshot", "pubsub.snapshots.delete"),
            // Seek always requires consume; snapshots.seek is an extra check when seeking to a snapshot.
            Map.entry("Seek", "pubsub.subscriptions.consume"));

    private static final Map<String, String> SECRET_MANAGER_METHODS = Map.ofEntries(
            Map.entry("CreateSecret", "secretmanager.secrets.create"),
            Map.entry("GetSecret", "secretmanager.secrets.get"),
            Map.entry("ListSecrets", "secretmanager.secrets.list"),
            Map.entry("UpdateSecret", "secretmanager.secrets.update"),
            Map.entry("DeleteSecret", "secretmanager.secrets.delete"),
            Map.entry("GetIamPolicy", "secretmanager.secrets.getIamPolicy"),
            Map.entry("SetIamPolicy", "secretmanager.secrets.setIamPolicy"),
            // GCP requires no special permission; map like getIamPolicy so strict mode stays gated.
            Map.entry("TestIamPermissions", "secretmanager.secrets.getIamPolicy"),
            Map.entry("AddSecretVersion", "secretmanager.versions.add"),
            Map.entry("GetSecretVersion", "secretmanager.versions.get"),
            Map.entry("ListSecretVersions", "secretmanager.versions.list"),
            Map.entry("AccessSecretVersion", "secretmanager.versions.access"),
            Map.entry("DisableSecretVersion", "secretmanager.versions.disable"),
            Map.entry("EnableSecretVersion", "secretmanager.versions.enable"),
            Map.entry("DestroySecretVersion", "secretmanager.versions.destroy"));

    private static final Map<String, String> CLOUD_TASKS_METHODS = Map.ofEntries(
            Map.entry("ListQueues", "cloudtasks.queues.list"),
            Map.entry("GetQueue", "cloudtasks.queues.get"),
            Map.entry("CreateQueue", "cloudtasks.queues.create"),
            Map.entry("UpdateQueue", "cloudtasks.queues.update"),
            Map.entry("DeleteQueue", "cloudtasks.queues.delete"),
            Map.entry("PurgeQueue", "cloudtasks.queues.purge"),
            Map.entry("PauseQueue", "cloudtasks.queues.pause"),
            Map.entry("ResumeQueue", "cloudtasks.queues.resume"),
            Map.entry("ListTasks", "cloudtasks.tasks.list"),
            Map.entry("GetTask", "cloudtasks.tasks.get"),
            Map.entry("CreateTask", "cloudtasks.tasks.create"),
            Map.entry("DeleteTask", "cloudtasks.tasks.delete"),
            Map.entry("RunTask", "cloudtasks.tasks.run"));

    private static final Map<String, String> CLOUD_KMS_METHODS = Map.ofEntries(
            Map.entry("CreateKeyRing", "cloudkms.keyRings.create"),
            Map.entry("GetKeyRing", "cloudkms.keyRings.get"),
            Map.entry("ListKeyRings", "cloudkms.keyRings.list"),
            Map.entry("CreateCryptoKey", "cloudkms.cryptoKeys.create"),
            Map.entry("GetCryptoKey", "cloudkms.cryptoKeys.get"),
            Map.entry("ListCryptoKeys", "cloudkms.cryptoKeys.list"),
            Map.entry("UpdateCryptoKey", "cloudkms.cryptoKeys.update"),
            Map.entry("UpdateCryptoKeyPrimaryVersion", "cloudkms.cryptoKeys.update"),
            Map.entry("CreateCryptoKeyVersion", "cloudkms.cryptoKeyVersions.create"),
            Map.entry("GetCryptoKeyVersion", "cloudkms.cryptoKeyVersions.get"),
            Map.entry("ListCryptoKeyVersions", "cloudkms.cryptoKeyVersions.list"),
            Map.entry("UpdateCryptoKeyVersion", "cloudkms.cryptoKeyVersions.update"),
            Map.entry("DestroyCryptoKeyVersion", "cloudkms.cryptoKeyVersions.destroy"),
            Map.entry("RestoreCryptoKeyVersion", "cloudkms.cryptoKeyVersions.restore"),
            Map.entry("GetPublicKey", "cloudkms.cryptoKeyVersions.viewPublicKey"),
            Map.entry("Encrypt", "cloudkms.cryptoKeyVersions.useToEncrypt"),
            Map.entry("Decrypt", "cloudkms.cryptoKeyVersions.useToDecrypt"),
            Map.entry("AsymmetricSign", "cloudkms.cryptoKeyVersions.useToSign"),
            Map.entry("AsymmetricDecrypt", "cloudkms.cryptoKeyVersions.useToDecrypt"),
            Map.entry("GenerateRandomBytes", "cloudkms.locations.generateRandomBytes"));

    private static final Map<String, String> CLOUD_SCHEDULER_METHODS = Map.ofEntries(
            Map.entry("ListJobs", "cloudscheduler.jobs.list"),
            Map.entry("GetJob", "cloudscheduler.jobs.get"),
            Map.entry("CreateJob", "cloudscheduler.jobs.create"),
            Map.entry("UpdateJob", "cloudscheduler.jobs.update"),
            Map.entry("DeleteJob", "cloudscheduler.jobs.delete"),
            Map.entry("PauseJob", "cloudscheduler.jobs.pause"),
            Map.entry("ResumeJob", "cloudscheduler.jobs.enable"),
            Map.entry("RunJob", "cloudscheduler.jobs.run"));

    private static final Map<String, String> LOGGING_METHODS = Map.of(
            "WriteLogEntries", "logging.logEntries.create",
            "ListLogEntries", "logging.logEntries.list",
            "ListLogs", "logging.logs.list",
            "DeleteLog", "logging.logs.delete");

    private static final Map<String, String> MONITORING_METHODS = Map.ofEntries(
            Map.entry("CreateTimeSeries", "monitoring.timeSeries.create"),
            Map.entry("ListTimeSeries", "monitoring.timeSeries.list"),
            Map.entry("ListMetricDescriptors", "monitoring.metricDescriptors.list"),
            Map.entry("CreateMetricDescriptor", "monitoring.metricDescriptors.create"),
            Map.entry("GetMetricDescriptor", "monitoring.metricDescriptors.get"),
            Map.entry("DeleteMetricDescriptor", "monitoring.metricDescriptors.delete"),
            Map.entry("ListMonitoredResourceDescriptors",
                    "monitoring.monitoredResourceDescriptors.list"),
            Map.entry("GetMonitoredResourceDescriptor",
                    "monitoring.monitoredResourceDescriptors.get"));

    public Optional<String> map(String fullMethodName) {
        if (fullMethodName == null || fullMethodName.isBlank()) {
            return Optional.empty();
        }
        if (fullMethodName.startsWith(DATASTORE_PREFIX)) {
            return Optional.ofNullable(DATASTORE_METHODS.get(
                    fullMethodName.substring(DATASTORE_PREFIX.length())));
        }
        if (fullMethodName.startsWith(FIRESTORE_PREFIX)) {
            return Optional.ofNullable(FIRESTORE_METHODS.get(
                    fullMethodName.substring(FIRESTORE_PREFIX.length())));
        }
        if (fullMethodName.startsWith(PUBSUB_PUBLISHER_PREFIX)) {
            return Optional.ofNullable(PUBSUB_PUBLISHER_METHODS.get(
                    fullMethodName.substring(PUBSUB_PUBLISHER_PREFIX.length())));
        }
        if (fullMethodName.startsWith(PUBSUB_SUBSCRIBER_PREFIX)) {
            return Optional.ofNullable(PUBSUB_SUBSCRIBER_METHODS.get(
                    fullMethodName.substring(PUBSUB_SUBSCRIBER_PREFIX.length())));
        }
        if (fullMethodName.startsWith(SECRET_MANAGER_PREFIX)) {
            return Optional.ofNullable(SECRET_MANAGER_METHODS.get(
                    fullMethodName.substring(SECRET_MANAGER_PREFIX.length())));
        }
        if (fullMethodName.startsWith(CLOUD_TASKS_PREFIX)) {
            return Optional.ofNullable(CLOUD_TASKS_METHODS.get(
                    fullMethodName.substring(CLOUD_TASKS_PREFIX.length())));
        }
        if (fullMethodName.startsWith(CLOUD_KMS_PREFIX)) {
            return Optional.ofNullable(CLOUD_KMS_METHODS.get(
                    fullMethodName.substring(CLOUD_KMS_PREFIX.length())));
        }
        if (fullMethodName.startsWith(CLOUD_SCHEDULER_PREFIX)) {
            return Optional.ofNullable(CLOUD_SCHEDULER_METHODS.get(
                    fullMethodName.substring(CLOUD_SCHEDULER_PREFIX.length())));
        }
        if (fullMethodName.startsWith(LOGGING_PREFIX)) {
            return Optional.ofNullable(LOGGING_METHODS.get(
                    fullMethodName.substring(LOGGING_PREFIX.length())));
        }
        if (fullMethodName.startsWith(MONITORING_PREFIX)) {
            return Optional.ofNullable(MONITORING_METHODS.get(
                    fullMethodName.substring(MONITORING_PREFIX.length())));
        }
        return Optional.empty();
    }
}
