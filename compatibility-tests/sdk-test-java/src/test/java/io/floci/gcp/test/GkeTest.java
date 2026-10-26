package io.floci.gcp.test;

import com.google.cloud.container.v1.ClusterManagerClient;
import com.google.container.v1.Cluster;
import com.google.container.v1.CreateClusterRequest;
import com.google.container.v1.DeleteClusterRequest;
import com.google.container.v1.GetClusterRequest;
import com.google.container.v1.GetOperationRequest;
import com.google.container.v1.ListClustersRequest;
import com.google.container.v1.NodePool;
import com.google.container.v1.Operation;
import com.google.container.v1.SetLocationsRequest;
import com.google.container.v1.SetNodePoolSizeRequest;
import com.google.container.v1.UpdateMasterRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GKE (container.googleapis.com) compatibility via the official google-cloud-container SDK,
 * using the HttpJson transport against the emulator. Assertions are mode-agnostic: a mock cluster
 * is RUNNING with its create operation already DONE, while a real k3s cluster's create operation
 * stays RUNNING until the control plane is up, so the create test waits on the operation the way
 * Terraform and gcloud do and then expects a RUNNING cluster.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GkeTest {

    private static ClusterManagerClient client;
    private static final String CLUSTER = TestFixtures.uniqueName("gke");
    private static final String PARENT =
            "projects/" + TestFixtures.projectId() + "/locations/us-central1";
    private static final String CLUSTER_NAME = PARENT + "/clusters/" + CLUSTER;
    private static final Duration CREATE_TIMEOUT = Duration.ofMinutes(3);

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
    void createCluster() throws InterruptedException {
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
        assertThat(op.getStatus()).isIn(Operation.Status.RUNNING, Operation.Status.DONE);
        boolean provisioned = op.getStatus() != Operation.Status.DONE;

        Operation done = awaitDone(op);
        assertThat(done.getStatusMessage()).isEmpty();
        assertThat(done.getEndTime()).isNotBlank();

        Cluster created = client.getCluster(GetClusterRequest.newBuilder()
                .setName(CLUSTER_NAME)
                .build());
        assertThat(created.getStatus()).isEqualTo(Cluster.Status.RUNNING);
        if (provisioned) {
            assertThat(created.getEndpoint()).isNotBlank();
            assertThat(created.getMasterAuth().getClusterCaCertificate()).isNotBlank();
        }
    }

    private static Operation awaitDone(Operation op) throws InterruptedException {
        Instant deadline = Instant.now().plus(CREATE_TIMEOUT);
        Operation current = op;
        while (current.getStatus() != Operation.Status.DONE) {
            assertThat(Instant.now()).as("operation %s still %s", op.getName(), current.getStatus())
                    .isBefore(deadline);
            Thread.sleep(2_000);
            current = client.getOperation(GetOperationRequest.newBuilder()
                    .setName(PARENT + "/operations/" + op.getName())
                    .build());
        }
        return current;
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

        var allLocations = client.listClusters(ListClustersRequest.newBuilder()
                .setParent("projects/" + TestFixtures.projectId() + "/locations/-")
                .build());
        assertThat(allLocations.getClustersList())
                .anyMatch(c -> c.getName().equals(CLUSTER));
    }

    @Test
    @Order(4)
    void updateMasterUpgradesOnlyTheControlPlane() {
        Cluster before = client.getCluster(GetClusterRequest.newBuilder()
                .setName(CLUSTER_NAME)
                .build());

        Operation op = client.updateMaster(UpdateMasterRequest.newBuilder()
                .setName(CLUSTER_NAME)
                .setMasterVersion("1.31.5-gke.1")
                .build());

        assertThat(op.getOperationType()).isEqualTo(Operation.Type.UPGRADE_MASTER);
        assertThat(op.getStatus()).isEqualTo(Operation.Status.DONE);

        Cluster after = client.getCluster(GetClusterRequest.newBuilder()
                .setName(CLUSTER_NAME)
                .build());
        assertThat(after.getCurrentMasterVersion()).isEqualTo("1.31.5-gke.1");
        // Real GKE upgrades the control plane independently of node pools.
        assertThat(after.getCurrentNodeVersion()).isEqualTo(before.getCurrentNodeVersion());
    }

    @Test
    @Order(5)
    void setNodePoolSizeReportsItsOwnOperationType() {
        // The generated client parses operationType into the closed Operation.Type enum, so this
        // exercises the decode path, not just the string the emulator writes (#228).
        Operation op = client.setNodePoolSize(SetNodePoolSizeRequest.newBuilder()
                .setName(CLUSTER_NAME + "/nodePools/default-pool")
                .setNodeCount(3)
                .build());

        assertThat(op.getOperationType()).isEqualTo(Operation.Type.SET_NODE_POOL_SIZE);
        assertThat(op.getStatus()).isEqualTo(Operation.Status.DONE);

        NodePool pool = client.getNodePool(CLUSTER_NAME + "/nodePools/default-pool");
        assertThat(pool.getInitialNodeCount()).isEqualTo(3);
    }

    @Test
    @Order(6)
    @SuppressWarnings("deprecation")
    void setLocationsIsAnUpdateClusterOperation() {
        // Operation.Type has no SET_LOCATIONS; real GKE reports the cluster-level set* RPCs as
        // UPDATE_CLUSTER. An invented value would decode as TYPE_UNSPECIFIED or fail here.
        Operation op = client.setLocations(SetLocationsRequest.newBuilder()
                .setName(CLUSTER_NAME)
                .addLocations("us-central1-a")
                .addLocations("us-central1-b")
                .build());

        assertThat(op.getOperationType()).isEqualTo(Operation.Type.UPDATE_CLUSTER);
        assertThat(op.getStatus()).isEqualTo(Operation.Status.DONE);

        Cluster after = client.getCluster(GetClusterRequest.newBuilder()
                .setName(CLUSTER_NAME)
                .build());
        assertThat(after.getLocationsList()).containsExactly("us-central1-a", "us-central1-b");
    }

    @Test
    @Order(7)
    void deleteCluster() {
        Operation op = client.deleteCluster(DeleteClusterRequest.newBuilder()
                .setName(CLUSTER_NAME)
                .build());

        assertThat(op.getOperationType()).isEqualTo(Operation.Type.DELETE_CLUSTER);
        assertThat(op.getStatus()).isEqualTo(Operation.Status.DONE);
    }
}
