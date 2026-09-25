package io.floci.gcp.test;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.rpc.NotFoundException;
import com.google.cloud.run.v2.Container;
import com.google.cloud.run.v2.CreateWorkerPoolRequest;
import com.google.cloud.run.v2.DeleteWorkerPoolRequest;
import com.google.cloud.run.v2.GetWorkerPoolRequest;
import com.google.cloud.run.v2.InstanceSplitAllocationType;
import com.google.cloud.run.v2.ListRevisionsRequest;
import com.google.cloud.run.v2.Revision;
import com.google.cloud.run.v2.RevisionsClient;
import com.google.cloud.run.v2.RevisionsSettings;
import com.google.cloud.run.v2.UpdateWorkerPoolRequest;
import com.google.cloud.run.v2.WorkerPool;
import com.google.cloud.run.v2.WorkerPoolRevisionTemplate;
import com.google.cloud.run.v2.WorkerPoolsClient;
import com.google.cloud.run.v2.WorkerPoolsSettings;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.Policy;
import com.google.protobuf.FieldMask;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CloudRunWorkerPoolsTest {

    private static final String PROJECT_ID = TestFixtures.projectId();
    private static final String LOCATION = "us-central1";
    private static final String POOL_ID = TestFixtures.uniqueName("run-wp");
    private static final String PARENT = "projects/" + PROJECT_ID + "/locations/" + LOCATION;
    private static final String POOL_NAME = PARENT + "/workerPools/" + POOL_ID;

    private static WorkerPoolsClient workerPoolsClient;
    private static RevisionsClient revisionsClient;
    private static String firstRevision;
    private static String secondRevision;

    @BeforeAll
    static void setUp() throws IOException {
        workerPoolsClient = WorkerPoolsClient.create(WorkerPoolsSettings.newHttpJsonBuilder()
                .setEndpoint(TestFixtures.endpoint())
                .setCredentialsProvider(NoCredentialsProvider.create())
                .build());
        revisionsClient = RevisionsClient.create(RevisionsSettings.newHttpJsonBuilder()
                .setEndpoint(TestFixtures.endpoint())
                .setCredentialsProvider(NoCredentialsProvider.create())
                .build());
    }

    @AfterAll
    static void tearDown() {
        if (workerPoolsClient != null) {
            workerPoolsClient.close();
        }
        if (revisionsClient != null) {
            revisionsClient.close();
        }
    }

    @Test
    @Order(1)
    void createWorkerPoolWithLro() throws Exception {
        WorkerPool pool = WorkerPool.newBuilder()
                .setTemplate(WorkerPoolRevisionTemplate.newBuilder()
                        .addContainers(Container.newBuilder()
                                .setImage("busybox:latest")
                                .addCommand("sh")
                                .addCommand("-c")
                                .addArgs("trap 'exit 0' TERM; while true; do sleep 1; done")
                                .build())
                        .build())
                .build();

        WorkerPool created = workerPoolsClient.createWorkerPoolAsync(CreateWorkerPoolRequest.newBuilder()
                        .setParent(PARENT)
                        .setWorkerPoolId(POOL_ID)
                        .setWorkerPool(pool)
                        .build())
                .get(180, TimeUnit.SECONDS);

        assertThat(created.getName()).isEqualTo(POOL_NAME);
        assertThat(created.getScaling().getManualInstanceCount()).isEqualTo(1);
        assertThat(created.getTemplate().getContainers(0).getResources().getLimitsMap())
                .containsEntry("cpu", "1000m")
                .containsEntry("memory", "512Mi");
        assertThat(created.getTerminalCondition().getType()).isEqualTo("Ready");
        assertThat(created.getLatestCreatedRevision())
                .matches(POOL_NAME + "/revisions/" + POOL_ID + "-00001-[a-z0-9]{3}");
        assertThat(created.getLatestReadyRevision()).isEqualTo(created.getLatestCreatedRevision());
        assertThat(created.getInstanceSplitStatuses(0).getType())
                .isEqualTo(InstanceSplitAllocationType.INSTANCE_SPLIT_ALLOCATION_TYPE_LATEST);
        firstRevision = created.getLatestCreatedRevision();
        assertThat(created.getInstanceSplitStatuses(0).getRevision())
                .isEqualTo(firstRevision.substring(firstRevision.lastIndexOf('/') + 1));
    }

    @Test
    @Order(2)
    void forceNewRevisionCreatesRevisionWithSameTemplate() throws Exception {
        WorkerPool current = workerPoolsClient.getWorkerPool(GetWorkerPoolRequest.newBuilder()
                .setName(POOL_NAME)
                .build());

        WorkerPool updated = workerPoolsClient.updateWorkerPoolAsync(UpdateWorkerPoolRequest.newBuilder()
                        .setWorkerPool(current)
                        .setUpdateMask(FieldMask.newBuilder().addPaths("template").build())
                        .setForceNewRevision(true)
                        .build())
                .get(180, TimeUnit.SECONDS);

        assertThat(updated.getGeneration()).isEqualTo(2);
        assertThat(updated.getLatestCreatedRevision())
                .matches(POOL_NAME + "/revisions/" + POOL_ID + "-00002-[a-z0-9]{3}");
        assertThat(updated.getLatestReadyRevision()).isEqualTo(updated.getLatestCreatedRevision());
        secondRevision = updated.getLatestCreatedRevision();
    }

    @Test
    @Order(3)
    void listRevisionsUnderWorkerPool() {
        List<Revision> revisions = new ArrayList<>();
        revisionsClient.listRevisions(ListRevisionsRequest.newBuilder()
                        .setParent(POOL_NAME)
                        .build())
                .iterateAll()
                .forEach(revisions::add);

        assertThat(revisions).extracting(Revision::getName).containsExactly(secondRevision, firstRevision);
        assertThat(revisions).allSatisfy(revision -> {
            assertThat(revision.getService()).isEmpty();
            assertThat(revision.getContainers(0).getName()).isEqualTo("busybox-1");
        });
        assertThat(revisions.get(1).getConditionsList())
                .anySatisfy(condition -> {
                    assertThat(condition.getType()).isEqualTo("Active");
                    assertThat(condition.getMessage()).isEqualTo("Revision retired.");
                });
    }

    @Test
    @Order(4)
    void getIamPolicy() {
        Policy policy = workerPoolsClient.getIamPolicy(GetIamPolicyRequest.newBuilder()
                .setResource(POOL_NAME)
                .build());

        assertThat(policy.getBindingsList()).isEmpty();
    }

    @Test
    @Order(5)
    void deleteWorkerPoolWithLro() throws Exception {
        WorkerPool deleted = workerPoolsClient.deleteWorkerPoolAsync(DeleteWorkerPoolRequest.newBuilder()
                        .setName(POOL_NAME)
                        .build())
                .get(120, TimeUnit.SECONDS);

        assertThat(deleted.getName()).isEqualTo(POOL_NAME);
        assertThat(deleted.hasDeleteTime()).isTrue();
        assertThatThrownBy(() -> workerPoolsClient.getWorkerPool(GetWorkerPoolRequest.newBuilder()
                .setName(POOL_NAME)
                .build()))
                .isInstanceOf(NotFoundException.class);
    }
}
