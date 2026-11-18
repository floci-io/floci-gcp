package io.floci.gcp.test;

import com.google.api.MonitoredResource;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.api.gax.rpc.TransportChannelProvider;
import com.google.cloud.logging.v2.ConfigClient;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminSettings;
import com.google.pubsub.v1.PushConfig;
import com.google.pubsub.v1.ReceivedMessage;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import com.google.cloud.logging.v2.ConfigSettings;
import com.google.cloud.logging.v2.LoggingClient;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.logging.type.LogSeverity;
import com.google.logging.v2.CreateSinkRequest;
import com.google.logging.v2.LogEntry;
import com.google.logging.v2.LogExclusion;
import com.google.logging.v2.LogSink;
import com.google.logging.v2.WriteLogEntriesRequest;
import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingExportTest {
    @Test
    void grpcLoggingRoutesFilteredEntriesToStorageJson() throws Exception {
        String id = TestFixtures.uniqueName("java-export");
        String parent = "projects/" + TestFixtures.projectId();
        String log = parent + "/logs/" + id;
        Storage storage = TestFixtures.storageClient();
        storage.create(BucketInfo.of(id));
        ConfigSettings settings = ConfigSettings.newBuilder()
                .setTransportChannelProvider(ConfigSettings.defaultGrpcTransportProviderBuilder()
                        .setEndpoint(TestFixtures.grpcTarget())
                        .setChannelConfigurator(builder -> builder.usePlaintext()).build())
                .setCredentialsProvider(NoCredentialsProvider.create()).build();
        try (ConfigClient config = ConfigClient.create(settings); LoggingClient logging = TestFixtures.loggingClient()) {
            config.createSink(CreateSinkRequest.newBuilder().setParent(parent).setUniqueWriterIdentity(true)
                    .setSink(LogSink.newBuilder().setName(id).setDestination("storage.googleapis.com/" + id)
                            .setFilter("logName=\"" + log + "\" AND severity>=WARNING"
                                    + " AND timestamp>=\"2026-10-20\" AND timestamp<\"2026-10-21\"")
                            .addExclusions(LogExclusion.newBuilder().setName("noise").setFilter("textPayload:noisy OR textPayload=STRASSE"
                                    + " OR jsonPayload.count=skip OR jsonPayload.message=skip"
                                    + " OR jsonPayload.message:true OR jsonPayload.count:7"))
                            .addExclusions(LogExclusion.newBuilder().setName("null-field").setFilter("jsonPayload.secret:* OR jsonPayload.message=1 OR jsonPayload.message=true"))).build());
            try {
                logging.writeLogEntries(WriteLogEntriesRequest.newBuilder().setLogName(log)
                        .setResource(MonitoredResource.newBuilder().setType("global"))
                        .addEntries(entry().setSeverity(LogSeverity.ERROR).setJsonPayload(Struct.newBuilder()
                                .putFields("message", Value.newBuilder().setNumberValue(1).build())))
                        .addEntries(entry().setSeverity(LogSeverity.ERROR).setJsonPayload(Struct.newBuilder()
                                .putFields("message", Value.newBuilder().setBoolValue(true).build())))
                        .addEntries(entry().setSeverity(LogSeverity.ERROR).setJsonPayload(Struct.newBuilder()
                                .putFields("message", Value.newBuilder().setStructValue(Struct.newBuilder()
                                        .putFields("nested", Value.newBuilder().setStringValue("skip").build())).build())
                                .putFields("secret", Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build())))
                        .addEntries(entry().setSeverity(LogSeverity.INFO).setTextPayload("filtered"))
                        .addEntries(entry().setSeverity(LogSeverity.ERROR).setTextPayload("noisy"))
                        .addEntries(entry().setSeverity(LogSeverity.ERROR).setTextPayload("Straße"))
                        .addEntries(entry().setSeverity(LogSeverity.ERROR).setTextPayload("exported"))
                        .addEntries(entry().setSeverity(LogSeverity.WARNING).setJsonPayload(Struct.newBuilder()
                                .putFields("message", Value.newBuilder().setStringValue("structured-export").build())
                                .putFields("count", Value.newBuilder().setNumberValue(7).build())))
                        .addEntries(entry().setSeverity(LogSeverity.ERROR).setJsonPayload(Struct.newBuilder()
                                .putFields("secret", Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build()))).build());
                List<Blob> objects = new ArrayList<>();
                storage.list(id).iterateAll().forEach(objects::add);
                assertThat(objects).hasSize(1);
                Blob object = objects.getFirst();
                assertThat(object.getName()).startsWith(id + "/").endsWith(".json");
                assertThat(object.getContentType()).isEqualTo("application/json");
                ListValue.Builder values = ListValue.newBuilder();
                JsonFormat.parser().merge(new String(object.getContent(), StandardCharsets.UTF_8), values);
                assertThat(values.getValuesCount()).isEqualTo(2);
                List<LogEntry> exported = new ArrayList<>();
                for (Value value : values.getValuesList()) {
                    LogEntry.Builder entry = LogEntry.newBuilder();
                    JsonFormat.parser().merge(JsonFormat.printer().print(value), entry);
                    exported.add(entry.build());
                }
                assertThat(exported).allSatisfy(entry -> {
                    assertThat(entry.getLogName()).isEqualTo(log);
                    assertThat(entry.getInsertId()).isNotEmpty();
                    assertThat(entry.hasReceiveTimestamp()).isTrue();
                });
                assertThat(exported).anySatisfy(entry -> {
                    assertThat(entry.getTextPayload()).isEqualTo("exported");
                    assertThat(entry.getSeverity()).isEqualTo(LogSeverity.ERROR);
                });
                assertThat(exported).filteredOn(LogEntry::hasJsonPayload).singleElement().satisfies(entry -> {
                    assertThat(entry.getJsonPayload().getFieldsOrThrow("message").getStringValue()).isEqualTo("structured-export");
                    assertThat(entry.getJsonPayload().getFieldsOrThrow("count").getNumberValue()).isEqualTo(7);
                });
                logging.writeLogEntries(WriteLogEntriesRequest.newBuilder().setLogName(log).setDryRun(true)
                        .addEntries(entry().setTextPayload("dry-run").setSeverity(LogSeverity.ERROR)).build());
                List<Blob> after = new ArrayList<>();
                storage.list(id).iterateAll().forEach(after::add);
                assertThat(after).hasSize(1);
            } finally {
                config.deleteSink(parent + "/sinks/" + id);
                logging.deleteLog(log);
            }
        } finally {
            storage.list(id).iterateAll().forEach(blob -> storage.delete(blob.getBlobId()));
            storage.delete(id);
            if (storage instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }
    @Test
    void grpcLoggingPublishesEachMatchingEntryToThePubSubSinkTopic() throws Exception {
        String id = TestFixtures.uniqueName("java-pubsub-export");
        String parent = "projects/" + TestFixtures.projectId();
        String log = parent + "/logs/" + id;
        String topic = parent + "/topics/" + id;
        String subscription = parent + "/subscriptions/" + id;
        ManagedChannel channel = ManagedChannelBuilder.forTarget(TestFixtures.grpcTarget()).usePlaintext().build();
        TransportChannelProvider channels = FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel));
        ConfigSettings settings = ConfigSettings.newBuilder()
                .setTransportChannelProvider(channels)
                .setCredentialsProvider(NoCredentialsProvider.create()).build();
        try (TopicAdminClient topics = TopicAdminClient.create(TopicAdminSettings.newBuilder()
                        .setTransportChannelProvider(channels).setCredentialsProvider(NoCredentialsProvider.create()).build());
                SubscriptionAdminClient subscriptions = SubscriptionAdminClient.create(SubscriptionAdminSettings.newBuilder()
                        .setTransportChannelProvider(channels).setCredentialsProvider(NoCredentialsProvider.create()).build());
                ConfigClient config = ConfigClient.create(settings);
                LoggingClient logging = TestFixtures.loggingClient()) {
            topics.createTopic(topic);
            subscriptions.createSubscription(subscription, topic, PushConfig.getDefaultInstance(), 10);
            config.createSink(CreateSinkRequest.newBuilder().setParent(parent).setUniqueWriterIdentity(true)
                    .setSink(LogSink.newBuilder().setName(id).setDestination("pubsub.googleapis.com/" + topic)
                            .setFilter("logName=\"" + log + "\" AND severity>=WARNING")).build());
            try {
                logging.writeLogEntries(WriteLogEntriesRequest.newBuilder().setLogName(log)
                        .setResource(MonitoredResource.newBuilder().setType("global"))
                        .addEntries(entry().setSeverity(LogSeverity.ERROR).setTextPayload("published"))
                        .addEntries(entry().setSeverity(LogSeverity.INFO).setTextPayload("filtered"))
                        .addEntries(entry().setSeverity(LogSeverity.WARNING).setJsonPayload(Struct.newBuilder()
                                .putFields("message", Value.newBuilder().setStringValue("structured").build())))
                        .build());
                List<ReceivedMessage> received = subscriptions.pull(subscription, 10).getReceivedMessagesList();
                assertThat(received).hasSize(2);
                List<LogEntry> published = new ArrayList<>();
                for (ReceivedMessage message : received) {
                    assertThat(message.getMessage().getAttributesMap())
                            .containsEntry("logging.googleapis.com/timestamp", "2026-10-20T01:00:00Z");
                    LogEntry.Builder entry = LogEntry.newBuilder();
                    JsonFormat.parser().merge(message.getMessage().getData().toStringUtf8(), entry);
                    published.add(entry.build());
                }
                assertThat(published).allSatisfy(entry -> {
                    assertThat(entry.getLogName()).isEqualTo(log);
                    assertThat(entry.getInsertId()).isNotEmpty();
                    assertThat(entry.hasReceiveTimestamp()).isTrue();
                });
                assertThat(published.get(0).getTextPayload()).isEqualTo("published");
                assertThat(published.get(1).getJsonPayload().getFieldsOrThrow("message").getStringValue())
                        .isEqualTo("structured");
            } finally {
                config.deleteSink(parent + "/sinks/" + id);
                logging.deleteLog(log);
                subscriptions.deleteSubscription(subscription);
                topics.deleteTopic(topic);
            }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void grpcLoggingStreamsMatchingEntriesIntoTheBigQuerySinkDataset() throws Exception {
        String id = TestFixtures.uniqueName("java-bq-export");
        String parent = "projects/" + TestFixtures.projectId();
        String log = parent + "/logs/" + id;
        String dataset = id.replace("-", "_");
        com.google.cloud.bigquery.BigQuery bigquery = TestFixtures.bigQueryClient();
        bigquery.create(com.google.cloud.bigquery.DatasetInfo.of(dataset));
        ConfigSettings settings = ConfigSettings.newBuilder()
                .setTransportChannelProvider(ConfigSettings.defaultGrpcTransportProviderBuilder()
                        .setEndpoint(TestFixtures.grpcTarget())
                        .setChannelConfigurator(builder -> builder.usePlaintext()).build())
                .setCredentialsProvider(NoCredentialsProvider.create()).build();
        try (ConfigClient config = ConfigClient.create(settings); LoggingClient logging = TestFixtures.loggingClient()) {
            config.createSink(CreateSinkRequest.newBuilder().setParent(parent).setUniqueWriterIdentity(true)
                    .setSink(LogSink.newBuilder().setName(id)
                            .setDestination("bigquery.googleapis.com/projects/" + TestFixtures.projectId() + "/datasets/" + dataset)
                            .setFilter("logName=\"" + log + "\" AND severity>=WARNING")).build());
            try {
                logging.writeLogEntries(WriteLogEntriesRequest.newBuilder().setLogName(log)
                        .setResource(MonitoredResource.newBuilder().setType("global"))
                        .addEntries(entry().setSeverity(LogSeverity.ERROR).setJsonPayload(Struct.newBuilder()
                                .putFields("Message", Value.newBuilder().setStringValue("to-bigquery").build())))
                        .addEntries(entry().setSeverity(LogSeverity.INFO).setTextPayload("filtered"))
                        .build());
                String table = id.replace("-", "_") + "_20261020";
                com.google.cloud.bigquery.TableId tableId = com.google.cloud.bigquery.TableId.of(dataset, table);
                com.google.cloud.bigquery.TableResult rows = bigquery.listTableData(tableId,
                        bigquery.getTable(tableId).getDefinition().getSchema());
                assertThat(rows.getTotalRows()).isEqualTo(1);
                com.google.cloud.bigquery.FieldValueList row = rows.iterateAll().iterator().next();
                assertThat(row.get("logName").getStringValue()).isEqualTo(log);
                assertThat(row.get("severity").getStringValue()).isEqualTo("ERROR");
                assertThat(row.get("jsonPayload").getRecordValue().get("message").getStringValue())
                        .isEqualTo("to-bigquery");
            } finally {
                config.deleteSink(parent + "/sinks/" + id);
                logging.deleteLog(log);
            }
        } finally {
            bigquery.delete(com.google.cloud.bigquery.DatasetId.of(dataset),
                    com.google.cloud.bigquery.BigQuery.DatasetDeleteOption.deleteContents());
        }
    }

    private static LogEntry.Builder entry() {
        return LogEntry.newBuilder().setTimestamp(Timestamp.newBuilder()
                .setSeconds(Instant.parse("2026-10-20T01:00:00Z").getEpochSecond()));
    }

}
