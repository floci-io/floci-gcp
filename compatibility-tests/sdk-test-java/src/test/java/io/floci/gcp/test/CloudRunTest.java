package io.floci.gcp.test;

import com.google.cloud.run.v2.Container;
import com.google.cloud.run.v2.ContainerPort;
import com.google.cloud.run.v2.CreateServiceRequest;
import com.google.cloud.run.v2.DeleteServiceRequest;
import com.google.cloud.run.v2.GetRevisionRequest;
import com.google.cloud.run.v2.GetServiceRequest;
import com.google.cloud.run.v2.ListRevisionsRequest;
import com.google.cloud.run.v2.ListServicesRequest;
import com.google.cloud.run.v2.Revision;
import com.google.cloud.run.v2.RevisionTemplate;
import com.google.cloud.run.v2.RevisionsClient;
import com.google.cloud.run.v2.Service;
import com.google.cloud.run.v2.ServicesClient;
import com.google.iam.v1.Binding;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import com.google.iam.v1.TestIamPermissionsResponse;
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
class CloudRunTest {

    static {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
    }

    private static final String PROJECT_ID = TestFixtures.projectId();
    private static final String LOCATION = "us-central1";
    private static final String SERVICE_ID = TestFixtures.uniqueName("run-svc");
    private static final String PARENT = "projects/" + PROJECT_ID + "/locations/" + LOCATION;
    private static final String SERVICE_NAME = PARENT + "/services/" + SERVICE_ID;
    private static final boolean EXECUTION_ENABLED = Boolean.parseBoolean(
            System.getenv().getOrDefault("FLOCI_GCP_CLOUDRUN_EXECUTION_ENABLED", "false"));

    private static ServicesClient servicesClient;
    private static RevisionsClient revisionsClient;
    private static String revisionName;

    @BeforeAll
    static void setUp() throws IOException {
        servicesClient = TestFixtures.cloudRunServicesClient();
        revisionsClient = TestFixtures.cloudRunRevisionsClient();
    }

    @AfterAll
    static void tearDown() {
        if (servicesClient != null) {
            servicesClient.close();
        }
        if (revisionsClient != null) {
            revisionsClient.close();
        }
    }

    @Test
    @Order(1)
    void createServiceWithLro() throws Exception {
        Service service = Service.newBuilder()
                .setTemplate(RevisionTemplate.newBuilder()
                        .addContainers(Container.newBuilder()
                                .setImage("nginx:latest")
                                .addPorts(ContainerPort.newBuilder().setContainerPort(80))
                                .build())
                        .build())
                .build();

        Service created = servicesClient.createServiceAsync(CreateServiceRequest.newBuilder()
                        .setParent(PARENT)
                        .setServiceId(SERVICE_ID)
                        .setService(service)
                        .build())
                .get(120, TimeUnit.SECONDS);

        assertThat(created.getName()).isEqualTo(SERVICE_NAME);
        if (EXECUTION_ENABLED) {
            assertThat(created.getUri())
                    .startsWith("http://" + SERVICE_ID + "-")
                    .contains("." + LOCATION + ".run.floci-gcp:4588");
            assertThat(created.getUrlsList()).contains(created.getUri());
            assertThat(created.getTrafficStatuses(0).getUri()).isEqualTo(created.getUri());
        } else {
            assertThat(created.getUri()).startsWith("https://" + SERVICE_ID + "-");
        }
        assertThat(created.getLatestReadyRevision()).contains("/revisions/");
        assertThat(created.getTerminalCondition().getType()).isEqualTo("Ready");
        assertThat(created.getTrafficStatuses(0).getPercent()).isEqualTo(100);
        revisionName = created.getLatestReadyRevision();
    }

    @Test
    @Order(2)
    void invokeServiceWhenExecutionEnabled() throws Exception {
        if (!EXECUTION_ENABLED) {
            return;
        }

        Service service = servicesClient.getService(GetServiceRequest.newBuilder()
                .setName(SERVICE_NAME)
                .build());
        URI serviceUri = URI.create(service.getUri());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TestFixtures.endpoint() + "/?compat=java"))
                .header("Host", serviceUri.getAuthority())
                .header("X-Compat-Test", "cloud-run")
                .GET()
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Welcome to nginx");
    }

    @Test
    @Order(3)
    void getService() {
        Service service = servicesClient.getService(GetServiceRequest.newBuilder()
                .setName(SERVICE_NAME)
                .build());

        assertThat(service.getName()).isEqualTo(SERVICE_NAME);
        assertThat(service.getLatestReadyRevision()).isEqualTo(revisionName);
    }

    @Test
    @Order(4)
    void listServices() {
        List<Service> services = new ArrayList<>();
        servicesClient.listServices(ListServicesRequest.newBuilder()
                        .setParent(PARENT)
                        .build())
                .iterateAll()
                .forEach(services::add);

        assertThat(services).anyMatch(service -> service.getName().equals(SERVICE_NAME));
    }

    @Test
    @Order(5)
    void setGetAndTestIamPolicy() {
        Policy policy = Policy.newBuilder()
                .addBindings(Binding.newBuilder()
                        .setRole("roles/run.invoker")
                        .addMembers("allUsers")
                        .build())
                .build();

        Policy saved = servicesClient.setIamPolicy(SetIamPolicyRequest.newBuilder()
                .setResource(SERVICE_NAME)
                .setPolicy(policy)
                .build());
        assertThat(saved.getBindings(0).getMembersList()).contains("allUsers");

        Policy fetched = servicesClient.getIamPolicy(GetIamPolicyRequest.newBuilder()
                .setResource(SERVICE_NAME)
                .build());
        assertThat(fetched.getBindings(0).getRole()).isEqualTo("roles/run.invoker");

        TestIamPermissionsResponse permissions = servicesClient.testIamPermissions(
                TestIamPermissionsRequest.newBuilder()
                        .setResource(SERVICE_NAME)
                        .addPermissions("run.services.get")
                        .addPermissions("run.services.delete")
                        .build());
        assertThat(permissions.getPermissionsList()).containsExactly("run.services.get", "run.services.delete");
    }

    @Test
    @Order(6)
    void listAndGetRevision() {
        List<Revision> revisions = new ArrayList<>();
        revisionsClient.listRevisions(ListRevisionsRequest.newBuilder()
                        .setParent(SERVICE_NAME)
                        .build())
                .iterateAll()
                .forEach(revisions::add);
        assertThat(revisions).anyMatch(revision -> revision.getName().equals(revisionName));

        Revision revision = revisionsClient.getRevision(GetRevisionRequest.newBuilder()
                .setName(revisionName)
                .build());
        assertThat(revision.getService()).isEqualTo(SERVICE_NAME);
        assertThat(revision.getContainers(0).getImage()).isEqualTo("nginx:latest");
    }

    @Test
    @Order(7)
    void deleteServiceWithLro() throws Exception {
        servicesClient.deleteServiceAsync(DeleteServiceRequest.newBuilder()
                        .setName(SERVICE_NAME)
                        .build())
                .get(60, TimeUnit.SECONDS);

        List<Service> services = new ArrayList<>();
        servicesClient.listServices(ListServicesRequest.newBuilder()
                        .setParent(PARENT)
                        .build())
                .iterateAll()
                .forEach(services::add);
        assertThat(services).noneMatch(service -> service.getName().equals(SERVICE_NAME));
    }
}
