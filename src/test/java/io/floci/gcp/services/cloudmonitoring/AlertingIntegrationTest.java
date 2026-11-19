package io.floci.gcp.services.cloudmonitoring;

import com.google.monitoring.v3.AlertPolicy;
import com.google.monitoring.v3.AlertPolicyServiceGrpc;
import com.google.monitoring.v3.ComparisonType;
import com.google.monitoring.v3.CreateAlertPolicyRequest;
import com.google.monitoring.v3.CreateNotificationChannelRequest;
import com.google.monitoring.v3.DeleteAlertPolicyRequest;
import com.google.monitoring.v3.DeleteNotificationChannelRequest;
import com.google.monitoring.v3.GetAlertPolicyRequest;
import com.google.monitoring.v3.ListNotificationChannelDescriptorsRequest;
import com.google.monitoring.v3.NotificationChannel;
import com.google.monitoring.v3.NotificationChannelServiceGrpc;
import com.google.protobuf.Duration;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AlertingIntegrationTest {
    private static final String PARENT = "projects/test-project";

    @TestHTTPResource
    URI endpoint;

    @Inject
    BeanManager beanManager;

    @Test
    void alertingGrpcBindsAfterTheMonitoringDescriptorIsRegistered() {
        Set<ObserverMethod<? super StartupEvent>> observers = beanManager.resolveObserverMethods(new StartupEvent());
        int registers = startupPriority(observers, CloudMonitoringService.class);
        int binds = startupPriority(observers, AlertPolicyService.class);
        assertTrue(binds > registers, "AlertPolicyService binds at priority " + binds
                + ", not after the monitoring registration at " + registers);
    }

    @Test
    void restManagesChannelsAndPoliciesTheWayTerraformCallsThem() {
        String channel = given().contentType("application/json")
                .body(Map.of("type", "webhook_tokenauth", "displayName", "hook", "labels", Map.of("url", "http://hook")))
                .post("/v3/" + PARENT + "/notificationChannels").then().statusCode(200)
                .body("enabled", equalTo(true)).body("creationRecord.mutateTime", notNullValue())
                .extract().path("name");
        String policy = given().contentType("application/json").body(Map.of(
                        "displayName", "CPU high", "combiner", "OR",
                        "notificationChannels", List.of(channel),
                        "documentation", Map.of("content", "Page the on-call"),
                        "conditions", List.of(Map.of("displayName", "cpu", "conditionThreshold", Map.of(
                                "filter", "metric.type=\"custom.googleapis.com/cpu\" AND resource.type=\"global\"",
                                "comparison", "COMPARISON_GT", "thresholdValue", 0.9, "duration", "60s",
                                "aggregations", List.of(Map.of("alignmentPeriod", "60s", "perSeriesAligner", "ALIGN_MEAN")))))))
                .post("/v3/" + PARENT + "/alertPolicies").then().statusCode(200)
                .body("enabled", equalTo(true)).body("documentation.mimeType", equalTo("text/markdown"))
                .body("conditions[0].name", startsWith(PARENT + "/alertPolicies/"))
                .extract().path("name");
        try {
            given().contentType("application/json").queryParam("updateMask", "displayName,enabled")
                    .body(Map.of("displayName", "CPU very high", "enabled", false))
                    .patch("/v3/" + policy).then().statusCode(200)
                    .body("displayName", equalTo("CPU very high")).body("enabled", equalTo(false))
                    .body("conditions.size()", equalTo(1));
            given().queryParam("filter", "display_name=\"CPU very high\"")
                    .get("/v3/" + PARENT + "/alertPolicies").then().statusCode(200)
                    .body("alertPolicies.name", hasItem(policy));
            given().get("/v3/" + PARENT + "/notificationChannelDescriptors/email").then().statusCode(200)
                    .body("labels[0].key", equalTo("email_address"));
            given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                    .post("/v3/" + channel + ":sendVerificationCode").then().statusCode(200);
            given().urlEncodingEnabled(false).contentType("application/json").body(Map.of("code", "G-000000-wrong"))
                    .post("/v3/" + channel + ":verify").then().statusCode(400)
                    .body("error.status", equalTo("INVALID_ARGUMENT"));

            given().delete("/v3/" + channel).then().statusCode(400).body("error.status", equalTo("FAILED_PRECONDITION"));
            given().queryParam("force", true).delete("/v3/" + channel).then().statusCode(200);
            given().get("/v3/" + policy).then().statusCode(200).body("notificationChannels", anyOf(nullValue(), empty()));
        } finally {
            given().delete("/v3/" + policy).then().statusCode(200);
        }
        given().get("/v3/" + policy).then().statusCode(404).body("error.status", equalTo("NOT_FOUND"));
    }

    @Test
    void grpcClientsSeeTheSamePoliciesAndChannels() throws Exception {
        ManagedChannel grpc = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
        try {
            var channels = NotificationChannelServiceGrpc.newBlockingStub(grpc).withDeadlineAfter(10, TimeUnit.SECONDS);
            var policies = AlertPolicyServiceGrpc.newBlockingStub(grpc).withDeadlineAfter(10, TimeUnit.SECONDS);
            assertFalse(channels.listNotificationChannelDescriptors(ListNotificationChannelDescriptorsRequest.newBuilder()
                    .setName(PARENT).build()).getChannelDescriptorsList().isEmpty());
            NotificationChannel email = channels.createNotificationChannel(CreateNotificationChannelRequest.newBuilder()
                    .setName(PARENT).setNotificationChannel(NotificationChannel.newBuilder().setType("email")
                            .putLabels("email_address", "ops@example.com")).build());
            AlertPolicy created = policies.createAlertPolicy(CreateAlertPolicyRequest.newBuilder().setName(PARENT)
                    .setAlertPolicy(AlertPolicy.newBuilder().setDisplayName("gRPC policy")
                            .addNotificationChannels(email.getName())
                            .addConditions(AlertPolicy.Condition.newBuilder().setDisplayName("cpu")
                                    .setConditionThreshold(AlertPolicy.Condition.MetricThreshold.newBuilder()
                                            .setFilter("metric.type=\"custom.googleapis.com/cpu\"")
                                            .setComparison(ComparisonType.COMPARISON_GT).setThresholdValue(1)
                                            .setDuration(Duration.newBuilder().setSeconds(60))))).build());
            assertEquals(created, policies.getAlertPolicy(GetAlertPolicyRequest.newBuilder()
                    .setName(created.getName()).build()));
            given().get("/v3/" + created.getName()).then().statusCode(200).body("displayName", equalTo("gRPC policy"));

            StatusRuntimeException refused = assertThrows(StatusRuntimeException.class, () ->
                    channels.deleteNotificationChannel(DeleteNotificationChannelRequest.newBuilder()
                            .setName(email.getName()).build()));
            assertEquals(Status.Code.FAILED_PRECONDITION, refused.getStatus().getCode());
            policies.deleteAlertPolicy(DeleteAlertPolicyRequest.newBuilder().setName(created.getName()).build());
            channels.deleteNotificationChannel(DeleteNotificationChannelRequest.newBuilder()
                    .setName(email.getName()).build());
        } finally {
            grpc.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static int startupPriority(Set<ObserverMethod<? super StartupEvent>> observers, Class<?> bean) {
        return observers.stream().filter(observer -> observer.getBeanClass().equals(bean))
                .mapToInt(ObserverMethod::getPriority).findFirst().orElseThrow();
    }
}
