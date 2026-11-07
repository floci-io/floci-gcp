package io.floci.gcp.services.kafka.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A Managed Kafka schema registry. The API resource has only a name (its contexts are derived);
 * the rest names the storage of the Redpanda container that serves the registry's data.
 */
@RegisterForReflection
public class StoredSchemaRegistry {

    private String name;
    private String volumeId;
    private String volumeName;

    public StoredSchemaRegistry() {}

    public StoredSchemaRegistry(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVolumeId() { return volumeId; }
    public void setVolumeId(String volumeId) { this.volumeId = volumeId; }

    public String getVolumeName() { return volumeName; }
    public void setVolumeName(String volumeName) { this.volumeName = volumeName; }

    @JsonIgnore
    public String registryId() {
        return name.substring(name.lastIndexOf('/') + 1);
    }
}
