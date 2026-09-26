package io.floci.gcp.test;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.cloudresourcemanager.CloudResourceManager;
import com.google.api.services.cloudresourcemanager.model.Binding;
import com.google.api.services.cloudresourcemanager.model.Expr;
import com.google.api.services.cloudresourcemanager.model.GetIamPolicyRequest;
import com.google.api.services.cloudresourcemanager.model.Policy;
import com.google.api.services.cloudresourcemanager.model.SetIamPolicyRequest;
import com.google.api.services.cloudresourcemanager.model.TestIamPermissionsRequest;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against disabled mode by default; set FLOCI_GCP_IAM_TEST_ENFORCEMENT=true for an enforcing server. */
class IamEnforcementTest {
    private final boolean enforce = Boolean.parseBoolean(System.getenv("FLOCI_GCP_IAM_TEST_ENFORCEMENT"));
    private String project;
    private String member;
    private CloudResourceManager setup;
    private CloudResourceManager reader;

    @BeforeEach
    void setup() throws Exception {
        project = TestFixtures.uniqueName("iam-sdk");
        String email = "reader@" + project + ".iam.gserviceaccount.com";
        member = "serviceAccount:" + email;
        HttpRequest request = HttpRequest.newBuilder(URI.create(TestFixtures.endpoint()
                        + "/v1/projects/-/serviceAccounts/" + email + ":generateAccessToken"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"scope\":[\"https://www.googleapis.com/auth/cloud-platform\"]}"))
                .build();
        String token;
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            token = JsonParser.parseString(response.body()).getAsJsonObject().get("accessToken").getAsString();
        }
        setup = client(null);
        reader = client(token);
    }

    @Test
    void browserGrantAllowsReadingButDeniesPolicyMutationAndSiblingProject() throws Exception {
        setup.projects().setIamPolicy(project, grant(null)).execute();
        assertThat(reader.projects().get(project).execute().getProjectId()).isEqualTo(project);
        assertThat(reader.projects().getIamPolicy(project, new GetIamPolicyRequest()).execute().getBindings())
                .extracting(Binding::getRole).containsExactly("roles/browser");
        denied(() -> reader.projects().setIamPolicy(project, grant(null)).execute());
        denied(() -> reader.projects().get(project + "-sibling").execute());
        List<String> permissions = List.of("resourcemanager.projects.get", "resourcemanager.projects.setIamPolicy");
        assertThat(reader.projects().testIamPermissions(project,
                new TestIamPermissionsRequest().setPermissions(permissions)).execute().getPermissions())
                .containsExactlyElementsOf(enforce ? List.of(permissions.getFirst()) : permissions);
    }

    @Test
    void projectConditionExcludesThenAllowsTheCaller() throws Exception {
        setup.projects().setIamPolicy(project, grant("resource.name == 'projects/excluded'")).execute();
        denied(() -> reader.projects().get(project).execute());
        setup.projects().setIamPolicy(project, grant("resource.name == 'projects/" + project + "'")).execute();
        assertThat(reader.projects().get(project).execute().getProjectId()).isEqualTo(project);
    }

    private CloudResourceManager client(String token) {
        return new CloudResourceManager.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance(),
                request -> {
                    if (token != null) {
                        request.getHeaders().setAuthorization("Bearer " + token);
                    }
                }).setRootUrl(TestFixtures.endpoint() + "/").setApplicationName("floci-iam-compat").build();
    }

    private SetIamPolicyRequest grant(String expression) {
        Binding binding = new Binding().setRole("roles/browser").setMembers(List.of(member));
        if (expression != null) {
            binding.setCondition(new Expr().setTitle("scope").setExpression(expression));
        }
        return new SetIamPolicyRequest().setPolicy(new Policy().setVersion(expression == null ? 1 : 3)
                .setBindings(List.of(binding)));
    }

    private void denied(IoOperation operation) throws IOException {
        if (enforce) {
            assertThatThrownBy(operation::run).isInstanceOfSatisfying(GoogleJsonResponseException.class,
                    error -> {
                        assertThat(error.getStatusCode()).isEqualTo(403);
                        assertThat(error.getDetails().get("status")).isEqualTo("PERMISSION_DENIED");
                    });
        } else {
            operation.run();
        }
    }

    @FunctionalInterface
    private interface IoOperation {
        void run() throws IOException;
    }
}
