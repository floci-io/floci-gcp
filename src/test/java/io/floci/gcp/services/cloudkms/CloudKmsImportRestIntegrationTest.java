package io.floci.gcp.services.cloudkms;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/** Import jobs and ImportCryptoKeyVersion on the KMS REST transport. */
@QuarkusTest
class CloudKmsImportRestIntegrationTest {

    private static final String PARENT = "/v1/projects/kms-import-rest-it/locations/global";
    private static final String KEY_RING = PARENT + "/keyRings/ring";
    private static final String KEY_RING_NAME = KEY_RING.substring("/v1/".length());

    /** Custom-method paths carry a ':', which RestAssured would otherwise percent-encode. */
    private static RequestSpecification json() {
        return given().urlEncodingEnabled(false).contentType("application/json");
    }

    private static void ensureKeyRing() {
        json().queryParam("keyRingId", "ring").body("{}").when().post(PARENT + "/keyRings");
    }

    private static String createImportJob(String id) {
        ensureKeyRing();
        return json().queryParam("importJobId", id)
                .body("{\"importMethod\": \"RSA_OAEP_3072_SHA256_AES_256\", \"protectionLevel\": \"SOFTWARE\"}")
                .when().post(KEY_RING + "/importJobs")
                .then().statusCode(200)
                .extract().path("publicKey.pem");
    }

    private static String createImportOnlyKey(String id) {
        ensureKeyRing();
        json().queryParam("cryptoKeyId", id).queryParam("skipInitialVersionCreation", true)
                .body("{\"purpose\": \"ENCRYPT_DECRYPT\", \"importOnly\": true}")
                .when().post(KEY_RING + "/cryptoKeys")
                .then().statusCode(200)
                .body("importOnly", equalTo(true));
        return KEY_RING + "/cryptoKeys/" + id;
    }

    @Test
    void createGetAndListImportJobs() {
        String pem = createImportJob("listed");
        String name = KEY_RING_NAME + "/importJobs/listed";

        assertThat(pem, startsWith("-----BEGIN PUBLIC KEY-----"));
        given().when().get(KEY_RING + "/importJobs/listed")
                .then().statusCode(200)
                .body("name", equalTo(name))
                .body("state", equalTo("ACTIVE"))
                .body("importMethod", equalTo("RSA_OAEP_3072_SHA256_AES_256"))
                .body("protectionLevel", equalTo("SOFTWARE"))
                .body("expireTime", notNullValue())
                .body("publicKey.pem", equalTo(pem));
        given().when().get(KEY_RING + "/importJobs")
                .then().statusCode(200)
                .body("importJobs.name", hasItem(name));
        json().queryParam("importJobId", "listed")
                .body("{\"importMethod\": \"RSA_OAEP_3072_SHA256_AES_256\", \"protectionLevel\": \"SOFTWARE\"}")
                .when().post(KEY_RING + "/importJobs")
                .then().statusCode(409)
                .body("error.status", equalTo("ALREADY_EXISTS"));
    }

    @Test
    void importedKeyEncryptsAndDecrypts() throws Exception {
        String pem = createImportJob("job-wrapped");
        String key = createImportOnlyKey("imported");

        json().body("{\"algorithm\": \"GOOGLE_SYMMETRIC_ENCRYPTION\","
                        + " \"importJob\": \"" + KEY_RING_NAME + "/importJobs/job-wrapped\","
                        + " \"wrappedKey\": \"" + b64(rsaAesWrap(pem, aes256())) + "\"}")
                .when().post(key + "/cryptoKeyVersions:import")
                .then().statusCode(200)
                .body("state", equalTo("ENABLED"))
                .body("importJob", equalTo(KEY_RING_NAME + "/importJobs/job-wrapped"))
                .body("importTime", notNullValue());

        byte[] plaintext = "imported over rest".getBytes(StandardCharsets.UTF_8);
        String ciphertext = json().body("{\"plaintext\": \"" + b64(plaintext) + "\"}")
                .when().post(key + ":encrypt")
                .then().statusCode(200)
                .extract().path("ciphertext");
        json().body("{\"ciphertext\": \"" + ciphertext + "\"}")
                .when().post(key + ":decrypt")
                .then().statusCode(200)
                .body("plaintext", equalTo(b64(plaintext)));
    }

    @Test
    void importAcceptsTheDeprecatedRsaAesWrappedKeyField() throws Exception {
        String pem = createImportJob("job-deprecated");
        String key = createImportOnlyKey("imported-deprecated");

        json().body("{\"algorithm\": \"GOOGLE_SYMMETRIC_ENCRYPTION\","
                        + " \"importJob\": \"" + KEY_RING_NAME + "/importJobs/job-deprecated\","
                        + " \"rsaAesWrappedKey\": \"" + b64(rsaAesWrap(pem, aes256())) + "\"}")
                .when().post(key + "/cryptoKeyVersions:import")
                .then().statusCode(200)
                .body("state", equalTo("ENABLED"));
    }

    @Test
    void failedImportCannotBeEnabledOrDestroyed() throws Exception {
        createImportJob("job-target");
        String otherPem = createImportJob("job-other");
        String key = createImportOnlyKey("imported-failed");

        String version = json().body("{\"algorithm\": \"GOOGLE_SYMMETRIC_ENCRYPTION\","
                        + " \"importJob\": \"" + KEY_RING_NAME + "/importJobs/job-target\","
                        + " \"wrappedKey\": \"" + b64(rsaAesWrap(otherPem, aes256())) + "\"}")
                .when().post(key + "/cryptoKeyVersions:import")
                .then().statusCode(200)
                .body("state", equalTo("IMPORT_FAILED"))
                .body("importFailureReason", notNullValue())
                .extract().path("name");
        String versionPath = "/v1/" + version;

        json().body("{\"state\": \"ENABLED\"}")
                .when().patch(versionPath)
                .then().statusCode(400)
                .body("error.status", equalTo("FAILED_PRECONDITION"));
        json().body("{}")
                .when().post(versionPath + ":destroy")
                .then().statusCode(400)
                .body("error.status", equalTo("FAILED_PRECONDITION"));
        given().when().get(versionPath)
                .then().statusCode(200)
                .body("state", equalTo("IMPORT_FAILED"));
    }

    private static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    private static byte[] aes256() throws Exception {
        KeyGenerator gen = KeyGenerator.getInstance("AES");
        gen.init(256);
        return gen.generateKey().getEncoded();
    }

    /** Client-side wrapping for {@code RSA_OAEP_3072_SHA256_AES_256}, as the GCP import docs describe it. */
    private static byte[] rsaAesWrap(String pem, byte[] targetKey) throws Exception {
        PublicKey wrappingKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(
                Base64.getDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""))));
        byte[] ephemeral = aes256();
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.ENCRYPT_MODE, wrappingKey,
                new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        byte[] wrappedEphemeral = rsa.doFinal(ephemeral);
        Cipher kwp = Cipher.getInstance("AES/KWP/NoPadding");
        kwp.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(ephemeral, "AES"));
        byte[] wrappedTarget = kwp.doFinal(targetKey);
        byte[] out = new byte[wrappedEphemeral.length + wrappedTarget.length];
        System.arraycopy(wrappedEphemeral, 0, out, 0, wrappedEphemeral.length);
        System.arraycopy(wrappedTarget, 0, out, wrappedEphemeral.length, wrappedTarget.length);
        return out;
    }
}
