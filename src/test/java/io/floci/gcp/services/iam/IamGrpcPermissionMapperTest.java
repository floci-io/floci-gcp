package io.floci.gcp.services.iam;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IamGrpcPermissionMapperTest {

    private final IamGrpcPermissionMapper mapper = new IamGrpcPermissionMapper();

    @ParameterizedTest
    @CsvSource({
            "google.datastore.v1.Datastore/Lookup, datastore.entities.get",
            "google.datastore.v1.Datastore/BeginTransaction, datastore.entities.get",
            "google.datastore.v1.Datastore/Rollback, datastore.entities.get",
            "google.datastore.v1.Datastore/RunQuery, datastore.entities.list",
            "google.datastore.v1.Datastore/RunAggregationQuery, datastore.entities.list",
            "google.datastore.v1.Datastore/Commit, datastore.entities.create",
            "google.datastore.v1.Datastore/AllocateIds, datastore.entities.allocateIds",
            "google.datastore.v1.Datastore/ReserveIds, datastore.entities.allocateIds",
            "google.firestore.v1.Firestore/GetDocument, datastore.entities.get",
            "google.firestore.v1.Firestore/BatchGetDocuments, datastore.entities.get",
            "google.firestore.v1.Firestore/BeginTransaction, datastore.entities.get",
            "google.firestore.v1.Firestore/Rollback, datastore.entities.get",
            "google.firestore.v1.Firestore/Listen, datastore.entities.get",
            "google.firestore.v1.Firestore/RunQuery, datastore.entities.list",
            "google.firestore.v1.Firestore/RunAggregationQuery, datastore.entities.list",
            "google.firestore.v1.Firestore/ListDocuments, datastore.entities.list",
            "google.firestore.v1.Firestore/ListCollectionIds, datastore.entities.list",
            "google.firestore.v1.Firestore/PartitionQuery, datastore.entities.list",
            "google.firestore.v1.Firestore/Commit, datastore.entities.create",
            "google.firestore.v1.Firestore/BatchWrite, datastore.entities.create",
            "google.firestore.v1.Firestore/CreateDocument, datastore.entities.create",
            "google.firestore.v1.Firestore/Write, datastore.entities.create",
            "google.firestore.v1.Firestore/UpdateDocument, datastore.entities.update",
            "google.firestore.v1.Firestore/DeleteDocument, datastore.entities.delete",
            "google.pubsub.v1.Publisher/CreateTopic, pubsub.topics.create",
            "google.pubsub.v1.Publisher/GetTopic, pubsub.topics.get",
            "google.pubsub.v1.Publisher/ListTopics, pubsub.topics.list",
            "google.pubsub.v1.Publisher/UpdateTopic, pubsub.topics.update",
            "google.pubsub.v1.Publisher/DeleteTopic, pubsub.topics.delete",
            "google.pubsub.v1.Publisher/Publish, pubsub.topics.publish",
            "google.pubsub.v1.Publisher/ListTopicSubscriptions, pubsub.topics.get",
            "google.pubsub.v1.Publisher/DetachSubscription, pubsub.topics.detachSubscription",
            "google.pubsub.v1.Subscriber/CreateSubscription, pubsub.subscriptions.create",
            "google.pubsub.v1.Subscriber/GetSubscription, pubsub.subscriptions.get",
            "google.pubsub.v1.Subscriber/ListSubscriptions, pubsub.subscriptions.list",
            "google.pubsub.v1.Subscriber/UpdateSubscription, pubsub.subscriptions.update",
            "google.pubsub.v1.Subscriber/DeleteSubscription, pubsub.subscriptions.delete",
            "google.pubsub.v1.Subscriber/Pull, pubsub.subscriptions.consume",
            "google.pubsub.v1.Subscriber/Acknowledge, pubsub.subscriptions.consume",
            "google.pubsub.v1.Subscriber/StreamingPull, pubsub.subscriptions.consume",
            "google.pubsub.v1.Subscriber/ModifyAckDeadline, pubsub.subscriptions.consume",
            "google.pubsub.v1.Subscriber/ModifyPushConfig, pubsub.subscriptions.update",
            "google.pubsub.v1.Subscriber/CreateSnapshot, pubsub.snapshots.create",
            "google.pubsub.v1.Subscriber/GetSnapshot, pubsub.snapshots.get",
            "google.pubsub.v1.Subscriber/ListSnapshots, pubsub.snapshots.list",
            "google.pubsub.v1.Subscriber/UpdateSnapshot, pubsub.snapshots.update",
            "google.pubsub.v1.Subscriber/DeleteSnapshot, pubsub.snapshots.delete",
            "google.pubsub.v1.Subscriber/Seek, pubsub.subscriptions.consume",
            "google.cloud.secretmanager.v1.SecretManagerService/CreateSecret, secretmanager.secrets.create",
            "google.cloud.secretmanager.v1.SecretManagerService/GetSecret, secretmanager.secrets.get",
            "google.cloud.secretmanager.v1.SecretManagerService/ListSecrets, secretmanager.secrets.list",
            "google.cloud.secretmanager.v1.SecretManagerService/UpdateSecret, secretmanager.secrets.update",
            "google.cloud.secretmanager.v1.SecretManagerService/DeleteSecret, secretmanager.secrets.delete",
            "google.cloud.secretmanager.v1.SecretManagerService/GetIamPolicy, secretmanager.secrets.getIamPolicy",
            "google.cloud.secretmanager.v1.SecretManagerService/SetIamPolicy, secretmanager.secrets.setIamPolicy",
            "google.cloud.secretmanager.v1.SecretManagerService/TestIamPermissions, secretmanager.secrets.getIamPolicy",
            "google.cloud.secretmanager.v1.SecretManagerService/AddSecretVersion, secretmanager.versions.add",
            "google.cloud.secretmanager.v1.SecretManagerService/GetSecretVersion, secretmanager.versions.get",
            "google.cloud.secretmanager.v1.SecretManagerService/ListSecretVersions, secretmanager.versions.list",
            "google.cloud.secretmanager.v1.SecretManagerService/AccessSecretVersion, secretmanager.versions.access",
            "google.cloud.secretmanager.v1.SecretManagerService/DisableSecretVersion, secretmanager.versions.disable",
            "google.cloud.secretmanager.v1.SecretManagerService/EnableSecretVersion, secretmanager.versions.enable",
            "google.cloud.secretmanager.v1.SecretManagerService/DestroySecretVersion, secretmanager.versions.destroy",
            "google.cloud.tasks.v2.CloudTasks/ListQueues, cloudtasks.queues.list",
            "google.cloud.tasks.v2.CloudTasks/GetQueue, cloudtasks.queues.get",
            "google.cloud.tasks.v2.CloudTasks/CreateQueue, cloudtasks.queues.create",
            "google.cloud.tasks.v2.CloudTasks/UpdateQueue, cloudtasks.queues.update",
            "google.cloud.tasks.v2.CloudTasks/DeleteQueue, cloudtasks.queues.delete",
            "google.cloud.tasks.v2.CloudTasks/PurgeQueue, cloudtasks.queues.purge",
            "google.cloud.tasks.v2.CloudTasks/PauseQueue, cloudtasks.queues.pause",
            "google.cloud.tasks.v2.CloudTasks/ResumeQueue, cloudtasks.queues.resume",
            "google.cloud.tasks.v2.CloudTasks/ListTasks, cloudtasks.tasks.list",
            "google.cloud.tasks.v2.CloudTasks/GetTask, cloudtasks.tasks.get",
            "google.cloud.tasks.v2.CloudTasks/CreateTask, cloudtasks.tasks.create",
            "google.cloud.tasks.v2.CloudTasks/DeleteTask, cloudtasks.tasks.delete",
            "google.cloud.tasks.v2.CloudTasks/RunTask, cloudtasks.tasks.run",
            "google.cloud.kms.v1.KeyManagementService/CreateKeyRing, cloudkms.keyRings.create",
            "google.cloud.kms.v1.KeyManagementService/GetKeyRing, cloudkms.keyRings.get",
            "google.cloud.kms.v1.KeyManagementService/ListKeyRings, cloudkms.keyRings.list",
            "google.cloud.kms.v1.KeyManagementService/CreateCryptoKey, cloudkms.cryptoKeys.create",
            "google.cloud.kms.v1.KeyManagementService/GetCryptoKey, cloudkms.cryptoKeys.get",
            "google.cloud.kms.v1.KeyManagementService/ListCryptoKeys, cloudkms.cryptoKeys.list",
            "google.cloud.kms.v1.KeyManagementService/UpdateCryptoKey, cloudkms.cryptoKeys.update",
            "google.cloud.kms.v1.KeyManagementService/UpdateCryptoKeyPrimaryVersion, cloudkms.cryptoKeys.update",
            "google.cloud.kms.v1.KeyManagementService/CreateCryptoKeyVersion, cloudkms.cryptoKeyVersions.create",
            "google.cloud.kms.v1.KeyManagementService/GetCryptoKeyVersion, cloudkms.cryptoKeyVersions.get",
            "google.cloud.kms.v1.KeyManagementService/ListCryptoKeyVersions, cloudkms.cryptoKeyVersions.list",
            "google.cloud.kms.v1.KeyManagementService/UpdateCryptoKeyVersion, cloudkms.cryptoKeyVersions.update",
            "google.cloud.kms.v1.KeyManagementService/DestroyCryptoKeyVersion, cloudkms.cryptoKeyVersions.destroy",
            "google.cloud.kms.v1.KeyManagementService/RestoreCryptoKeyVersion, cloudkms.cryptoKeyVersions.restore",
            "google.cloud.kms.v1.KeyManagementService/GetPublicKey, cloudkms.cryptoKeyVersions.viewPublicKey",
            "google.cloud.kms.v1.KeyManagementService/Encrypt, cloudkms.cryptoKeyVersions.useToEncrypt",
            "google.cloud.kms.v1.KeyManagementService/Decrypt, cloudkms.cryptoKeyVersions.useToDecrypt",
            "google.cloud.kms.v1.KeyManagementService/AsymmetricSign, cloudkms.cryptoKeyVersions.useToSign",
            "google.cloud.kms.v1.KeyManagementService/AsymmetricDecrypt, cloudkms.cryptoKeyVersions.useToDecrypt",
            "google.cloud.kms.v1.KeyManagementService/GenerateRandomBytes, cloudkms.locations.generateRandomBytes",
            "google.cloud.scheduler.v1.CloudScheduler/ListJobs, cloudscheduler.jobs.list",
            "google.cloud.scheduler.v1.CloudScheduler/GetJob, cloudscheduler.jobs.get",
            "google.cloud.scheduler.v1.CloudScheduler/CreateJob, cloudscheduler.jobs.create",
            "google.cloud.scheduler.v1.CloudScheduler/UpdateJob, cloudscheduler.jobs.update",
            "google.cloud.scheduler.v1.CloudScheduler/DeleteJob, cloudscheduler.jobs.delete",
            "google.cloud.scheduler.v1.CloudScheduler/PauseJob, cloudscheduler.jobs.pause",
            "google.cloud.scheduler.v1.CloudScheduler/ResumeJob, cloudscheduler.jobs.enable",
            "google.cloud.scheduler.v1.CloudScheduler/RunJob, cloudscheduler.jobs.run",
            "google.logging.v2.LoggingServiceV2/WriteLogEntries, logging.logEntries.create",
            "google.logging.v2.LoggingServiceV2/ListLogEntries, logging.logEntries.list",
            "google.logging.v2.LoggingServiceV2/ListLogs, logging.logs.list",
            "google.logging.v2.LoggingServiceV2/DeleteLog, logging.logs.delete",
            "google.monitoring.v3.MetricService/CreateTimeSeries, monitoring.timeSeries.create",
            "google.monitoring.v3.MetricService/ListTimeSeries, monitoring.timeSeries.list",
            "google.monitoring.v3.MetricService/ListMetricDescriptors, monitoring.metricDescriptors.list",
            "google.monitoring.v3.MetricService/CreateMetricDescriptor, monitoring.metricDescriptors.create",
            "google.monitoring.v3.MetricService/GetMetricDescriptor, monitoring.metricDescriptors.get",
            "google.monitoring.v3.MetricService/DeleteMetricDescriptor, monitoring.metricDescriptors.delete",
            "google.monitoring.v3.MetricService/ListMonitoredResourceDescriptors, monitoring.monitoredResourceDescriptors.list",
            "google.monitoring.v3.MetricService/GetMonitoredResourceDescriptor, monitoring.monitoredResourceDescriptors.get",
    })
    void mapsKnownMethods(String fullMethodName, String expectedPermission) {
        assertEquals(Optional.of(expectedPermission), mapper.map(fullMethodName));
    }

    @ParameterizedTest
    @CsvSource({
            "google.pubsub.v1.Publisher/UnknownMethod",
            "google.pubsub.v1.Subscriber/UnknownMethod",
            "google.cloud.secretmanager.v1.SecretManagerService/UnknownMethod",
            "google.datastore.v1.Datastore/UnknownMethod",
            "google.firestore.v1.Firestore/UnknownMethod",
            "google.cloud.tasks.v2.CloudTasks/UnknownMethod",
            "google.cloud.tasks.v2.CloudTasks/GetIamPolicy",
            "google.cloud.tasks.v2.CloudTasks/SetIamPolicy",
            "google.cloud.tasks.v2.CloudTasks/TestIamPermissions",
            "google.cloud.kms.v1.KeyManagementService/UnknownMethod",
            "google.cloud.scheduler.v1.CloudScheduler/UnknownMethod",
            "google.logging.v2.LoggingServiceV2/UnknownMethod",
            "google.logging.v2.LoggingServiceV2/ListMonitoredResourceDescriptors",
            "google.logging.v2.LoggingServiceV2/TailLogEntries",
            "google.monitoring.v3.MetricService/UnknownMethod",
    })
    void returnsEmptyForUnmapped(String fullMethodName) {
        assertEquals(Optional.empty(), mapper.map(fullMethodName));
    }

    @org.junit.jupiter.api.Test
    void returnsEmptyForBlank() {
        assertEquals(Optional.empty(), mapper.map(""));
        assertEquals(Optional.empty(), mapper.map(null));
    }
}
