package io.floci.gcp.core.common;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerEnvHardeningTest {

    @Test
    void blocksGcpAndFlociAuthKeys() {
        assertTrue(ContainerEnvHardening.isBlocked("GOOGLE_APPLICATION_CREDENTIALS"));
        assertTrue(ContainerEnvHardening.isBlocked("GOOGLE_API_KEY"));
        assertTrue(ContainerEnvHardening.isBlocked("GCLOUD_ACCESS_TOKEN"));
        assertTrue(ContainerEnvHardening.isBlocked("GOOGLE_OAUTH_ACCESS_TOKEN"));
        assertTrue(ContainerEnvHardening.isBlocked("GOOGLE_CLOUD_PROJECT"));
        assertTrue(ContainerEnvHardening.isBlocked("GCLOUD_PROJECT"));
        assertTrue(ContainerEnvHardening.isBlocked("CLOUDSDK_CORE_PROJECT"));
        assertTrue(ContainerEnvHardening.isBlocked("floci_gcp_auth_custom"));
        assertFalse(ContainerEnvHardening.isBlocked("CUSTOM_VAR"));
    }

    @Test
    void blocksCloudSdkAndApplicationPrefixes() {
        assertTrue(ContainerEnvHardening.isBlocked("CLOUDSDK_AUTH_ACCESS_TOKEN"));
        assertTrue(ContainerEnvHardening.isBlocked("CLOUDSDK_AUTH_CREDENTIAL_FILE_OVERRIDE"));
        assertTrue(ContainerEnvHardening.isBlocked("CLOUDSDK_AUTH_CUSTOM"));
        assertTrue(ContainerEnvHardening.isBlocked("CLOUDSDK_API_ENDPOINT_OVERRIDES_STORAGE"));
        assertTrue(ContainerEnvHardening.isBlocked("CLOUDSDK_CORE_ACCOUNT"));
        assertTrue(ContainerEnvHardening.isBlocked("GOOGLE_APPLICATION_CREDENTIALS_JSON"));
    }

    @Test
    void blocksFlociGcpAuthPrefix() {
        assertTrue(ContainerEnvHardening.isBlocked("FLOCI_GCP_AUTH_ROOT_SERVICE_ACCOUNT"));
        assertTrue(ContainerEnvHardening.isBlocked("FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN"));
        assertTrue(ContainerEnvHardening.isBlocked("FLOCI_GCP_AUTH_VALIDATE_SIGNATURES"));
    }

    @Test
    void isBlockedIsCaseInsensitive() {
        assertTrue(ContainerEnvHardening.isBlocked("google_application_credentials"));
        assertTrue(ContainerEnvHardening.isBlocked("Google_Api_Key"));
        assertTrue(ContainerEnvHardening.isBlocked("cloudsdk_auth_access_token"));
        assertTrue(ContainerEnvHardening.isBlocked("google_oauth_access_token"));
        assertTrue(ContainerEnvHardening.isBlocked("gcloud_project"));
    }

    @Test
    void isBlockedTrimsKeysBeforeCheck() {
        assertTrue(ContainerEnvHardening.isBlocked(" GOOGLE_OAUTH_ACCESS_TOKEN "));
        assertTrue(ContainerEnvHardening.isBlocked("\tGOOGLE_CLOUD_PROJECT\t"));
        assertTrue(ContainerEnvHardening.isBlocked(" CLOUDSDK_API_ENDPOINT_OVERRIDES_IAM "));
    }

    @Test
    void nullAndBlankKeysAreNotBlocked() {
        assertFalse(ContainerEnvHardening.isBlocked(null));
        assertFalse(ContainerEnvHardening.isBlocked(""));
        assertFalse(ContainerEnvHardening.isBlocked("   "));
    }

    @Test
    void operatorCredentialsWinAfterUserEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("GOOGLE_APPLICATION_CREDENTIALS", "player-creds.json");
        env.put("PLAYER_ROLE", "scanner");

        ContainerEnvHardening.mergeUserEnvThenOperatorCredentials(env, Map.of(
                "GOOGLE_API_KEY", "player-api-key"));

        assertEquals("scanner", env.get("PLAYER_ROLE"));
        assertFalse(env.containsKey("GOOGLE_API_KEY"),
                "user-supplied API key must not survive merge");
        assertFalse(env.containsKey("GOOGLE_APPLICATION_CREDENTIALS"),
                "user-supplied credentials path must not survive merge");
    }

    @Test
    void userEnvCannotInjectCloudSdkAuth() {
        Map<String, String> env = new LinkedHashMap<>();
        ContainerEnvHardening.putAllIfAllowed(env, Map.of(
                "CLOUDSDK_AUTH_ACCESS_TOKEN", "attacker-token",
                "PLAYER_VAR", "ok"));
        assertFalse(env.containsKey("CLOUDSDK_AUTH_ACCESS_TOKEN"));
        assertEquals("ok", env.get("PLAYER_VAR"));
    }

    @Test
    void filterEnvListDropsBlockedEntries() {
        List<String> filtered = ContainerEnvHardening.filterEnvList(List.of(
                "PLAYER=x",
                "GOOGLE_APPLICATION_CREDENTIALS=/tmp/creds.json",
                "FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN=token"));
        assertEquals(List.of("PLAYER=x"), filtered);
    }

    @Test
    void mergeUserEnvListToMapStripsBlockedAndAppliesOverrides() {
        Map<String, String> taskDef = new LinkedHashMap<>();
        taskDef.put("PLAYER_ROLE", "scanner");
        taskDef.put("GOOGLE_APPLICATION_CREDENTIALS", "task-creds.json");
        taskDef.put("FLOCI_GCP_AUTH_ROOT_SERVICE_ACCOUNT", "sa@example.com");

        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("PLAYER_ROLE", "exfil");
        overrides.put("GCLOUD_ACCESS_TOKEN", "task-token");
        overrides.put("CUSTOM_VAR", "yes");

        Map<String, String> merged = ContainerEnvHardening.mergeUserEnvListToMap(taskDef, overrides);

        assertEquals("exfil", merged.get("PLAYER_ROLE"));
        assertEquals("yes", merged.get("CUSTOM_VAR"));
        assertFalse(merged.containsKey("GOOGLE_APPLICATION_CREDENTIALS"));
        assertFalse(merged.containsKey("GCLOUD_ACCESS_TOKEN"));
        assertFalse(merged.containsKey("FLOCI_GCP_AUTH_ROOT_SERVICE_ACCOUNT"));
    }

    @Test
    void putIfAllowedSkipsBlockedKeys() {
        Map<String, String> env = new LinkedHashMap<>();
        ContainerEnvHardening.putIfAllowed(env, "SAFE_VAR", "yes");
        ContainerEnvHardening.putIfAllowed(env, "GOOGLE_API_KEY", "no");
        assertEquals("yes", env.get("SAFE_VAR"));
        assertFalse(env.containsKey("GOOGLE_API_KEY"));
    }

    @Test
    void removeBlockedKeysStripsExistingEntries() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("KEEP", "1");
        env.put("GOOGLE_API_KEY", "secret");
        env.put("FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN", "token");
        ContainerEnvHardening.removeBlockedKeys(env);
        assertEquals(Map.of("KEEP", "1"), env);
    }
}
