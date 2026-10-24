package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.core.common.ProjectNumbers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The metadata server's view of one instance, laid out as GCE serves it (Compute docs, predefined metadata keys and
 * querying metadata): {@code instance/}, {@code project/} and {@code universe/}. Fixed keys are kebab-case and
 * become camelCase in recursive JSON; user keys ({@code attributes/}) and service account emails keep their spelling.
 * Values: {@code instance/zone} is {@code projects/NUMBER/zones/ZONE}, {@code instance/tags} a JSON array, numbers
 * JSON numbers in recursive output. {@code token} and {@code identity} are listed but generated per request.
 */
final class ComputeMetadataTree {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    sealed interface Node permits Dir, Leaf {}

    /**
     * A directory; {@code rawKeys} keeps user-chosen keys out of the camelCase conversion, and {@code indexed}
     * marks one keyed 0, 1, 2..., which recursive JSON renders as an array (Compute docs, querying metadata:
     * {@code disks/?recursive=true} is {@code [{"deviceName":"boot",...}]}).
     */
    record Dir(Map<String, Node> children, boolean rawKeys, boolean indexed) implements Node {
        Dir() { this(new LinkedHashMap<>(), false, false); }
        static Dir raw() { return new Dir(new LinkedHashMap<>(), true, false); }
        static Dir indexedList() { return new Dir(new LinkedHashMap<>(), false, true); }
        Dir put(String key, Node node) { children.put(key, node); return this; }
        Dir put(String key, String text) { return put(key, Leaf.text(text)); }
    }

    /** A value: its plain-text form and its JSON form. A generated leaf is served by the controller instead. */
    record Leaf(String text, JsonNode json, boolean generated) implements Node {
        static Leaf text(String value) { return new Leaf(value, JSON.textNode(value), false); }
        static Leaf number(String value) {
            JsonNode json;
            try { json = JSON.numberNode(Long.parseLong(value)); } catch (NumberFormatException e) { json = JSON.textNode(value); }
            return new Leaf(value, json, false);
        }
        static Leaf list(List<String> values) {
            ArrayNode json = JSON.arrayNode();
            values.forEach(json::add);
            return new Leaf(String.join("\n", values) + (values.isEmpty() ? "" : "\n"), json, false);
        }
        static Leaf dynamic() { return new Leaf("", JSON.nullNode(), true); }
    }

    private ComputeMetadataTree() {}

    static Dir build(String project, String zone, ComputeService.MetadataView view) {
        String number = ProjectNumbers.of(project);
        ObjectNode vm = view.instance();
        String name = vm.path("name").asText();

        Dir instance = new Dir();
        instance.put("attributes", attributes(vm.path("metadata")));
        if (vm.has("description")) { instance.put("description", vm.path("description").asText()); }
        Dir disks = Dir.indexedList();
        int index = 0;
        for (JsonNode attachment : vm.path("disks")) {
            disks.put(Integer.toString(index), new Dir()
                    .put("device-name", attachment.path("deviceName").asText(name))
                    .put("index", Leaf.number(Integer.toString(index)))
                    .put("interface", attachment.path("interface").asText("SCSI"))
                    .put("mode", attachment.path("mode").asText("READ_WRITE"))
                    .put("type", attachment.path("type").asText("PERSISTENT")));
            index++;
        }
        instance.put("disks", disks);
        instance.put("hostname", name + "." + zone + ".c." + project + ".internal");
        instance.put("id", Leaf.number(vm.path("id").asText()));
        if (view.bootImage() != null) {
            String image = view.bootImage();
            instance.put("image", image.substring(image.indexOf("projects/")));
        }
        String machine = vm.path("machineType").asText();
        instance.put("machine-type", "projects/" + number + "/machineTypes/" + machine.substring(machine.lastIndexOf('/') + 1));
        instance.put("name", name);
        instance.put("network-interfaces", networkInterfaces(vm, view.subnet(), number));
        instance.put("service-accounts", serviceAccounts(vm, number));
        List<String> tags = new ArrayList<>();
        vm.path("tags").path("items").forEach(tag -> tags.add(tag.asText()));
        instance.put("tags", Leaf.list(tags));
        instance.put("zone", "projects/" + number + "/zones/" + zone);

        Dir projectDir = new Dir()
                .put("attributes", attributes(view.projectMetadata()))
                .put("numeric-project-id", Leaf.number(number))
                .put("project-id", project);
        return new Dir()
                .put("instance", instance)
                .put("project", projectDir)
                .put("universe", new Dir().put("universe-domain", "googleapis.com"));
    }

    private static Dir attributes(JsonNode metadata) {
        Dir attributes = Dir.raw();
        for (JsonNode item : metadata.path("items")) {
            attributes.put(item.path("key").asText(), item.path("value").asText());
        }
        return attributes;
    }

    private static Dir networkInterfaces(ObjectNode vm, ObjectNode subnet, String number) {
        Dir interfaces = Dir.indexedList();
        int index = 0;
        for (JsonNode nic : vm.path("networkInterfaces")) {
            Dir dir = new Dir();
            Dir accessConfigs = Dir.indexedList();
            int accessIndex = 0;
            for (JsonNode access : nic.path("accessConfigs")) {
                Dir config = new Dir().put("type", access.path("type").asText("ONE_TO_ONE_NAT"));
                if (access.has("natIP")) { config.put("external-ip", access.path("natIP").asText()); }
                accessConfigs.put(Integer.toString(accessIndex++), config);
            }
            dir.put("access-configs", accessConfigs);
            dir.put("dns-servers", "169.254.169.254");
            if (subnet.has("gatewayAddress")) { dir.put("gateway", subnet.path("gatewayAddress").asText()); }
            String ip = nic.path("networkIP").asText();
            dir.put("ip", ip);
            dir.put("mac", mac(vm.path("id").asText() + "/" + index));
            dir.put("mtu", Leaf.number("1460"));
            String network = nic.path("network").asText();
            dir.put("network", "projects/" + number + "/networks/" + network.substring(network.lastIndexOf('/') + 1));
            if (subnet.has("ipCidrRange")) { dir.put("subnetmask", netmask(subnet.path("ipCidrRange").asText())); }
            interfaces.put(Integer.toString(index++), dir);
        }
        return interfaces;
    }

    private static Dir serviceAccounts(ObjectNode vm, String number) {
        Dir accounts = Dir.raw();
        boolean first = true;
        for (JsonNode account : vm.path("serviceAccounts")) {
            String email = account.path("email").asText();
            if (email.isEmpty() || email.equals("default")) { email = number + "-compute@developer.gserviceaccount.com"; }
            List<String> scopes = new ArrayList<>();
            account.path("scopes").forEach(scope -> scopes.add(scope.asText()));
            Dir dir = new Dir()
                    .put("aliases", Leaf.list(first ? List.of("default") : List.of()))
                    .put("email", email)
                    .put("identity", Leaf.dynamic())
                    .put("scopes", Leaf.list(scopes))
                    .put("token", Leaf.dynamic());
            if (first) { accounts.put("default", dir); }
            accounts.put(email, dir);
            first = false;
        }
        return accounts;
    }

    /** A stable locally administered MAC address derived from the instance id and interface index. */
    private static String mac(String seed) {
        int hash = seed.hashCode();
        return String.format("42:01:%02x:%02x:%02x:%02x", (hash >> 24) & 0xff, (hash >> 16) & 0xff, (hash >> 8) & 0xff, hash & 0xff);
    }

    private static String netmask(String cidr) {
        int bits = Integer.parseInt(cidr.substring(cidr.indexOf('/') + 1));
        long mask = bits == 0 ? 0 : (0xffffffffL << (32 - bits)) & 0xffffffffL;
        return ((mask >> 24) & 0xff) + "." + ((mask >> 16) & 0xff) + "." + ((mask >> 8) & 0xff) + "." + (mask & 0xff);
    }

    /** Recursive JSON for a node, camelCasing fixed keys and leaving out generated leaves. */
    static JsonNode json(Node node) {
        if (node instanceof Leaf leaf) { return leaf.json(); }
        Dir dir = (Dir) node;
        if (dir.indexed()) {
            ArrayNode items = JSON.arrayNode();
            dir.children().values().forEach(child -> items.add(json(child)));
            return items;
        }
        ObjectNode out = JSON.objectNode();
        dir.children().forEach((key, child) -> {
            if (child instanceof Leaf leaf && leaf.generated()) { return; }
            out.set(dir.rawKeys() ? key : camel(key), json(child));
        });
        return out;
    }

    /** Recursive plain text: one {@code path value} line per leaf, in the original key spelling. */
    static String text(Node node, String prefix) {
        StringBuilder out = new StringBuilder();
        if (node instanceof Leaf leaf) {
            if (!leaf.generated()) {
                for (String line : leaf.text().split("\n", -1)) {
                    if (!line.isEmpty() || leaf.text().isEmpty()) { out.append(prefix).append(' ').append(line).append('\n'); }
                }
            }
            return out.toString();
        }
        ((Dir) node).children().forEach((key, child) -> out.append(text(child, prefix.isEmpty() ? key : prefix + "/" + key)));
        return out.toString();
    }

    static String camel(String key) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : key.toCharArray()) {
            if (c == '-') { upper = true; continue; }
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return out.toString();
    }
}
