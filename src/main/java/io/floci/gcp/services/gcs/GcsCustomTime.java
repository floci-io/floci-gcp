package io.floci.gcp.services.gcs;

import com.google.protobuf.Timestamp;
import io.floci.gcp.core.common.GcpException;

import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.function.Function;

/**
 * customTime rules shared by the JSON and gRPC paths. GCS stores the value as a protobuf
 * Timestamp, renders it in UTC with 0, 3, 6 or 9 fraction digits, never removes it once set,
 * and refuses to move it backwards.
 */
final class GcsCustomTime {

    private static final String PARSE_ERROR = """
            Parse Error: Invalid value for type.googleapis.com/google.protobuf.Timestamp field: \
            'Field 'customTime', Illegal timestamp format; timestamps must end with 'Z' or have \
            a valid timezone offset.'.""";

    private static final DateTimeFormatter ERROR_SECONDS = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    // GCS holds the value as int64 nanoseconds. A seconds field it cannot multiply is
    // "too large"; a Timestamp that breaks the protobuf rules (nanos outside 0..999999999,
    // or before 0001-01-01) is an internal error; anything else saturates at the int64 limits.
    private static final long MAX_SECONDS = Long.MAX_VALUE / 1_000_000_000L;
    private static final long MIN_SECONDS = -62_135_596_800L;
    private static final String TOO_LARGE = "Invalid timestamp - too large to convert to nanoseconds.";
    private static final String INTERNAL = "We encountered an internal error. Please try again.";
    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
    private static final BigInteger INT64_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger INT64_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private GcsCustomTime() {
    }

    static String normalize(String value) {
        try {
            return Instant.parse(value).toString();
        } catch (DateTimeParseException e) {
            throw GcpException.invalidArgument(PARSE_ERROR);
        }
    }

    /** WriteObject reports a too-large custom_time as INVALID_ARGUMENT. */
    static String fromWrite(Timestamp value) {
        return fromProto(value, GcpException::invalidArgument);
    }

    /** UpdateObject reports the same failure as INTERNAL. */
    static String fromUpdate(Timestamp value) {
        return fromProto(value, GcpException::internal);
    }

    private static String fromProto(Timestamp value, Function<String, GcpException> tooLarge) {
        if (value.getSeconds() > MAX_SECONDS) {
            throw tooLarge.apply(TOO_LARGE);
        }
        if (value.getNanos() < 0 || value.getNanos() > 999_999_999 || value.getSeconds() < MIN_SECONDS) {
            throw GcpException.internal(INTERNAL);
        }
        var nanos = BigInteger.valueOf(value.getSeconds()).multiply(NANOS_PER_SECOND)
                .add(BigInteger.valueOf(value.getNanos()))
                .max(INT64_MIN).min(INT64_MAX);
        return Instant.EPOCH.plusNanos(nanos.longValueExact()).toString();
    }

    static void requireNotDecreased(String previous, String next) {
        if (previous == null) {
            return;
        }
        var before = Instant.parse(previous);
        var after = Instant.parse(next);
        if (after.isBefore(before)) {
            throw GcpException.invalidArgument("Custom time cannot be decreased. Previously: "
                    + errorFormat(before) + ". Attempting to set: " + errorFormat(after) + ".");
        }
    }

    // The message renders the fraction without trailing zeros and with an explicit +00:00.
    private static String errorFormat(Instant instant) {
        var text = new StringBuilder(ERROR_SECONDS.format(instant));
        if (instant.getNano() != 0) {
            text.append('.').append(String.format("%09d", instant.getNano()).replaceFirst("0+$", ""));
        }
        return text.append("+00:00").toString();
    }
}
