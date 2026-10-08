package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ComputeCatalog {
    static final Set<String> COLLECTIONS = Set.of("regions", "zones", "machineTypes", "diskTypes", "acceleratorTypes");
    private ComputeCatalog() {}

    /** Machine types as {vCPUs, memoryMb}: shared-core E2, plus standard, highmem and highcpu shapes of the common families. */
    private static Map<String, int[]> machineTypes() {
        Map<String, int[]> types = new LinkedHashMap<>();
        types.put("e2-micro", new int[] {2, 1024});
        types.put("e2-small", new int[] {2, 2048});
        types.put("e2-medium", new int[] {2, 4096});
        shapes(types, "e2-standard", 4096, 2, 4, 8, 16, 32);
        shapes(types, "e2-highmem", 8192, 2, 4, 8, 16);
        shapes(types, "e2-highcpu", 1024, 2, 4, 8, 16, 32);
        shapes(types, "n1-standard", 3840, 1, 2, 4, 8, 16, 32, 64, 96);
        shapes(types, "n2-standard", 4096, 2, 4, 8, 16, 32, 48, 64, 80, 96, 128);
        shapes(types, "n2-highmem", 8192, 2, 4, 8, 16, 32, 48, 64, 80, 96, 128);
        shapes(types, "n2-highcpu", 1024, 2, 4, 8, 16, 32, 48, 64, 80, 96);
        shapes(types, "n2d-standard", 4096, 2, 4, 8, 16, 32, 48, 64, 80, 96);
        shapes(types, "t2d-standard", 4096, 1, 2, 4, 8, 16, 32, 48, 60);
        shapes(types, "c3-standard", 4096, 4, 8, 22, 44, 88);
        shapes(types, "g2-standard", 4096, 4, 8, 12, 16, 24, 32, 48, 96);
        return types;
    }

    private static void shapes(Map<String, int[]> types, String family, int memoryMbPerCpu, int... cpus) {
        for (int count : cpus) {
            types.put(family + "-" + count, new int[] {count, count * memoryMbPerCpu});
        }
    }

    static List<ObjectNode> list(ComputeService.Context c, List<String> regions) {
        List<ObjectNode> result = new ArrayList<>();
        switch (c.collection()) {
            case "regions" -> regions.forEach(region -> {
                ObjectNode r = c.object().put("name", region).put("status", "UP");
                var zones = r.putArray("zones");
                for (String suffix : List.of("a", "b", "c")) {
                    zones.add(c.link("zones/" + region + "-" + suffix));
                }
                result.add(r);
            });
            case "zones" -> regions.forEach(region -> {
                for (String suffix : List.of("a", "b", "c")) {
                    result.add(c.object().put("name", region + "-" + suffix).put("status", "UP")
                            .put("region", c.link("regions/" + region)));
                }
            });
            case "machineTypes" -> machineTypes().forEach((name, shape) -> {
                ObjectNode r = c.object().put("name", name).put("guestCpus", shape[0]).put("memoryMb", shape[1])
                        .put("zone", c.scope().substring(6));
                if (shape[0] == 2 && shape[1] < 4096) {
                    r.put("isSharedCpu", true);
                }
                if (name.startsWith("g2-")) {
                    r.putArray("accelerators").addObject().put("guestAcceleratorType", "nvidia-l4")
                            .put("guestAcceleratorCount", switch (shape[0]) { case 24 -> 2; case 48 -> 4; case 96 -> 8; default -> 1; });
                }
                result.add(r);
            });
            case "diskTypes" -> List.of("pd-standard", "pd-balanced", "pd-ssd", "hyperdisk-balanced", "hyperdisk-throughput", "hyperdisk-extreme")
                    .forEach(name -> result.add(c.object().put("name", name).put("zone", c.link(c.scope()))));
            case "acceleratorTypes" -> List.of("nvidia-tesla-t4", "nvidia-l4")
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
