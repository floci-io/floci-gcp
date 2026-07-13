package io.floci.gcp.core.common;

import io.floci.gcp.config.EmulatorConfig;

public final class OperatorRootAuth {

    private OperatorRootAuth() {}

    public static boolean matches(EmulatorConfig config, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        var rootTok = config.auth().rootAccessToken();
        var rootSa = config.auth().rootServiceAccount();
        return rootTok.filter(t -> t.equals(token)).isPresent()
                && rootSa.filter(s -> !s.isBlank()).isPresent();
    }

    public static String rootEmail(EmulatorConfig config) {
        return config.auth().rootServiceAccount().orElse("");
    }
}
