package io.floci.gcp.core.common;

import com.google.cloud.bigquery.storage.v1.CreateReadSessionRequest;
import com.google.cloud.bigquery.storage.v1.ReadSession;
import com.google.cloud.secretmanager.v1.Secret;
import com.google.cloud.secretmanager.v1.UpdateSecretRequest;
import com.google.datastore.v1.LookupRequest;
import com.google.logging.v2.LogEntry;
import com.google.logging.v2.WriteLogEntriesRequest;
import com.google.storage.v2.Bucket;
import com.google.storage.v2.CreateBucketRequest;
import com.google.storage.v2.GetObjectRequest;
import com.google.storage.v2.RewriteObjectRequest;
import io.floci.gcp.core.common.ProtoResourceFields.Ref;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtoResourceFieldsTest {

    @Test
    void annotatedReferenceIsReadAndAPlainStringIsNot() {
        assertEquals(List.of(new Ref("bucket", "storage.googleapis.com/Bucket", true, "projects/_/buckets/b")),
                ProtoResourceFields.refs(GetObjectRequest.newBuilder()
                        .setBucket("projects/_/buckets/b")
                        .setObject("projects/_/buckets/other").build()));
    }

    @Test
    void referenceToAnotherApiKeepsItsType() {
        assertEquals(List.of(
                        new Ref("destination_bucket", "storage.googleapis.com/Bucket", true, "projects/_/buckets/d"),
                        new Ref("destination_kms_key", "cloudkms.googleapis.com/CryptoKey", true,
                                "projects/k/locations/l/keyRings/r/cryptoKeys/c"),
                        new Ref("source_bucket", "storage.googleapis.com/Bucket", true, "projects/_/buckets/s")),
                ProtoResourceFields.refs(RewriteObjectRequest.newBuilder()
                        .setDestinationName("projects/x/buckets/y")
                        .setDestinationBucket("projects/_/buckets/d")
                        .setDestinationKmsKey("projects/k/locations/l/keyRings/r/cryptoKeys/c")
                        .setSourceBucket("projects/_/buckets/s")
                        .setSourceObject("projects/x/buckets/z").build()));
    }

    @Test
    void childTypeParentAndResourceMessageNameAreRead() {
        assertEquals(List.of(
                        new Ref("parent", "storage.googleapis.com/Bucket", true, "projects/p"),
                        new Ref("name", "storage.googleapis.com/Bucket", false, "projects/_/buckets/b")),
                ProtoResourceFields.refs(CreateBucketRequest.newBuilder()
                        .setParent("projects/p")
                        .setBucket(Bucket.newBuilder().setName("projects/_/buckets/b"))
                        .setBucketId("projects/x/buckets/y").build()));
        assertEquals(List.of(new Ref("name", "secretmanager.googleapis.com/Secret", false, "projects/p/secrets/s")),
                ProtoResourceFields.refs(UpdateSecretRequest.newBuilder()
                        .setSecret(Secret.newBuilder().setName("projects/p/secrets/s")).build()));
    }

    @Test
    void nestedReferenceAndCustomNameFieldAreRead() {
        assertEquals(List.of(
                        new Ref("parent", "cloudresourcemanager.googleapis.com/Project", true, "projects/a"),
                        new Ref("table", "bigquery.googleapis.com/Table", false, "projects/b/datasets/d/tables/t")),
                ProtoResourceFields.refs(CreateReadSessionRequest.newBuilder()
                        .setParent("projects/a")
                        .setReadSession(ReadSession.newBuilder().setTable("projects/b/datasets/d/tables/t"))
                        .build()));
        assertEquals(List.of(
                        new Ref("log_name", "logging.googleapis.com/Log", true, "projects/a/logs/l"),
                        new Ref("log_name", "logging.googleapis.com/Log", false, "projects/b/logs/l")),
                ProtoResourceFields.refs(WriteLogEntriesRequest.newBuilder()
                        .setLogName("projects/a/logs/l")
                        .addEntries(LogEntry.newBuilder().setLogName("projects/b/logs/l")
                                .setTextPayload("projects/c/logs/l")
                                .putLabels("k", "projects/d/logs/l"))
                        .build()));
    }

    @Test
    void unannotatedTargetFieldOfTheRequestIsRead() {
        assertEquals(List.of(new Ref("project_id", null, true, "p")),
                ProtoResourceFields.refs(LookupRequest.newBuilder().setProjectId("p")
                        .setDatabaseId("projects/x").build()));
    }
}
