package io.floci.gcp.services.gke;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Fields that {@code google.container.v1beta1} adds to {@code Cluster} and {@code NodeConfig}
 * over {@code v1}. They are accepted on {@code v1beta1} requests and not modelled; this names
 * the ones a request set to a non-default value so the drop can be logged.
 */
final class GkeBetaFields {

    private static final List<String> CLUSTER = List.of(
            "clusterTelemetry", "master", "masterIpv4CidrBlock", "nodePoolUpgradeConcurrencyConfig",
            "podSecurityPolicyConfig", "privateCluster", "protectConfig", "tpuConfig",
            "workloadAltsConfig", "workloadCertificates");
    private static final List<String> NODE_CONFIG = List.of("ephemeralStorageConfig", "hostMaintenancePolicy");

    private GkeBetaFields() {
    }

    static List<String> setOnCluster(Map<String, Object> cluster) {
        List<String> found = new ArrayList<>();
        if (cluster == null) {
            return found;
        }
        collect(cluster, CLUSTER, "", found);
        collect(asMap(cluster.get("nodeConfig")), NODE_CONFIG, "nodeConfig.", found);
        if (cluster.get("nodePools") instanceof List<?> pools) {
            for (int i = 0; i < pools.size(); i++) {
                collect(asMap(asMap(pools.get(i)).get("config")), NODE_CONFIG, "nodePools[" + i + "].config.", found);
            }
        }
        return found;
    }

    static List<String> setOnNodePool(Map<String, Object> nodePool) {
        List<String> found = new ArrayList<>();
        if (nodePool != null) {
            collect(asMap(nodePool.get("config")), NODE_CONFIG, "config.", found);
        }
        return found;
    }

    private static void collect(Map<?, ?> message, List<String> fields, String prefix, List<String> found) {
        for (String field : fields) {
            if (isSet(message.get(field))) {
                found.add(prefix + field);
            }
        }
    }

    private static boolean isSet(Object value) {
        if (value == null || Boolean.FALSE.equals(value) || "".equals(value)) {
            return false;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (value instanceof Map<?, ?> m) {
            return m.values().stream().anyMatch(GkeBetaFields::isSet);
        }
        if (value instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        return true;
    }

    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> m ? m : Map.of();
    }
}
