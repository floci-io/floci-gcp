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
class KafkaTest {

    private static final String PROJECT = TestFixtures.projectId();
    private static final String LOCATION = "us-central1";
    private static final String CLUSTER_ID = TestFixtures.uniqueName("java-cluster");
    private static final String TOPIC_ID = TestFixtures.uniqueName("java-topic");

    private static final HttpClient http = HttpClient.newHttpClient();
    private static final ObjectMapper json = new ObjectMapper();

    private static String base() {
        return TestFixtures.endpoint() + "/v1/projects/" + PROJECT + "/locations/" + LOCATION;
    }

    @AfterAll
    static void tearDown() throws Exception {
        delete("/v1/projects/" + PROJECT + "/locations/" + LOCATION + "/clusters/" + CLUSTER_ID);
    }

    @Test
    @Order(1)
    void createCluster() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "capacityConfig", Map.of("vcpuCount", 3, "memoryBytes", 3221225472L),
                "gcpConfig", Map.of("accessConfig", Map.of("networkConfigs",
                        List.of(Map.of("subnet", "projects/test/regions/us-central1/subnetworks/default"))))));

        JsonNode resp = post("/v1/projects/" + PROJECT + "/locations/" + LOCATION + "/clusters?clusterId=" + CLUSTER_ID, body);

        assertThat(resp.path("done").asBoolean()).isTrue();
        assertThat(resp.path("response").path("name").asText()).contains(CLUSTER_ID);
        assertThat(resp.path("response").path("state").asText()).isEqualTo("ACTIVE");
    }

    @Test
    @Order(2)
    void getCluster() throws Exception {
        JsonNode resp = get("/v1/projects/" + PROJECT + "/locations/" + LOCATION + "/clusters/" + CLUSTER_ID);

        assertThat(resp.path("name").asText()).contains(CLUSTER_ID);
        assertThat(resp.path("state").asText()).isEqualTo("ACTIVE");
        assertThat(resp.path("bootstrapAddress").asText()).isNotBlank();
    }

    @Test
    @Order(3)
    void listClusters() throws Exception {
        JsonNode resp = get("/v1/projects/" + PROJECT + "/locations/" + LOCATION + "/clusters");

        boolean found = false;
        for (JsonNode c : resp.path("clusters")) {
            if (c.path("name").asText().contains(CLUSTER_ID)) {
                found = true;
                break;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    @Order(4)
    void updateCluster() throws Exception {
        String body = json.writeValueAsString(Map.of("capacityConfig", Map.of("vcpuCount", 6, "memoryBytes", 6442450944L)));
        String path = "/v1/projects/" + PROJECT + "/locations/" + LOCATION + "/clusters/" + CLUSTER_ID;

        JsonNode resp = patch(path, body);

        assertThat(resp.path("done").asBoolean()).isTrue();
        assertThat(resp.path("response").path("vcpuCount").asLong()).isEqualTo(6L);
    }

    @Test
    @Order(5)
    void createTopic() throws Exception {
        String body = json.writeValueAsString(Map.of("partitionCount", 3, "replicationFactor", 1));
        String path = "/v1/projects/" + PROJECT + "/locations/" + LOCATION
                + "/clusters/" + CLUSTER_ID + "/topics?topicId=" + TOPIC_ID;

        JsonNode resp = post(path, body);

        assertThat(resp.path("name").asText()).contains(TOPIC_ID);
        assertThat(resp.path("partitionCount").asInt()).isEqualTo(3);
    }

    @Test
    @Order(6)
    void getTopic() throws Exception {
        JsonNode resp = get("/v1/projects/" + PROJECT + "/locations/" + LOCATION
                + "/clusters/" + CLUSTER_ID + "/topics/" + TOPIC_ID);

        assertThat(resp.path("name").asText()).contains(TOPIC_ID);
        assertThat(resp.path("partitionCount").asInt()).isEqualTo(3);
    }

    @Test
    @Order(7)
    void listTopics() throws Exception {
        JsonNode resp = get("/v1/projects/" + PROJECT + "/locations/" + LOCATION
                + "/clusters/" + CLUSTER_ID + "/topics");

        boolean found = false;
        for (JsonNode t : resp.path("topics")) {
            if (t.path("name").asText().contains(TOPIC_ID)) {
                found = true;
                break;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    @Order(8)
    void updateTopic() throws Exception {
        String body = json.writeValueAsString(Map.of("partitionCount", 6));
        String path = "/v1/projects/" + PROJECT + "/locations/" + LOCATION
                + "/clusters/" + CLUSTER_ID + "/topics/" + TOPIC_ID;

        JsonNode resp = patch(path, body);

        assertThat(resp.path("partitionCount").asInt()).isEqualTo(6);
    }

    @Test
    @Order(9)
    void listConsumerGroups() throws Exception {
        JsonNode resp = get("/v1/projects/" + PROJECT + "/locations/" + LOCATION
                + "/clusters/" + CLUSTER_ID + "/consumerGroups");

        assertThat(resp.has("consumerGroups")).isTrue();
    }

    @Test
    @Order(10)
    void aclOperations() throws Exception {
        String baseAclPath = "/v1/projects/" + PROJECT + "/locations/" + LOCATION
                + "/clusters/" + CLUSTER_ID + "/acls";

        // 1. Create ACL (topic/java-topic)
        String body = json.writeValueAsString(Map.of(
                "aclEntries", List.of(Map.of("principal", "User:alice", "permissionType", "ALLOW", "operation", "READ", "host", "*"))
        ));
        JsonNode created = post(baseAclPath + "?aclId=topic/" + TOPIC_ID, body);
        assertThat(created.path("name").asText()).contains("acls/topic/" + TOPIC_ID);
        assertThat(created.path("resourceType").asText()).isEqualTo("TOPIC");
        assertThat(created.path("resourceName").asText()).isEqualTo(TOPIC_ID);
        assertThat(created.path("patternType").asText()).isEqualTo("LITERAL");
        assertThat(created.path("aclEntries").size()).isEqualTo(1);
        String etag = created.path("etag").asText();
        assertThat(etag).isNotBlank();

        // 2. Get ACL
        JsonNode retrieved = get(baseAclPath + "/topic/" + TOPIC_ID);
        assertThat(retrieved.path("name").asText()).isEqualTo(created.path("name").asText());

        // 3. List ACLs
        JsonNode listResp = get(baseAclPath);
        assertThat(listResp.path("acls").size()).isGreaterThanOrEqualTo(1);

        // 4. Update ACL with etag
        String updateBody = json.writeValueAsString(Map.of(
                "etag", etag,
                "aclEntries", List.of(Map.of("principal", "User:bob", "permissionType", "ALLOW", "operation", "WRITE", "host", "*"))
        ));
        JsonNode updated = patch(baseAclPath + "/topic/" + TOPIC_ID, updateBody);
        assertThat(updated.path("aclEntries").get(0).path("principal").asText()).isEqualTo("User:bob");
        assertThat(updated.path("etag").asText()).isNotEqualTo(etag);

        // 5. Add ACL Entry
        String addBody = json.writeValueAsString(Map.of("aclEntry", Map.of(
                "principal", "User:charlie", "permissionType", "ALLOW", "operation", "ALL", "host", "*"
        )));
        JsonNode afterAdd = post(baseAclPath + "/topic/" + TOPIC_ID + ":addAclEntry", addBody);
        assertThat(afterAdd.path("aclEntries").size()).isEqualTo(2);

        // 6. Remove ACL Entry
        String removeBody = json.writeValueAsString(Map.of("aclEntry", Map.of(
                "principal", "User:bob", "permissionType", "ALLOW", "operation", "WRITE", "host", "*"
        )));
        JsonNode afterRemove = post(baseAclPath + "/topic/" + TOPIC_ID + ":removeAclEntry", removeBody);
        assertThat(afterRemove.path("aclDeleted").asBoolean()).isFalse();

        // 7. Delete ACL
        delete(baseAclPath + "/topic/" + TOPIC_ID);
    }

    @Test
    @Order(20)
    void deleteTopic() throws Exception {
        delete("/v1/projects/" + PROJECT + "/locations/" + LOCATION
                + "/clusters/" + CLUSTER_ID + "/topics/" + TOPIC_ID);

        JsonNode resp = get("/v1/projects/" + PROJECT + "/locations/" + LOCATION
                + "/clusters/" + CLUSTER_ID + "/topics");
        for (JsonNode t : resp.path("topics")) {
            assertThat(t.path("name").asText()).doesNotContain(TOPIC_ID);
        }
    }

    @Test
    @Order(21)
    void deleteCluster() throws Exception {
        delete("/v1/projects/" + PROJECT + "/locations/" + LOCATION + "/clusters/" + CLUSTER_ID);

        JsonNode resp = get("/v1/projects/" + PROJECT + "/locations/" + LOCATION + "/clusters");
        for (JsonNode c : resp.path("clusters")) {
            assertThat(c.path("name").asText()).doesNotContain(CLUSTER_ID);
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

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
        if (path.contains("/null")) {
            return;
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(TestFixtures.endpoint() + path))
                .DELETE()
                .build();
        http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
