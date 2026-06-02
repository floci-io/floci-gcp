package io.floci.gcp.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IamTest {

    private static final String PROJECT_ID = TestFixtures.projectId();
    private static final String ACCOUNT_ID = TestFixtures.uniqueName("java-sa");

    private static final HttpClient http = HttpClient.newHttpClient();
    private static final ObjectMapper json = new ObjectMapper();

    private static String email;
    private static String createdKeyId;

    @BeforeAll
    static void init() {
        email = ACCOUNT_ID + "@" + PROJECT_ID + ".iam.gserviceaccount.com";
    }

    @AfterAll
    static void tearDown() throws Exception {
        delete("/v1/projects/" + PROJECT_ID + "/serviceAccounts/" + email);
    }

    @Test
    @Order(1)
    void createServiceAccount() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "accountId", ACCOUNT_ID,
                "serviceAccount", Map.of("displayName", "Java Test SA")));

        JsonNode resp = post("/v1/projects/" + PROJECT_ID + "/serviceAccounts", body);

        assertThat(resp.path("email").asText()).isEqualTo(email);
        assertThat(resp.path("projectId").asText()).isEqualTo(PROJECT_ID);
        assertThat(resp.path("name").asText()).contains(ACCOUNT_ID);
    }

    @Test
    @Order(2)
    void getServiceAccount() throws Exception {
        JsonNode resp = get("/v1/projects/" + PROJECT_ID + "/serviceAccounts/" + email);

        assertThat(resp.path("email").asText()).isEqualTo(email);
        assertThat(resp.path("displayName").asText()).isEqualTo("Java Test SA");
    }

    @Test
    @Order(3)
    void listServiceAccounts() throws Exception {
        JsonNode resp = get("/v1/projects/" + PROJECT_ID + "/serviceAccounts");

        boolean found = false;
        for (JsonNode account : resp.path("accounts")) {
            if (email.equals(account.path("email").asText())) {
                found = true;
                break;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    @Order(4)
    void createServiceAccountKey() throws Exception {
        JsonNode resp = post(
                "/v1/projects/" + PROJECT_ID + "/serviceAccounts/" + email + "/keys", "{}");

        assertThat(resp.path("name").asText()).contains(email);
        assertThat(resp.path("keyId").asText()).isNotBlank();
        createdKeyId = resp.path("keyId").asText();
    }

    @Test
    @Order(5)
    void listServiceAccountKeys() throws Exception {
        JsonNode resp = get("/v1/projects/" + PROJECT_ID + "/serviceAccounts/" + email + "/keys");

        assertThat(resp.path("keys").isArray()).isTrue();
        assertThat(resp.path("keys").size()).isGreaterThanOrEqualTo(1);

        boolean found = false;
        for (JsonNode key : resp.path("keys")) {
            if (createdKeyId != null && createdKeyId.equals(key.path("keyId").asText())) {
                found = true;
                break;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    @Order(6)
    void deleteServiceAccountKey() throws Exception {
        if (createdKeyId != null) {
            delete("/v1/projects/" + PROJECT_ID + "/serviceAccounts/" + email + "/keys/" + createdKeyId);

            JsonNode resp = get("/v1/projects/" + PROJECT_ID + "/serviceAccounts/" + email + "/keys");
            for (JsonNode key : resp.path("keys")) {
                assertThat(key.path("keyId").asText()).isNotEqualTo(createdKeyId);
            }
        }
    }

    @Test
    @Order(7)
    void getIamPolicy() throws Exception {
        JsonNode resp = post(
                "/v1/projects/" + PROJECT_ID + "/serviceAccounts/" + email + ":getIamPolicy", "{}");

        assertThat(resp.path("version").asInt()).isEqualTo(1);
        assertThat(resp.path("bindings").isArray()).isTrue();
        assertThat(resp.path("bindings").size()).isEqualTo(0);
    }

    @Test
    @Order(8)
    void setAndGetIamPolicy() throws Exception {
        String binding = json.writeValueAsString(Map.of(
                "policy", Map.of(
                        "version", 1,
                        "bindings", List.of(Map.of(
                                "role", "roles/iam.serviceAccountUser",
                                "members", List.of("user:test@example.com"))))));

        JsonNode set = post(
                "/v1/projects/" + PROJECT_ID + "/serviceAccounts/" + email + ":setIamPolicy", binding);
        assertThat(set.path("bindings").size()).isEqualTo(1);
        assertThat(set.path("bindings").get(0).path("role").asText())
                .isEqualTo("roles/iam.serviceAccountUser");
    }

    @Test
    @Order(9)
    void testIamPermissions() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "permissions", List.of("iam.serviceAccounts.get", "iam.serviceAccounts.list")));

        JsonNode resp = post(
                "/v1/projects/" + PROJECT_ID + "/serviceAccounts/" + email + ":testIamPermissions", body);

        assertThat(resp.path("permissions").isArray()).isTrue();
        assertThat(resp.path("permissions").size()).isEqualTo(2);
    }

    @Test
    @Order(10)
    void updateServiceAccount() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "serviceAccount", Map.of(
                        "displayName", "Updated SA",
                        "description", "updated via patch"),
                "updateMask", "displayName,description"));

        JsonNode resp = patch(
                "/v1/projects/" + PROJECT_ID + "/serviceAccounts/" + email, body);
        assertThat(resp.path("displayName").asText()).isEqualTo("Updated SA");
        assertThat(resp.path("description").asText()).isEqualTo("updated via patch");

        JsonNode got = get("/v1/projects/" + PROJECT_ID + "/serviceAccounts/" + email);
        assertThat(got.path("displayName").asText()).isEqualTo("Updated SA");
        assertThat(got.path("description").asText()).isEqualTo("updated via patch");
    }

    @Test
    @Order(11)
    void deleteServiceAccount() throws Exception {
        delete("/v1/projects/" + PROJECT_ID + "/serviceAccounts/" + email);

        JsonNode resp = get("/v1/projects/" + PROJECT_ID + "/serviceAccounts");
        for (JsonNode account : resp.path("accounts")) {
            assertThat(account.path("email").asText()).isNotEqualTo(email);
        }
        email = null;
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private static JsonNode post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(TestFixtures.endpoint() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return json.readTree(resp.body());
    }

    private static JsonNode patch(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(TestFixtures.endpoint() + path))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return json.readTree(resp.body());
    }

    private static JsonNode get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(TestFixtures.endpoint() + path))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return json.readTree(resp.body());
    }

    private static void delete(String path) throws Exception {
        if (path.endsWith("/null")) return;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(TestFixtures.endpoint() + path))
                .DELETE()
                .build();
        http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
