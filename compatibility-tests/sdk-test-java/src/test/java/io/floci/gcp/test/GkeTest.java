package io.floci.gcp.test;

import com.google.cloud.container.v1.ClusterManagerClient;
import com.google.container.v1.Cluster;
import com.google.container.v1.CreateClusterRequest;
import com.google.container.v1.DeleteClusterRequest;
import com.google.container.v1.GetClusterRequest;
import com.google.container.v1.ListClustersRequest;
import com.google.container.v1.Operation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GKE (container.googleapis.com) compatibility via the official google-cloud-container SDK,
 * using the HttpJson transport against the emulator. Assertions are mode-agnostic so the suite
 * passes whether the cluster is mock (instant RUNNING) or real k3s (PROVISIONING then RUNNING).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GkeTest {

    private static ClusterManagerClient client;
    private static final String CLUSTER = TestFixtures.uniqueName("gke");
    private static final String PARENT =
            "projects/" + TestFixtures.projectId() + "/locations/us-central1";
    private static final String CLUSTER_NAME = PARENT + "/clusters/" + CLUSTER;

    @BeforeAll
    static void setUp() throws Exception {
        client = TestFixtures.gkeClient();
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @Order(1)
    void createCluster() {
        Cluster cluster = Cluster.newBuilder()
                .setName(CLUSTER)
                .setInitialNodeCount(1)
                .build();
        Operation op = client.createCluster(CreateClusterRequest.newBuilder()
                .setParent(PARENT)
                .setCluster(cluster)
                .build());

        assertThat(op.getName()).startsWith("operation-");
        assertThat(op.getOperationType()).isEqualTo(Operation.Type.CREATE_CLUSTER);
        assertThat(op.getStatus()).isEqualTo(Operation.Status.DONE);
    }

    @Test
    @Order(2)
    void getCluster() {
        Cluster cluster = client.getCluster(GetClusterRequest.newBuilder()
                .setName(CLUSTER_NAME)
                .build());

        assertThat(cluster.getName()).isEqualTo(CLUSTER);
        assertThat(cluster.getStatus())
                .isIn(Cluster.Status.PROVISIONING, Cluster.Status.RUNNING);
        assertThat(cluster.getCurrentMasterVersion()).isNotBlank();
    }

    @Test
    @Order(3)
    void listClustersContainsCreated() {
        var response = client.listClusters(ListClustersRequest.newBuilder()
                .setParent(PARENT)
                .build());
        assertThat(response.getClustersList())
                .anyMatch(c -> c.getName().equals(CLUSTER));
    }

    @Test
    @Order(4)
    void deleteCluster() {
        Operation op = client.deleteCluster(DeleteClusterRequest.newBuilder()
                .setName(CLUSTER_NAME)
                .build());

        assertThat(op.getOperationType()).isEqualTo(Operation.Type.DELETE_CLUSTER);
        assertThat(op.getStatus()).isEqualTo(Operation.Status.DONE);
    }
}
