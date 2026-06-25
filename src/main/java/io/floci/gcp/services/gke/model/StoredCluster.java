package io.floci.gcp.services.gke.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * Internal persistence model for a GKE cluster. The REST response shape
 * ({@code google.container.v1.Cluster}) is built from this by the controller;
 * fields here include emulator-internal state (container id, host port) that is
 * never returned to clients.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class StoredCluster {

    private String name;
    private String project;
    private String location;
    private String status;
    private String endpoint;
    private String caCertificate;
    private String currentMasterVersion;
    private String network;
    private String subnetwork;
    private List<Map<String, Object>> nodePools;
    private Map<String, String> resourceLabels;
    private String createTime;
    private String selfLink;

    // Emulator-internal (real mode only)
    private String containerId;
    private int hostPort;
    private String internalEndpoint;

    public StoredCluster() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public List<Map<String, Object>> getNodePools() {
        return nodePools;
    }

    public void setNodePools(List<Map<String, Object>> nodePools) {
        this.nodePools = nodePools;
    }

    public Map<String, String> getResourceLabels() {
        return resourceLabels;
    }

    public void setResourceLabels(Map<String, String> resourceLabels) {
        this.resourceLabels = resourceLabels;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getSelfLink() {
        return selfLink;
    }

    public void setSelfLink(String selfLink) {
        this.selfLink = selfLink;
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
}
