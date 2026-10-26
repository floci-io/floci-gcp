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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    /** The unit the guest agent's account lines carry on the serial console. */
    static final String AGENT = "google_guest_agent";
    /** Users the agent created, so a user whose keys leave metadata loses them again (the agent's google_users). */
    static final String GOOGLE_USERS = DIRECTORY + "/google_users";
    static final String ACCOUNTS_SCRIPT = "floci-guest-accounts-";
    /** The newest account update that started in the guest; an older one still running stops before writing keys. */
    static final String ACCOUNTS_REQUEST = DIRECTORY + "/floci-accounts.request";
    /** Held while one update changes accounts. */
    static final String ACCOUNTS_LOCK = DIRECTORY + "/floci-accounts.lock";
    /** Longest the account step waits, an sshd package install included. */
    static final Duration ACCOUNTS_TIMEOUT = Duration.ofMinutes(5);
    /** The guest agent's default extra groups for the users it creates ({@code cfg.go}: groups). */
    static final List<String> USER_GROUPS = List.of("adm", "dip", "docker", "lxd", "plugdev", "video");
    /** The guest environment runs metadata scripts as root, whatever user the image declares. */
    static final String ROOT = "root";
    /** Longest line kept before it is written out unfinished, so output without newlines stays bounded. */
    static final int MAX_LINE_BYTES = 8 * 1024;
    /** Exit status of the boot guard when the container has restarted since the boot its script belongs to. */
    static final int REBOOTED = 125;
    /**
     * Runs the script ({@code $0}) only while PID 1 still has the start time ({@code $1}) it had at its boot. A stop
     * and start, or a reset, replace PID 1, so a script queued for an earlier boot never runs in a later one, however
     * late it gets to exec. Field 22 of /proc/1/stat is the process start time.
     */
    static final String BOOT_GUARD = "[ \"$(cut -d ' ' -f22 /proc/1/stat)\" = \"$1\" ] || exit " + REBOOTED
            + "; exec \"$0\"";

    /** The unit cloud-init's lines carry on the serial console. */
    static final String CLOUD_INIT = "cloud-init";
    /** The NoCloud seed directory cloud-init reads user-data and meta-data from. */
    static final String CLOUD_INIT_SEED = "/var/lib/cloud/seed/nocloud";
    /**
     * Each boot stages its seed and scripts in a directory of its own, which only its own script, past the boot guard,
     * copies into the seed directory: a superseded boot still uploading can never overwrite a later boot's seed.
     */
    static final String CLOUD_INIT_STAGE = DIRECTORY + "/floci-cloud-init-";
    static final String CLOUD_INIT_EARLY = "early.sh";
    static final String CLOUD_INIT_FINAL = "final.sh";

    /**
     * What cloud-init needs for one boot: the instance's id (cloud-init's instance-id), its name, its own
     * {@code user-data} and {@code user-data-encoding} attributes (cloud-init's GCE source reads no project
     * user-data), and whether write_files and runcmd run on every boot, as on Container-Optimized OS.
     */
    public record CloudInit(String instanceId, String hostname, String userData, String encoding, boolean everyBoot) {}

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
     * since ended (the instance stopped, reset or was deleted) does nothing more, and {@code bootMark} (the start
     * time of the boot's PID 1, or null when unknown) is checked inside the container as each script starts.
     */
    void run(Phase phase, String instance, String containerId, Map<String, String> metadata, Duration timeout,
             ComputeSerialPort port, BooleanSupplier current, String bootMark) {
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
                String path = DIRECTORY + "/" + key;
                List<String> command = bootMark == null
                        ? List.of("/bin/sh", "-c", "\"$0\"", path)
                        : List.of("/bin/sh", "-c", BOOT_GUARD, path, bootMark);
                ContainerExec.Result result = lifecycleManager.execStreaming(containerId, ROOT, List.of(), command,
                        left, out, out);
                out.close();
                if (bootMark != null && result.exitCode() == REBOOTED && !result.timedOut()
                        && !bootMark.equals(currentBootMark(containerId))) {
                    port.write(line(instance, key + " not run: the instance rebooted since this boot."));
                    return;
                }
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

    /**
     * Applies {@code ssh-keys} as the guest agent's accounts manager does: each user exists (created with an empty
     * password, a home directory and the agent's groups, and in {@code google-sudoers}, which may sudo without a
     * password), its {@code authorized_keys} holds its metadata keys after a {@code # Added by Google} line each and
     * keeps the lines the user added, and a user created earlier whose keys left metadata loses them and its sudo
     * group but keeps its account. Then sshd is started, a guest without one getting openssh-server from its
     * package manager first. {@code users} null leaves accounts alone (OS Login). A step that fails says so on serial
     * port 1, and so does a nonzero exit status.
     */
    void accounts(String instance, String containerId, Map<String, List<String>> users, long request,
                  ComputeSerialPort port) {
        String script = accountsScript(users, request);
        String name = ACCOUNTS_SCRIPT + request + ".sh";
        try {
            lifecycleManager.execStreaming(containerId, ROOT, List.of(), List.of("mkdir", "-p", DIRECTORY),
                    Duration.ofSeconds(30), OutputStream.nullOutputStream(), OutputStream.nullOutputStream());
            RetryingTarCopier.copyBytes(lifecycleManager.getDockerClient(), containerId, DIRECTORY, name,
                    script.getBytes(StandardCharsets.UTF_8), 0700);
            LineWriter out = new LineWriter(port, instance, AGENT, "");
            ContainerExec.Result result = lifecycleManager.execStreaming(containerId, ROOT, List.of(),
                    List.of("/bin/sh", DIRECTORY + "/" + name), ACCOUNTS_TIMEOUT, out, out);
            out.close();
            if (result.timedOut()) {
                port.write(line(instance, AGENT, "Account setup still running after " + ACCOUNTS_TIMEOUT + "."));
            } else if (result.exitCode() != 0) {
                port.write(line(instance, AGENT, "Account setup exit status " + result.exitCode()));
            }
        } catch (RuntimeException e) {
            LOG.warnf("Compute instance %s could not apply its SSH keys: %s", instance, e.getMessage());
            port.write(line(instance, AGENT, "Could not apply SSH keys: " + e.getMessage()));
        }
    }

    /**
     * The account update numbered {@code request}: later requests have higher numbers, across emulator restarts too.
     * It records itself as the newest update as it starts, and changes accounts only while holding the guest-side
     * lock and only while it still is the newest, so an update the emulator stopped waiting for never overlaps or
     * outlasts a newer one. The lock is created with its owner's pid and start time in one step, so a lock left by a
     * process that died, in this boot or an earlier one, is recognised and taken over.
     */
    static String accountsScript(Map<String, List<String>> users, long request) {
        StringBuilder sh = new StringBuilder("""
                status=0
                request=REQUEST
                mark=REQUEST_FILE
                lock=LOCK_DIR
                cleanup() { rm -f "$0" "$lock.$$"; [ -n "$me" ] && [ "$(cat "$lock" 2>/dev/null)" = "$me" ] && rm -f "$lock"; }
                trap cleanup EXIT
                newest="$(cat "$mark" 2>/dev/null || echo 0)"
                [ "$newest" -gt "$request" ] 2>/dev/null && exit 0
                echo "$request" > "$mark"
                failed() { echo "$1"; status=1; }
                has() { command -v "$1" >/dev/null 2>&1; }
                sshd_bin() { for b in /usr/sbin/sshd /usr/local/sbin/sshd /sbin/sshd "$(command -v sshd 2>/dev/null)"; do [ -n "$b" ] && [ -x "$b" ] && { echo "$b"; return; }; done; }
                if [ -z "$(sshd_bin)" ]; then
                  echo "Installing openssh-server."
                  if has apt-get; then DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=300 update -qq >/dev/null 2>&1; DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=300 install -y -qq openssh-server sudo >/dev/null 2>&1
                  elif has apk; then apk add --no-cache openssh-server shadow sudo >/dev/null 2>&1
                  elif has dnf; then dnf install -y -q openssh-server sudo shadow-utils >/dev/null 2>&1
                  elif has microdnf; then microdnf install -y openssh-server sudo shadow-utils >/dev/null 2>&1
                  elif has yum; then yum install -y -q openssh-server sudo shadow-utils >/dev/null 2>&1
                  fi
                  [ -n "$(sshd_bin)" ] || failed "Could not install openssh-server: no supported package manager or no network."
                fi
                owner() { s="$(sed 's/.*) //' "/proc/$1/stat" 2>/dev/null | cut -d' ' -f20)"; [ -n "$s" ] && echo "$1 $s"; }
                me="$(owner $$)"
                take_lock() {
                  echo "$me" > "$lock.$$"
                  [ -d "$lock" ] && rm -rf "$lock"
                  until ln "$lock.$$" "$lock" 2>/dev/null; do
                    [ "$(cat "$mark" 2>/dev/null)" = "$request" ] || exit 0
                    holder="$(cat "$lock" 2>/dev/null)"
                    if [ -z "$holder" ] || [ "$(owner "${holder%% *}")" != "$holder" ]; then rm -rf "$lock"; continue; fi
                    sleep 1
                  done
                  rm -f "$lock.$$"
                }
                take_lock
                [ "$(cat "$mark" 2>/dev/null)" = "$request" ] || exit 0
                """.replace("REQUEST_FILE", ACCOUNTS_REQUEST).replace("LOCK_DIR", ACCOUNTS_LOCK)
                .replace("REQUEST", Long.toString(request)));
        if (users != null) {
            sh.append("""
                    in_group() { id -nG "$1" 2>/dev/null | tr ' ' '\\n' | grep -qx "$2"; }
                    join_group() { in_group "$1" "$2" || gpasswd -a "$1" "$2" >/dev/null 2>&1 || usermod -aG "$2" "$1" >/dev/null 2>&1 || addgroup "$1" "$2" >/dev/null 2>&1; }
                    leave_group() { gpasswd -d "$1" "$2" >/dev/null 2>&1 || delgroup "$1" "$2" >/dev/null 2>&1; }
                    home_of() { awk -F: -v u="$1" '$1 == u { print $6 }' /etc/passwd; }
                    write_keys() {
                      user="$1"; shift; home="$(home_of "$user")"; [ -n "$home" ] || return 0
                      mkdir -p "$home/.ssh"; ak="$home/.ssh/authorized_keys"
                      { [ -f "$ak" ] && awk 'skip { skip = 0; next } $0 == "# Added by Google" { skip = 1; next } $0 != "" { print }' "$ak"
                        for key in "$@"; do printf '# Added by Google\\n%s\\n' "$key"; done; } > "$ak.google"
                      chmod 600 "$ak.google" && mv "$ak.google" "$ak" && chmod 700 "$home/.ssh" \\
                        && chown -R "$user" "$home/.ssh" || failed "Could not update the SSH keys of $user."
                    }
                    groupadd google-sudoers >/dev/null 2>&1 || addgroup google-sudoers >/dev/null 2>&1
                    mkdir -p /etc/sudoers.d
                    [ -e /etc/sudoers.d/google_sudoers ] || { echo '%google-sudoers ALL=(ALL:ALL) NOPASSWD:ALL' > /etc/sudoers.d/google_sudoers; chmod 0440 /etc/sudoers.d/google_sudoers; }
                    touch GOOGLE_USERS
                    ensure_user() {
                      if ! id "$1" >/dev/null 2>&1; then
                        echo "Creating user $1."
                        if has useradd; then
                          if [ -x /bin/bash ]; then useradd -m -s /bin/bash -p '*' "$1" >/dev/null; else useradd -m -p '*' "$1" >/dev/null; fi
                        else adduser -D "$1" >/dev/null && sed -i "s/^$1:!/$1:*/" /etc/shadow; fi
                        id "$1" >/dev/null 2>&1 || { failed "Could not create user $1."; return 1; }
                        for group in GROUPS; do grep -q "^$group:" /etc/group && join_group "$1" "$group"; done
                        join_group "$1" google-sudoers || failed "Could not add user $1 to google-sudoers."
                        grep -qx "$1" GOOGLE_USERS || echo "$1" >> GOOGLE_USERS
                      elif ! grep -qx "$1" GOOGLE_USERS; then
                        echo "Adding existing user $1 to google-sudoers group."
                        join_group "$1" google-sudoers || failed "Could not add user $1 to google-sudoers."
                        echo "$1" >> GOOGLE_USERS
                      fi
                      return 0
                    }
                    """.replace("GOOGLE_USERS", GOOGLE_USERS).replace("GROUPS", String.join(" ", USER_GROUPS)));
            for (Map.Entry<String, List<String>> user : users.entrySet()) {
                String name = quote(user.getKey());
                sh.append("ensure_user ").append(name).append(" && echo \"Updating keys for user \"").append(name)
                        .append("\".\" && write_keys ").append(name);
                for (String key : user.getValue()) { sh.append(' ').append(quote(key)); }
                sh.append('\n');
            }
            sh.append("current=").append(quote(String.join("\n", users.keySet()))).append('\n');
            sh.append("""
                    while IFS= read -r old; do
                      [ -n "$old" ] || continue
                      printf '%s\\n' "$current" | grep -qx "$old" && continue
                      echo "Removing user $old."
                      write_keys "$old"; leave_group "$old" google-sudoers
                    done < GOOGLE_USERS
                    printf '%s\\n' "$current" | grep -v '^$' > GOOGLE_USERS.new; mv GOOGLE_USERS.new GOOGLE_USERS
                    """.replace("GOOGLE_USERS", GOOGLE_USERS));
        }
        sh.append("""
                sshd="$(sshd_bin)"
                if [ -n "$sshd" ]; then
                  running=
                  for comm in /proc/[0-9]*/comm; do [ "$(cat "$comm" 2>/dev/null)" = sshd ] && running=1 && break; done
                  if [ -z "$running" ]; then
                    ssh-keygen -A >/dev/null 2>&1; mkdir -p /run/sshd
                    if "$sshd"; then echo "Started sshd."; else failed "Could not start sshd."; fi
                  fi
                fi
                exit $status
                """);
        return sh.toString();
    }

    /** One shell word: single quotes around the value, each embedded single quote closed, escaped and reopened. */
    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * Runs cloud-init's early stages with the instance's {@code user-data}, as the images that ship it do at boot: the
     * local, network and config stages run before google-startup-scripts, while the final stage (packages, runcmd,
     * user scripts) waits for multi-user.target, which the startup scripts are part of; {@link #cloudInitFinal} runs it.
     * The guest gets cloud-init from its package manager the first time; the user-data and meta-data are seeded for
     * the NoCloud data source (cloud-init's GCE source only runs on Google hardware), and the output goes to serial
     * port 1. Per-instance modules run once per instance, keyed by its id, unless {@link CloudInit#everyBoot()} reruns
     * write_files and runcmd. A step that fails is reported on serial port 1.
     *
     * @return this boot's staging directory, for {@link #cloudInitFinal}; null when cloud-init did not start
     */
    String cloudInit(String instance, String containerId, CloudInit seed, Duration timeout, ComputeSerialPort port,
                     BooleanSupplier current, String bootMark) {
        byte[] userData;
        try {
            userData = userData(seed);
        } catch (IllegalArgumentException e) {
            port.write(line(instance, CLOUD_INIT, "Could not decode base64 user-data: " + e.getMessage()));
            return null;
        }
        if (!current.getAsBoolean()) { return null; }
        String stage = CLOUD_INIT_STAGE + UUID.randomUUID().toString().replace("-", "");
        try {
            lifecycleManager.execStreaming(containerId, ROOT, List.of(), List.of("mkdir", "-p", stage),
                    Duration.ofSeconds(30), OutputStream.nullOutputStream(), OutputStream.nullOutputStream());
            RetryingTarCopier.copyBytes(lifecycleManager.getDockerClient(), containerId, stage, "user-data", userData, 0600);
            RetryingTarCopier.copyBytes(lifecycleManager.getDockerClient(), containerId, stage, "meta-data",
                    metaData(seed).getBytes(StandardCharsets.UTF_8), 0600);
            RetryingTarCopier.copyBytes(lifecycleManager.getDockerClient(), containerId, stage, CLOUD_INIT_EARLY,
                    cloudInitEarlyScript(seed.everyBoot(), stage).getBytes(StandardCharsets.UTF_8), 0700);
            RetryingTarCopier.copyBytes(lifecycleManager.getDockerClient(), containerId, stage, CLOUD_INIT_FINAL,
                    cloudInitFinalScript(stage).getBytes(StandardCharsets.UTF_8), 0700);
            if (!current.getAsBoolean()) { return null; }
            ContainerExec.Result result = runCloudInitStage(containerId, stage + "/" + CLOUD_INIT_EARLY, timeout, port,
                    instance, bootMark);
            if (rebooted(containerId, bootMark, result)) {
                port.write(line(instance, CLOUD_INIT, "cloud-init not run: the instance rebooted since this boot."));
                return null;
            }
            if (result.timedOut()) {
                port.write(line(instance, CLOUD_INIT, "cloud-init still running after " + timeout
                        + "; no longer waiting for it. Its final stage runs once it is done."));
            } else if (result.exitCode() != 0) {
                port.write(line(instance, CLOUD_INIT, "cloud-init exit status " + result.exitCode()));
            }
            return stage;
        } catch (RuntimeException e) {
            LOG.warnf("Compute instance %s could not run cloud-init: %s", instance, e.getMessage());
            port.write(line(instance, CLOUD_INIT, "Could not run cloud-init: " + e.getMessage()));
            return null;
        }
    }

    /**
     * Runs cloud-init's final stage for a boot whose early stages {@link #cloudInit} started, after the startup
     * scripts; it waits for the early stages first, should they still be running. A nonzero {@code cloud-init status}
     * is reported on serial port 1.
     */
    void cloudInitFinal(String instance, String containerId, String stage, Duration timeout, ComputeSerialPort port,
                        BooleanSupplier current, String bootMark) {
        if (stage == null || !current.getAsBoolean()) { return; }
        try {
            ContainerExec.Result result = runCloudInitStage(containerId, stage + "/" + CLOUD_INIT_FINAL, timeout, port,
                    instance, bootMark);
            if (rebooted(containerId, bootMark, result)) {
                return;
            }
            if (result.timedOut()) {
                port.write(line(instance, CLOUD_INIT, "cloud-init final stage still running after " + timeout
                        + "; no longer waiting for it."));
            } else if (result.exitCode() != 0) {
                port.write(line(instance, CLOUD_INIT, "cloud-init exit status " + result.exitCode()));
            }
        } catch (RuntimeException e) {
            LOG.warnf("Compute instance %s could not run cloud-init: %s", instance, e.getMessage());
            port.write(line(instance, CLOUD_INIT, "Could not run cloud-init: " + e.getMessage()));
        }
    }

    private ContainerExec.Result runCloudInitStage(String containerId, String path, Duration timeout,
                                                   ComputeSerialPort port, String instance, String bootMark) {
        LineWriter out = new LineWriter(port, instance, CLOUD_INIT, "");
        List<String> command = bootMark == null
                ? List.of("/bin/sh", "-c", "\"$0\"", path)
                : List.of("/bin/sh", "-c", BOOT_GUARD, path, bootMark);
        ContainerExec.Result result = lifecycleManager.execStreaming(containerId, ROOT, List.of(), command, timeout,
                out, out);
        out.close();
        return result;
    }

    private boolean rebooted(String containerId, String bootMark, ContainerExec.Result result) {
        return bootMark != null && result.exitCode() == REBOOTED && !result.timedOut()
                && !bootMark.equals(currentBootMark(containerId));
    }

    /**
     * The user-data bytes: base64-decoded when {@code user-data-encoding} is {@code base64}, as cloud-init's GCE source
     * does (other encodings are used as they are); throws IllegalArgumentException on invalid base64.
     */
    static byte[] userData(CloudInit seed) {
        return "base64".equals(seed.encoding()) ? Base64.getMimeDecoder().decode(seed.userData())
                : seed.userData().getBytes(StandardCharsets.UTF_8);
    }

    /** NoCloud meta-data: the instance id keys per-instance modules, and the name is the local hostname. */
    static String metaData(CloudInit seed) {
        return "instance-id: " + yamlString(seed.instanceId()) + "\nlocal-hostname: " + yamlString(seed.hostname()) + "\n";
    }

    private static String yamlString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * The early stages for the boot staged in {@code stage}: installs cloud-init when missing, points it at the NoCloud
     * seed only, leaves the container's network and hostname alone (Docker owns both), moves this boot's seed into
     * place, drops what earlier boots staged, and runs the local, network and config stages systemd would. Marks the
     * stage {@code early.done} when it ends, however it ends, for the final stage to wait on.
     */
    static String cloudInitEarlyScript(boolean everyBoot, String stage) {
        String staged = stage.substring(0, stage.lastIndexOf('-') + 1);
        return """
                #!/bin/sh
                stage=STAGE
                trap 'touch "$stage/early.done"' EXIT
                for old in STAGED*; do [ "$old" = "$stage" ] || rm -rf "$old"; done
                has() { command -v "$1" >/dev/null 2>&1; }
                if ! has cloud-init; then
                  echo "Installing cloud-init."
                  if has apt-get; then DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=300 update -qq >/dev/null 2>&1; DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=300 install -y -qq --no-install-recommends cloud-init >/dev/null 2>&1
                  elif has dnf; then dnf install -y -q cloud-init >/dev/null 2>&1
                  elif has microdnf; then microdnf install -y cloud-init >/dev/null 2>&1
                  elif has yum; then yum install -y -q cloud-init >/dev/null 2>&1
                  elif has apk; then apk add --no-cache cloud-init >/dev/null 2>&1
                  fi
                  has cloud-init || { echo "Could not install cloud-init: no supported package manager or no network."; exit 1; }
                fi
                mkdir -p /etc/cloud/cloud.cfg.d SEED
                cat > /etc/cloud/cloud.cfg.d/90_floci.cfg <<'EOF'
                datasource_list: [NoCloud, None]
                network:
                  config: disabled
                preserve_hostname: true
                ssh_deletekeys: false
                EOF
                cp "$stage/user-data" "$stage/meta-data" SEED/
                """.replace("STAGED", quote(staged)).replace("STAGE", quote(stage)).replace("SEED", CLOUD_INIT_SEED)
                + (everyBoot ? """
                for sem in config_write_files config_write_files_deferred config_runcmd config_scripts_user; do
                  rm -f /var/lib/cloud/instances/*/sem/"$sem"
                done
                """ : "") + """
                cloud-init init --local
                cloud-init init
                cloud-init modules --mode=config
                """;
    }

    /**
     * The final stage for the boot staged in {@code stage}: waits for the early stages to end, runs the final modules
     * when cloud-init is there, and exits with {@code cloud-init status}, which is 1 on errors and 2 when degraded.
     */
    static String cloudInitFinalScript(String stage) {
        return """
                #!/bin/sh
                stage=STAGE
                trap 'rm -rf "$stage"' EXIT
                while [ ! -e "$stage/early.done" ]; do sleep 1; done
                command -v cloud-init >/dev/null 2>&1 || exit 0
                cloud-init modules --mode=final
                cloud-init status
                """.replace("STAGE", quote(stage));
    }

    /**
     * The start time of the container's PID 1 now, or null when it cannot be read (the container stopped). A script
     * may exit with {@link #REBOOTED} itself, so that status only means a reboot when this no longer matches.
     */
    String currentBootMark(String containerId) {
        try {
            ContainerLifecycleManager.ExecResult result = lifecycleManager.exec(containerId, List.of(),
                    List.of("cut", "-d", " ", "-f22", "/proc/1/stat"));
            return result != null && result.exitCode() == 0 ? result.stdout().strip() : null;
        } catch (RuntimeException e) {
            return null;
        }
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
        return line(instance, RUNNER, message);
    }

    static byte[] line(String instance, String unit, String message) {
        return (prefix(instance, unit) + message + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String prefix(String instance, String unit) {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS) + " " + instance + " " + unit + "[1]: ";
    }

    /** Writes a program's output to the serial port one line at a time, each line behind its unit's prefix. */
    static final class LineWriter extends OutputStream {
        private final ComputeSerialPort port;
        private final String instance;
        private final String unit;
        private final String label;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        LineWriter(ComputeSerialPort port, String instance, String label) {
            this(port, instance, RUNNER, label);
        }

        LineWriter(ComputeSerialPort port, String instance, String unit, String label) {
            this.port = port;
            this.instance = instance;
            this.unit = unit;
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
            lineBytes.writeBytes((prefix(instance, unit) + label).getBytes(StandardCharsets.UTF_8));
            lineBytes.writeBytes(pending.toByteArray());
            lineBytes.write('\n');
            port.write(lineBytes.toByteArray());
            pending.reset();
        }
    }
}
