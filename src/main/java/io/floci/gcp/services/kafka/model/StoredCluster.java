package io.floci.gcp.services.kafka.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class StoredCluster {

    private String name;
    private ClusterState state;
    private Instant createTime;
    private Instant updateTime;
    private long vcpuCount;
    private long memoryBytes;
    private String bootstrapAddress;

    @JsonIgnore
    private String containerId;
    private String volumeId;
    private String volumeName;

    public StoredCluster() {}

    public StoredCluster(String name) {
        this.name = name;
        this.state = ClusterState.CREATING;
        this.createTime = Instant.now();
        this.updateTime = this.createTime;
        this.vcpuCount = 3;
        this.memoryBytes = 3221225472L;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public ClusterState getState() { return state; }
    public void setState(ClusterState state) { this.state = state; }

    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }

    public Instant getUpdateTime() { return updateTime; }
    public void setUpdateTime(Instant updateTime) { this.updateTime = updateTime; }

    public long getVcpuCount() { return vcpuCount; }
    public void setVcpuCount(long vcpuCount) { this.vcpuCount = vcpuCount; }

    public long getMemoryBytes() { return memoryBytes; }
    public void setMemoryBytes(long memoryBytes) { this.memoryBytes = memoryBytes; }

    public String getBootstrapAddress() { return bootstrapAddress; }
    public void setBootstrapAddress(String bootstrapAddress) { this.bootstrapAddress = bootstrapAddress; }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getVolumeId() { return volumeId; }
    public void setVolumeId(String volumeId) { this.volumeId = volumeId; }

    public String getVolumeName() { return volumeName; }
    public void setVolumeName(String volumeName) { this.volumeName = volumeName; }
}
