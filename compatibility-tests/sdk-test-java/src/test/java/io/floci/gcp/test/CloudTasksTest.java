package io.floci.gcp.test;

import com.google.cloud.tasks.v2.*;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CloudTasksTest {

    private static final String PROJECT_ID = TestFixtures.projectId();
    private static final String LOCATION = "us-central1";
    private static final String QUEUE_ID = TestFixtures.uniqueName("test-queue");
    private static final String TASK_URL = "http://localhost:8080/worker";

    private static CloudTasksClient client;
    private static String queueName;
    private static String taskName;

    @BeforeAll
    static void setUp() throws IOException {
        client = TestFixtures.cloudTasksClient();
        queueName = "projects/" + PROJECT_ID + "/locations/" + LOCATION + "/queues/" + QUEUE_ID;
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @Order(1)
    void createQueue() {
        LocationName parent = LocationName.of(PROJECT_ID, LOCATION);

        Queue queue = Queue.newBuilder()
                .setName(queueName)
                .setRateLimits(RateLimits.newBuilder()
                        .setMaxDispatchesPerSecond(100)
                        .setMaxConcurrentDispatches(10)
                        .build())
                .setRetryConfig(RetryConfig.newBuilder()
                        .setMaxAttempts(5)
                        .build())
                .build();

        Queue created = client.createQueue(parent, queue);
        assertThat(created.getName()).isEqualTo(queueName);
        assertThat(created.getState()).isEqualTo(Queue.State.RUNNING);
    }

    @Test
    @Order(2)
    void getQueue() {
        Queue queue = client.getQueue(queueName);
        assertThat(queue.getName()).isEqualTo(queueName);
        assertThat(queue.getState()).isEqualTo(Queue.State.RUNNING);
        assertThat(queue.getRateLimits().getMaxDispatchesPerSecond()).isEqualTo(100.0);
        assertThat(queue.getRetryConfig().getMaxAttempts()).isEqualTo(5);
    }

    @Test
    @Order(3)
    void listQueues() {
        LocationName parent = LocationName.of(PROJECT_ID, LOCATION);
        List<Queue> queues = new ArrayList<>();
        client.listQueues(parent).iterateAll().forEach(queues::add);

        assertThat(queues).isNotEmpty();
        assertThat(queues).anyMatch(q -> q.getName().equals(queueName));
    }

    @Test
    @Order(4)
    void createTask() {
        QueueName parent = QueueName.of(PROJECT_ID, LOCATION, QUEUE_ID);

        Task task = Task.newBuilder()
                .setHttpRequest(HttpRequest.newBuilder()
                        .setUrl(TASK_URL)
                        .setHttpMethod(HttpMethod.POST)
                        .setBody(ByteString.copyFromUtf8("{\"job\":\"process\"}"))
                        .putHeaders("Content-Type", "application/json")
                        .build())
                .build();

        Task created = client.createTask(parent, task);
        taskName = created.getName();

        assertThat(taskName).contains(QUEUE_ID);
        assertThat(created.getHttpRequest().getUrl()).isEqualTo(TASK_URL);
        assertThat(created.getHttpRequest().getHttpMethod()).isEqualTo(HttpMethod.POST);
    }

    @Test
    @Order(5)
    void getTask() {
        Task task = client.getTask(taskName);
        assertThat(task.getName()).isEqualTo(taskName);
        assertThat(task.getHttpRequest().getUrl()).isEqualTo(TASK_URL);
        assertThat(task.getDispatchCount()).isEqualTo(0);
    }

    @Test
    @Order(6)
    void listTasks() {
        QueueName parent = QueueName.of(PROJECT_ID, LOCATION, QUEUE_ID);
        List<Task> tasks = new ArrayList<>();
        client.listTasks(parent).iterateAll().forEach(tasks::add);

        assertThat(tasks).isNotEmpty();
        assertThat(tasks).anyMatch(t -> t.getName().equals(taskName));
    }

    @Test
    @Order(7)
    void pauseQueue() {
        Queue paused = client.pauseQueue(queueName);
        assertThat(paused.getState()).isEqualTo(Queue.State.PAUSED);
    }

    @Test
    @Order(8)
    void resumeQueue() {
        Queue resumed = client.resumeQueue(queueName);
        assertThat(resumed.getState()).isEqualTo(Queue.State.RUNNING);
    }

    @Test
    @Order(9)
    void deleteTask() {
        client.deleteTask(taskName);

        QueueName parent = QueueName.of(PROJECT_ID, LOCATION, QUEUE_ID);
        List<Task> tasks = new ArrayList<>();
        client.listTasks(parent).iterateAll().forEach(tasks::add);

        assertThat(tasks).noneMatch(t -> t.getName().equals(taskName));
    }

    @Test
    @Order(10)
    void deleteQueue() {
        client.deleteQueue(queueName);

        LocationName parent = LocationName.of(PROJECT_ID, LOCATION);
        List<Queue> queues = new ArrayList<>();
        client.listQueues(parent).iterateAll().forEach(queues::add);

        assertThat(queues).noneMatch(q -> q.getName().equals(queueName));
    }
}
