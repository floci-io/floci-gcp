package io.floci.gcp.config;

import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;

import java.util.Set;

/**
 * Rejects a one-sided TLS PEM configuration with a clear message. Without this guard, a
 * lone {@code floci-gcp.tls.certificate} or {@code floci-gcp.tls.certificate-key} reaches
 * Quarkus's ssl options and aborts the entire emulator at HTTP server init — plaintext
 * listener included — with the cause buried in a stack trace. Runs in the config layer
 * because that failure happens before any startup observer would.
 */
public class TlsCertificatePairInterceptor implements ConfigSourceInterceptor {

    private static final String CERT = "floci-gcp.tls.certificate";
    private static final String KEY = "floci-gcp.tls.certificate-key";
    private static final Set<String> GUARDED = Set.of(
            CERT, KEY,
            "quarkus.http.ssl.certificate.files",
            "quarkus.http.ssl.certificate.key-files");

    @Override
    public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
        if (GUARDED.contains(name)) {
            boolean hasCert = isSet(context, CERT);
            boolean hasKey = isSet(context, KEY);
            if (hasCert != hasKey) {
                String set = hasCert ? CERT : KEY;
                String missing = hasCert ? KEY : CERT;
                throw new IllegalStateException(
                        "TLS configuration is one-sided: " + set + " is set but " + missing
                                + " is not. Set both FLOCI_GCP_TLS_CERTIFICATE and"
                                + " FLOCI_GCP_TLS_CERTIFICATE_KEY to enable the TLS listener,"
                                + " or neither to leave it disabled.");
            }
        }
        return context.proceed(name);
    }

    private static boolean isSet(ConfigSourceInterceptorContext context, String name) {
        ConfigValue value = context.proceed(name);
        return value != null && value.getValue() != null && !value.getValue().isEmpty();
    }
}
