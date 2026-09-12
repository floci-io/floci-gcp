package io.floci.gcp.test;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.cloud.NoCredentials;
import com.google.cloud.compute.v1.*;
import com.google.cloud.storage.*;
import com.google.cloud.storage.multipartupload.model.*;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class RestartContractTest {
    @TestFactory List<DynamicTest> persistenceContract() {
        String phase = System.getenv().getOrDefault("FLOCI_GCP_RESTART_PHASE", "roundtrip");
        return List.of(DynamicTest.dynamicTest("persistence-" + phase, () -> {
            if (phase.equals("seed") || phase.equals("roundtrip")) { seed(); }
            if (phase.equals("verify") || phase.equals("roundtrip")) { verify(); }
            assertThat(phase).isIn("seed", "verify", "roundtrip");
        }));
    }
    private Path statePath() {
        return Path.of(System.getenv().getOrDefault("FLOCI_GCP_RESTART_STATE", "target/restart.properties"));
    }
    private DisksClient disks() throws Exception {
        return DisksClient.create(DisksSettings.newBuilder().setEndpoint(TestFixtures.endpoint() + "/")
                .setCredentialsProvider(NoCredentialsProvider.create()).build());
    }
    private HttpStorageOptions options() {
        return StorageOptions.http().setHost(TestFixtures.endpoint()).setProjectId(TestFixtures.projectId())
                .setCredentials(NoCredentials.getInstance()).build();
    }
    private void seed() throws Exception {
        var state = new Properties();
        state.setProperty("project", TestFixtures.uniqueName("java-restart"));
        state.setProperty("bucket", TestFixtures.uniqueName("java-restart"));
        state.setProperty("request", UUID.randomUUID().toString());
        var options = options();
        var storage = options.getService();
        storage.create(BucketInfo.of(state.getProperty("bucket")));
        var uploads = MultipartUploadClient.create(MultipartUploadSettings.of(options));
        String id = uploads.createMultipartUpload(CreateMultipartUploadRequest.builder().bucket(state.getProperty("bucket")).key("pending").build()).uploadId();
        state.setProperty("upload", id);
        String etag = uploads.uploadPart(UploadPartRequest.builder().bucket(state.getProperty("bucket")).key("pending").uploadId(id).partNumber(7).build(), RequestBody.of(ByteBuffer.wrap(new byte[]{1,0,3,4}))).eTag();
        state.setProperty("etag", etag);
        try (var disks = disks()) {
            var op = disks.insertCallable().call(InsertDiskRequest.newBuilder().setProject(state.getProperty("project")).setZone("us-central1-a")
                    .setRequestId(state.getProperty("request")).setDiskResource(Disk.newBuilder().setName("persistent").setSizeGb(24)).build());
            state.setProperty("operation", op.getName());
        }
        Files.createDirectories(statePath().getParent());
        try (var out = Files.newOutputStream(statePath())) { state.store(out, "Synthetic SDK restart fixture"); }
    }
    private void verify() throws Exception {
        var state = new Properties();
        try (var input = Files.newInputStream(statePath())) { state.load(input); }
        String project = state.getProperty("project"), bucket = state.getProperty("bucket"), upload = state.getProperty("upload");
        try (var disks = disks()) {
            assertThat(disks.get(project, "us-central1-a", "persistent").getSizeGb()).isEqualTo(24);
            var op = disks.insertAsync(InsertDiskRequest.newBuilder().setProject(project).setZone("us-central1-a")
                    .setRequestId(state.getProperty("request")).setDiskResource(Disk.newBuilder().setName("persistent").setSizeGb(24)).build()).get(20, TimeUnit.SECONDS);
            assertThat(op.getName()).isEqualTo(state.getProperty("operation"));
            assertThat(op.getStatus()).isEqualTo(Operation.Status.DONE);
            var options = options();
            var storage = options.getService();
            var uploads = MultipartUploadClient.create(MultipartUploadSettings.of(options));
            assertThat(storage.get(bucket, "pending")).isNull();
            assertThat(uploads.listParts(ListPartsRequest.builder().bucket(bucket).key("pending").uploadId(upload).build()).parts()).hasSize(1);
            uploads.completeMultipartUpload(CompleteMultipartUploadRequest.builder().bucket(bucket).key("pending").uploadId(upload)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(List.of(CompletedPart.builder().partNumber(7).eTag(state.getProperty("etag")).build())).build()).build());
            assertThat(storage.readAllBytes(bucket, "pending")).isEqualTo(new byte[]{1,0,3,4});
            storage.delete(bucket, "pending"); storage.delete(bucket);
            disks.deleteAsync(project, "us-central1-a", "persistent").get(20, TimeUnit.SECONDS);
            assertThat(disks.list(project, "us-central1-a").iterateAll()).isEmpty();
        }
    }
}
