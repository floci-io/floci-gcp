package io.floci.gcp.test;

import com.google.api.MonitoredResource;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.cloud.logging.v2.ConfigClient;
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
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.NullValue;
import com.google.protobuf.Value;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
                            .setFilter("logName=\"" + log + "\" AND severity>=WARNING")
                            .addExclusions(LogExclusion.newBuilder().setName("noise").setFilter("textPayload:noisy OR textPayload=STRASSE OR jsonPayload.count=skip OR jsonPayload.message=skip"))
                            .addExclusions(LogExclusion.newBuilder().setName("null-field").setFilter("jsonPayload.secret:* OR jsonPayload.message=1 OR jsonPayload.message=true"))).build());
            try {
                logging.writeLogEntries(WriteLogEntriesRequest.newBuilder().setLogName(log)
                        .setResource(MonitoredResource.newBuilder().setType("global"))
                        .addEntries(LogEntry.newBuilder().setSeverity(LogSeverity.ERROR).setJsonPayload(Struct.newBuilder()
                                .putFields("message", Value.newBuilder().setNumberValue(1).build())))
                        .addEntries(LogEntry.newBuilder().setSeverity(LogSeverity.ERROR).setJsonPayload(Struct.newBuilder()
                                .putFields("message", Value.newBuilder().setBoolValue(true).build())))
                        .addEntries(LogEntry.newBuilder().setSeverity(LogSeverity.INFO).setTextPayload("filtered"))
                        .addEntries(LogEntry.newBuilder().setSeverity(LogSeverity.ERROR).setTextPayload("noisy"))
                        .addEntries(LogEntry.newBuilder().setSeverity(LogSeverity.ERROR).setTextPayload("Straße"))
                        .addEntries(LogEntry.newBuilder().setSeverity(LogSeverity.ERROR).setTextPayload("exported"))
                        .addEntries(LogEntry.newBuilder().setSeverity(LogSeverity.WARNING).setJsonPayload(Struct.newBuilder()
                                .putFields("message", Value.newBuilder().setStringValue("structured-export").build())
                                .putFields("count", Value.newBuilder().setNumberValue(7).build())))
                        .addEntries(LogEntry.newBuilder().setSeverity(LogSeverity.ERROR).setJsonPayload(Struct.newBuilder()
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
                        .addEntries(LogEntry.newBuilder().setTextPayload("dry-run").setSeverity(LogSeverity.ERROR)).build());
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
}
