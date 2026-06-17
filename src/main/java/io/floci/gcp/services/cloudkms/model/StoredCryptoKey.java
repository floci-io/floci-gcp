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
    private Map<String, String> labels;

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

    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels; }
}
