package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which {@code ssh-keys} a Linux guest installs, as the guest agent's accounts manager decides
 * ({@code google_guest_agent/non_windows_accounts.go}, {@code metadata/metadata.go}, {@code utils/ssh.go}):
 * instance keys, then project keys unless {@code block-project-ssh-keys} is true or the instance still uses the
 * legacy {@code sshKeys} key, and none at all while OS Login is enabled.
 */
final class ComputeSshKeys {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> KEY_TYPES = Set.of("ssh-rsa", "ssh-dss", "ssh-ed25519", "ecdsa-sha2-nistp256",
            "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521", "sk-ssh-ed25519@openssh.com",
            "sk-ecdsa-sha2-nistp256@openssh.com");
    private static final DateTimeFormatter NUMERIC_OFFSET = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

    private ComputeSshKeys() {}

    /**
     * The {@code user:key} lines the guest agent reads from instance and project metadata, or null when OS Login is
     * enabled (the instance value overriding the project's), in which case the agent manages no accounts.
     */
    static List<String> lines(Map<String, String> instance, Map<String, String> project) {
        Boolean instanceOsLogin = parseBool(instance.get("enable-oslogin"));
        Boolean osLogin = instanceOsLogin != null ? instanceOsLogin : parseBool(project.get("enable-oslogin"));
        if (Boolean.TRUE.equals(osLogin)) { return null; }
        List<String> lines = new ArrayList<>(split(instance.get("ssh-keys")));
        String legacy = instance.get("sshKeys");
        boolean block = Boolean.TRUE.equals(parseBool(instance.get("block-project-ssh-keys")))
                || legacy != null && !legacy.isEmpty();
        lines.addAll(split(legacy));
        if (!block) {
            lines.addAll(split(project.get("ssh-keys")));
            lines.addAll(split(project.get("sshKeys")));
        }
        return List.copyOf(lines);
    }

    /** The valid, unexpired keys of each user, in metadata order; malformed and expired lines are left out. */
    static Map<String, List<String>> users(List<String> lines, Instant now) {
        Map<String, List<String>> users = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.strip();
            int colon = line.indexOf(':');
            if (colon <= 0 || colon == line.length() - 1) { continue; }
            String user = line.substring(0, colon);
            String key = line.substring(colon + 1).strip();
            if (user.chars().anyMatch(Character::isWhitespace) || !validKey(key) || expired(key, now)) { continue; }
            users.computeIfAbsent(user, u -> new ArrayList<>()).add(key);
        }
        users.replaceAll((user, keys) -> List.copyOf(keys));
        return users;
    }

    /** An authorized_keys line: optional options, then a known key type followed by its base64 blob. */
    private static boolean validKey(String key) {
        if (key.contains("\n") || key.contains("\r")) { return false; }
        String[] fields = key.split("\\s+");
        for (int i = 0; i < fields.length - 1; i++) {
            if (KEY_TYPES.contains(fields[i])) { return fields[i + 1].matches("[A-Za-z0-9+/]+={0,2}"); }
        }
        return false;
    }

    /** A {@code google-ssh {"expireOn":...}} comment whose time has passed, or that cannot be read, expires the key. */
    private static boolean expired(String key, Instant now) {
        int marker = key.indexOf(" google-ssh");
        if (marker < 0) { return false; }
        String comment = key.substring(marker + 1);
        int space = comment.indexOf(' ');
        if (space < 0) { return true; }
        try {
            JsonNode expiration = JSON.readTree(comment.substring(space + 1));
            String expireOn = expiration.path("expireOn").asText("");
            return expireOn(expireOn).isBefore(now);
        } catch (Exception e) {
            return true;
        }
    }

    private static Instant expireOn(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return OffsetDateTime.parse(value, NUMERIC_OFFSET).toInstant();
        }
    }

    private static List<String> split(String value) {
        if (value == null || value.isEmpty()) { return List.of(); }
        return Arrays.asList(value.split("\n"));
    }

    /** Go's strconv.ParseBool, which the agent uses: null when the value is absent or not a boolean. */
    static Boolean parseBool(String value) {
        if (value == null) { return null; }
        return switch (value) {
            case "1", "t", "T", "TRUE", "true", "True" -> true;
            case "0", "f", "F", "FALSE", "false", "False" -> false;
            default -> null;
        };
    }
}
