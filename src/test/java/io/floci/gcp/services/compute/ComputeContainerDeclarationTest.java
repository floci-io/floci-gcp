package io.floci.gcp.services.compute;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputeContainerDeclarationTest {

    @Test
    void theMinimalDeclarationTerraformSendsRunsTheImageAsDeclared() {
        ComputeContainerDeclaration declaration = ComputeContainerDeclaration.parse(
                "spec:\n containers:\n - name: test\n   image: gcr.io/google-containers/busybox\n");

        assertEquals("test", declaration.name());
        assertEquals("gcr.io/google-containers/busybox", declaration.image());
        assertNull(declaration.command(), "the image's entrypoint");
        assertNull(declaration.args(), "the image's cmd");
        assertEquals(List.of(), declaration.env());
        assertEquals("always", declaration.restart(), "konlet's default restart policy");
        assertFalse(declaration.stdin());
        assertFalse(declaration.tty());
        assertFalse(declaration.privileged());
    }

    @Test
    void everyKonletFieldIsRead() {
        ComputeContainerDeclaration declaration = ComputeContainerDeclaration.parse("""
                spec:
                  containers:
                  - name: web
                    image: nginx:1.27
                    command: [/bin/sh]
                    args: [-c, 'echo hi']
                    env:
                    - name: MODE
                      value: test
                    - name: EMPTY
                    stdin: true
                    tty: true
                    securityContext:
                      privileged: true
                    volumeMounts:
                    - name: scratch
                      mountPath: /scratch
                    - name: logs
                      mountPath: /var/log/app
                  volumes:
                  - name: scratch
                    emptyDir:
                      medium: Memory
                  - name: logs
                    hostPath:
                      path: /var/log
                  restartPolicy: OnFailure
                """);

        assertEquals(List.of("/bin/sh"), declaration.command());
        assertEquals(List.of("-c", "echo hi"), declaration.args());
        assertEquals(List.of("MODE=test", "EMPTY="), declaration.env());
        assertTrue(declaration.stdin());
        assertTrue(declaration.tty());
        assertTrue(declaration.privileged());
        assertEquals("on-failure", declaration.restart());
        assertEquals(List.of("/scratch"), declaration.tmpfs());
        assertEquals(List.of("volume logs at /var/log/app (hostPath volumes are not supported)"), declaration.skipped());
    }

    @Test
    void neverMapsToDockersNo() {
        assertEquals("no", ComputeContainerDeclaration.parse(
                "spec:\n  containers:\n  - name: a\n    image: busybox\n  restartPolicy: Never\n").restart());
    }

    @Test
    void declarationsKonletRefusesAreRefusedWithItsMessages() {
        assertRefused("spec:\n  containers: []\n", "Container declaration should include exactly 1 container, 0 found");
        assertRefused("spec:\n  containers:\n  - name: a\n    image: x\n  - name: b\n    image: y\n",
                "Container declaration should include exactly 1 container, 2 found");
        assertRefused("spec:\n  containers:\n  - name: a\n    image: x\n  restartPolicy: Sometimes\n",
                "Invalid container declaration: Unsupported container restart policy 'Sometimes'");
        assertRefused("spec:\n  containers:\n  - name: a\n    image: x\n    volumeMounts:\n    - name: v\n      mountPath: /v\n",
                "Invalid container declaration: Volume v referenced in container a (index: 0) not found in volume definitions.");
        assertRefused("spec:\n  containers:\n  - name: a\n    image: x\n  volumes:\n  - name: v\n    emptyDir:\n      medium: Memory\n",
                "Invalid container declaration: Volume v not referenced by any container.");
        assertRefused("spec:\n  containers:\n  - name: a\n    image: x\n    volumeMounts:\n    - name: v\n      mountPath: /v\n"
                + "  volumes:\n  - name: v\n    emptyDir: {}\n", "Volume v: Unsupported emptyDir volume medium: ");
        assertRefused("spec: [unclosed", "Cannot parse container declaration 'spec: [unclosed'");
    }

    private static void assertRefused(String value, String message) {
        ComputeContainerDeclaration.InvalidDeclaration refused = assertThrows(
                ComputeContainerDeclaration.InvalidDeclaration.class, () -> ComputeContainerDeclaration.parse(value));
        assertTrue(refused.getMessage().startsWith(message), refused.getMessage());
    }
}
