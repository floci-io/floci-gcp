package io.floci.gcp.services.cloudmonitoring.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Map;

@RegisterForReflection
public class StoredTimeSeriesPoint {

    private String metricType;
    private Map<String, String> metricLabels;
    private String resourceType;
    private Map<String, String> resourceLabels;
    private String metricKind;
    private String valueType;
    private String startTime;
    private String endTime;
    private String valueJson;
    private long sequence;

    public StoredTimeSeriesPoint() {}

    public String getMetricType() {
        return metricType;
    }

    public void setMetricType(String metricType) {
        this.metricType = metricType;
    }

    public Map<String, String> getMetricLabels() {
        return metricLabels;
    }

    public void setMetricLabels(Map<String, String> metricLabels) {
        this.metricLabels = metricLabels;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public Map<String, String> getResourceLabels() {
        return resourceLabels;
    }

    public void setResourceLabels(Map<String, String> resourceLabels) {
        this.resourceLabels = resourceLabels;
    }

    public String getMetricKind() {
        return metricKind;
    }

    public void setMetricKind(String metricKind) {
        this.metricKind = metricKind;
    }

    public String getValueType() {
        return valueType;
    }

    public void setValueType(String valueType) {
        this.valueType = valueType;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public String getValueJson() {
        return valueJson;
    }

    public void setValueJson(String valueJson) {
        this.valueJson = valueJson;
    }

    public long getSequence() {
        return sequence;
    }

    public void setSequence(long sequence) {
        this.sequence = sequence;
    }
}
