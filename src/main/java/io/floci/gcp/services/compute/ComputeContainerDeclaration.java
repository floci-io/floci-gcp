package io.floci.gcp.services.compute;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code gce-container-declaration} metadata value, read as the container startup agent (konlet) reads it at
 * boot: YAML with {@code spec.containers} (exactly one), {@code spec.volumes} and {@code spec.restartPolicy}. Field
 * names are konlet's YAML keys ({@code types/api.go}); validation and its messages follow
 * {@code gce-containers-startup.go}, {@code runtime/runtime.go} and {@code volumes/volumes.go}.
 *
 * @param name        the container's declared name
 * @param command     the entrypoint, or null for the image's
 * @param args        the cmd, or null for the image's
 * @param env         {@code NAME=value} entries
 * @param restart     the Docker restart policy: {@code always}, {@code on-failure} or {@code no}
 * @param tmpfs       container paths backed by a {@code emptyDir} volume with {@code medium: Memory}
 * @param skipped     volume mounts floci-gcp does not provide, each with the reason
 */
record ComputeContainerDeclaration(String name, String image, List<String> command, List<String> args,
                                   List<String> env, boolean stdin, boolean tty, boolean privileged, String restart,
                                   List<String> tmpfs, List<String> skipped) {

    static final String KEY = "gce-container-declaration";

    /** A declaration konlet would refuse; the message is the one konlet logs. */
    static final class InvalidDeclaration extends RuntimeException {
        InvalidDeclaration(String message) {
            super(message);
        }
    }

    static ComputeContainerDeclaration parse(String value) {
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(value);
        } catch (YAMLException e) {
            throw new InvalidDeclaration("Cannot parse container declaration '" + value + "': " + e.getMessage());
        }
        Map<?, ?> spec = map(map(root, "declaration").get("spec"), "spec");
        List<?> containers = list(spec.get("containers"), "spec.containers");
        if (containers.size() != 1) {
            throw new InvalidDeclaration("Container declaration should include exactly 1 container, "
                    + containers.size() + " found");
        }
        Map<?, ?> container = map(containers.get(0), "spec.containers[0]");
        String name = string(container.get("name"));
        String image = string(container.get("image"));
        if (image == null || image.isBlank()) {
            throw new InvalidDeclaration("Invalid container declaration: container " + name + " has no image.");
        }

        List<String> env = new ArrayList<>();
        for (Object entry : list(container.get("env"), "env")) {
            Map<?, ?> variable = map(entry, "env");
            env.add(string(variable.get("name")) + "=" + (variable.get("value") == null ? "" : string(variable.get("value"))));
        }

        Object policy = spec.get("restartPolicy");
        String restart = switch (policy == null ? "Always" : string(policy)) {
            case "Always" -> "always";
            case "OnFailure" -> "on-failure";
            case "Never" -> "no";
            default -> throw new InvalidDeclaration(
                    "Invalid container declaration: Unsupported container restart policy '" + policy + "'");
        };

        Map<String, Map<?, ?>> volumes = new LinkedHashMap<>();
        for (Object entry : list(spec.get("volumes"), "spec.volumes")) {
            Map<?, ?> volume = map(entry, "spec.volumes");
            volumes.put(string(volume.get("name")), volume);
        }
        List<String> tmpfs = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> referenced = new ArrayList<>();
        for (Object entry : list(container.get("volumeMounts"), "volumeMounts")) {
            Map<?, ?> mount = map(entry, "volumeMounts");
            String volumeName = string(mount.get("name"));
            String path = string(mount.get("mountPath"));
            Map<?, ?> volume = volumes.get(volumeName);
            if (volume == null) {
                throw new InvalidDeclaration("Invalid container declaration: Volume " + volumeName
                        + " referenced in container " + name + " (index: 0) not found in volume definitions.");
            }
            referenced.add(volumeName);
            long definitions = List.of("emptyDir", "hostPath", "gcePersistentDisk").stream()
                    .filter(kind -> volume.get(kind) != null).count();
            if (definitions != 1) {
                throw new InvalidDeclaration("Invalid container declaration: Exactly one volume specification required for volume "
                        + volumeName + ", " + definitions + " found.");
            }
            if (volume.get("emptyDir") != null) {
                Object medium = map(volume.get("emptyDir"), "emptyDir").get("medium");
                if (!"Memory".equals(medium)) {
                    throw new InvalidDeclaration("Volume " + volumeName + ": Unsupported emptyDir volume medium: "
                            + (medium == null ? "" : medium));
                }
                tmpfs.add(path);
            } else {
                skipped.add("volume " + volumeName + " at " + path + " (" + (volume.get("hostPath") != null
                        ? "hostPath" : "gcePersistentDisk") + " volumes are not supported)");
            }
        }
        for (String volumeName : volumes.keySet()) {
            if (!referenced.contains(volumeName)) {
                throw new InvalidDeclaration("Invalid container declaration: Volume " + volumeName
                        + " not referenced by any container.");
            }
        }

        Map<?, ?> security = container.get("securityContext") == null
                ? Map.of() : map(container.get("securityContext"), "securityContext");
        return new ComputeContainerDeclaration(name, image, strings(container.get("command"), "command"),
                strings(container.get("args"), "args"), List.copyOf(env), flag(container.get("stdin")),
                flag(container.get("tty")), flag(security.get("privileged")), restart, List.copyOf(tmpfs),
                List.copyOf(skipped));
    }

    private static Map<?, ?> map(Object value, String field) {
        if (value instanceof Map<?, ?> map) { return map; }
        throw new InvalidDeclaration("Cannot parse container declaration: " + field + " is not a mapping");
    }

    private static List<?> list(Object value, String field) {
        if (value == null) { return List.of(); }
        if (value instanceof List<?> list) { return list; }
        throw new InvalidDeclaration("Cannot parse container declaration: " + field + " is not a list");
    }

    private static List<String> strings(Object value, String field) {
        if (value == null) { return null; }
        return list(value, field).stream().map(ComputeContainerDeclaration::string).toList();
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean flag(Object value) {
        return Boolean.TRUE.equals(value) || "true".equals(value);
    }
}
