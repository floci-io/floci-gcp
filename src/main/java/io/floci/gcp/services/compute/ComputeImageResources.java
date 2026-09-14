package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.core.common.GcpException;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Set;
import static io.floci.gcp.services.compute.ComputeService.*;

@ApplicationScoped
public class ComputeImageResources implements ComputeResourceHandler {
    public boolean handles(String kind) { return Set.of("images", "snapshots").contains(kind); }
    public void create(ComputeService.Context c, ObjectNode r) {
        c.scope("global");
        String source = null;
        for (String field : List.of("sourceDisk", "sourceSnapshot", "sourceImage")) {
            if (r.has(field)) {
                if (source != null) { throw GcpException.invalidArgument("Exactly one image source is required"); }
                source = field;
            }
        }
        if (source == null) { throw GcpException.invalidArgument("A source resource is required"); }
        if (c.collection().equals("snapshots") && !source.equals("sourceDisk")) { throw GcpException.invalidArgument("Snapshots require sourceDisk"); }
        String collection = switch (source) { case "sourceDisk" -> "disks"; case "sourceSnapshot" -> "snapshots"; default -> "images"; };
        ObjectNode original = c.reference(r, source, collection);
        if (c.collection().equals("images") && source.equals("sourceDisk") && !original.path("users").isEmpty()
                && !c.option("forceCreate", "false").equals("true")) { throw GcpException.failedPrecondition("Source disk is in use; forceCreate is required"); }
        r.put(source + "Id", original.path("id").asText());
        r.put("diskSizeGb", original.path(source.equals("sourceDisk") ? "sizeGb" : "diskSizeGb").asText());
        for (String field : List.of("licenses", "guestOsFeatures", "architecture")) {
            if (!r.has(field) && original.has(field)) { r.set(field, original.get(field).deepCopy()); }
        }
        for (JsonNode location : r.path("storageLocations")) {
            if (!Set.of("us", "eu", "asia").contains(location.asText())) { c.require("regions/" + location.asText()); }
        }
        if (r.has("family")) { name(required(r, "family")); }
        if (c.collection().equals("snapshots")) {
            if (!r.path("snapshotType").asText("STANDARD").equals("STANDARD")) {
                throw GcpException.unimplemented("Only standard snapshots are supported");
            }
            r.put("snapshotType", "STANDARD");
        }
        r.remove("forceCreate");
        c.transition(r, c.collection().equals("images") ? "PENDING" : "CREATING", "READY");
    }
}
