package io.floci.gcp.test;

import com.google.cloud.eventarc.v1.CloudRun;
import com.google.cloud.eventarc.v1.CreateTriggerRequest;
import com.google.cloud.eventarc.v1.DeleteTriggerRequest;
import com.google.cloud.eventarc.v1.Destination;
import com.google.cloud.eventarc.v1.EventFilter;
import com.google.cloud.eventarc.v1.EventarcClient;
import com.google.cloud.eventarc.v1.GetProviderRequest;
import com.google.cloud.eventarc.v1.GetTriggerRequest;
import com.google.cloud.eventarc.v1.ListProvidersRequest;
import com.google.cloud.eventarc.v1.ListTriggersRequest;
import com.google.cloud.eventarc.v1.Provider;
import com.google.cloud.eventarc.v1.Trigger;
import com.google.cloud.eventarc.v1.UpdateTriggerRequest;
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
class EventarcTest {

    private static final String PROJECT_ID = TestFixtures.projectId();
    private static final String LOCATION = "us-central1";
    private static final String TRIGGER_ID = TestFixtures.uniqueName("eventarc-trigger");
    private static final String PARENT = "projects/" + PROJECT_ID + "/locations/" + LOCATION;
    private static final String TRIGGER_NAME = PARENT + "/triggers/" + TRIGGER_ID;

    private static EventarcClient client;

    @BeforeAll
    static void setUp() throws IOException {
        client = TestFixtures.eventarcClient();
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @Order(1)
    void createTriggerWithLro() throws Exception {
        Trigger trigger = Trigger.newBuilder()
                .setDestination(Destination.newBuilder()
                        .setCloudRun(CloudRun.newBuilder()
                                .setService("hello-run")
                                .setRegion("us-central1")
                                .build())
                        .build())
                .addEventFilters(EventFilter.newBuilder()
                        .setAttribute("type")
                        .setValue("google.cloud.pubsub.topic.v1.messagePublished")
                        .build())
                .setServiceAccount("test-sa@project.iam.gserviceaccount.com")
                .build();

        Trigger created = client.createTriggerAsync(CreateTriggerRequest.newBuilder()
                        .setParent(PARENT)
                        .setTriggerId(TRIGGER_ID)
                        .setTrigger(trigger)
                        .build())
                .get(10, TimeUnit.SECONDS);

        assertThat(created.getName()).isEqualTo(TRIGGER_NAME);
        assertThat(created.getServiceAccount()).isEqualTo("test-sa@project.iam.gserviceaccount.com");
        assertThat(created.getDestination().getCloudRun().getService()).isEqualTo("hello-run");
        assertThat(created.getEventFiltersCount()).isEqualTo(1);
        assertThat(created.getEventFilters(0).getValue()).isEqualTo("google.cloud.pubsub.topic.v1.messagePublished");
    }

    @Test
    @Order(2)
    void getTrigger() {
        Trigger trigger = client.getTrigger(GetTriggerRequest.newBuilder()
                .setName(TRIGGER_NAME)
                .build());

        assertThat(trigger.getName()).isEqualTo(TRIGGER_NAME);
        assertThat(trigger.getServiceAccount()).isEqualTo("test-sa@project.iam.gserviceaccount.com");
        assertThat(trigger.getDestination().getCloudRun().getService()).isEqualTo("hello-run");
    }

    @Test
    @Order(3)
    void listTriggers() {
        List<Trigger> triggers = new ArrayList<>();
        client.listTriggers(ListTriggersRequest.newBuilder()
                        .setParent(PARENT)
                        .build())
                .iterateAll()
                .forEach(triggers::add);

        assertThat(triggers).anyMatch(t -> t.getName().equals(TRIGGER_NAME));
    }

    @Test
    @Order(4)
    void updateTriggerWithLro() throws Exception {
        Trigger trigger = client.getTrigger(TRIGGER_NAME);
        Trigger updatedTrigger = trigger.toBuilder()
                .setServiceAccount("updated-sa@project.iam.gserviceaccount.com")
                .build();

        Trigger updated = client.updateTriggerAsync(UpdateTriggerRequest.newBuilder()
                        .setTrigger(updatedTrigger)
                        .setUpdateMask(FieldMask.newBuilder().addPaths("service_account").build())
                        .build())
                .get(10, TimeUnit.SECONDS);

        assertThat(updated.getServiceAccount()).isEqualTo("updated-sa@project.iam.gserviceaccount.com");
    }

    @Test
    @Order(5)
    void listProviders() {
        List<Provider> providers = new ArrayList<>();
        client.listProviders(ListProvidersRequest.newBuilder()
                        .setParent(PARENT)
                        .build())
                .iterateAll()
                .forEach(providers::add);

        assertThat(providers).anyMatch(p -> p.getName().endsWith("storage.googleapis.com"));
        assertThat(providers).anyMatch(p -> p.getName().endsWith("pubsub.googleapis.com"));
    }

    @Test
    @Order(6)
    void getProvider() {
        String providerName = PARENT + "/providers/storage.googleapis.com";
        Provider provider = client.getProvider(GetProviderRequest.newBuilder()
                .setName(providerName)
                .build());

        assertThat(provider.getName()).isEqualTo(providerName);
        assertThat(provider.getDisplayName()).isEqualTo("Cloud Storage");
    }

    @Test
    @Order(7)
    void deleteTriggerWithLro() throws Exception {
        client.deleteTriggerAsync(DeleteTriggerRequest.newBuilder()
                        .setName(TRIGGER_NAME)
                        .build())
                .get(10, TimeUnit.SECONDS);

        assertThatThrownBy(() -> client.getTrigger(GetTriggerRequest.newBuilder()
                        .setName(TRIGGER_NAME)
                        .build()))
                .isInstanceOf(RuntimeException.class);
    }
}
