package io.floci.gcp.services.cloudkms.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
public class StoredCryptoKey {

    private String name;
    private String purpose;
    private String algorithm;
    private String createTime;
    private Integer primaryVersion;
    private int nextVersionNumber;
    private boolean importOnly;
    private Map<String, String> labels;
    private String rotationPeriod;
    private String nextRotationTime;
    private String destroyScheduledDuration;

    public StoredCryptoKey() {}

    public StoredCryptoKey(String name, String purpose, String algorithm, String createTime) {
        this.name = name;
        this.purpose = purpose;
        this.algorithm = algorithm;
        this.createTime = createTime;
        this.nextVersionNumber = 1;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }

    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }

    public Integer getPrimaryVersion() { return primaryVersion; }
    public void setPrimaryVersion(Integer primaryVersion) { this.primaryVersion = primaryVersion; }

    public int getNextVersionNumber() { return nextVersionNumber; }
    public void setNextVersionNumber(int nextVersionNumber) { this.nextVersionNumber = nextVersionNumber; }

    public boolean isImportOnly() { return importOnly; }
    public void setImportOnly(boolean importOnly) { this.importOnly = importOnly; }

    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels; }

    public String getRotationPeriod() { return rotationPeriod; }
    public void setRotationPeriod(String rotationPeriod) { this.rotationPeriod = rotationPeriod; }

    public String getNextRotationTime() { return nextRotationTime; }
    public void setNextRotationTime(String nextRotationTime) { this.nextRotationTime = nextRotationTime; }

    public String getDestroyScheduledDuration() { return destroyScheduledDuration; }
    public void setDestroyScheduledDuration(String destroyScheduledDuration) {
        this.destroyScheduledDuration = destroyScheduledDuration;
    }
}
