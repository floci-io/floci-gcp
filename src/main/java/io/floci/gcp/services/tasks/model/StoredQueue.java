package io.floci.gcp.services.tasks.model;

public class StoredQueue {

    private String name;
    private String state;
    private String createTime;
    private String purgeTime;
    private double maxDispatchesPerSecond;
    private int maxConcurrentDispatches;
    private int maxBurstSize;
    private int maxAttempts;

    public StoredQueue() {
    }

    public StoredQueue(String name, String createTime) {
        this.name = name;
        this.state = "RUNNING";
        this.createTime = createTime;
        this.maxDispatchesPerSecond = 500.0;
        this.maxConcurrentDispatches = 1000;
        this.maxBurstSize = 500;
        this.maxAttempts = 100;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }

    public String getPurgeTime() { return purgeTime; }
    public void setPurgeTime(String purgeTime) { this.purgeTime = purgeTime; }

    public double getMaxDispatchesPerSecond() { return maxDispatchesPerSecond; }
    public void setMaxDispatchesPerSecond(double v) { this.maxDispatchesPerSecond = v; }

    public int getMaxConcurrentDispatches() { return maxConcurrentDispatches; }
    public void setMaxConcurrentDispatches(int v) { this.maxConcurrentDispatches = v; }

    public int getMaxBurstSize() { return maxBurstSize; }
    public void setMaxBurstSize(int v) { this.maxBurstSize = v; }

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int v) { this.maxAttempts = v; }
}
