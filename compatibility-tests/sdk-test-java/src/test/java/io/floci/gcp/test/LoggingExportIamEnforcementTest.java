package io.floci.gcp.test;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.Identity;
import com.google.cloud.Policy;
import com.google.cloud.Role;
import com.google.cloud.iam.credentials.v1.GenerateAccessTokenResponse;
import com.google.cloud.iam.credentials.v1.IamCredentialsClient;
import com.google.cloud.logging.v2.ConfigClient;
import com.google.cloud.logging.v2.ConfigSettings;
import com.google.cloud.logging.v2.LoggingClient;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.cloud.logging.v2.LoggingSettings;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.logging.v2.CreateSinkRequest;
import com.google.logging.v2.LogEntry;
import com.google.logging.v2.LogSink;
import com.google.logging.v2.WriteLogEntriesRequest;
import com.google.protobuf.Duration;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs in both the ordinary SDK suite and its isolated IAM-enforcing native stage. */
class LoggingExportIamEnforcementTest {
    @Test
    void writerNeedsItsOwnDestinationGrantDespiteTheIngestionCallersBucketAdminRole() throws Exception {
        boolean enforce = Boolean.parseBoolean(System.getenv("FLOCI_GCP_IAM_TEST_ENFORCEMENT"));
        String id = TestFixtures.uniqueName("sdk-export-iam");
        String parent = "projects/" + TestFixtures.projectId();
        String log = parent + "/logs/" + id;
        String callerEmail = "caller@" + id + ".iam.gserviceaccount.com";
        GenerateAccessTokenResponse token;
        try (IamCredentialsClient credentials = TestFixtures.iamCredentialsClient()) {
            token = credentials.generateAccessToken("projects/-/serviceAccounts/" + callerEmail,
                    List.of(), List.of("https://www.googleapis.com/auth/cloud-platform"), Duration.newBuilder().setSeconds(600).build());
        }
        GoogleCredentials caller = GoogleCredentials.create(new AccessToken(token.getAccessToken(),
                Date.from(Instant.ofEpochSecond(token.getExpireTime().getSeconds()))));
        Storage storage = TestFixtures.storageClient(caller);
        storage.create(BucketInfo.of(id));
        ConfigSettings settings = ConfigSettings.newBuilder()
                .setTransportChannelProvider(ConfigSettings.defaultGrpcTransportProviderBuilder()
                        .setEndpoint(TestFixtures.grpcTarget()).setChannelConfigurator(builder -> builder.usePlaintext()).build())
                .setCredentialsProvider(NoCredentialsProvider.create()).build();
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token.getAccessToken());
        ManagedChannel channel = ManagedChannelBuilder.forTarget(TestFixtures.grpcTarget()).usePlaintext()
                .intercept(MetadataUtils.newAttachHeadersInterceptor(headers)).build();
        try (ConfigClient config = ConfigClient.create(settings); LoggingClient logging = LoggingClient.create(LoggingSettings.newBuilder()
                .setTransportChannelProvider(TestFixtures.channelProviderFor(channel))
                .setCredentialsProvider(NoCredentialsProvider.create()).build())) {
            LogSink sink = config.createSink(CreateSinkRequest.newBuilder().setParent(parent).setUniqueWriterIdentity(true)
                    .setSink(LogSink.newBuilder().setName(id).setDestination("storage.googleapis.com/" + id)
                            .setFilter("logName=\"" + log + "\"")).build());
            Policy callerOnly = Policy.newBuilder().addIdentity(Role.of("roles/storage.admin"), Identity.serviceAccount(callerEmail)).build();
            try {
                storage.setIamPolicy(id, callerOnly);
                write(logging, log, "denied-without-writer-grant");
                assertThat(objects(storage, id)).hasSize(enforce ? 0 : 1);
                String writerEmail = sink.getWriterIdentity().substring("serviceAccount:".length());
                storage.setIamPolicy(id, callerOnly.toBuilder()
                        .addIdentity(Role.of("roles/storage.objectCreator"), Identity.serviceAccount(writerEmail)).build());
                write(logging, log, "allowed-with-writer-grant");
                List<Blob> exported = objects(storage, id);
                assertThat(exported).hasSize(enforce ? 1 : 2);
                assertThat(exported).anyMatch(blob -> new String(blob.getContent(), StandardCharsets.UTF_8)
                        .contains("allowed-with-writer-grant"));
                storage.setIamPolicy(id, callerOnly);
                write(logging, log, "denied-after-revocation");
                assertThat(objects(storage, id)).hasSize(enforce ? 1 : 3);
            } finally {
                config.deleteSink(parent + "/sinks/" + id);
                logging.deleteLog(log);
            }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            objects(storage, id).forEach(blob -> storage.delete(blob.getBlobId()));
            storage.delete(id);
            if (storage instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    private static List<Blob> objects(Storage storage, String bucket) {
        List<Blob> objects = new ArrayList<>();
        storage.list(bucket).iterateAll().forEach(objects::add);
        return objects;
    }

    private static void write(LoggingClient logging, String log, String text) {
        logging.writeLogEntries(WriteLogEntriesRequest.newBuilder().setLogName(log)
                .addEntries(LogEntry.newBuilder().setTextPayload(text)).build());
    }
}
