package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.services.compute.model.ComputeProject;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The container TCP ports an instance container publishes: 22, for SSH, and every TCP port an enabled ingress
 * firewall rule on the instance's network allows, when the rule has no {@code targetTags} or shares one with the
 * instance. Source ranges, deny rules and priorities are not enforced: a published port is reachable from the
 * Docker host.
 */
final class ComputeFirewallPorts {
    private static final Logger LOG = Logger.getLogger(ComputeFirewallPorts.class);
    static final int SSH = 22;
    /** Most ports one instance publishes, so a rule allowing a wide range does not publish thousands. */
    static final int MAX_PORTS = 64;

    private ComputeFirewallPorts() {}

    static List<Integer> forInstance(ComputeProject state, ObjectNode instance) {
        Set<String> networks = new HashSet<>();
        for (JsonNode nic : instance.path("networkInterfaces")) { networks.add(networkPath(nic.path("network").asText())); }
        Set<String> tags = new HashSet<>();
        instance.path("tags").path("items").forEach(tag -> tags.add(tag.asText()));
        TreeSet<Integer> ports = new TreeSet<>(Set.of(SSH));
        for (Map.Entry<String, ObjectNode> entry : state.resources.entrySet()) {
            if (!entry.getKey().startsWith("global/firewalls/")) { continue; }
            ObjectNode rule = entry.getValue();
            if (!"INGRESS".equals(rule.path("direction").asText("INGRESS")) || rule.path("disabled").asBoolean()
                    || !networks.contains(networkPath(rule.path("network").asText()))) {
                continue;
            }
            JsonNode targets = rule.path("targetTags");
            if (targets.isArray() && !targets.isEmpty()) {
                boolean matches = false;
                for (JsonNode target : targets) { matches |= tags.contains(target.asText()); }
                if (!matches) { continue; }
            }
            for (JsonNode allowed : rule.path("allowed")) {
                if (!"tcp".equalsIgnoreCase(allowed.path("IPProtocol").asText())) { continue; }
                for (JsonNode spec : allowed.path("ports")) { add(ports, spec.asText(), entry.getKey(), instance); }
            }
        }
        return List.copyOf(ports);
    }

    private static void add(TreeSet<Integer> ports, String spec, String rule, ObjectNode instance) {
        int dash = spec.indexOf('-');
        try {
            int from = Integer.parseInt(dash < 0 ? spec : spec.substring(0, dash));
            int to = dash < 0 ? from : Integer.parseInt(spec.substring(dash + 1));
            for (int port = from; port <= to; port++) {
                if (ports.size() >= MAX_PORTS && !ports.contains(port)) {
                    LOG.warnf("Compute instance %s: %s allows more than %d ports; publishing the first %d",
                            instance.path("name").asText(), rule, MAX_PORTS, MAX_PORTS);
                    return;
                }
                ports.add(port);
            }
        } catch (NumberFormatException e) {
            LOG.debugv("Ignoring unreadable firewall port {0} in {1}", spec, rule);
        }
    }

    /** {@code global/networks/NAME} from a full or relative network reference. */
    private static String networkPath(String reference) {
        int at = reference.indexOf("global/networks/");
        return at < 0 ? reference : reference.substring(at);
    }
}
