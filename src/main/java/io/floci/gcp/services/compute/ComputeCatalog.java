package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class ComputeCatalog {
    static final Set<String> COLLECTIONS = Set.of("regions", "zones", "machineTypes", "diskTypes", "acceleratorTypes");
    private ComputeCatalog() {}

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
            case "machineTypes" -> {
                for (String name : List.of("e2-standard-2", "n2-standard-4", "n2-standard-8", "g2-standard-4", "c3-standard-4")) {
                    int cpus = Integer.parseInt(name.substring(name.lastIndexOf('-') + 1));
                    ObjectNode r = c.object().put("name", name).put("guestCpus", cpus).put("memoryMb", cpus * 4096)
                            .put("zone", c.scope().substring(6));
                    if (name.startsWith("g2-")) {
                        r.putArray("accelerators").addObject().put("guestAcceleratorType", "nvidia-l4").put("guestAcceleratorCount", 1);
                    }
                    result.add(r);
                }
            }
            case "diskTypes" -> List.of("pd-standard", "pd-balanced", "pd-ssd", "hyperdisk-balanced", "hyperdisk-throughput", "hyperdisk-extreme")
                    .forEach(name -> result.add(c.object().put("name", name).put("zone", c.scope().substring(6))));
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
