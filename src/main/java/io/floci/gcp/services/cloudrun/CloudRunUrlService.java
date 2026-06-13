package io.floci.gcp.services.cloudrun;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.dns.EmbeddedDnsServer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class CloudRunUrlService {

    private static final int PROJECT_TOKEN_LENGTH = 12;
    private static final Pattern FIRST_LABEL = Pattern.compile("^(.+)-([0-9a-f]{" + PROJECT_TOKEN_LENGTH + "})$");

    private final EmulatorConfig config;

    @Inject
    public CloudRunUrlService(EmulatorConfig config) {
        this.config = config;
    }

    public String invocationUri(String project, String location, String serviceId) {
        URI base = URI.create(config.effectiveBaseUrl());
        String host = serviceId.toLowerCase(Locale.ROOT)
                + "-" + projectToken(project)
                + "." + location.toLowerCase(Locale.ROOT)
                + ".run."
                + urlHostSuffix();
        return base.getScheme() + "://" + authority(host, base.getPort());
    }

    public Optional<ParsedHost> parseHost(String host) {
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        String normalized = stripTrailingDot(stripPort(host)).toLowerCase(Locale.ROOT);
        String runSuffix = ".run." + urlHostSuffix().toLowerCase(Locale.ROOT);
        if (!normalized.endsWith(runSuffix)) {
            return Optional.empty();
        }

        String prefix = normalized.substring(0, normalized.length() - runSuffix.length());
        int locationStart = prefix.lastIndexOf('.');
        if (locationStart < 0 || locationStart == prefix.length() - 1) {
            return Optional.empty();
        }

        String firstLabel = prefix.substring(0, locationStart);
        String location = prefix.substring(locationStart + 1);
        Matcher matcher = FIRST_LABEL.matcher(firstLabel);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new ParsedHost(matcher.group(1), matcher.group(2), location));
    }

    public boolean matchesProjectToken(String project, String token) {
        return projectToken(project).equals(token);
    }

    String urlHostSuffix() {
        return config.services().cloudrun().execution().urlHostSuffix()
                .or(config::hostname)
                .orElse(EmbeddedDnsServer.DEFAULT_SUFFIX)
                .toLowerCase(Locale.ROOT);
    }

    String projectToken(String project) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(project.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, PROJECT_TOKEN_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String authority(String host, int port) {
        if (port < 0) {
            return host;
        }
        return host + ":" + port;
    }

    private static String stripPort(String host) {
        int portStart = host.lastIndexOf(':');
        if (portStart < 0) {
            return host;
        }
        return host.substring(0, portStart);
    }

    private static String stripTrailingDot(String host) {
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }

    public record ParsedHost(String serviceId, String projectToken, String location) {}
}
