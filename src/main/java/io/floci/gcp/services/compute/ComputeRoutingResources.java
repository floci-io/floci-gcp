package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.core.common.GcpException;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;
import static io.floci.gcp.services.compute.ComputeService.*;

@ApplicationScoped
public class ComputeRoutingResources implements ComputeResourceHandler {
    public boolean handles(String kind) {
        return Set.of("networkEndpointGroups", "healthChecks", "backendServices", "urlMaps", "targetHttpProxies", "forwardingRules").contains(kind);
    }
    public void create(ComputeService.Context c, ObjectNode r) {
        validate(c, r, true);
        if (Set.of("backendServices", "urlMaps", "forwardingRules").contains(c.collection())) { r.put("fingerprint", fingerprint()); }
        if (c.collection().equals("networkEndpointGroups")) { r.put("size", 0); c.state.endpoints.put(c.key(), new ArrayList<>()); }
    }
    private void validate(ComputeService.Context c, ObjectNode r, boolean creating) {
        if (c.collection().equals("networkEndpointGroups")) {
            c.scope("zones");
            if (!r.path("networkEndpointType").asText().equals("GCE_VM_IP_PORT")) { throw GcpException.unimplemented("Only GCE_VM_IP_PORT network endpoints are implemented"); }
            ObjectNode subnet = c.reference(r, "subnetwork", "subnetworks");
            c.reference(r, "network", "networks");
            if (!r.path("network").equals(subnet.path("network"))) { throw GcpException.invalidArgument("NEG network does not own subnet"); }
            String region = c.scope().substring(6, c.scope().lastIndexOf('-'));
            if (!subnet.path("region").asText().equals(c.link("regions/" + region))) { throw GcpException.invalidArgument("NEG and subnet regions differ"); }
            if (r.has("defaultPort")) { port(r.path("defaultPort")); }
            return;
        }
        c.scope("global");
        switch (c.collection()) {
            case "healthChecks" -> {
                if (!r.path("type").asText().equals("HTTP")) { throw GcpException.unimplemented("Only HTTP health check configuration is supported"); }
                ObjectNode check = r.has("httpHealthCheck") ? (ObjectNode) r.get("httpHealthCheck") : r.putObject("httpHealthCheck");
                check.put("port", check.path("port").asInt(80)); port(check.path("port"));
                check.put("requestPath", check.path("requestPath").asText("/"));
                r.put("checkIntervalSec", integer(r.path("checkIntervalSec").asText("5"), 1, 300, "checkIntervalSec"));
                r.put("timeoutSec", integer(r.path("timeoutSec").asText("5"), 1, r.path("checkIntervalSec").asInt(), "timeoutSec"));
            }
            case "backendServices" -> {
                if (!r.path("loadBalancingScheme").asText("EXTERNAL_MANAGED").equals("EXTERNAL_MANAGED")
                        || !r.path("protocol").asText("HTTP").equals("HTTP")) { throw GcpException.unimplemented("Only global external managed HTTP backends are supported"); }
                r.put("loadBalancingScheme", "EXTERNAL_MANAGED").put("protocol", "HTTP");
                if (!r.path("healthChecks").isArray() || r.path("healthChecks").size() != 1) { throw GcpException.invalidArgument("Exactly one health check is required"); }
                var checks = r.withArray("healthChecks");
                for (int i = 0; i < checks.size(); i++) {
                    ObjectNode wrapper = object().put("check", checks.get(i).asText());
                    c.reference(wrapper, "check", "healthChecks"); checks.set(i, wrapper.get("check"));
                }
                Set<String> groups = new HashSet<>();
                for (JsonNode backend : r.path("backends")) {
                    ObjectNode group = c.reference((ObjectNode) backend, "group", "networkEndpointGroups");
                    if (!groups.add(group.path("selfLink").asText())) { throw GcpException.invalidArgument("Duplicate backend group"); }
                    if (!backend.path("balancingMode").asText("RATE").equals("RATE")) { throw GcpException.unimplemented("Only RATE backend balancing is supported"); }
                    ((ObjectNode) backend).put("balancingMode", "RATE");
                    if (backend.path("maxRatePerEndpoint").asDouble(1) <= 0) { throw GcpException.invalidArgument("maxRatePerEndpoint must be positive"); }
                }
                if (!Set.of("NONE", "CLIENT_IP", "GENERATED_COOKIE", "HTTP_COOKIE").contains(r.path("sessionAffinity").asText("NONE"))) { throw GcpException.unimplemented("Unsupported sessionAffinity"); }
            }
            case "urlMaps" -> {
                c.reference(r, "defaultService", "backendServices");
                Set<String> matchers = new HashSet<>();
                for (JsonNode matcherNode : r.path("pathMatchers")) {
                    ObjectNode matcher = (ObjectNode) matcherNode;
                    name(required(matcher, "name"));
                    if (!matchers.add(matcher.path("name").asText())) { throw GcpException.invalidArgument("Duplicate path matcher"); }
                    c.reference(matcher, "defaultService", "backendServices");
                    for (JsonNode rule : matcher.path("pathRules")) {
                        c.reference((ObjectNode) rule, "service", "backendServices");
                        if (rule.path("paths").isEmpty()) { throw GcpException.invalidArgument("Path rules require paths"); }
                        for (JsonNode path : rule.path("paths")) {
                            if (!path.asText().startsWith("/") || path.asText().contains("?")) { throw GcpException.invalidArgument("Invalid path rule"); }
                        }
                    }
                    if (!matcher.path("routeRules").isEmpty()) { throw GcpException.unimplemented("Advanced routeRules are not implemented; use pathRules"); }
                }
                for (JsonNode host : r.path("hostRules")) {
                    if (!matchers.contains(host.path("pathMatcher").asText())) { throw GcpException.invalidArgument("Host rule references a missing path matcher"); }
                }
            }
            case "targetHttpProxies" -> c.reference(r, "urlMap", "urlMaps");
            case "forwardingRules" -> {
                if (!r.path("loadBalancingScheme").asText().equals("EXTERNAL_MANAGED") || !r.path("IPProtocol").asText().equals("TCP")) { throw GcpException.unimplemented("Only EXTERNAL_MANAGED TCP forwarding rules are supported"); }
                c.reference(r, "target", "targetHttpProxies");
                int[] ports = portRange(r);
                if (!r.path("networkTier").asText("PREMIUM").equals("PREMIUM")) {
                    throw GcpException.invalidArgument("Global external forwarding rules require PREMIUM network tier");
                }
                r.put("networkTier", "PREMIUM");
                ObjectNode reservation = null;
                if (creating) {
                    if (r.has("IPAddress")) {
                        reservation = reservation(c, required(r, "IPAddress"));
                        r.put("IPAddress", reservation.path("address").asText());
                    } else { r.put("IPAddress", ComputeNetworkResources.allocateExternal(c)); }
                }
                for (var entry : c.state.resources.entrySet()) {
                    ObjectNode other = entry.getValue();
                    if (!entry.getKey().startsWith("global/forwardingRules/")
                            || other.path("selfLink").equals(r.path("selfLink"))
                            || !other.path("IPAddress").equals(r.path("IPAddress"))) { continue; }
                    if (!other.path("loadBalancingScheme").equals(r.path("loadBalancingScheme"))
                            || !other.path("networkTier").asText("PREMIUM").equals(r.path("networkTier").asText())) {
                        throw GcpException.invalidArgument("Shared forwarding address requires matching scheme and tier");
                    }
                    int[] otherPorts = portRange(other);
                    if (other.path("IPProtocol").equals(r.path("IPProtocol")) && ports[0] <= otherPorts[1] && otherPorts[0] <= ports[1]) {
                        throw GcpException.invalidArgument("Forwarding address protocol and ports are already in use");
                    }
                }
                if (reservation != null) { ComputeNetworkResources.claim(reservation, r.path("selfLink").asText()); }
            }
            default -> throw GcpException.unimplemented("Unknown routing resource");
        }
    }
    public void update(ComputeService.Context c, ObjectNode r, ObjectNode body, String verb) {
        if (c.collection().equals("networkEndpointGroups")) { ComputeResourceHandler.super.update(c, r, body, verb); return; }
        if (r.has("fingerprint")) { checkFingerprint(r, body, "fingerprint"); }
        if (c.collection().equals("forwardingRules") && body.has("IPAddress")) {
            String requested = required(body, "IPAddress"), current = r.path("IPAddress").asText();
            String normalized = requested.equals(current) ? current : reservation(c, requested).path("address").asText();
            if (!normalized.equals(current)) { throw GcpException.invalidArgument("Forwarding rule IPAddress cannot be changed"); }
            body = body.deepCopy();
            body.put("IPAddress", current);
        }
        for (var e : body.properties()) {
            if (!Set.of("id", "kind", "selfLink", "creationTimestamp", "name").contains(e.getKey())) { r.set(e.getKey(), e.getValue()); }
        }
        validate(c, r, false);
        if (r.has("fingerprint")) { r.put("fingerprint", fingerprint()); }
    }
    public void action(ComputeService.Context c, ObjectNode r, String action, ObjectNode body, Map<String,String> query) {
        if (c.collection().equals("targetHttpProxies") && action.equals("setUrlMap")) {
            c.reference(body, "urlMap", "urlMaps"); r.set("urlMap", body.get("urlMap")); return;
        }
        if (c.collection().equals("forwardingRules") && action.equals("setTarget")) {
            c.reference(body, "target", "targetHttpProxies"); r.set("target", body.get("target")); return;
        }
        if (!c.collection().equals("networkEndpointGroups") || !Set.of("attachNetworkEndpoints", "detachNetworkEndpoints").contains(action)) {
            ComputeResourceHandler.super.action(c, r, action, body, query); return;
        }
        List<ObjectNode> endpoints = c.state.endpoints.get(c.key());
        if (!body.path("networkEndpoints").isArray() || body.path("networkEndpoints").isEmpty()) { throw GcpException.invalidArgument("networkEndpoints are required"); }
        for (JsonNode node : body.path("networkEndpoints")) {
            ObjectNode endpoint = (ObjectNode) node;
            if (endpoint.has("instance") && !endpoint.path("instance").asText().contains("/")) { endpoint.put("instance", c.scope() + "/instances/" + endpoint.path("instance").asText()); }
            ObjectNode vm = c.reference(endpoint, "instance", "instances");
            if (!vm.path("zone").asText().equals(c.link(c.scope()))) { throw GcpException.invalidArgument("Endpoint and VM zones differ"); }
            JsonNode nic = vm.path("networkInterfaces").get(0);
            if (!nic.path("network").equals(r.path("network")) || !nic.path("subnetwork").equals(r.path("subnetwork"))) { throw GcpException.invalidArgument("Endpoint is outside NEG network/subnet"); }
            if (!endpoint.has("ipAddress")) { endpoint.put("ipAddress", nic.path("networkIP").asText()); }
            if (!endpoint.path("ipAddress").equals(nic.path("networkIP"))) { throw GcpException.invalidArgument("Endpoint IP does not belong to instance"); }
            if (!endpoint.has("port") && r.has("defaultPort")) { endpoint.set("port", r.get("defaultPort")); }
            port(endpoint.path("port"));
            if (action.equals("attachNetworkEndpoints")) {
                if (!endpoints.contains(endpoint)) { endpoints.add(endpoint.deepCopy()); }
            } else if (!endpoints.remove(endpoint)) { throw GcpException.notFound("Network endpoint not found"); }
        }
        r.put("size", endpoints.size());
    }
    public void delete(ComputeService.Context c, ObjectNode r) {
        if (c.collection().equals("networkEndpointGroups")) { c.state.endpoints.remove(c.key()); }
        if (c.collection().equals("forwardingRules")) {
            for (ObjectNode address : c.state.resources.values()) {
                if (address.path("address").asText().equals(r.path("IPAddress").asText()) && address.path("kind").asText().equals("compute#address")) {
                    ComputeNetworkResources.release(address, r.path("selfLink").asText());
                }
            }
        }
    }
    private static ObjectNode reservation(ComputeService.Context c, String value) {
        ObjectNode address = c.state.resources.entrySet().stream()
                .filter(e -> e.getKey().startsWith("global/addresses/") && (e.getValue().path("address").asText().equals(value)
                        || e.getValue().path("selfLink").asText().equals(value) || e.getKey().equals(value)))
                .map(Map.Entry::getValue).findFirst().orElseThrow(() -> GcpException.invalidArgument("A global address reservation is required"));
        if (!address.path("addressType").asText().equals("EXTERNAL") || !address.path("ipVersion").asText().equals("IPV4")
                || !address.path("networkTier").asText("PREMIUM").equals("PREMIUM")) {
            throw GcpException.invalidArgument("A global external PREMIUM IPv4 address is required");
        }
        return address;
    }
    private static int[] portRange(ObjectNode r) {
        String[] values = required(r, "portRange").split("-", -1);
        int first = integer(values[0], 1, 65535, "portRange");
        if (values.length > 2) { throw GcpException.invalidArgument("Invalid portRange"); }
        int last = values.length == 2 ? integer(values[1], first, 65535, "portRange") : first;
        return new int[]{first, last};
    }
    private static void port(JsonNode value) { integer(value.asText(), 1, 65535, "port"); }
}
