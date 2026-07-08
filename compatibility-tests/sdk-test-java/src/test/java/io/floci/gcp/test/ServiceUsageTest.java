package io.floci.gcp.test;

import com.google.api.serviceusage.v1.BatchEnableServicesRequest;
import com.google.api.serviceusage.v1.BatchGetServicesRequest;
import com.google.api.serviceusage.v1.BatchGetServicesResponse;
import com.google.api.serviceusage.v1.DisableServiceRequest;
import com.google.api.serviceusage.v1.DisableServiceResponse;
import com.google.api.serviceusage.v1.EnableServiceRequest;
import com.google.api.serviceusage.v1.EnableServiceResponse;
import com.google.api.serviceusage.v1.GetServiceRequest;
import com.google.api.serviceusage.v1.ListServicesRequest;
import com.google.api.serviceusage.v1.Service;
import com.google.api.serviceusage.v1.ServiceUsageClient;
import com.google.api.serviceusage.v1.State;
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
class ServiceUsageTest {

    private static final String PROJECT_ID = TestFixtures.projectId();
    private static final String PARENT = "projects/" + PROJECT_ID;
    private static final String SERVICE = "run.googleapis.com";
    private static final String SERVICE_NAME = PARENT + "/services/" + SERVICE;

    private static ServiceUsageClient client;

    @BeforeAll
    static void setUp() throws IOException {
        client = TestFixtures.serviceUsageClient();
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @Order(1)
    void enableServiceWithLro() throws Exception {
        EnableServiceResponse response = client.enableServiceAsync(EnableServiceRequest.newBuilder()
                        .setName(SERVICE_NAME)
                        .build())
                .get(10, TimeUnit.SECONDS);

        Service enabled = response.getService();
        assertThat(enabled.getName()).isEqualTo(SERVICE_NAME);
        assertThat(enabled.getParent()).isEqualTo(PARENT);
        assertThat(enabled.getConfig().getName()).isEqualTo(SERVICE);
        assertThat(enabled.getState()).isEqualTo(State.ENABLED);
    }

    @Test
    @Order(2)
    void getServiceReportsEnabled() {
        Service service = client.getService(GetServiceRequest.newBuilder()
                .setName(SERVICE_NAME)
                .build());

        assertThat(service.getName()).isEqualTo(SERVICE_NAME);
        assertThat(service.getState()).isEqualTo(State.ENABLED);
    }

    @Test
    @Order(3)
    void listServicesHonorsEnabledFilter() {
        List<Service> services = new ArrayList<>();
        client.listServices(ListServicesRequest.newBuilder()
                        .setParent(PARENT)
                        .setFilter("state:ENABLED")
                        .build())
                .iterateAll()
                .forEach(services::add);

        assertThat(services).anyMatch(s -> s.getName().equals(SERVICE_NAME));
        assertThat(services).allMatch(s -> s.getState() == State.ENABLED);
    }

    @Test
    @Order(4)
    void batchEnableAndBatchGetServices() throws Exception {
        client.batchEnableServicesAsync(BatchEnableServicesRequest.newBuilder()
                        .setParent(PARENT)
                        .addServiceIds("pubsub.googleapis.com")
                        .addServiceIds("storage.googleapis.com")
                        .build())
                .get(10, TimeUnit.SECONDS);

        BatchGetServicesResponse response = client.batchGetServices(BatchGetServicesRequest.newBuilder()
                .setParent(PARENT)
                .addNames(PARENT + "/services/pubsub.googleapis.com")
                .addNames(PARENT + "/services/storage.googleapis.com")
                .addNames(PARENT + "/services/never-enabled.googleapis.com")
                .build());

        assertThat(response.getServicesList())
                .extracting(Service::getState)
                .containsExactly(State.ENABLED, State.ENABLED, State.DISABLED);
    }

    @Test
    @Order(5)
    void disableServiceWithLro() throws Exception {
        DisableServiceResponse response = client.disableServiceAsync(DisableServiceRequest.newBuilder()
                        .setName(SERVICE_NAME)
                        .build())
                .get(10, TimeUnit.SECONDS);

        assertThat(response.getService().getState()).isEqualTo(State.DISABLED);

        Service service = client.getService(GetServiceRequest.newBuilder()
                .setName(SERVICE_NAME)
                .build());
        assertThat(service.getState()).isEqualTo(State.DISABLED);
    }

    @Test
    @Order(6)
    void disableNotEnabledServiceFailsPrecondition() {
        // gax httpjson maps errors from the HTTP code alone (400 → InvalidArgumentException);
        // the FAILED_PRECONDITION status from the error body survives in the cause chain.
        assertThatThrownBy(() -> client.disableServiceAsync(DisableServiceRequest.newBuilder()
                        .setName(PARENT + "/services/never-enabled.googleapis.com")
                        .build())
                .get(10, TimeUnit.SECONDS))
                .hasStackTraceContaining("FAILED_PRECONDITION");
    }
}
