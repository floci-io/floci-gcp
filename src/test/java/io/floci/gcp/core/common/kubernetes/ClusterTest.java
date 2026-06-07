package io.floci.gcp.core.common.kubernetes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClusterTest {

    @Test
    void shouldCreateClusterUsingConstructor() {

        Cluster cluster = new Cluster(
                "cluster-1",
                "dev",
                ClusterStatus.RUNNING,
                ClusterDriverType.K3D);

        assertEquals("cluster-1", cluster.getId());
        assertEquals("dev", cluster.getName());
        assertEquals(
                ClusterStatus.RUNNING,
                cluster.getStatus());
        assertEquals(
                ClusterDriverType.K3D,
                cluster.getDriver());
    }

    @Test
    void shouldSetAndGetFields() {

        Cluster cluster = new Cluster();

        cluster.setId("cluster-2");
        cluster.setName("staging");
        cluster.setStatus(ClusterStatus.CREATING);
        cluster.setDriver(ClusterDriverType.GKE);

        assertEquals(
                "cluster-2",
                cluster.getId());

        assertEquals(
                "staging",
                cluster.getName());

        assertEquals(
                ClusterStatus.CREATING,
                cluster.getStatus());

        assertEquals(
                ClusterDriverType.GKE,
                cluster.getDriver());
    }

    @Test
    void shouldAllowNullValues() {

        Cluster cluster = new Cluster();

        assertNull(cluster.getId());
        assertNull(cluster.getName());
        assertNull(cluster.getStatus());
        assertNull(cluster.getDriver());
    }
}