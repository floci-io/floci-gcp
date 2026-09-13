package io.floci.gcp.services.gke.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal persistence model for a GKE cluster. The REST response shape
 * ({@code google.container.v1.Cluster}) is built from this by the controller;
 * fields here include emulator-internal state (container id, host port) that is
 * never returned to clients.
 *
 * <p>{@code extraConfig} holds the nested config blocks the emulator does not act
 * on semantically (network policy enforcement, binary authorization, autoscaling
 * profiles, etc.) keyed by their {@code google.container.v1.Cluster} JSON field
 * name (e.g. {@code "networkPolicy"}, {@code "ipAllocationPolicy"}). They are
 * stored verbatim from the create/update request body and echoed back unchanged
 * on every read, so Terraform/gcloud plan-refresh diffs stay consistent without
 * the emulator needing to hand-model every nested proto message.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class StoredCluster {

    private String name;
    private String description;
    private String project;
    private String location;
    private String zone;
    private String status;
    private String statusMessage;
    private String endpoint;
    private String caCertificate;
    private String currentMasterVersion;
    private String currentNodeVersion;
    private String initialClusterVersion;
    private int initialNodeCount;
    private String network;
    private String subnetwork;
    private String clusterIpv4Cidr;
    private List<String> locations;
    private String loggingService;
    private String monitoringService;
    // The node pool store is the real source of truth, not this field, which is only populated
    // on the detached copy that GkeService.getCluster/listClusters return. It is deliberately
    // NOT @JsonIgnore'd: clusters persisted by a version of floci-gcp before node pools had
    // their own store embedded their pools directly here, and the startup migration moves that
    // data into the node pool store, which only works if Jackson still deserializes it. The
    // migration clears this field once a cluster is migrated, so a persisted record only carries
    // embedded pools while it still has un-migrated ones.
    private List<StoredNodePool> nodePools;
    private Map<String, String> resourceLabels;
    private String labelFingerprint;
    private String createTime;
    private String expireTime;
    private String selfLink;
    private String etag;
    private List<Map<String, Object>> conditions;
    private Map<String, Object> extraConfig;

    // Emulator-internal (real mode only)
    private String containerId;
    private int hostPort;
    private String internalEndpoint;
    private String volumeName;

    public StoredCluster() {
    }

    /**
     * Detached copy used to attach a node-pool view without mutating the persisted record.
     *
     * <p>{@code getCluster}/{@code listClusters} need to return a cluster carrying its node
     * pools, but the store hands back the live object: calling {@code setNodePools} on it wrote
     * a stale pool snapshot back to disk on the next flush, which the startup migration could
     * then replay and resurrect deleted pools. Copying first keeps the read path side-effect
     * free. Collections are copied defensively; {@code extraConfig} and {@code conditions} hold
     * request-shaped values that are only ever replaced wholesale, so a shallow copy is enough.
     */
    public StoredCluster(StoredCluster other) {
        this.name = other.name;
        this.description = other.description;
        this.project = other.project;
        this.location = other.location;
        this.zone = other.zone;
        this.status = other.status;
        this.statusMessage = other.statusMessage;
        this.endpoint = other.endpoint;
        this.caCertificate = other.caCertificate;
        this.currentMasterVersion = other.currentMasterVersion;
        this.currentNodeVersion = other.currentNodeVersion;
        this.initialClusterVersion = other.initialClusterVersion;
        this.initialNodeCount = other.initialNodeCount;
        this.network = other.network;
        this.subnetwork = other.subnetwork;
        this.clusterIpv4Cidr = other.clusterIpv4Cidr;
        this.locations = other.locations == null ? null : new ArrayList<>(other.locations);
        this.loggingService = other.loggingService;
        this.monitoringService = other.monitoringService;
        this.nodePools = other.nodePools == null ? null : new ArrayList<>(other.nodePools);
        this.resourceLabels = other.resourceLabels == null ? null : new LinkedHashMap<>(other.resourceLabels);
        this.labelFingerprint = other.labelFingerprint;
        this.createTime = other.createTime;
        this.expireTime = other.expireTime;
        this.selfLink = other.selfLink;
        this.etag = other.etag;
        this.conditions = other.conditions == null ? null : new ArrayList<>(other.conditions);
        this.extraConfig = other.extraConfig == null ? null : new LinkedHashMap<>(other.extraConfig);
        this.containerId = other.containerId;
        this.hostPort = other.hostPort;
        this.internalEndpoint = other.internalEndpoint;
        this.volumeName = other.volumeName;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getCaCertificate() {
        return caCertificate;
    }

    public void setCaCertificate(String caCertificate) {
        this.caCertificate = caCertificate;
    }

    public String getCurrentMasterVersion() {
        return currentMasterVersion;
    }

    public void setCurrentMasterVersion(String currentMasterVersion) {
        this.currentMasterVersion = currentMasterVersion;
    }

    public String getCurrentNodeVersion() {
        return currentNodeVersion;
    }

    public void setCurrentNodeVersion(String currentNodeVersion) {
        this.currentNodeVersion = currentNodeVersion;
    }

    public String getInitialClusterVersion() {
        return initialClusterVersion;
    }

    public void setInitialClusterVersion(String initialClusterVersion) {
        this.initialClusterVersion = initialClusterVersion;
    }

    public int getInitialNodeCount() {
        return initialNodeCount;
    }

    public void setInitialNodeCount(int initialNodeCount) {
        this.initialNodeCount = initialNodeCount;
    }

    public String getNetwork() {
        return network;
    }

    public void setNetwork(String network) {
        this.network = network;
    }

    public String getSubnetwork() {
        return subnetwork;
    }

    public void setSubnetwork(String subnetwork) {
        this.subnetwork = subnetwork;
    }

    public String getClusterIpv4Cidr() {
        return clusterIpv4Cidr;
    }

    public void setClusterIpv4Cidr(String clusterIpv4Cidr) {
        this.clusterIpv4Cidr = clusterIpv4Cidr;
    }

    public List<String> getLocations() {
        return locations;
    }

    public void setLocations(List<String> locations) {
        this.locations = locations;
    }

    public String getLoggingService() {
        return loggingService;
    }

    public void setLoggingService(String loggingService) {
        this.loggingService = loggingService;
    }

    public String getMonitoringService() {
        return monitoringService;
    }

    public void setMonitoringService(String monitoringService) {
        this.monitoringService = monitoringService;
    }

    public List<StoredNodePool> getNodePools() {
        return nodePools;
    }

    public void setNodePools(List<StoredNodePool> nodePools) {
        this.nodePools = nodePools;
    }

    public Map<String, String> getResourceLabels() {
        return resourceLabels;
    }

    public void setResourceLabels(Map<String, String> resourceLabels) {
        this.resourceLabels = resourceLabels;
    }

    public String getLabelFingerprint() {
        return labelFingerprint;
    }

    public void setLabelFingerprint(String labelFingerprint) {
        this.labelFingerprint = labelFingerprint;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(String expireTime) {
        this.expireTime = expireTime;
    }

    public String getSelfLink() {
        return selfLink;
    }

    public void setSelfLink(String selfLink) {
        this.selfLink = selfLink;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public List<Map<String, Object>> getConditions() {
        return conditions;
    }

    public void setConditions(List<Map<String, Object>> conditions) {
        this.conditions = conditions;
    }

    public Map<String, Object> getExtraConfig() {
        return extraConfig;
    }

    public void setExtraConfig(Map<String, Object> extraConfig) {
        this.extraConfig = extraConfig;
    }

    public String getContainerId() {
        return containerId;
    }

    public void setContainerId(String containerId) {
        this.containerId = containerId;
    }

    public int getHostPort() {
        return hostPort;
    }

    public void setHostPort(int hostPort) {
        this.hostPort = hostPort;
    }

    public String getInternalEndpoint() {
        return internalEndpoint;
    }

    public void setInternalEndpoint(String internalEndpoint) {
        this.internalEndpoint = internalEndpoint;
    }

    public String getVolumeName() {
        return volumeName;
    }

    public void setVolumeName(String volumeName) {
        this.volumeName = volumeName;
    }
}
