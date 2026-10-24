package io.floci.gcp.services.compute;

import com.github.dockerjava.api.DockerClient;
import io.floci.gcp.core.common.docker.ContainerExec;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.services.gcs.GcsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        scripts.run(ComputeGuestScripts.Phase.STARTUP, "vm", "c-1", metadata, TIMEOUT, port);

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
        scripts.run(ComputeGuestScripts.Phase.SHUTDOWN, "vm", "c-1", Map.of(), TIMEOUT, port);

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

        scripts.run(ComputeGuestScripts.Phase.STARTUP, "vm", "c-1", metadata, TIMEOUT, port);

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

        scripts.run(ComputeGuestScripts.Phase.SHUTDOWN, "vm", "c-1", metadata, limit, port);

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

    private static ContainerExec.Result print(OutputStream out, String text, int exitCode) throws Exception {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        return new ContainerExec.Result(exitCode, "", "", false);
    }

    /** The serial lines without their time, host and runner prefix. */
    private List<String> messages() {
        return port.read(0).contents().lines()
                .map(line -> line.substring(line.indexOf("google_metadata_script_runner[1]: ") + 34)).toList();
    }
}
