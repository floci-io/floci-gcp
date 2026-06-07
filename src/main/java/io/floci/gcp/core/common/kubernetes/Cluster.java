package io.floci.gcp.core.common.kubernetes;

public class Cluster {

    private String id;
    private String name;
    private ClusterStatus status;
    private ClusterDriverType driver;

    public Cluster() {
    }

    public Cluster(
            String id,
            String name,
            ClusterStatus status,
            ClusterDriverType driver) {

        this.id = id;
        this.name = name;
        this.status = status;
        this.driver = driver;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ClusterStatus getStatus() {
        return status;
    }

    public void setStatus(ClusterStatus status) {
        this.status = status;
    }

    public ClusterDriverType getDriver() {
        return driver;
    }

    public void setDriver(ClusterDriverType driver) {
        this.driver = driver;
    }
}