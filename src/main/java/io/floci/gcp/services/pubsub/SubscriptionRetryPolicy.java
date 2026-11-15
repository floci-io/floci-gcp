package io.floci.gcp.services.pubsub;

import com.google.protobuf.Duration;
import com.google.protobuf.util.Durations;
import com.google.pubsub.v1.RetryPolicy;

import java.text.ParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts a subscription retry policy between its gRPC message and the stored form, which is the
 * REST JSON shape ({@code {"minimumBackoff":"10s","maximumBackoff":"600s"}}), so both transports
 * read and write the same value.
 *
 * <p>A present policy is kept with the defaults the proto documents for unset fields (10 seconds
 * minimum, 600 seconds maximum backoff), so it stays distinct from an absent one, which means
 * immediate redelivery.
 */
final class SubscriptionRetryPolicy {

    private static final String MINIMUM_BACKOFF = "minimumBackoff";
    private static final String MAXIMUM_BACKOFF = "maximumBackoff";
    private static final Duration DEFAULT_MINIMUM_BACKOFF = Durations.fromSeconds(10);
    private static final Duration DEFAULT_MAXIMUM_BACKOFF = Durations.fromSeconds(600);

    private SubscriptionRetryPolicy() {}

    /** Returns the stored form of a gRPC retry policy, or null when the request has none. */
    static Map<String, Object> toStored(boolean present, RetryPolicy policy) {
        if (!present) {
            return null;
        }
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put(MINIMUM_BACKOFF, Durations.toString(
                policy.hasMinimumBackoff() ? policy.getMinimumBackoff() : DEFAULT_MINIMUM_BACKOFF));
        stored.put(MAXIMUM_BACKOFF, Durations.toString(
                policy.hasMaximumBackoff() ? policy.getMaximumBackoff() : DEFAULT_MAXIMUM_BACKOFF));
        return stored;
    }

    /** Returns the gRPC retry policy for a stored one, or null when none is stored. */
    static RetryPolicy toProto(Map<String, Object> stored) {
        if (stored == null) {
            return null;
        }
        RetryPolicy.Builder builder = RetryPolicy.newBuilder();
        setIfParseable(stored.get(MINIMUM_BACKOFF), builder, true);
        setIfParseable(stored.get(MAXIMUM_BACKOFF), builder, false);
        return builder.build();
    }

    private static void setIfParseable(Object value, RetryPolicy.Builder builder, boolean minimum) {
        if (value == null) {
            return;
        }
        try {
            Duration duration = Durations.parse(value.toString());
            if (minimum) {
                builder.setMinimumBackoff(duration);
            } else {
                builder.setMaximumBackoff(duration);
            }
        } catch (ParseException ignored) {
            // A value the REST surface stored but that is not a protobuf duration has no gRPC form.
        }
    }
}
