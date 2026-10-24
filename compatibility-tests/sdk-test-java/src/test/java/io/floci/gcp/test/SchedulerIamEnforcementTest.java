package io.floci.gcp.test;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider;
import com.google.api.gax.rpc.FailedPreconditionException;
import com.google.api.gax.rpc.InvalidArgumentException;
import com.google.api.gax.rpc.PermissionDeniedException;
import com.google.api.services.pubsub.Pubsub;
import com.google.api.services.pubsub.model.PullRequest;
import com.google.api.services.pubsub.model.PullResponse;
import com.google.api.services.pubsub.model.Subscription;
import com.google.api.services.pubsub.model.Topic;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.iam.credentials.v1.IamCredentialsClient;
import com.google.cloud.scheduler.v1.AppEngineHttpTarget;
import com.google.cloud.scheduler.v1.CloudSchedulerClient;
import com.google.cloud.scheduler.v1.CloudSchedulerSettings;
import com.google.cloud.scheduler.v1.HttpTarget;
import com.google.cloud.scheduler.v1.Job;
import com.google.cloud.scheduler.v1.OAuthToken;
import com.google.cloud.scheduler.v1.OidcToken;
import com.google.cloud.scheduler.v1.PubsubTarget;
import com.google.cloud.scheduler.v1.UpdateJobRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.FieldMask;
import com.google.protobuf.util.JsonFormat;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchedulerIamEnforcementTest {
    private final boolean enforce = Boolean.parseBoolean(System.getenv("FLOCI_GCP_IAM_TEST_ENFORCEMENT"));

    @ParameterizedTest(name = "job scope, REST={0}")
    @ValueSource(booleans = {false, true})
    void projectConditionRestrictsEveryJobOperation(boolean rest) throws Exception {
        String project = TestFixtures.uniqueName("scheduler-iam");
        String parent = "projects/" + project + "/locations/us-central1";
        String allowed = parent + "/jobs/moss-connector-test";
        String sibling = parent + "/jobs/unrelated";
        String other = "projects/" + project + "-other/locations/us-central1/jobs/moss-connector-test";
        String email = "reconciler@" + project + ".iam.gserviceaccount.com";
        try (CloudSchedulerClient admin = client(rest, null);
                CloudSchedulerClient caller = client(rest, token(email))) {
            admin.createJob(parent, job(allowed));
            admin.createJob(parent, job(sibling));
            admin.createJob(other.substring(0, other.indexOf("/jobs/")), job(other));
            grant(project, email, "roles/cloudscheduler.admin", "resource.name.startsWith('" + allowed + "')");

            assertThat(caller.getJob(allowed).getName()).isEqualTo(allowed);
            assertThat(caller.updateJob(update(allowed)).getDescription()).isEqualTo("updated");
            assertThat(caller.pauseJob(allowed).getState()).isEqualTo(Job.State.PAUSED);
            assertThat(caller.resumeJob(allowed).getState()).isEqualTo(Job.State.ENABLED);
            assertThat(caller.runJob(allowed).hasLastAttemptTime()).isTrue();

            denied(() -> caller.getJob(sibling));
            denied(() -> caller.getJob(other));
            Job before = admin.getJob(sibling);
            denied(() -> caller.updateJob(update(sibling)));
            if (enforce) {
                assertThat(admin.getJob(sibling)).isEqualTo(before);
            }
            denied(() -> caller.pauseJob(sibling));
            if (enforce) {
                assertThat(admin.getJob(sibling)).isEqualTo(before);
            }
            admin.pauseJob(sibling);
            Job paused = admin.getJob(sibling);
            denied(() -> caller.resumeJob(sibling));
            if (enforce) {
                assertThat(admin.getJob(sibling)).isEqualTo(paused);
            }
            denied(() -> caller.runJob(sibling));
            if (enforce) {
                assertThat(admin.getJob(sibling)).isEqualTo(paused);
            }
            denied(() -> caller.deleteJob(sibling));
            if (enforce) {
                assertThat(admin.getJob(sibling)).isEqualTo(paused);
            }
            denied(() -> caller.listJobs(parent).iterateAll().iterator().hasNext());
            String created = parent + "/jobs/moss-connector-new";
            denied(() -> caller.createJob(parent, job(created)));
            if (enforce) {
                assertThat(admin.listJobs(parent).iterateAll()).noneMatch(value -> value.getName().equals(created));
            }
            caller.deleteJob(allowed);
            assertThat(admin.listJobs(parent).iterateAll()).noneMatch(value -> value.getName().equals(allowed));
        }
    }

    @ParameterizedTest(name = "location parent and role slices, REST={0}")
    @ValueSource(booleans = {false, true})
    void locationGrantControlsCreateAndListAndViewerCannotMutate(boolean rest) throws Exception {
        String project = TestFixtures.uniqueName("scheduler-parent");
        String parent = "projects/" + project + "/locations/us-central1";
        String otherParent = "projects/" + project + "/locations/us-east1";
        String name = parent + "/jobs/created";
        String email = "reconciler@" + project + ".iam.gserviceaccount.com";
        try (CloudSchedulerClient admin = client(rest, null);
                CloudSchedulerClient caller = client(rest, token(email))) {
            grant(project, email, "roles/cloudscheduler.admin", "resource.name == '" + parent + "'");
            assertThat(caller.createJob(parent, job(name)).getName()).isEqualTo(name);
            assertThat(caller.listJobs(parent).iterateAll()).anyMatch(value -> value.getName().equals(name));
            String excluded = otherParent + "/jobs/excluded";
            denied(() -> caller.createJob(otherParent, job(excluded)));
            denied(() -> caller.listJobs(otherParent).iterateAll().iterator().hasNext());
            if (enforce) {
                assertThat(admin.listJobs(otherParent).iterateAll()).isEmpty();
            }
            grant(project, email, "roles/cloudscheduler.viewer", null);
            assertThat(caller.getJob(name).getName()).isEqualTo(name);
            assertThat(caller.listJobs(parent).iterateAll()).anyMatch(value -> value.getName().equals(name));
            Job before = admin.getJob(name);
            denied(() -> caller.updateJob(update(name)));
            denied(() -> caller.runJob(name));
            if (enforce) {
                assertThat(admin.getJob(name)).isEqualTo(before);
            }
            grant(project, email, "roles/cloudscheduler.jobRunner", null);
            assertThat(caller.runJob(name).hasLastAttemptTime()).isTrue();
            denied(() -> caller.getJob(name));
            denied(() -> caller.pauseJob(name));
            grant(project, email, "roles/unknown.schedulerRole", null);
            denied(() -> caller.getJob(name));
            grant(project, email, "roles/cloudscheduler.admin", null);
            caller.deleteJob(name);
        }
    }

    @ParameterizedTest(name = "no binding, REST={0}")
    @ValueSource(booleans = {false, true})
    void unboundCredentialCannotAccessAnyJobMethod(boolean rest) throws Exception {
        String project = TestFixtures.uniqueName("scheduler-unbound");
        String parent = "projects/" + project + "/locations/us-central1";
        String name = parent + "/jobs/existing";
        try (CloudSchedulerClient admin = client(rest, null);
                CloudSchedulerClient caller = client(rest, token("caller@" + project + ".iam.gserviceaccount.com"))) {
            admin.createJob(parent, job(name));
            Job before = admin.getJob(name);
            denied(() -> caller.getJob(name));
            denied(() -> caller.listJobs(parent).iterateAll().iterator().hasNext());
            denied(() -> caller.updateJob(update(name)));
            denied(() -> caller.pauseJob(name));
            admin.pauseJob(name);
            Job paused = admin.getJob(name);
            denied(() -> caller.resumeJob(name));
            denied(() -> caller.runJob(name));
            denied(() -> caller.deleteJob(name));
            denied(() -> caller.createJob(parent, job(parent + "/jobs/new")));
            if (enforce) {
                assertThat(before.getDescription()).isEmpty();
                assertThat(admin.getJob(name)).isEqualTo(paused);
                assertThat(admin.listJobs(parent).iterateAll()).hasSize(1);
            }
        }
    }

    @ParameterizedTest(name = "authenticated HTTP target rejected, REST={0}")
    @ValueSource(booleans = {false, true})
    void authenticatedTargetsFailClosedUntilSupported(boolean rest) throws Exception {
        String project = TestFixtures.uniqueName("scheduler-auth-target");
        String parent = "projects/" + project + "/locations/us-central1";
        String name = parent + "/jobs/authenticated";
        String email = "caller@" + project + ".iam.gserviceaccount.com";
        try (CloudSchedulerClient admin = client(rest, null);
                CloudSchedulerClient caller = client(rest, token(email))) {
            grant(project, email, "roles/cloudscheduler.admin", null);
            for (boolean oidc : List.of(true, false)) {
                HttpTarget.Builder target = HttpTarget.newBuilder().setUri("https://example.invalid/target");
                if (oidc) {
                    target.setOidcToken(OidcToken.newBuilder().setServiceAccountEmail(email));
                } else {
                    target.setOauthToken(OAuthToken.newBuilder().setServiceAccountEmail(email));
                }
                Job authenticated = Job.newBuilder().setName(name).setSchedule("0 0 1 1 *").setHttpTarget(target).build();
                if (enforce) {
                    assertThatThrownBy(() -> caller.createJob(parent, authenticated))
                            .isInstanceOf(rest ? InvalidArgumentException.class : FailedPreconditionException.class);
                    assertThat(admin.listJobs(parent).iterateAll()).isEmpty();
                    admin.createJob(parent, job(name));
                    Job before = admin.getJob(name);
                    assertThatThrownBy(() -> caller.updateJob(UpdateJobRequest.newBuilder().setJob(authenticated)
                            .setUpdateMask(FieldMask.newBuilder().addPaths("http_target")).build()))
                            .isInstanceOf(rest ? InvalidArgumentException.class : FailedPreconditionException.class);
                    assertThat(admin.getJob(name)).isEqualTo(before);
                    admin.deleteJob(name);
                } else {
                    caller.createJob(parent, authenticated);
                    caller.deleteJob(name);
                }
                String field = oidc ? "oidcToken" : "oauthToken";
                String json = "{\"name\":\"" + name + "\",\"schedule\":\"0 0 1 1 *\",\"httpTarget\":null,"
                        + "\"http_target\":{\"uri\":\"https://example.invalid/target\",\"" + field
                        + "\":{\"serviceAccountEmail\":\"" + email + "\"}}}";
                try (HttpClient http = HttpClient.newHttpClient()) {
                    HttpResponse<String> response = sendJson(http, token(email), "POST", parent + "/jobs", json);
                    assertThat(response.statusCode()).as(response.body()).isEqualTo(enforce ? 400 : 200);
                    if (enforce) {
                        assertThat(response.body()).contains("FAILED_PRECONDITION");
                        assertThat(admin.listJobs(parent).iterateAll()).isEmpty();
                    } else {
                        admin.deleteJob(name);
                    }
                }
            }
        }
    }

    @ParameterizedTest(name = "denied run has no Pub/Sub delivery, REST={0}")
    @ValueSource(booleans = {false, true})
    void deniedRunDoesNotPublishToItsTarget(boolean rest) throws Exception {
        String project = TestFixtures.uniqueName("scheduler-delivery");
        String parent = "projects/" + project + "/locations/us-central1";
        String allowed = parent + "/jobs/allowed";
        String sibling = parent + "/jobs/unrelated";
        String email = "caller@" + project + ".iam.gserviceaccount.com";
        String topic = "projects/" + project + "/topics/delivery";
        String subscription = "projects/" + project + "/subscriptions/delivery";
        Pubsub pubsub = new Pubsub.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance(), null)
                .setRootUrl(TestFixtures.endpoint() + "/").setApplicationName("scheduler-iam-delivery").build();
        pubsub.projects().topics().create(topic, new Topic()).execute();
        pubsub.projects().subscriptions().create(subscription,
                new Subscription().setTopic(topic).setAckDeadlineSeconds(10)).execute();
        try (CloudSchedulerClient admin = client(rest, null);
                CloudSchedulerClient caller = client(rest, token(email))) {
            admin.createJob(parent, deliveryJob(allowed, topic, "permitted"));
            admin.createJob(parent, deliveryJob(sibling, topic, "forbidden"));
            grant(project, email, "roles/cloudscheduler.admin", "resource.name == '" + allowed + "'");
            caller.runJob(allowed);
            Job before = admin.getJob(sibling);
            denied(() -> caller.runJob(sibling));
            if (enforce) {
                assertThat(admin.getJob(sibling)).isEqualTo(before);
            }
            PullResponse response = pubsub.projects().subscriptions().pull(subscription,
                    new PullRequest().setMaxMessages(10).setReturnImmediately(true)).execute();
            List<String> deliveries = response.getReceivedMessages().stream()
                    .map(message -> new String(Base64.getDecoder().decode(message.getMessage().getData()),
                            StandardCharsets.UTF_8)).toList();
            assertThat(deliveries).containsExactlyInAnyOrderElementsOf(
                    enforce ? List.of("permitted") : List.of("permitted", "forbidden"));
            admin.deleteJob(allowed);
            admin.deleteJob(sibling);
        } finally {
            pubsub.projects().subscriptions().delete(subscription).execute();
            pubsub.projects().topics().delete(topic).execute();
        }
    }

    @Test
    void restUnsetFieldsAndDomainScopedProjectsFollowProtobufSemantics() throws Exception {
        String project = "example.com:" + TestFixtures.uniqueName("scheduler-domain");
        String parent = "projects/" + project + "/locations/us-central1";
        String name = parent + "/jobs/intended";
        String email = "caller@" + TestFixtures.uniqueName("scheduler-caller") + ".iam.gserviceaccount.com";
        String credential = token(email);
        try (CloudSchedulerClient admin = client(true, null); HttpClient http = HttpClient.newHttpClient()) {
            grant(project, email, "roles/cloudscheduler.admin", null);
            assertThat(sendJson(http, credential, "POST", parent + "/jobs",
                    "{\"name\":\"" + name + "\",\"schedule\":\"0 0 1 1 *\","
                            + "\"httpTarget\":{\"uri\":\"https://example.invalid\",\"oidcToken\":null,\"oauthToken\":null}}")
                    .statusCode()).isEqualTo(200);
            for (String field : List.of("", "\"name\":null,", "\"name\":\"\",")) {
                HttpResponse<String> response = sendJson(http, credential, "PATCH", name + "?updateMask=description",
                        "{" + field + "\"description\":\"updated\"}");
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                assertThat(admin.getJob(name).getDescription()).isEqualTo("updated");
            }
            admin.deleteJob(name);
        }
    }

    private static HttpResponse<String> sendJson(HttpClient http, String credential, String method,
            String resource, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(TestFixtures.endpoint() + "/v1/" + resource))
                .header("Authorization", "Bearer " + credential).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Job deliveryJob(String name, String topic, String payload) {
        return Job.newBuilder().setName(name).setSchedule("0 0 1 1 *")
                .setPubsubTarget(PubsubTarget.newBuilder().setTopicName(topic)
                        .setData(ByteString.copyFromUtf8(payload))).build();
    }

    @Test
    void restBodyNameCannotRedirectAnAuthorizedUpdateToASibling() throws Exception {
        String project = TestFixtures.uniqueName("scheduler-body-name");
        String parent = "projects/" + project + "/locations/us-central1";
        String allowed = parent + "/jobs/allowed";
        String sibling = parent + "/jobs/unrelated";
        String email = "caller@" + project + ".iam.gserviceaccount.com";
        String token = token(email);
        try (CloudSchedulerClient admin = client(true, null); HttpClient http = HttpClient.newHttpClient()) {
            admin.createJob(parent, job(allowed));
            admin.createJob(parent, job(sibling));
            grant(project, email, "roles/cloudscheduler.admin", "resource.name == '" + allowed + "'");
            Job before = admin.getJob(sibling);
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(TestFixtures.endpoint()
                            + "/v1/" + allowed + "?updateMask=description"))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(JsonFormat.printer().print(
                            Job.newBuilder().setName(sibling).setDescription("redirected").build()))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(enforce ? 403 : 400);
            if (enforce) {
                assertThat(response.body()).contains("PERMISSION_DENIED");
                assertThat(admin.getJob(sibling)).isEqualTo(before);
            }
            assertThat(admin.getJob(sibling)).isEqualTo(before);
        }
    }

    private void denied(Runnable action) {
        if (enforce) {
            assertThatThrownBy(action::run).isInstanceOf(PermissionDeniedException.class);
        } else {
            action.run();
        }
    }

    private static Job job(String name) {
        // The next annual occurrence keeps background dispatch out of these management checks.
        return Job.newBuilder().setName(name).setSchedule("0 0 1 1 *").setAppEngineHttpTarget(AppEngineHttpTarget.newBuilder()
                .setRelativeUri("/local-recorded-attempt")).build();
    }

    private static UpdateJobRequest update(String name) {
        return UpdateJobRequest.newBuilder().setJob(Job.newBuilder().setName(name).setDescription("updated"))
                .setUpdateMask(FieldMask.newBuilder().addPaths("description")).build();
    }

    private static CloudSchedulerClient client(boolean rest, String token) throws Exception {
        CloudSchedulerSettings.Builder settings;
        if (rest) {
            settings = CloudSchedulerSettings.newHttpJsonBuilder().setEndpoint(TestFixtures.endpoint());
        } else {
            URI endpoint = URI.create(TestFixtures.endpoint());
            Metadata headers = new Metadata();
            if (token != null) {
                headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
            }
            settings = CloudSchedulerSettings.newBuilder().setTransportChannelProvider(
                    InstantiatingGrpcChannelProvider.newBuilder().setEndpoint(endpoint.getHost() + ":" + endpoint.getPort())
                            .setChannelConfigurator(builder -> builder.usePlaintext()
                                    .intercept(MetadataUtils.newAttachHeadersInterceptor(headers))).build());
        }
        settings.setCredentialsProvider(token == null || !rest ? NoCredentialsProvider.create()
                : FixedCredentialsProvider.create(GoogleCredentials.create(
                        new AccessToken(token, Date.from(Instant.now().plusSeconds(300))))));
        return CloudSchedulerClient.create(settings.build());
    }

    private static String token(String email) throws Exception {
        try (IamCredentialsClient credentials = TestFixtures.iamCredentialsClient()) {
            return credentials.generateAccessToken("projects/-/serviceAccounts/" + email, List.of(),
                    List.of("https://www.googleapis.com/auth/cloud-platform"),
                    Duration.newBuilder().setSeconds(300).build()).getAccessToken();
        }
    }

    private static void grant(String project, String email, String role, String expression) throws Exception {
        String condition = expression == null ? "" : ",\"condition\":{\"title\":\"scope\",\"expression\":\"" + expression + "\"}";
        String body = "{\"policy\":{\"version\":3,\"bindings\":[{\"role\":\"" + role
                + "\",\"members\":[\"serviceAccount:" + email + "\"]" + condition + "}]}}";
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(TestFixtures.endpoint()
                            + "/v1/projects/" + project + ":setIamPolicy"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        }
    }
}
