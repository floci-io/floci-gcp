package io.floci.gcp.test;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.cloud.run.v2.Condition;
import com.google.cloud.run.v2.Container;
import com.google.cloud.run.v2.CreateInstanceRequest;
import com.google.cloud.run.v2.DeleteInstanceRequest;
import com.google.cloud.run.v2.GetInstanceRequest;
import com.google.cloud.run.v2.Instance;
import com.google.cloud.run.v2.InstancesClient;
import com.google.cloud.run.v2.InstancesSettings;
import com.google.cloud.run.v2.ListInstancesRequest;
import com.google.cloud.run.v2.StartInstanceRequest;
import com.google.cloud.run.v2.StopInstanceRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CloudRunInstancesTest {

    static {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
    }

    private static final String PROJECT_ID = TestFixtures.projectId();
    private static final String LOCATION = "us-central1";
    private static final String INSTANCE_ID = TestFixtures.uniqueName("run-inst");
    private static final String PARENT = "projects/" + PROJECT_ID + "/locations/" + LOCATION;
    private static final String INSTANCE_NAME = PARENT + "/instances/" + INSTANCE_ID;
    private static final boolean EXECUTION_ENABLED = Boolean.parseBoolean(
            System.getenv().getOrDefault("FLOCI_GCP_CLOUDRUN_EXECUTION_ENABLED", "false"));

    private static InstancesClient instancesClient;

    @BeforeAll
    static void setUp() throws IOException {
        InstancesSettings settings = InstancesSettings.newHttpJsonBuilder()
                .setEndpoint(TestFixtures.endpoint())
                .setCredentialsProvider(NoCredentialsProvider.create())
                .build();
        instancesClient = InstancesClient.create(settings);
    }

    @AfterAll
    static void tearDown() {
        if (instancesClient != null) {
            instancesClient.close();
        }
    }

    @Test
    @Order(1)
    void createInstanceWithLro() throws Exception {
        Instance instance = Instance.newBuilder()
                .addContainers(Container.newBuilder()
                        .setImage("busybox:latest")
                        .addCommand("sh")
                        .addCommand("-c")
                        .addArgs("mkdir -p /www && echo hello-instance > /www/index.html && httpd -f -p 8080 -h /www")
                        .build())
                .build();

        Instance created = instancesClient.createInstanceAsync(CreateInstanceRequest.newBuilder()
                        .setParent(PARENT)
                        .setInstanceId(INSTANCE_ID)
                        .setInstance(instance)
                        .build())
                .get(120, TimeUnit.SECONDS);

        assertThat(created.getName()).isEqualTo(INSTANCE_NAME);
        assertThat(created.getTerminalCondition().getType()).isEqualTo("Running");
        assertThat(created.getTerminalCondition().getState()).isEqualTo(Condition.State.CONDITION_SUCCEEDED);
        assertThat(created.getContainers(0).getResourcesOrBuilder().getLimitsMap())
                .containsEntry("cpu", "2000m")
                .containsEntry("memory", "2048Mi");
        assertThat(created.getContainers(0).getPorts(0).getContainerPort()).isEqualTo(8080);
        assertThat(created.getUrlsList()).hasSize(1);
        assertThat(created.getUrls(0)).startsWith("http://" + INSTANCE_ID + "-");
    }

    @Test
    @Order(2)
    void getAndListInstance() {
        Instance instance = instancesClient.getInstance(GetInstanceRequest.newBuilder()
                .setName(INSTANCE_NAME)
                .build());
        assertThat(instance.getGeneration()).isEqualTo(1);

        List<Instance> instances = new ArrayList<>();
        instancesClient.listInstances(ListInstancesRequest.newBuilder().setParent(PARENT).build())
                .iterateAll()
                .forEach(instances::add);
        assertThat(instances).anyMatch(listed -> listed.getName().equals(INSTANCE_NAME));
    }

    @Test
    @Order(3)
    void invokeInstanceWhenExecutionEnabled() throws Exception {
        if (!EXECUTION_ENABLED) {
            return;
        }
        assertThat(invoke().body()).contains("hello-instance");
    }

    @Test
    @Order(4)
    void stopInstanceWithLro() throws Exception {
        Instance stopped = instancesClient.stopInstanceAsync(StopInstanceRequest.newBuilder()
                        .setName(INSTANCE_NAME)
                        .build())
                .get(120, TimeUnit.SECONDS);

        assertThat(stopped.getTerminalCondition().getState()).isEqualTo(Condition.State.CONDITION_FAILED);
        assertThat(stopped.getTerminalCondition().getMessage()).isEqualTo("Instance stopped.");
        assertThat(stopped.getGeneration()).isEqualTo(2);
        assertThat(stopped.getUrlsList()).hasSize(1);
        if (EXECUTION_ENABLED) {
            assertThat(invoke().statusCode()).isEqualTo(503);
        }
    }

    @Test
    @Order(5)
    void startInstanceWithLro() throws Exception {
        Instance started = instancesClient.startInstanceAsync(StartInstanceRequest.newBuilder()
                        .setName(INSTANCE_NAME)
                        .build())
                .get(120, TimeUnit.SECONDS);

        assertThat(started.getTerminalCondition().getState()).isEqualTo(Condition.State.CONDITION_SUCCEEDED);
        assertThat(started.getGeneration()).isEqualTo(3);
        if (EXECUTION_ENABLED) {
            assertThat(invoke().body()).contains("hello-instance");
        }
    }

    @Test
    @Order(6)
    void deleteInstanceWithLro() throws Exception {
        Instance deleted = instancesClient.deleteInstanceAsync(DeleteInstanceRequest.newBuilder()
                        .setName(INSTANCE_NAME)
                        .build())
                .get(120, TimeUnit.SECONDS);
        assertThat(deleted.getTerminalCondition().getMessage()).isEqualTo("Instance completed for deletion.");

        List<Instance> instances = new ArrayList<>();
        instancesClient.listInstances(ListInstancesRequest.newBuilder().setParent(PARENT).build())
                .iterateAll()
                .forEach(instances::add);
        assertThat(instances).noneMatch(listed -> listed.getName().equals(INSTANCE_NAME));
    }

    private static HttpResponse<String> invoke() throws Exception {
        Instance instance = instancesClient.getInstance(GetInstanceRequest.newBuilder()
                .setName(INSTANCE_NAME)
                .build());
        URI url = URI.create(instance.getUrls(0));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TestFixtures.endpoint() + "/"))
                .header("Host", url.getAuthority())
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
