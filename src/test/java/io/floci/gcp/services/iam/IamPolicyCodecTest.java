package io.floci.gcp.services.iam;

import com.google.iam.v1.Binding;
import com.google.iam.v1.Policy;
import com.google.protobuf.ByteString;
import com.google.type.Expr;
import io.floci.gcp.services.iam.model.StoredPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IamPolicyCodecTest {

    @Test
    void protoPolicyRoundTripsThroughStoredForm() {
        Policy original = Policy.newBuilder()
                .setVersion(3)
                .setEtag(ByteString.copyFromUtf8("abc123"))
                .addBindings(Binding.newBuilder()
                        .setRole("roles/pubsub.publisher")
                        .addMembers("serviceAccount:a@p.iam.gserviceaccount.com")
                        .addMembers("user:b@example.com"))
                .addBindings(Binding.newBuilder()
                        .setRole("roles/pubsub.viewer")
                        .addMembers("group:c@example.com"))
                .build();

        Policy roundTripped = IamPolicyCodec.toProtoPolicy(IamPolicyCodec.toStoredPolicy(original));

        assertEquals(original, roundTripped);
    }

    @Test
    void conditionBlockRoundTripsUnchanged() {
        Policy original = Policy.newBuilder()
                .setVersion(3)
                .addBindings(Binding.newBuilder()
                        .setRole("roles/pubsub.publisher")
                        .addMembers("user:x@example.com")
                        .setCondition(Expr.newBuilder()
                                .setExpression("request.time < timestamp('2030-01-01T00:00:00Z')")
                                .setTitle("expiry")
                                .setDescription("temporary grant")
                                .setLocation("policy.tf:12")))
                .build();

        Policy roundTripped = IamPolicyCodec.toProtoPolicy(IamPolicyCodec.toStoredPolicy(original));

        assertEquals(original, roundTripped);
    }

    @Test
    void fromJsonMapParsesRestPolicyShape() {
        Map<String, Object> json = Map.of(
                "version", 1,
                "etag", "xyz",
                "bindings", List.of(Map.of(
                        "role", "roles/pubsub.publisher",
                        "members", List.of("user:x@example.com"))));

        StoredPolicy stored = IamPolicyCodec.fromJsonMap(json);

        assertEquals(1, stored.getVersion());
        assertEquals("xyz", stored.getEtag());
        assertEquals("roles/pubsub.publisher", stored.getBindings().get(0).get("role"));
    }

    @Test
    void fromJsonMapWithoutEtagLeavesItEmptyForBlindWrites() {
        StoredPolicy stored = IamPolicyCodec.fromJsonMap(Map.of("bindings", List.of()));
        assertEquals("", stored.getEtag());
        assertFalse(stored.getBindings() == null);
    }
}
