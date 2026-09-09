package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.core.common.GcpException;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static io.floci.gcp.services.compute.ComputeService.*;

@ApplicationScoped
public class ComputeNetworkResources implements ComputeResourceHandler {
    public boolean handles(String kind) { return Set.of("networks", "subnetworks", "firewalls", "addresses").contains(kind); }
    public void create(ComputeService.Context c, ObjectNode r) {
        switch (c.collection()) {
            case "networks" -> {
                c.scope("global");
                if (r.path("autoCreateSubnetworks").asBoolean(true)) {
                    throw GcpException.unimplemented("Only custom-mode networks (autoCreateSubnetworks=false) are implemented");
                }
                r.put("autoCreateSubnetworks", false);
                r.putArray("subnetworks");
                r.put("mtu", r.path("mtu").asInt(1460));
            }
            case "subnetworks" -> {
                c.scope("regions");
                ObjectNode network = c.reference(r, "network", "networks");
                String range = required(r, "ipCidrRange");
                long[] bounds = cidr(range);
                for (ObjectNode existing : c.state.resources.values()) {
                    if (existing.path("kind").asText().equals("compute#subnetwork")
                            && existing.path("network").equals(r.path("network"))) {
                        long[] other = cidr(existing.path("ipCidrRange").asText());
                        if (bounds[0] <= other[1] && other[0] <= bounds[1]) { throw GcpException.invalidArgument("Overlapping subnet ranges"); }
                    }
                }
                r.put("gatewayAddress", ip(bounds[0] + 1)).put("fingerprint", fingerprint());
                r.put("stackType", "IPV4_ONLY");
                network.withArray("subnetworks").add(r.path("selfLink").asText());
            }
            case "firewalls" -> {
                c.scope("global"); c.reference(r, "network", "networks"); firewall(r);
            }
            case "addresses" -> {
                if (!c.scope().equals("global")) { c.scope("regions"); }
                String type = r.path("addressType").asText("EXTERNAL");
                if (!Set.of("INTERNAL", "EXTERNAL").contains(type)) { throw GcpException.invalidArgument("Invalid addressType"); }
                r.put("addressType", type).put("ipVersion", "IPV4");
                if (type.equals("INTERNAL")) {
                    c.scope("regions");
                    ObjectNode subnet = c.reference(r, "subnetwork", "subnetworks");
                    if (!subnet.path("region").asText().equals(c.link(c.scope()))) { throw GcpException.invalidArgument("Address and subnet regions differ"); }
                    r.put("address", allocatePrivate(c, subnet, r.path("address").asText("")));
                } else if (!r.has("address")) { r.put("address", allocateExternal(c)); }
                ipv4(required(r, "address"));
                if (used(c, r.path("address").asText())) { throw GcpException.invalidArgument("Address already reserved or in use"); }
                r.put("status", "RESERVED"); r.putArray("users");
            }
            default -> throw GcpException.unimplemented("Unknown network resource");
        }
    }
    public void update(ComputeService.Context c, ObjectNode r, ObjectNode body, String verb) {
        if (!c.collection().equals("firewalls")) { ComputeResourceHandler.super.update(c, r, body, verb); return; }
        for (var e : body.properties()) {
            if (Set.of("name", "id", "selfLink", "creationTimestamp").contains(e.getKey())) { continue; }
            r.set(e.getKey(), e.getValue());
        }
        c.reference(r, "network", "networks"); firewall(r);
    }
    public void delete(ComputeService.Context c, ObjectNode r) {
        if (c.collection().equals("subnetworks")) {
            ObjectNode network = c.require(r.path("network").asText());
            var members = network.putArray("subnetworks");
            c.state.resources.values().stream().filter(s -> s.path("kind").asText().equals("compute#subnetwork")
                    && s.path("network").equals(r.path("network")) && !s.path("selfLink").equals(r.path("selfLink")))
                    .forEach(s -> members.add(s.path("selfLink").asText()));
        }
    }
    private static void firewall(ObjectNode r) {
        r.put("priority", integer(r.path("priority").asText("1000"), 0, 65535, "priority"));
        String direction = r.path("direction").asText("INGRESS");
        if (!Set.of("INGRESS", "EGRESS").contains(direction)) { throw GcpException.invalidArgument("Invalid firewall direction"); }
        r.put("direction", direction);
        boolean allowed = !r.path("allowed").isEmpty(), denied = !r.path("denied").isEmpty();
        if (allowed == denied) { throw GcpException.invalidArgument("Specify exactly one of allowed or denied"); }
        JsonNode rules = allowed ? r.get("allowed") : r.get("denied");
        if (!rules.isArray() || rules.isEmpty()) { throw GcpException.invalidArgument("Empty firewall rules"); }
        for (JsonNode rule : rules) {
            String protocol = rule.path("IPProtocol").asText();
            if (!Set.of("tcp", "udp", "icmp", "all", "6", "17", "1").contains(protocol)) { throw GcpException.unimplemented("Firewall protocol: " + protocol); }
            for (JsonNode port : rule.path("ports")) {
                if (!Set.of("tcp", "udp", "6", "17").contains(protocol)) { throw GcpException.invalidArgument("Ports require TCP or UDP"); }
                String[] range = port.asText().split("-", -1);
                int start = integer(range[0], 0, 65535, "port");
                if (range.length > 2 || range.length == 2 && integer(range[1], 0, 65535, "port") < start) { throw GcpException.invalidArgument("Invalid port range"); }
            }
        }
        for (String field : List.of("sourceRanges", "destinationRanges")) { r.path(field).forEach(n -> cidr(n.asText())); }
        for (String field : List.of("targetTags", "sourceTags")) { r.path(field).forEach(n -> name(n.asText())); }
    }
    static String allocatePrivate(ComputeService.Context c, ObjectNode subnet, String requested) {
        long[] bounds = cidr(subnet.path("ipCidrRange").asText());
        if (!requested.isBlank()) {
            long address = ipv4(requested);
            if (address < bounds[0] + 2 || address > bounds[1] - 2 || used(c, requested)) { throw GcpException.invalidArgument("Private address unavailable"); }
            return requested;
        }
        for (long value = bounds[0] + 2; value <= bounds[1] - 2; value++) {
            String address = ip(value); if (!used(c, address)) { return address; }
        }
        throw GcpException.resourceExhausted("Subnet address space exhausted");
    }
    static String allocateExternal(ComputeService.Context c) {
        for (int i = 1; i <= 254; i++) {
            String address = "203.0.113." + i; if (!used(c, address)) { return address; }
        }
        throw GcpException.resourceExhausted("Emulator external IPv4 pool exhausted");
    }
    static boolean used(ComputeService.Context c, String address) {
        for (ObjectNode r : c.state.resources.values()) {
            if (r.path("address").asText().equals(address) || r.path("IPAddress").asText().equals(address)) { return true; }
            for (JsonNode nic : r.path("networkInterfaces")) {
                if (nic.path("networkIP").asText().equals(address)) { return true; }
                for (JsonNode access : nic.path("accessConfigs")) { if (access.path("natIP").asText().equals(address)) { return true; } }
            }
        }
        return false;
    }
    static long[] cidr(String value) {
        String[] parts = value.split("/", -1);
        if (parts.length != 2) { throw GcpException.invalidArgument("Invalid IPv4 CIDR"); }
        long ip = ipv4(parts[0]);
        int bits = integer(parts[1], 0, 32, "CIDR prefix");
        long mask = bits == 0 ? 0 : (0xffffffffL << (32 - bits)) & 0xffffffffL;
        if ((ip & mask) != ip) { throw GcpException.invalidArgument("CIDR must start at a network boundary"); }
        return new long[]{ip, ip | (0xffffffffL ^ mask)};
    }
    static long ipv4(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) { throw GcpException.invalidArgument("Invalid IPv4 address"); }
        long result = 0;
        for (String octet : octets) { result = (result << 8) | integer(octet, 0, 255, "IPv4 octet"); }
        return result;
    }
    static String ip(long value) { return ((value >>> 24) & 255) + "." + ((value >>> 16) & 255) + "." + ((value >>> 8) & 255) + "." + (value & 255); }
}
