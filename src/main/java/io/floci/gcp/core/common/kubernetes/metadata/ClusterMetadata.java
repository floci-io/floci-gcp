package io.floci.gcp.core.common.kubernetes.metadata;

import java.util.List;
import java.util.Map;

public record ClusterMetadata(
        String location,
        String endpoint,
        String caCertificate,
        String kubernetesVersion,
        List<Map<String, Object>> nodePools,
        String network,
        String subnetwork,
        Map<String, String> labels) {
}