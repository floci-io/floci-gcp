package io.floci.gcp.services.pubsub;

import com.google.protobuf.util.Durations;
import com.google.pubsub.v1.ExpirationPolicy;

import java.text.ParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts a subscription expiration policy between its gRPC message and the stored form, which is the
 * REST JSON shape ({@code {"ttl":"172800s"}}), so both transports read and write the same value.
 */
final class SubscriptionExpirationPolicy {

    private static final String TTL = "ttl";

    private SubscriptionExpirationPolicy() {}

    /** Returns the stored form of a gRPC expiration policy, or null when the request has none. */
    static Map<String, Object> toStored(boolean present, ExpirationPolicy policy) {
        if (!present) {
            return null;
        }
        Map<String, Object> stored = new LinkedHashMap<>();
        if (policy.hasTtl()) {
            stored.put(TTL, Durations.toString(policy.getTtl()));
        }
        return stored;
    }

    /** Returns the gRPC expiration policy for a stored one, or null when none is stored. */
    static ExpirationPolicy toProto(Map<String, Object> stored) {
        if (stored == null) {
            return null;
        }
        ExpirationPolicy.Builder builder = ExpirationPolicy.newBuilder();
        Object ttl = stored.get(TTL);
        if (ttl != null) {
            try {
                builder.setTtl(Durations.parse(ttl.toString()));
            } catch (ParseException ignored) {
                // A value the REST surface stored but that is not a protobuf duration has no gRPC form.
            }
        }
        return builder.build();
    }
}
