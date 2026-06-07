package io.floci.gcp.core.common.kubernetes.drivers;

import java.util.List;

import io.floci.gcp.core.common.kubernetes.Cluster;

public interface ClusterDriver {

    Cluster create(String name);

    Cluster get(String name);

    boolean exists(String name);

    void delete(String name);

    List<Cluster> list();

    public String kubeConfig(String name);
}