package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.core.common.LocationCatalog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class ComputeCatalog {
    static final Set<String> COLLECTIONS = Set.of("regions", "zones", "machineTypes", "diskTypes", "acceleratorTypes");
    /** The only shared-core types served; each has two vCPUs, so the shape cannot tell them from e2-highcpu-2. */
    private static final Set<String> SHARED_CORE = Set.of("e2-micro", "e2-small", "e2-medium");
    private static final Map<String, MachineShape> MACHINE_TYPES = machineTypes();
    private ComputeCatalog() {}

    record MachineShape(int cpus, int memoryMb) {}

    static Optional<MachineShape> machineShape(String name) {
        return Optional.ofNullable(MACHINE_TYPES.get(name));
    }

    private static Map<String, MachineShape> machineTypes() {
        Map<String, MachineShape> types = new LinkedHashMap<>();
        types.put("e2-micro", new MachineShape(2, 1024));
        types.put("e2-small", new MachineShape(2, 2048));
        types.put("e2-medium", new MachineShape(2, 4096));
        shapes(types, "e2-standard", 4096, 2, 4, 8, 16, 32);
        shapes(types, "e2-highmem", 8192, 2, 4, 8, 16);
        shapes(types, "e2-highcpu", 1024, 2, 4, 8, 16, 32);
        shapes(types, "n1-standard", 3840, 1, 2, 4, 8, 16, 32, 64, 96);
        shapes(types, "n2-standard", 4096, 2, 4, 8, 16, 32, 48, 64, 80, 96, 128);
        shapes(types, "n2-highmem", 8192, 2, 4, 8, 16, 32, 48, 64, 80, 96);
        types.put("n2-highmem-128", new MachineShape(128, 864 * 1024));
        shapes(types, "n2-highcpu", 1024, 2, 4, 8, 16, 32, 48, 64, 80, 96);
        shapes(types, "n2d-standard", 4096, 2, 4, 8, 16, 32, 48, 64, 80, 96, 128, 224);
        shapes(types, "t2d-standard", 4096, 1, 2, 4, 8, 16, 32, 48, 60);
        shapes(types, "c3-standard", 4096, 4, 8, 22, 44, 88, 176);
        shapes(types, "g2-standard", 4096, 4, 8, 12, 16, 24, 32, 48, 96);
        return types;
    }

    private static void shapes(Map<String, MachineShape> types, String family, int memoryMbPerCpu, int... cpus) {
        for (int count : cpus) {
            types.put(family + "-" + count, new MachineShape(count, count * memoryMbPerCpu));
        }
    }

    private static int l4Count(int cpus) {
        return switch (cpus) {
            case 24 -> 2;
            case 48 -> 4;
            case 96 -> 8;
            default -> 1;
        };
    }

    static List<ObjectNode> list(ComputeService.Context c, List<String> regions, LocationCatalog locations) {
        List<ObjectNode> result = new ArrayList<>();
        switch (c.collection()) {
            case "regions" -> regions.forEach(region -> {
                ObjectNode r = c.object().put("name", region).put("status", "UP");
                ArrayNode zones = r.putArray("zones");
                for (String zone : locations.zones(region)) {
                    zones.add(c.link("zones/" + zone));
                }
                result.add(r);
            });
            case "zones" -> regions.forEach(region -> {
                for (String zone : locations.zones(region)) {
                    result.add(c.object().put("name", zone).put("status", "UP")
                            .put("region", c.link("regions/" + region)));
                }
            });
            case "machineTypes" -> MACHINE_TYPES.forEach((name, shape) -> {
                ObjectNode r = c.object().put("name", name).put("guestCpus", shape.cpus()).put("memoryMb", shape.memoryMb())
                        .put("isSharedCpu", SHARED_CORE.contains(name)).put("zone", c.scope().substring(6));
                if (name.startsWith("g2-")) {
                    r.putArray("accelerators").addObject().put("guestAcceleratorType", "nvidia-l4")
                            .put("guestAcceleratorCount", l4Count(shape.cpus()));
                }
                result.add(r);
            });
            case "diskTypes" -> List.of("pd-standard", "pd-balanced", "pd-ssd", "hyperdisk-balanced", "hyperdisk-throughput", "hyperdisk-extreme")
                    .forEach(name -> result.add(c.object().put("name", name).put("zone", c.link(c.scope()))));
            case "acceleratorTypes" -> List.of("nvidia-tesla-t4", "nvidia-l4", "nvidia-l4-vws")
                    .forEach(name -> result.add(c.object().put("name", name).put("maximumCardsPerInstance", 4).put("zone", c.scope().substring(6))));
            default -> throw new IllegalArgumentException(c.collection());
        }
        for (ObjectNode r : result) {
            String path = c.scope().isEmpty() ? c.collection() : c.scope() + "/" + c.collection();
            r.put("kind", "compute#" + ComputeService.singular(c.collection()));
            r.put("selfLink", c.link(path + "/" + r.path("name").asText()));
            r.put("id", Integer.toUnsignedString(r.path("selfLink").asText().hashCode()));
        }
        return result;
    }
}
