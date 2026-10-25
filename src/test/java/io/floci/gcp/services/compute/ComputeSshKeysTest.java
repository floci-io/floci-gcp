package io.floci.gcp.services.compute;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ComputeSshKeysTest {
    private static final String ALICE = "alice:ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIA alice@host";
    private static final String BOB = "bob:ssh-rsa AAAAB3NzaC1yc2EAAAADAQAB bob@host";

    @Test
    void instanceKeysComeFirstThenProjectKeys() {
        assertEquals(List.of(ALICE, BOB), ComputeSshKeys.lines(Map.of("ssh-keys", ALICE), Map.of("ssh-keys", BOB)));
    }

    @Test
    void blockProjectSshKeysAndTheLegacyKeyDropProjectKeys() {
        assertEquals(List.of(ALICE), ComputeSshKeys.lines(Map.of("ssh-keys", ALICE, "block-project-ssh-keys", "TRUE"),
                Map.of("ssh-keys", BOB)));
        assertEquals(List.of(ALICE), ComputeSshKeys.lines(Map.of("sshKeys", ALICE), Map.of("ssh-keys", BOB)));
        assertEquals(List.of(ALICE, BOB), ComputeSshKeys.lines(Map.of("ssh-keys", ALICE, "block-project-ssh-keys", "nope"),
                Map.of("ssh-keys", BOB)), "a value Go cannot parse as a bool leaves project keys in");
    }

    @Test
    void osLoginLeavesAccountsAloneWithTheInstanceValueWinning() {
        assertNull(ComputeSshKeys.lines(Map.of("ssh-keys", ALICE), Map.of("enable-oslogin", "true")));
        assertEquals(List.of(ALICE), ComputeSshKeys.lines(Map.of("ssh-keys", ALICE, "enable-oslogin", "false"),
                Map.of("enable-oslogin", "true")));
    }

    @Test
    void malformedAndExpiredKeysAreLeftOut() {
        Instant now = Instant.parse("2026-10-25T00:00:00Z");
        String expiring = "carol:ssh-ed25519 AAAAC3Nz google-ssh {\"userName\":\"carol\",\"expireOn\":\"2026-10-26T00:00:00+0000\"}";
        String expired = "dave:ssh-ed25519 AAAAC3Nz google-ssh {\"userName\":\"dave\",\"expireOn\":\"2026-10-24T00:00:00Z\"}";
        var users = ComputeSshKeys.users(List.of(ALICE, "alice:no-prefix-key", "no colon", ":ssh-rsa AAAA",
                "bad user:ssh-rsa AAAA", "erin:command=\"ls\" ssh-rsa AAAAB3Nz erin", expiring, expired,
                "alice:ssh-rsa AAAAB3NzaC1yc2E second"), now);

        assertEquals(List.of("alice", "erin", "carol"), List.copyOf(users.keySet()));
        assertEquals(List.of("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIA alice@host", "ssh-rsa AAAAB3NzaC1yc2E second"),
                users.get("alice"));
    }
}
