package io.floci.gcp.services.gke.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * Internal persistence model for a GKE node pool ({@code google.container.v1.NodePool}).
 *
 * <p>{@code config}, {@code autoscaling}, {@code management}, {@code upgradeSettings} and
 * {@code placementPolicy} are stored verbatim from the create/update request body and echoed
 * back unchanged — floci-gcp does not run real node VMs or enforce autoscaling/repair/upgrade
 * behavior, so there is nothing to act on semantically. Storing them as-is (rather than
 * hand-modeling every {@code NodeConfig} sub-field) keeps every field a real client sends
 * round-tripping consistently, which is what Terraform's plan/refresh diff and gcloud/SDK reads
 * actually depend on.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class StoredNodePool {

    private String name;
    private String project;
    private String location;
    private String clusterId;
    private int initialNodeCount;
    private List<String> locations;
    private String version;
    private String status;
    private String statusMessage;
    private String selfLink;
    private String etag;
    private List<String> instanceGroupUrls;
    private List<Map<String, Object>> conditions;
    private Map<String, Object> config;
    private Map<String, Object> autoscaling;
    private Map<String, Object> management;
    private Map<String, Object> upgradeSettings;
    private Map<String, Object> placementPolicy;
    private Map<String, Object> networkConfig;

    public StoredNodePool() {
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

    public String getClusterId() {
        return clusterId;
    }

    public void setClusterId(String clusterId) {
        this.clusterId = clusterId;
    }

    public int getInitialNodeCount() {
        return initialNodeCount;
    }

    public void setInitialNodeCount(int initialNodeCount) {
        this.initialNodeCount = initialNodeCount;
    }

    public List<String> getLocations() {
        return locations;
    }

    public void setLocations(List<String> locations) {
        this.locations = locations;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
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

    public List<String> getInstanceGroupUrls() {
        return instanceGroupUrls;
    }

    public void setInstanceGroupUrls(List<String> instanceGroupUrls) {
        this.instanceGroupUrls = instanceGroupUrls;
    }

    public List<Map<String, Object>> getConditions() {
        return conditions;
    }

    public void setConditions(List<Map<String, Object>> conditions) {
        this.conditions = conditions;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config;
    }

    public Map<String, Object> getAutoscaling() {
        return autoscaling;
    }

    public void setAutoscaling(Map<String, Object> autoscaling) {
        this.autoscaling = autoscaling;
    }

    public Map<String, Object> getManagement() {
        return management;
    }

    public void setManagement(Map<String, Object> management) {
        this.management = management;
    }

    public Map<String, Object> getUpgradeSettings() {
        return upgradeSettings;
    }

    public void setUpgradeSettings(Map<String, Object> upgradeSettings) {
        this.upgradeSettings = upgradeSettings;
    }

    public Map<String, Object> getPlacementPolicy() {
        return placementPolicy;
    }

    public void setPlacementPolicy(Map<String, Object> placementPolicy) {
        this.placementPolicy = placementPolicy;
    }

    public Map<String, Object> getNetworkConfig() {
        return networkConfig;
    }

    public void setNetworkConfig(Map<String, Object> networkConfig) {
        this.networkConfig = networkConfig;
    }
}
