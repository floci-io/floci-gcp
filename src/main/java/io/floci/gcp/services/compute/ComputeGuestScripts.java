package io.floci.gcp.services.compute;

import io.floci.gcp.core.common.docker.ContainerExec;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.RetryingTarCopier;
import io.floci.gcp.services.gcs.GcsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Runs an instance's startup or shutdown scripts inside its container, as the guest environment's
 * {@code google_metadata_script_runner} does: as root, {@code <phase>-script} first and then
 * {@code <phase>-script-url}, each copied to {@code /var/lib/google} and run verbatim (a script without a shebang runs
 * under {@code /bin/sh}). Every line a script prints, and the runner's own progress lines, go to serial port 1.
 * {@code <phase>-script-url} is read from floci-gcp's Cloud Storage ({@code gs://} and
 * {@code https://storage.googleapis.com/} URLs); other URLs are reported on serial port 1 and skipped.
 */
@ApplicationScoped
public class ComputeGuestScripts {
    private static final Logger LOG = Logger.getLogger(ComputeGuestScripts.class);
    static final String DIRECTORY = "/var/lib/google";
    static final String RUNNER = "google_metadata_script_runner";
    /** The guest environment runs metadata scripts as root, whatever user the image declares. */
    static final String ROOT = "root";
    /** Longest line kept before it is written out unfinished, so output without newlines stays bounded. */
    static final int MAX_LINE_BYTES = 8 * 1024;

    public enum Phase {
        STARTUP("startup"), SHUTDOWN("shutdown");

        final String prefix;

        Phase(String prefix) { this.prefix = prefix; }
    }

    private final ContainerLifecycleManager lifecycleManager;
    private final GcsService gcs;

    @Inject
    public ComputeGuestScripts(ContainerLifecycleManager lifecycleManager, GcsService gcs) {
        this.lifecycleManager = lifecycleManager;
        this.gcs = gcs;
    }

    /**
     * Runs the phase's scripts as the guest environment does. Each startup script may run for {@code timeout}, so a
     * long {@code startup-script} still lets {@code startup-script-url} run after it. The shutdown phase as a whole
     * gets {@code timeout}: preparation and every script share one deadline, so two shutdown scripts cannot take twice
     * the shutdown limit. {@code current} is checked before each step, so a run that belongs to a boot which has
     * since ended (the instance stopped, reset or was deleted) does nothing more.
     */
    void run(Phase phase, String instance, String containerId, Map<String, String> metadata, Duration timeout,
             ComputeSerialPort port, BooleanSupplier current) {
        Instant deadline = phase == Phase.SHUTDOWN ? Instant.now().plus(timeout) : null;
        List<String> keys = List.of(phase.prefix + "-script", phase.prefix + "-script-url");
        if (keys.stream().noneMatch(metadata::containsKey)) {
            port.write(line(instance, "No " + phase.prefix + " scripts to run."));
            return;
        }
        if (!current.getAsBoolean()) { return; }
        port.write(line(instance, "Starting " + phase.prefix + " scripts."));
        try {
            lifecycleManager.execStreaming(containerId, ROOT, List.of(), List.of("mkdir", "-p", DIRECTORY),
                    remaining(deadline, timeout), OutputStream.nullOutputStream(), OutputStream.nullOutputStream());
        } catch (RuntimeException e) {
            LOG.warnf("Compute instance %s could not prepare %s: %s", instance, DIRECTORY, e.getMessage());
            port.write(line(instance, "Could not prepare " + DIRECTORY + ": " + e.getMessage()));
            return;
        }
        for (String key : keys) {
            String value = metadata.get(key);
            if (value == null) { continue; }
            if (!current.getAsBoolean()) { return; }
            if (remaining(deadline, timeout).isZero()) {
                port.write(line(instance, key + " not run: the " + phase.prefix + " script time limit of " + timeout
                        + " was used up."));
                continue;
            }
            try {
                byte[] script = key.endsWith("-url") ? fetch(value.strip()) : value.getBytes(StandardCharsets.UTF_8);
                port.write(line(instance, "Found " + key + " in metadata."));
                RetryingTarCopier.copyBytes(lifecycleManager.getDockerClient(), containerId, DIRECTORY, key, script, 0755);
                Duration left = remaining(deadline, timeout);
                if (left.isZero()) {
                    port.write(line(instance, key + " not run: the " + phase.prefix + " script time limit of "
                            + timeout + " was used up."));
                    continue;
                }
                LineWriter out = new LineWriter(port, instance, key + ": ");
                ContainerExec.Result result = lifecycleManager.execStreaming(containerId, ROOT, List.of(),
                        List.of("/bin/sh", "-c", "\"$0\"", DIRECTORY + "/" + key), left, out, out);
                out.close();
                port.write(line(instance, result.timedOut()
                        ? key + " still running after " + timeout + "; no longer waiting for it."
                        : key + " exit status " + result.exitCode()));
            } catch (RuntimeException e) {
                LOG.warnf("Compute instance %s could not run %s: %s", instance, key, e.getMessage());
                port.write(line(instance, key + ": " + e.getMessage()));
            }
        }
        port.write(line(instance, "Finished running " + phase.prefix + " scripts."));
    }

    /** Time left before {@code deadline}, never negative; the whole {@code timeout} when there is no deadline. */
    private static Duration remaining(Instant deadline, Duration timeout) {
        if (deadline == null) { return timeout; }
        Duration left = Duration.between(Instant.now(), deadline);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** Reads a Cloud Storage URL from floci-gcp's own GCS. */
    byte[] fetch(String url) {
        String path;
        if (url.startsWith("gs://")) {
            path = url.substring(5);
        } else if (url.startsWith("https://storage.googleapis.com/") || url.startsWith("https://storage.cloud.google.com/")) {
            // An HTTPS URL percent-encodes the object name; a gs:// reference spells it literally.
            String decoded;
            try {
                decoded = URI.create(url).getPath();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("not a valid URL: " + url, e);
            }
            path = decoded == null ? "" : decoded.substring(1);
        } else {
            throw new IllegalArgumentException("only Cloud Storage URLs are supported, not " + url);
        }
        int slash = path.indexOf('/');
        if (slash <= 0 || slash == path.length() - 1) {
            throw new IllegalArgumentException("not a Cloud Storage object URL: " + url);
        }
        return gcs.getObjectData(path.substring(0, slash), path.substring(slash + 1));
    }

    /** One serial console line in the shape of a journald console line: time, host, unit and message. */
    static byte[] line(String instance, String message) {
        return (prefix(instance) + message + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String prefix(String instance) {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS) + " " + instance + " " + RUNNER + "[1]: ";
    }

    /** Writes a script's output to the serial port one line at a time, each line behind the runner prefix. */
    static final class LineWriter extends OutputStream {
        private final ComputeSerialPort port;
        private final String instance;
        private final String label;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        LineWriter(ComputeSerialPort port, String instance, String label) {
            this.port = port;
            this.instance = instance;
            this.label = label;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                emit();
            } else {
                pending.write(b);
                if (pending.size() >= MAX_LINE_BYTES) { emitWhole(); }
            }
        }

        // Writes out an over-long line up to its last complete UTF-8 character and keeps the rest pending.
        private void emitWhole() {
            byte[] bytes = pending.toByteArray();
            int cut = bytes.length;
            int back = 0;
            while (back < 3 && cut > 0 && (bytes[cut - 1] & 0xC0) == 0x80) { cut--; back++; }
            if (cut > 0 && (bytes[cut - 1] & 0x80) != 0 && sequenceLength(bytes[cut - 1]) > back + 1) {
                cut--;
            } else {
                cut = bytes.length;
            }
            pending.reset();
            pending.write(bytes, 0, cut);
            emit();
            pending.write(bytes, cut, bytes.length - cut);
        }

        private static int sequenceLength(byte lead) {
            if ((lead & 0xE0) == 0xC0) { return 2; }
            if ((lead & 0xF0) == 0xE0) { return 3; }
            if ((lead & 0xF8) == 0xF0) { return 4; }
            return 1;
        }

        @Override
        public synchronized void close() {
            if (pending.size() > 0) { emit(); }
        }

        private void emit() {
            ByteArrayOutputStream lineBytes = new ByteArrayOutputStream();
            lineBytes.writeBytes((prefix(instance) + label).getBytes(StandardCharsets.UTF_8));
            lineBytes.writeBytes(pending.toByteArray());
            lineBytes.write('\n');
            port.write(lineBytes.toByteArray());
            pending.reset();
        }
    }
}
