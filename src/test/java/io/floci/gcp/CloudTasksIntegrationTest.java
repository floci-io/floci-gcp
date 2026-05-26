package io.floci.gcp;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider;
import com.google.cloud.tasks.v2.*;
import com.google.protobuf.ByteString;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CloudTasksIntegrationTest {

    private static final String PROJECT_ID = "test-project";
    private static final String LOCATION = "us-central1";
    private static final String QUEUE_ID = "integration-test-queue-" + System.currentTimeMillis();
    private static final String TASK_URL = "http://localhost:8080/worker";

    private static CloudTasksClient client;
    private static String queueName;
    private static String taskName;

    @BeforeAll
    static void setUp() throws IOException {
        CloudTasksSettings settings = CloudTasksSettings.newBuilder()
                .setTransportChannelProvider(
                        InstantiatingGrpcChannelProvider.newBuilder()
                                .setEndpoint("127.0.0.1:8081")
                                .setChannelConfigurator(b -> b.usePlaintext())
                                .build())
                .setCredentialsProvider(NoCredentialsProvider.create())
                .build();
        client = CloudTasksClient.create(settings);
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
                        .setMaxConcurrentDispatches(5)
                        .build())
                .setRetryConfig(RetryConfig.newBuilder()
                        .setMaxAttempts(3)
                        .build())
                .build();

        Queue created = client.createQueue(parent, queue);
        assertEquals(queueName, created.getName());
        assertEquals(Queue.State.RUNNING, created.getState());
    }

    @Test
    @Order(2)
    void createTask() {
        QueueName parent = QueueName.of(PROJECT_ID, LOCATION, QUEUE_ID);
        Task task = Task.newBuilder()
                .setHttpRequest(HttpRequest.newBuilder()
                        .setUrl(TASK_URL)
                        .setHttpMethod(HttpMethod.POST)
                        .setBody(ByteString.copyFromUtf8("{\"key\":\"value\"}"))
                        .build())
                .build();

        Task created = client.createTask(parent, task);
        taskName = created.getName();
        assertTrue(taskName.contains(QUEUE_ID));
        assertEquals(TASK_URL, created.getHttpRequest().getUrl());
    }

    @Test
    @Order(3)
    void listTasksContainsCreatedTask() {
        QueueName parent = QueueName.of(PROJECT_ID, LOCATION, QUEUE_ID);
        List<Task> tasks = new ArrayList<>();
        client.listTasks(parent).iterateAll().forEach(tasks::add);

        assertFalse(tasks.isEmpty());
        assertTrue(tasks.stream().anyMatch(t -> t.getName().equals(taskName)));
    }

    @Test
    @Order(4)
    void pauseAndResumeQueue() {
        Queue paused = client.pauseQueue(queueName);
        assertEquals(Queue.State.PAUSED, paused.getState());

        Queue resumed = client.resumeQueue(queueName);
        assertEquals(Queue.State.RUNNING, resumed.getState());
    }

    @Test
    @Order(5)
    void deleteTaskAndVerifyGone() {
        client.deleteTask(taskName);

        QueueName parent = QueueName.of(PROJECT_ID, LOCATION, QUEUE_ID);
        List<Task> tasks = new ArrayList<>();
        client.listTasks(parent).iterateAll().forEach(tasks::add);
        assertTrue(tasks.stream().noneMatch(t -> t.getName().equals(taskName)));
    }
}
