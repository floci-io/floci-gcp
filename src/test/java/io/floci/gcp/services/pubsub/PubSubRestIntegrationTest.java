package io.floci.gcp.services.pubsub;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

@QuarkusTest
class PubSubRestIntegrationTest {

    @Test
    void terraformStylePubSubRestCrudAndPublishPullWork() {
        String project = "pubsub-rest-it";
        String topic = "orders-events";
        String subscription = "orders-events-to-bigquery";
        String topicName = "projects/" + project + "/topics/" + topic;
        String subscriptionName = "projects/" + project + "/subscriptions/" + subscription;

        given()
                .contentType("application/json")
                .body("""
                        {
                          "labels": {"app": "orders", "env": "local"},
                          "messageRetentionDuration": "604800s"
                        }
                        """)
                .when().put("/v1/projects/" + project + "/topics/" + topic)
                .then()
                .statusCode(200)
                .body("name", equalTo(topicName))
                .body("labels.app", equalTo("orders"))
                .body("messageRetentionDuration", equalTo("604800s"));

        given()
                .when().get("/v1/projects/" + project + "/topics")
                .then()
                .statusCode(200)
                .body("topics.name", hasItem(topicName));

        given()
                .contentType("application/json")
                .body("""
                        {
                          "topic": {
                            "labels": {"app": "orders", "env": "patched"},
                            "messageRetentionDuration": "1200s"
                          },
                          "updateMask": "labels,messageRetentionDuration"
                        }
                        """)
                .when().patch("/v1/projects/" + project + "/topics/" + topic)
                .then()
                .statusCode(200)
                .body("labels.env", equalTo("patched"))
                .body("messageRetentionDuration", equalTo("1200s"));

        given()
                .contentType("application/json")
                .body("""
                        {
                          "topic": "%s",
                          "ackDeadlineSeconds": 10,
                          "messageRetentionDuration": "604800s",
                          "labels": {"app": "orders", "sink": "bigquery"},
                          "bigqueryConfig": {
                            "table": "%s.orders_analytics.order_events",
                            "useTableSchema": true,
                            "writeMetadata": false,
                            "dropUnknownFields": true
                          }
                        }
                        """.formatted(topicName, project))
                .when().put("/v1/projects/" + project + "/subscriptions/" + subscription)
                .then()
                .statusCode(200)
                .body("name", equalTo(subscriptionName))
                .body("topic", equalTo(topicName))
                .body("ackDeadlineSeconds", equalTo(10))
                .body("labels.sink", equalTo("bigquery"))
                .body("bigqueryConfig.table", equalTo(project + ".orders_analytics.order_events"))
                .body("bigqueryConfig.state", equalTo("ACTIVE"));

        given()
                .contentType("application/json")
                .queryParam("updateMask", "labels,ackDeadlineSeconds")
                .body("""
                        {
                          "ackDeadlineSeconds": 20,
                          "labels": {"app": "orders", "sink": "worker"}
                        }
                        """)
                .when().patch("/v1/projects/" + project + "/subscriptions/" + subscription)
                .then()
                .statusCode(200)
                .body("ackDeadlineSeconds", equalTo(20))
                .body("labels.sink", equalTo("worker"))
                .body("bigqueryConfig.table", equalTo(project + ".orders_analytics.order_events"));

        given()
                .contentType("application/json")
                .body("""
                        {
                          "subscription": {
                            "ackDeadlineSeconds": 30,
                            "labels": {"app": "orders", "sink": "canonical"},
                            "retainAckedMessages": true,
                            "bigqueryConfig": {
                              "table": "%s.orders_analytics.order_events_v2",
                              "useTableSchema": true
                            }
                          },
                          "updateMask": "ackDeadlineSeconds,labels,retainAckedMessages,bigqueryConfig"
                        }
                        """.formatted(project))
                .when().patch("/v1/projects/" + project + "/subscriptions/" + subscription)
                .then()
                .statusCode(200)
                .body("ackDeadlineSeconds", equalTo(30))
                .body("labels.sink", equalTo("canonical"))
                .body("retainAckedMessages", equalTo(true))
                .body("bigqueryConfig.table", equalTo(project + ".orders_analytics.order_events_v2"))
                .body("bigqueryConfig.state", equalTo("ACTIVE"));

        String payload = Base64.getEncoder().encodeToString("hello from rest".getBytes());
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("""
                        {
                          "messages": [
                            {"data": "%s", "attributes": {"event_type": "created"}}
                          ]
                        }
                        """.formatted(payload))
                .when().post("/v1/projects/" + project + "/topics/" + topic + ":publish")
                .then()
                .statusCode(200)
                .body("messageIds.size()", equalTo(1));

        String ackId = given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"maxMessages\": 1}")
                .when().post("/v1/projects/" + project + "/subscriptions/" + subscription + ":pull")
                .then()
                .statusCode(200)
                .body("receivedMessages.size()", equalTo(1))
                .body("receivedMessages[0].message.data", equalTo(payload))
                .body("receivedMessages[0].message.attributes.event_type", equalTo("created"))
                .extract().path("receivedMessages[0].ackId");

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"ackIds\": [\"" + ackId + "\"]}")
                .when().post("/v1/projects/" + project + "/subscriptions/" + subscription + ":acknowledge")
                .then()
                .statusCode(200)
                .body("$", anEmptyMap());

        given()
                .when().get("/v1/projects/" + project + "/subscriptions")
                .then()
                .statusCode(200)
                .body("subscriptions.name", hasItem(subscriptionName));

        given()
                .when().delete("/v1/projects/" + project + "/subscriptions/" + subscription)
                .then()
                .statusCode(200)
                .body("$", anEmptyMap());

        given()
                .when().delete("/v1/projects/" + project + "/topics/" + topic)
                .then()
                .statusCode(200)
                .body("$", anEmptyMap());
    }
}
