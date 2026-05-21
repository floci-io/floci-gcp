package io.floci.gcp.services.secretmanager.model;

public class StoredSecretVersion {

    private String name;
    private int versionNumber;
    private String state;
    private String createTime;
    private String destroyTime;
    private byte[] payload;
    private Long dataCrc32c;

    public StoredSecretVersion() {}

    public StoredSecretVersion(String name, int versionNumber, String createTime, byte[] payload, Long dataCrc32c) {
        this.name = name;
        this.versionNumber = versionNumber;
        this.state = "ENABLED";
        this.createTime = createTime;
        this.payload = payload;
        this.dataCrc32c = dataCrc32c;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getVersionNumber() { return versionNumber; }
    public void setVersionNumber(int versionNumber) { this.versionNumber = versionNumber; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }

    public String getDestroyTime() { return destroyTime; }
    public void setDestroyTime(String destroyTime) { this.destroyTime = destroyTime; }

    public byte[] getPayload() { return payload; }
    public void setPayload(byte[] payload) { this.payload = payload; }

    public Long getDataCrc32c() { return dataCrc32c; }
    public void setDataCrc32c(Long dataCrc32c) { this.dataCrc32c = dataCrc32c; }
}
