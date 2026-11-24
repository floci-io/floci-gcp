package io.floci.gcp.services.cloudkms;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * CryptoKey fields beyond purpose and algorithm over REST: rotationPeriod, nextRotationTime,
 * destroyScheduledDuration and labels are stored on create and returned by create and get, and
 * updateCryptoKey applies the fields its update mask names.
 */
@QuarkusTest
class CloudKmsCryptoKeyRestIntegrationTest {

    private static final String LOCATION = "/v1/projects/kms-fields-rest-it/locations/us-east1";
    private static final String KEY_RING = LOCATION + "/keyRings/ring";

    private static RequestSpecification json() {
        return given().contentType("application/json");
    }

    private static String createKey(String id, String body) {
        json().queryParam("keyRingId", "ring").body("{}").when().post(LOCATION + "/keyRings");
        json().queryParam("cryptoKeyId", id).body(body)
                .when().post(KEY_RING + "/cryptoKeys")
                .then().statusCode(200);
        return KEY_RING + "/cryptoKeys/" + id;
    }

    private static void createRejected(String id, String body) {
        json().queryParam("keyRingId", "ring").body("{}").when().post(LOCATION + "/keyRings");
        json().queryParam("cryptoKeyId", id).body(body)
                .when().post(KEY_RING + "/cryptoKeys")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
        given().when().get(KEY_RING + "/cryptoKeys/" + id).then().statusCode(404);
    }

    @Test
    void createStoresRotationDestroyDurationAndLabels() {
        json().queryParam("keyRingId", "ring").body("{}").when().post(LOCATION + "/keyRings");
        json().queryParam("cryptoKeyId", "scheduled")
                .body("{\"purpose\": \"ENCRYPT_DECRYPT\", \"rotationPeriod\": \"7776000s\","
                        + " \"nextRotationTime\": \"2027-01-01T00:00:00Z\", \"destroyScheduledDuration\": \"604800s\","
                        + " \"labels\": {\"team\": \"repro\"}}")
                .when().post(KEY_RING + "/cryptoKeys")
                .then().statusCode(200)
                .body("rotationPeriod", equalTo("7776000s"))
                .body("nextRotationTime", equalTo("2027-01-01T00:00:00Z"))
                .body("destroyScheduledDuration", equalTo("604800s"))
                .body("labels.team", equalTo("repro"));

        given().when().get(KEY_RING + "/cryptoKeys/scheduled")
                .then().statusCode(200)
                .body("rotationPeriod", equalTo("7776000s"))
                .body("nextRotationTime", equalTo("2027-01-01T00:00:00Z"))
                .body("destroyScheduledDuration", equalTo("604800s"))
                .body("labels.team", equalTo("repro"));
    }

    @Test
    void createWithoutDestroyScheduledDurationReportsTheThirtyDayDefault() {
        String key = createKey("defaults", "{\"purpose\": \"ENCRYPT_DECRYPT\"}");

        given().when().get(key)
                .then().statusCode(200)
                .body("destroyScheduledDuration", equalTo("2592000s"))
                .body("rotationPeriod", nullValue())
                .body("nextRotationTime", nullValue());
    }

    @Test
    void createRejectsAnInvalidRotationSchedule() {
        createRejected("sign-rotation", "{\"purpose\": \"ASYMMETRIC_SIGN\","
                + " \"versionTemplate\": {\"algorithm\": \"EC_SIGN_P256_SHA256\"},"
                + " \"rotationPeriod\": \"86400s\", \"nextRotationTime\": \"2027-01-01T00:00:00Z\"}");
        createRejected("no-next", "{\"purpose\": \"ENCRYPT_DECRYPT\", \"rotationPeriod\": \"86400s\"}");
        createRejected("too-short", "{\"purpose\": \"ENCRYPT_DECRYPT\", \"rotationPeriod\": \"86399s\","
                + " \"nextRotationTime\": \"2027-01-01T00:00:00Z\"}");
        createRejected("too-long", "{\"purpose\": \"ENCRYPT_DECRYPT\", \"rotationPeriod\": \"3153600001s\","
                + " \"nextRotationTime\": \"2027-01-01T00:00:00Z\"}");
        createRejected("bad-duration", "{\"purpose\": \"ENCRYPT_DECRYPT\", \"destroyScheduledDuration\": \"7d\"}");
        createRejected("bad-time", "{\"purpose\": \"ENCRYPT_DECRYPT\", \"rotationPeriod\": \"86400s\","
                + " \"nextRotationTime\": \"tomorrow\"}");
        createRejected("bad-label", "{\"purpose\": \"ENCRYPT_DECRYPT\", \"labels\": {\"n\": 1}}");
    }

    @Test
    void createAcceptsTheRotationPeriodBounds() {
        createKey("one-day", "{\"purpose\": \"ENCRYPT_DECRYPT\", \"rotationPeriod\": \"86400s\","
                + " \"nextRotationTime\": \"2027-01-01T00:00:00Z\"}");
        createKey("hundred-years", "{\"purpose\": \"ENCRYPT_DECRYPT\", \"rotationPeriod\": \"3153600000s\","
                + " \"nextRotationTime\": \"2027-01-01T00:00:00Z\"}");
        createKey("next-only", "{\"purpose\": \"ENCRYPT_DECRYPT\", \"nextRotationTime\": \"2027-01-01T00:00:00Z\"}");
    }

    @Test
    void updateAppliesTheMaskedRotationFieldsAndClearsThemWhenAbsent() {
        String key = createKey("rotate", "{\"purpose\": \"ENCRYPT_DECRYPT\", \"rotationPeriod\": \"7776000s\","
                + " \"nextRotationTime\": \"2027-01-01T00:00:00Z\", \"labels\": {\"team\": \"a\"}}");

        json().queryParam("updateMask", "rotationPeriod,nextRotationTime")
                .body("{\"rotationPeriod\": \"172800s\", \"nextRotationTime\": \"2027-02-01T00:00:00Z\"}")
                .when().patch(key)
                .then().statusCode(200)
                .body("rotationPeriod", equalTo("172800s"))
                .body("nextRotationTime", equalTo("2027-02-01T00:00:00Z"))
                .body("labels.team", equalTo("a"));

        json().queryParam("updateMask", "rotationPeriod,nextRotationTime")
                .body("{}")
                .when().patch(key)
                .then().statusCode(200)
                .body("rotationPeriod", nullValue())
                .body("nextRotationTime", nullValue())
                .body("labels.team", equalTo("a"));
    }

    @Test
    void updateWithALabelsMaskAndNoLabelsClearsThem() {
        String key = createKey("unlabel", "{\"purpose\": \"ENCRYPT_DECRYPT\"}");
        json().queryParam("updateMask", "labels").body("{\"labels\": {\"team\": \"a\"}}")
                .when().patch(key)
                .then().statusCode(200)
                .body("labels.team", equalTo("a"));

        json().queryParam("updateMask", "labels").body("{}")
                .when().patch(key)
                .then().statusCode(200)
                .body("labels", nullValue());
    }

    @Test
    void aRejectedUpdateLeavesTheKeyUnchanged() {
        String key = createKey("keep", "{\"purpose\": \"ENCRYPT_DECRYPT\", \"rotationPeriod\": \"7776000s\","
                + " \"nextRotationTime\": \"2027-01-01T00:00:00Z\", \"labels\": {\"team\": \"a\"}}");

        json().queryParam("updateMask", "labels,rotationPeriod")
                .body("{\"labels\": {\"team\": \"b\"}, \"rotationPeriod\": \"3600s\"}")
                .when().patch(key)
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));

        given().when().get(key)
                .then().statusCode(200)
                .body("rotationPeriod", equalTo("7776000s"))
                .body("labels.team", equalTo("a"));
    }

    @Test
    void updateDoesNotChangeTheImmutableDestroyScheduledDuration() {
        String key = createKey("immutable", "{\"purpose\": \"ENCRYPT_DECRYPT\", \"destroyScheduledDuration\": \"604800s\"}");

        json().queryParam("updateMask", "destroyScheduledDuration,labels")
                .body("{\"destroyScheduledDuration\": \"86400s\", \"labels\": {\"team\": \"a\"}}")
                .when().patch(key)
                .then().statusCode(200)
                .body("destroyScheduledDuration", equalTo("604800s"))
                .body("labels.team", equalTo("a"));
    }

    @Test
    void updateWithoutAMaskAppliesTheFieldsTheBodySets() {
        String key = createKey("no-mask", "{\"purpose\": \"ENCRYPT_DECRYPT\", \"labels\": {\"team\": \"a\"}}");

        json().body("{\"rotationPeriod\": \"86400s\", \"nextRotationTime\": \"2027-01-01T00:00:00Z\"}")
                .when().patch(key)
                .then().statusCode(200)
                .body("rotationPeriod", equalTo("86400s"))
                .body("labels.team", equalTo("a"));
    }

    @Test
    void createAcceptsThePurposeAsItsNumber() {
        String key = createKey("purpose-number", "{\"purpose\": 1}");

        given().when().get(key)
                .then().statusCode(200)
                .body("purpose", equalTo("ENCRYPT_DECRYPT"))
                .body("versionTemplate.algorithm", equalTo("GOOGLE_SYMMETRIC_ENCRYPTION"))
                .body("primary.state", equalTo("ENABLED"));
    }

    @Test
    void createAcceptsTheVersionTemplateEnumsAsNumbers() {
        String key = createKey("template-numbers",
                "{\"purpose\": 5, \"versionTemplate\": {\"algorithm\": 12, \"protectionLevel\": 1}}");

        given().when().get(key)
                .then().statusCode(200)
                .body("purpose", equalTo("ASYMMETRIC_SIGN"))
                .body("versionTemplate.algorithm", equalTo("EC_SIGN_P256_SHA256"))
                .body("versionTemplate.protectionLevel", equalTo("SOFTWARE"));
        given().when().get(key + "/cryptoKeyVersions/1")
                .then().statusCode(200)
                .body("algorithm", equalTo("EC_SIGN_P256_SHA256"));
    }

    @Test
    void updateAcceptsTheVersionTemplateAlgorithmAsItsNumber() {
        String key = createKey("update-algorithm-number",
                "{\"purpose\": \"MAC\", \"versionTemplate\": {\"algorithm\": \"HMAC_SHA256\"}}");

        json().queryParam("updateMask", "versionTemplate.algorithm")
                .body("{\"versionTemplate\": {\"algorithm\": 35}}")
                .when().patch(key)
                .then().statusCode(200)
                .body("versionTemplate.algorithm", equalTo("HMAC_SHA512"));
    }

    @Test
    void updateVersionAcceptsTheStateAsItsNumber() {
        String version = createKey("state-number", "{\"purpose\": \"ENCRYPT_DECRYPT\"}") + "/cryptoKeyVersions/1";

        json().body("{\"state\": 2}")
                .when().patch(version)
                .then().statusCode(200)
                .body("state", equalTo("DISABLED"));
        json().body("{\"state\": 1}")
                .when().patch(version)
                .then().statusCode(200)
                .body("state", equalTo("ENABLED"));
    }

    @Test
    void createRejectsAnEnumNumberOrNameTheEnumDoesNotDefine() {
        json().queryParam("keyRingId", "ring").body("{}").when().post(LOCATION + "/keyRings");
        json().queryParam("cryptoKeyId", "purpose-unknown").body("{\"purpose\": 99}")
                .when().post(KEY_RING + "/cryptoKeys")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"))
                .body("error.message", allOf(containsString("crypto_key.purpose"), containsString("99")));

        createRejected("purpose-fraction", "{\"purpose\": 1.5}");
        createRejected("purpose-name-unknown", "{\"purpose\": \"NOT_A_PURPOSE\"}");
        createRejected("algorithm-unknown", "{\"purpose\": 1, \"versionTemplate\": {\"algorithm\": 9999}}");
        createRejected("algorithm-boolean", "{\"purpose\": 1, \"versionTemplate\": {\"algorithm\": true}}");
        createRejected("protection-unknown",
                "{\"purpose\": 1, \"versionTemplate\": {\"algorithm\": 1, \"protectionLevel\": 99}}");
    }

    @Test
    void updateVersionRejectsAStateNumberTheEnumDoesNotDefine() {
        String version = createKey("state-unknown", "{\"purpose\": \"ENCRYPT_DECRYPT\"}") + "/cryptoKeyVersions/1";

        json().body("{\"state\": 99}")
                .when().patch(version)
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
        given().when().get(version)
                .then().statusCode(200)
                .body("state", equalTo("ENABLED"));
    }
}
