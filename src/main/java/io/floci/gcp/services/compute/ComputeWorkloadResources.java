package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.core.common.GcpException;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.*;
import static io.floci.gcp.services.compute.ComputeService.*;

@ApplicationScoped
public class ComputeWorkloadResources implements ComputeResourceHandler {
    public boolean handles(String kind) { return Set.of("instances", "disks").contains(kind); }
    public void create(ComputeService.Context c, ObjectNode r) {
        c.scope("zones");
        if (c.collection().equals("disks")) { disk(c, r); return; }
        machine(c, r);
        JsonNode nics = r.path("networkInterfaces");
        if (!nics.isArray() || nics.size() != 1) { throw GcpException.unimplemented("Exactly one IPv4 NIC is required"); }
        ObjectNode nic = (ObjectNode) nics.get(0);
        ObjectNode subnet = c.reference(nic, "subnetwork", "subnetworks");
        String region = c.scope().substring(6, c.scope().lastIndexOf('-'));
        if (!subnet.path("region").asText().equals(c.link("regions/" + region))) { throw GcpException.invalidArgument("VM and subnet regions differ"); }
        if (nic.has("network")) {
            c.reference(nic, "network", "networks");
            if (!nic.path("network").equals(subnet.path("network"))) { throw GcpException.invalidArgument("NIC network does not own subnet"); }
        }
        nic.set("network", subnet.path("network")); nic.put("name", "nic0");
        nic.put("networkIP", ComputeNetworkResources.allocatePrivate(c, subnet, nic.path("networkIP").asText("")));
        for (JsonNode access : nic.path("accessConfigs")) { access(c, r, (ObjectNode) access); }
        if (nic.path("accessConfigs").size() > 1) { throw GcpException.invalidArgument("One IPv4 access config is supported"); }
        ObjectNode metadata = r.has("metadata") ? (ObjectNode) r.get("metadata") : r.putObject("metadata");
        metadata.put("fingerprint", fingerprint());
        ObjectNode tags = r.has("tags") ? (ObjectNode) r.get("tags") : r.putObject("tags");
        for (JsonNode tag : tags.path("items")) { name(tag.asText()); }
        tags.put("fingerprint", fingerprint());
        JsonNode disks = r.path("disks");
        if (!disks.isArray() || disks.isEmpty()) { throw GcpException.invalidArgument("A boot disk is required"); }
        int index = 0, boots = 0;
        for (JsonNode node : disks) {
            ObjectNode attachment = (ObjectNode) node;
            if (attachment.path("boot").asBoolean()) { boots++; }
            if (attachment.has("initializeParams")) {
                if (attachment.has("source")) { throw GcpException.invalidArgument("Specify source or initializeParams, not both"); }
                ObjectNode params = (ObjectNode) attachment.remove("initializeParams");
                String diskName = params.path("diskName").asText(c.name() + (index == 0 ? "" : "-disk-" + index));
                name(diskName);
                String key = c.scope() + "/disks/" + diskName;
                if (c.state.resources.containsKey(key)) { throw GcpException.alreadyExists("Disk already exists: " + diskName); }
                ObjectNode disk = params.deepCopy();
                disk.put("name", diskName).put("id", Long.toString(++c.state.sequence)).put("kind", "compute#disk")
                        .put("selfLink", c.link(key)).put("zone", c.link(c.scope())).put("creationTimestamp", Instant.now().toString())
                        .put("labelFingerprint", fingerprint());
                if (params.has("diskSizeGb")) { disk.set("sizeGb", params.get("diskSizeGb")); }
                if (params.has("diskType")) { disk.set("type", params.get("diskType")); }
                disk.remove(List.of("diskSizeGb", "diskType", "diskName"));
                disk(c.child(key), disk);
                c.put(key, disk);
                attachment.put("source", c.link(key));
                attach(c, r, attachment, index++, true);
            } else { attach(c, r, attachment, index++, false); }
        }
        if (boots != 1) { throw GcpException.invalidArgument("Exactly one boot disk is required"); }
        c.transition(r, "PROVISIONING", "RUNNING");
        r.put("lastStartTimestamp", Instant.now().toString());
    }
    private void machine(ComputeService.Context c, ObjectNode r) {
        c.reference(r, "machineType", "machineTypes");
        if (!r.path("machineType").asText().startsWith(c.link(c.scope() + "/machineTypes/"))) { throw GcpException.invalidArgument("Machine type must be in VM zone"); }
        for (JsonNode accelerator : r.path("guestAccelerators")) {
            c.reference((ObjectNode) accelerator, "acceleratorType", "acceleratorTypes");
            if (!accelerator.path("acceleratorType").asText().startsWith(c.link(c.scope() + "/acceleratorTypes/"))) {
                throw GcpException.invalidArgument("Accelerator type must be in VM zone");
            }
            integer(accelerator.path("acceleratorCount").asText(), 1, 4, "acceleratorCount");
        }
        if (!r.path("guestAccelerators").isEmpty() && !r.path("scheduling").path("onHostMaintenance").asText().equals("TERMINATE")) {
            throw GcpException.invalidArgument("GPU instances require onHostMaintenance=TERMINATE");
        }
    }
    private void disk(ComputeService.Context c, ObjectNode r) {
        c.scope("zones");
        if (!r.has("type")) { r.put("type", c.scope() + "/diskTypes/pd-standard"); }
        c.reference(r, "type", "diskTypes");
        if (!r.path("type").asText().startsWith(c.link(c.scope() + "/diskTypes/"))) { throw GcpException.invalidArgument("Disk type must be in disk zone"); }
        int sources = 0, sourceSize = 0;
        for (String source : List.of("sourceImage", "sourceSnapshot")) {
            if (r.has(source)) {
                sources++;
                ObjectNode original = c.reference(r, source, source.equals("sourceImage") ? "images" : "snapshots");
                sourceSize = original.path("diskSizeGb").asInt();
                r.put(source + "Id", original.path("id").asText());
            }
        }
        if (sources > 1) { throw GcpException.invalidArgument("Only one disk source is allowed"); }
        int size = integer(r.path("sizeGb").asText(Integer.toString(Math.max(sourceSize, 10))), 1, 65536, "sizeGb");
        if (size < sourceSize) { throw GcpException.invalidArgument("Disk is smaller than its source"); }
        r.put("sizeGb", Integer.toString(size));
        performance(r); labels(r.path("labels"));
        r.putArray("users"); c.transition(r, "CREATING", "READY");
    }
    private static void performance(ObjectNode r) {
        String type = r.path("type").asText(); type = type.substring(type.lastIndexOf('/') + 1);
        if (!type.startsWith("hyperdisk-") && (r.has("provisionedIops") || r.has("provisionedThroughput"))) {
            throw GcpException.invalidArgument("Provisioned performance requires a supported Hyperdisk type");
        }
        if (r.has("provisionedIops")) {
            if (type.equals("hyperdisk-throughput")) { throw GcpException.invalidArgument("This disk type does not support provisionedIops"); }
            int min = type.equals("hyperdisk-balanced") ? 3000 : 2;
            int max = type.equals("hyperdisk-balanced") ? 160000 : 350000;
            r.put("provisionedIops", Integer.toString(integer(r.path("provisionedIops").asText(), min, max, "provisionedIops")));
        }
        if (r.has("provisionedThroughput")) {
            if (type.equals("hyperdisk-extreme")) { throw GcpException.invalidArgument("This disk type does not support provisionedThroughput"); }
            r.put("provisionedThroughput", Integer.toString(integer(r.path("provisionedThroughput").asText(), type.equals("hyperdisk-balanced") ? 140 : 10, 2400, "provisionedThroughput")));
        }
    }
    public void update(ComputeService.Context c, ObjectNode r, ObjectNode body, String verb) {
        if (!c.collection().equals("disks")) { ComputeResourceHandler.super.update(c, r, body, verb); return; }
        if (body.has("type") && !c.path(body.path("type").asText()).equals(c.path(r.path("type").asText()))) {
            throw GcpException.unimplemented("Disk type changes are not implemented");
        }
        for (String field : List.of("sizeGb", "provisionedIops", "provisionedThroughput")) {
            if (body.has(field)) {
                if (field.equals("sizeGb") && body.path(field).asLong() < r.path(field).asLong()) { throw GcpException.invalidArgument("Disk shrinking is not supported"); }
                r.set(field, body.get(field));
            }
        }
        if (body.has("sizeGb")) { r.put("sizeGb", Integer.toString(integer(body.path("sizeGb").asText(), 1, 65536, "sizeGb"))); }
        performance(r);
    }
    public void action(ComputeService.Context c, ObjectNode r, String action, ObjectNode body, Map<String,String> query) {
        if (c.collection().equals("disks")) {
            if (action.equals("resize")) { update(c, r, body, "PATCH"); return; }
            ComputeResourceHandler.super.action(c, r, action, body, query); return;
        }
        switch (action) {
            case "start" -> {
                requireState(r, "TERMINATED");
                for (JsonNode nic : r.path("networkInterfaces")) {
                    for (JsonNode access : nic.path("accessConfigs")) { if (!access.has("natIP")) { access(c, r, (ObjectNode) access); } }
                }
                c.transition(r, "PROVISIONING", "RUNNING"); r.put("lastStartTimestamp", Instant.now().toString());
            }
            case "stop" -> {
                requireState(r, "RUNNING");
                for (JsonNode nic : r.path("networkInterfaces")) {
                    for (JsonNode access : nic.path("accessConfigs")) {
                        String ip = access.path("natIP").asText();
                        if (reserved(c, ip) == null) { ((ObjectNode) access).remove("natIP"); }
                    }
                }
                c.transition(r, "STOPPING", "TERMINATED"); r.put("lastStopTimestamp", Instant.now().toString());
            }
            case "reset" -> { requireState(r, "RUNNING"); r.put("lastStartTimestamp", Instant.now().toString()); }
            case "setMachineType" -> { requireState(r, "TERMINATED"); r.put("machineType", required(body, "machineType")); machine(c, r); }
            case "setMetadata", "setTags" -> {
                String field = action.equals("setTags") ? "tags" : "metadata";
                checkFingerprint((ObjectNode) r.get(field), body, "fingerprint");
                if (field.equals("tags")) { for (JsonNode tag : body.path("items")) { name(tag.asText()); } }
                ObjectNode replacement = body.deepCopy(); replacement.put("fingerprint", fingerprint()); r.set(field, replacement);
            }
            case "attachDisk" -> {
                if (body.path("boot").asBoolean()) { throw GcpException.invalidArgument("A boot disk is already attached"); }
                attach(c, r, body, r.path("disks").size(), false); r.withArray("disks").add(body);
            }
            case "detachDisk" -> {
                String device = query.get("deviceName");
                ObjectNode attachment = attachment(r, device);
                if (attachment.path("boot").asBoolean()) { throw GcpException.failedPrecondition("Cannot detach the boot disk"); }
                detach(c, r, attachment);
                ArrayNode disks = (ArrayNode) r.get("disks");
                for (int i = 0; i < disks.size(); i++) { if (disks.get(i).path("deviceName").asText().equals(device)) { disks.remove(i); break; } }
            }
            case "setDiskAutoDelete" -> {
                if (!Set.of("true", "false").contains(query.getOrDefault("autoDelete", ""))) { throw GcpException.invalidArgument("autoDelete is required"); }
                attachment(r, query.get("deviceName")).put("autoDelete", Boolean.parseBoolean(query.get("autoDelete")));
            }
            case "addAccessConfig" -> {
                ObjectNode nic = nic(r, query.get("networkInterface"));
                if (!nic.path("accessConfigs").isEmpty()) { throw GcpException.alreadyExists("An external access config already exists"); }
                access(c, r, body); nic.withArray("accessConfigs").add(body);
            }
            case "deleteAccessConfig" -> {
                ObjectNode nic = nic(r, query.get("networkInterface"));
                boolean matches = false;
                for (JsonNode access : nic.path("accessConfigs")) {
                    if (access.path("name").asText().equals(query.get("accessConfig"))) { release(c, access.path("natIP").asText(), r.path("selfLink").asText()); matches = true; }
                }
                if (!matches) { throw GcpException.notFound("Access config not found"); }
                nic.putArray("accessConfigs");
            }
            default -> ComputeResourceHandler.super.action(c, r, action, body, query);
        }
    }
    public void delete(ComputeService.Context c, ObjectNode r) {
        if (c.collection().equals("disks")) {
            if (!r.path("users").isEmpty()) { throw GcpException.failedPrecondition("Disk is attached"); }
            return;
        }
        if (r.path("deletionProtection").asBoolean()) { throw GcpException.failedPrecondition("Deletion protection is enabled"); }
        for (JsonNode disk : r.path("disks")) {
            detach(c, r, (ObjectNode) disk);
            if (disk.path("autoDelete").asBoolean()) { c.state.resources.remove(c.path(disk.path("source").asText())); }
        }
        for (JsonNode nic : r.path("networkInterfaces")) {
            for (JsonNode access : nic.path("accessConfigs")) { release(c, access.path("natIP").asText(), r.path("selfLink").asText()); }
        }
    }
    private void attach(ComputeService.Context c, ObjectNode instance, ObjectNode attachment, int index, boolean creating) {
        String source = required(attachment, "source");
        if (!c.path(source).startsWith(c.scope() + "/disks/")) { throw GcpException.invalidArgument("Disk and VM zones differ"); }
        ObjectNode disk = c.require(source);
        if (!creating) { c.ready(disk); }
        if (!disk.path("users").isEmpty()) { throw GcpException.failedPrecondition("Disk is already attached"); }
        String device = attachment.path("deviceName").asText(disk.path("name").asText()); name(device);
        for (JsonNode existing : instance.path("disks")) {
            if (existing != attachment && existing.path("deviceName").asText().equals(device)) { throw GcpException.invalidArgument("Duplicate deviceName"); }
        }
        attachment.put("source", disk.path("selfLink").asText()).put("deviceName", device).put("index", index)
                .put("type", "PERSISTENT").put("mode", attachment.path("mode").asText("READ_WRITE"));
        if (!Set.of("READ_WRITE", "READ_ONLY").contains(attachment.path("mode").asText())) { throw GcpException.invalidArgument("Invalid disk mode"); }
        attachment.put("diskSizeGb", disk.path("sizeGb").asText());
        disk.withArray("users").add(instance.path("selfLink").asText());
    }
    private void detach(ComputeService.Context c, ObjectNode instance, ObjectNode attachment) {
        ObjectNode disk = c.require(attachment.path("source").asText());
        disk.putArray("users");
    }
    private static void requireState(ObjectNode r, String status) {
        if (!r.path("status").asText().equals(status)) { throw GcpException.failedPrecondition("Instance must be " + status); }
    }
    private static ObjectNode attachment(ObjectNode r, String name) {
        for (JsonNode disk : r.path("disks")) { if (disk.path("deviceName").asText().equals(name)) { return (ObjectNode) disk; } }
        throw GcpException.notFound("Disk attachment not found");
    }
    private static ObjectNode nic(ObjectNode r, String name) {
        for (JsonNode nic : r.path("networkInterfaces")) { if (nic.path("name").asText().equals(name)) { return (ObjectNode) nic; } }
        throw GcpException.notFound("Network interface not found");
    }
    private static ObjectNode reserved(ComputeService.Context c, String ip) {
        return c.state.resources.values().stream().filter(a -> a.path("kind").asText().equals("compute#address")
                && a.path("address").asText().equals(ip)).findFirst().orElse(null);
    }
    private static void access(ComputeService.Context c, ObjectNode instance, ObjectNode access) {
        if (!access.path("type").asText("ONE_TO_ONE_NAT").equals("ONE_TO_ONE_NAT")) { throw GcpException.unimplemented("Only IPv4 ONE_TO_ONE_NAT is supported"); }
        access.put("type", "ONE_TO_ONE_NAT").put("name", access.path("name").asText("External NAT"));
        if (access.has("natIP")) {
            ObjectNode address = reserved(c, access.path("natIP").asText());
            if (address == null || !address.path("users").isEmpty() || !address.path("addressType").asText().equals("EXTERNAL")) { throw GcpException.invalidArgument("natIP must be an available reserved external address"); }
            String region = c.scope().substring(6, c.scope().lastIndexOf('-'));
            if (!address.path("region").asText().equals(c.link("regions/" + region))) { throw GcpException.invalidArgument("External address and instance regions differ"); }
            ComputeNetworkResources.claim(address, instance.path("selfLink").asText());
        } else { access.put("natIP", ComputeNetworkResources.allocateExternal(c)); }
    }
    private static void release(ComputeService.Context c, String ip, String instance) {
        ObjectNode address = reserved(c, ip);
        if (address != null) { ComputeNetworkResources.release(address, instance); }
    }
}
