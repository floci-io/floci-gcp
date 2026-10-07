package io.floci.gcp.services.cloudrun;

import com.google.cloud.run.v2.Container;
import io.floci.gcp.core.common.GcpException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Docker limits parsed from a Cloud Run container's {@code resources.limits}. A null component means the
 * key is absent and the container runs without that limit.
 */
record CloudRunResourceLimits(Long memoryBytes, Long nanoCpus) {

    private static final Pattern QUANTITY = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)([A-Za-z]*)");

    private static final Map<String, BigDecimal> NANO_CPUS_PER_UNIT = Map.of(
            "", BigDecimal.valueOf(1_000_000_000L),
            "m", BigDecimal.valueOf(1_000_000L));

    private static final Map<String, BigDecimal> BYTES_PER_UNIT = Map.of(
            "", BigDecimal.ONE,
            "k", BigDecimal.valueOf(1_000L),
            "M", BigDecimal.valueOf(1_000_000L),
            "G", BigDecimal.valueOf(1_000_000_000L),
            "T", BigDecimal.valueOf(1_000_000_000_000L),
            "Ki", BigDecimal.valueOf(1L << 10),
            "Mi", BigDecimal.valueOf(1L << 20),
            "Gi", BigDecimal.valueOf(1L << 30),
            "Ti", BigDecimal.valueOf(1L << 40));

    private static final BigDecimal MAX_LONG = BigDecimal.valueOf(Long.MAX_VALUE);

    static CloudRunResourceLimits of(Container container) {
        Map<String, String> limits = container.getResources().getLimitsMap();
        return new CloudRunResourceLimits(
                parse(limits, "memory", BYTES_PER_UNIT),
                parse(limits, "cpu", NANO_CPUS_PER_UNIT));
    }

    private static Long parse(Map<String, String> limits, String key, Map<String, BigDecimal> scalePerUnit) {
        String value = limits.get(key);
        if (value == null) {
            return null;
        }
        Matcher matcher = QUANTITY.matcher(value);
        BigDecimal scale = matcher.matches() ? scalePerUnit.get(matcher.group(2)) : null;
        if (scale == null) {
            throw invalid(key, value);
        }
        BigDecimal amount = new BigDecimal(matcher.group(1)).multiply(scale).setScale(0, RoundingMode.CEILING);
        if (amount.signum() <= 0 || amount.compareTo(MAX_LONG) > 0) {
            throw invalid(key, value);
        }
        return amount.longValueExact();
    }

    private static GcpException invalid(String key, String value) {
        return GcpException.invalidArgument("Invalid value for resources.limits." + key + ": " + value);
    }
}
