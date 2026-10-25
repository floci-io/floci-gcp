package io.floci.gcp.services.compute;

import com.github.dockerjava.api.DockerClient;
import io.floci.gcp.core.common.docker.ContainerExec;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.services.gcs.GcsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComputeGuestScriptsTest {
    private static final Duration TIMEOUT = Duration.ofMinutes(1);

    private ContainerLifecycleManager lifecycleManager;
    private GcsService gcs;
    private ComputeGuestScripts scripts;
    private ComputeSerialPort port;

    @BeforeEach
    void setUp() {
        lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.getDockerClient()).thenReturn(mock(DockerClient.class, RETURNS_DEEP_STUBS));
        gcs = mock(GcsService.class);
        scripts = new ComputeGuestScripts(lifecycleManager, gcs);
        port = new ComputeSerialPort();
    }

    @Test
    void runsTheInlineScriptThenTheUrlScriptAndStreamsTheirLinesToSerialPortOne() {
        when(gcs.getObjectData("bucket", "boot/setup.sh")).thenReturn("echo from gcs".getBytes(StandardCharsets.UTF_8));
        when(lifecycleManager.execStreaming(eq("c-1"), eq("root"), anyList(), eq(List.of("/bin/sh", "-c", "\"$0\"",
                "/var/lib/google/startup-script")), any(), any(), any()))
                .thenAnswer(invocation -> print(invocation.getArgument(5), "hello\nworld", 0));
        when(lifecycleManager.execStreaming(eq("c-1"), eq("root"), anyList(), eq(List.of("/bin/sh", "-c", "\"$0\"",
                "/var/lib/google/startup-script-url")), any(), any(), any()))
                .thenAnswer(invocation -> print(invocation.getArgument(5), "from gcs\n", 3));
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("startup-script-url", "gs://bucket/boot/setup.sh");
        metadata.put("startup-script", "echo hello; echo world");

        scripts.run(ComputeGuestScripts.Phase.STARTUP, "vm", "c-1", metadata, TIMEOUT, port, () -> true, null);

        List<String> lines = messages();
        assertEquals(List.of("Starting startup scripts.", "Found startup-script in metadata.",
                "startup-script: hello", "startup-script: world", "startup-script exit status 0",
                "Found startup-script-url in metadata.", "startup-script-url: from gcs",
                "startup-script-url exit status 3", "Finished running startup scripts."), lines);
        verify(lifecycleManager).execStreaming(eq("c-1"), eq("root"), eq(List.of()),
                eq(List.of("mkdir", "-p", "/var/lib/google")), any(), any(), any());
    }

    @Test
    void everyLineCarriesTheRunnerPrefix() {
        scripts.run(ComputeGuestScripts.Phase.SHUTDOWN, "vm", "c-1", Map.of(), TIMEOUT, port, () -> true, null);

        String output = port.read(0).contents();
        assertTrue(output.matches("\\d{4}-\\d{2}-\\d{2}T[^ ]+ vm google_metadata_script_runner\\[1]: "
                + "No shutdown scripts to run\\.\\n"), output);
        verify(lifecycleManager, never()).execStreaming(any(), any(), anyList(), anyList(), any(), any(), any());
    }

    @Test
    void timeoutsAndUnsupportedUrlsAreReportedAndTheRunGoesOn() {
        when(lifecycleManager.execStreaming(eq("c-1"), eq("root"), anyList(), anyList(), any(), any(), any()))
                .thenReturn(new ContainerExec.Result(-1, "", "Timed out", true));
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("startup-script", "sleep 999");
        metadata.put("startup-script-url", "https://example.com/setup.sh");

        scripts.run(ComputeGuestScripts.Phase.STARTUP, "vm", "c-1", metadata, TIMEOUT, port, () -> true, null);

        List<String> lines = messages();
        assertTrue(lines.contains("startup-script still running after PT1M; no longer waiting for it."), lines.toString());
        assertTrue(lines.contains("startup-script-url: only Cloud Storage URLs are supported, not https://example.com/setup.sh"),
                lines.toString());
        assertEquals("Finished running startup scripts.", lines.getLast());
    }

    @Test
    void cloudStorageUrlFormsAreReadFromFlociGcs() {
        when(gcs.getObjectData("b", "dir/s.sh")).thenReturn(new byte[] {1});

        scripts.fetch("gs://b/dir/s.sh");
        scripts.fetch("https://storage.googleapis.com/b/dir/s.sh");
        scripts.fetch("https://storage.cloud.google.com/b/dir/s.sh");

        verify(gcs, times(3)).getObjectData("b", "dir/s.sh");
    }

    @Test
    void httpsUrlsAreDecodedAndGsReferencesAreTakenLiterally() {
        when(gcs.getObjectData(eq("b"), any())).thenReturn(new byte[] {1});

        scripts.fetch("https://storage.googleapis.com/b/boot/my%20script.sh");
        scripts.fetch("gs://b/boot/my%20script.sh");

        verify(gcs).getObjectData("b", "boot/my script.sh");
        verify(gcs).getObjectData("b", "boot/my%20script.sh");
    }

    @Test
    void allScriptsOfAPhaseShareOneDeadline() {
        Duration limit = Duration.ofMillis(300);
        when(lifecycleManager.execStreaming(eq("c-1"), eq("root"), anyList(), eq(List.of("/bin/sh", "-c", "\"$0\"",
                "/var/lib/google/shutdown-script")), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Thread.sleep(400);
                    return new ContainerExec.Result(-1, "", "Timed out", true);
                });
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("shutdown-script", "sleep 999");
        metadata.put("shutdown-script-url", "gs://bucket/stop.sh");

        scripts.run(ComputeGuestScripts.Phase.SHUTDOWN, "vm", "c-1", metadata, limit, port, () -> true, null);

        verify(lifecycleManager, never()).execStreaming(eq("c-1"), eq("root"), anyList(),
                eq(List.of("/bin/sh", "-c", "\"$0\"", "/var/lib/google/shutdown-script-url")), any(), any(), any());
        assertTrue(messages().contains("shutdown-script-url not run: the shutdown script time limit of PT0.3S was used up."),
                messages().toString());
    }

    @Test
    void outputWithoutNewlinesIsWrittenInBoundedLines() throws Exception {
        ComputeGuestScripts.LineWriter out = new ComputeGuestScripts.LineWriter(port, "vm", "startup-script: ");
        byte[] chunk = new byte[ComputeGuestScripts.MAX_LINE_BYTES * 3];
        Arrays.fill(chunk, (byte) 'x');

        out.write(chunk);
        out.close();

        List<String> lines = messages();
        assertEquals(3, lines.size());
        assertTrue(lines.stream().allMatch(line -> line.length() == "startup-script: ".length()
                + ComputeGuestScripts.MAX_LINE_BYTES), lines.toString().length() + " chars");
    }

    @Test
    void eachStartupScriptGetsTheWholeTimeoutAndTheSecondStillRuns() {
        Duration limit = Duration.ofMillis(300);
        when(gcs.getObjectData("bucket", "boot.sh")).thenReturn("echo url".getBytes(StandardCharsets.UTF_8));
        when(lifecycleManager.execStreaming(eq("c-1"), eq("root"), anyList(), eq(List.of("/bin/sh", "-c", "\"$0\"",
                "/var/lib/google/startup-script")), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Thread.sleep(400);
                    return new ContainerExec.Result(-1, "", "Timed out", true);
                });
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("startup-script", "sleep 999");
        metadata.put("startup-script-url", "gs://bucket/boot.sh");

        scripts.run(ComputeGuestScripts.Phase.STARTUP, "vm", "c-1", metadata, limit, port, () -> true, null);

        verify(lifecycleManager).execStreaming(eq("c-1"), eq("root"), anyList(),
                eq(List.of("/bin/sh", "-c", "\"$0\"", "/var/lib/google/startup-script-url")), eq(limit), any(), any());
    }

    @Test
    void aRunWhoseBootEndedStopsBeforeItsNextScript() {
        AtomicBoolean current = new AtomicBoolean(true);
        when(lifecycleManager.execStreaming(eq("c-1"), eq("root"), anyList(), eq(List.of("/bin/sh", "-c", "\"$0\"",
                "/var/lib/google/startup-script")), any(), any(), any()))
                .thenAnswer(invocation -> {
                    current.set(false);
                    return new ContainerExec.Result(0, "", "", false);
                });
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("startup-script", "true");
        metadata.put("startup-script-url", "gs://bucket/boot.sh");

        scripts.run(ComputeGuestScripts.Phase.STARTUP, "vm", "c-1", metadata, TIMEOUT, port, current::get, null);

        verify(gcs, never()).getObjectData(any(), any());
        verify(lifecycleManager, never()).execStreaming(eq("c-1"), eq("root"), anyList(),
                eq(List.of("/bin/sh", "-c", "\"$0\"", "/var/lib/google/startup-script-url")), any(), any(), any());
    }

    @Test
    void anOverLongLineIsNeverSplitInsideAUtf8Character() throws Exception {
        ComputeGuestScripts.LineWriter out = new ComputeGuestScripts.LineWriter(port, "vm", "s: ");
        byte[] ascii = new byte[ComputeGuestScripts.MAX_LINE_BYTES - 1];
        Arrays.fill(ascii, (byte) 'a');

        out.write(ascii);
        out.write("\u00e9b".getBytes(StandardCharsets.UTF_8));
        out.close();

        List<String> lines = messages();
        assertEquals(2, lines.size(), lines.size() + " lines");
        assertEquals("s: " + "a".repeat(ComputeGuestScripts.MAX_LINE_BYTES - 1), lines.get(0));
        assertEquals("s: \u00e9b", lines.get(1));
    }

    @Test
    void withABootMarkEachScriptRunsBehindTheBootGuard() {
        when(lifecycleManager.execStreaming(eq("c-1"), eq("root"), anyList(), eq(List.of("/bin/sh", "-c",
                ComputeGuestScripts.BOOT_GUARD, "/var/lib/google/startup-script", "4242")), any(), any(), any()))
                .thenReturn(new ContainerExec.Result(ComputeGuestScripts.REBOOTED, "", "", false));

        when(lifecycleManager.exec("c-1", List.of(), List.of("cut", "-d", " ", "-f22", "/proc/1/stat")))
                .thenReturn(new ContainerLifecycleManager.ExecResult(0, "5151\n", ""));

        scripts.run(ComputeGuestScripts.Phase.STARTUP, "vm", "c-1", Map.of("startup-script", "echo hi"), TIMEOUT,
                port, () -> true, "4242");

        assertTrue(messages().contains("startup-script not run: the instance rebooted since this boot."),
                messages().toString());
    }

    @Test
    void aScriptThatItselfExitsWith125InTheSameBootDoesNotStopTheNextScript() {
        when(gcs.getObjectData("bucket", "boot.sh")).thenReturn("echo url".getBytes(StandardCharsets.UTF_8));
        when(lifecycleManager.execStreaming(eq("c-1"), eq("root"), anyList(), eq(List.of("/bin/sh", "-c",
                ComputeGuestScripts.BOOT_GUARD, "/var/lib/google/startup-script", "4242")), any(), any(), any()))
                .thenReturn(new ContainerExec.Result(ComputeGuestScripts.REBOOTED, "", "", false));
        when(lifecycleManager.execStreaming(eq("c-1"), eq("root"), anyList(), eq(List.of("/bin/sh", "-c",
                ComputeGuestScripts.BOOT_GUARD, "/var/lib/google/startup-script-url", "4242")), any(), any(), any()))
                .thenReturn(new ContainerExec.Result(0, "", "", false));
        when(lifecycleManager.exec("c-1", List.of(), List.of("cut", "-d", " ", "-f22", "/proc/1/stat")))
                .thenReturn(new ContainerLifecycleManager.ExecResult(0, "4242\n", ""));
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("startup-script", "exit 125");
        metadata.put("startup-script-url", "gs://bucket/boot.sh");

        scripts.run(ComputeGuestScripts.Phase.STARTUP, "vm", "c-1", metadata, TIMEOUT, port, () -> true, "4242");

        assertTrue(messages().contains("startup-script exit status 125"), messages().toString());
        assertTrue(messages().contains("startup-script-url exit status 0"), messages().toString());
    }

    private static ContainerExec.Result print(OutputStream out, String text, int exitCode) throws Exception {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        return new ContainerExec.Result(exitCode, "", "", false);
    }

    /** The serial lines without their time, host and runner prefix. */
    private List<String> messages() {
        return port.read(0).contents().lines()
                .map(line -> line.substring(line.indexOf("google_metadata_script_runner[1]: ") + 34)).toList();
    }

    @Test
    void theAccountsScriptParsesAndHandsEachUserExactlyItsKeys() throws Exception {
        Map<String, List<String>> users = new LinkedHashMap<>();
        users.put("alice", List.of("ssh-ed25519 AAAAC3Nz alice@host", "ssh-rsa AAAAB3Nz it's mine"));
        users.put("carol", List.of("ssh-ed25519 AAAAC3Nzc carol"));
        String script = ComputeGuestScripts.accountsScript(users, 42);
        Path file = Files.createTempFile("accounts", ".sh");
        Files.writeString(file, script);
        assertEquals(0, new ProcessBuilder("sh", "-n", file.toString()).start().waitFor(), "the script parses");

        String userLines = script.lines().filter(line -> line.startsWith("ensure_user ")).collect(Collectors.joining("\n"));
        Process run = new ProcessBuilder("sh", "-c", "ensure_user() { :; }; write_keys() { printf '[%s]' \"$@\"; echo; }\n"
                + userLines).redirectErrorStream(true).start();
        String out = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, run.waitFor(), out);
        assertEquals("""
                Updating keys for user alice.
                [alice][ssh-ed25519 AAAAC3Nz alice@host][ssh-rsa AAAAB3Nz it's mine]
                Updating keys for user carol.
                [carol][ssh-ed25519 AAAAC3Nzc carol]
                """, out);
        Files.delete(file);
    }

    @Test
    void aFailedAccountStepIsReportedWithTheExitStatus() {
        ContainerExec.Result failed = mock(ContainerExec.Result.class);
        when(failed.exitCode()).thenReturn(1L);
        when(lifecycleManager.execStreaming(eq("c-1"), eq("root"), anyList(), eq(List.of("/bin/sh",
                "/var/lib/google/floci-guest-accounts-42.sh")), any(), any(), any())).thenReturn(failed);
        ComputeSerialPort port = new ComputeSerialPort();

        scripts.accounts("vm", "c-1", Map.of(), 42, port);

        assertTrue(port.read(0).contents().contains("google_guest_agent[1]: Account setup exit status 1"));
    }

    @Test
    void anOlderAccountUpdateStopsBeforeTouchingAccountsWhileANewerOneRecordedItself() throws Exception {
        Path dir = Files.createTempDirectory("accounts");
        String script = ComputeGuestScripts.accountsScript(Map.of("alice", List.of("ssh-ed25519 AAAAC3Nz alice")), 5)
                .replace(ComputeGuestScripts.ACCOUNTS_REQUEST, dir.resolve("request").toString())
                .replace(ComputeGuestScripts.ACCOUNTS_LOCK, dir.resolve("lock").toString());
        Files.writeString(dir.resolve("request"), "7\n");
        Path file = dir.resolve("run.sh");
        Files.writeString(file, script);

        Process run = new ProcessBuilder("sh", file.toString()).redirectErrorStream(true).start();
        String out = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, run.waitFor(), out);
        assertEquals("", out, "stopped before installing or creating anything");
        assertEquals("7", Files.readString(dir.resolve("request")).strip(), "the newer request stays recorded");
        assertFalse(Files.exists(file), "the script removes itself");
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "dead", "directory"})
    void anAbandonedAccountLockIsTakenOver(String left) throws Exception {
        Path dir = Files.createTempDirectory("accounts");
        Path lock = dir.resolve("lock");
        switch (left) {
            case "empty" -> Files.writeString(lock, "");
            case "dead" -> Files.writeString(lock, "1 0\n");
            default -> Files.createDirectories(lock.resolve("pid"));
        }

        String out = runLock(dir, "7", "take_lock; cat \"$lock\"; [ \"$(cat \"$lock\")\" = \"$me\" ] && echo owned");

        assertTrue(out.endsWith("owned\n"), out);
        try (var files = Files.list(dir)) {
            assertEquals(List.of(lock), files.toList(), "no temporary lock file is left behind");
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void aWaitingAccountUpdateGivesUpOnceANewerOneIsRecorded() throws Exception {
        Path dir = Files.createTempDirectory("accounts");

        String out = runLock(dir, "8", "echo \"$me\" > \"$lock\"; take_lock; echo took");

        assertEquals("", out, "left the live holder's lock alone");
    }

    /** Runs the accounts script's lock functions for request 7 with the newest request {@code newest}. */
    private static String runLock(Path dir, String newest, String body) throws Exception {
        String script = ComputeGuestScripts.accountsScript(null, 7);
        String functions = script.substring(script.indexOf("owner() {"), script.indexOf("\ntake_lock\n"));
        Files.writeString(dir.resolve("request"), newest + "\n");
        Process run = new ProcessBuilder("sh", "-c", "request=7\nmark=" + dir.resolve("request") + "\nlock="
                + dir.resolve("lock") + "\n" + functions + "\n" + body).redirectErrorStream(true).start();
        String out = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, run.waitFor(), out);
        Files.delete(dir.resolve("request"));
        return out;
    }

    @Test
    void quoteMakesOneShellWordOfAnything() {
        assertEquals("'it'\\''s'", ComputeGuestScripts.quote("it's"));
    }
}
