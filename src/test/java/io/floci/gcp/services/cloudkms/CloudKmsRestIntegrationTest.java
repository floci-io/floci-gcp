package io.floci.gcp.services.cloudkms;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.zip.CRC32C;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Request checksums on the KMS REST transport. Each optional {@code *Crc32c} field is verified
 * when present: a match sets the matching {@code verified*Crc32c} flag, a mismatch is
 * INVALID_ARGUMENT with no result, as the gRPC transport already does.
 */
@QuarkusTest
class CloudKmsRestIntegrationTest {

    private static final String KEY_RING = "/v1/projects/kms-crc-rest-it/locations/global/keyRings/ring";

    private static String crc32c(byte[] data) {
        CRC32C crc = new CRC32C();
        crc.update(data);
        return String.valueOf(crc.getValue());
    }

    /** Custom-method paths carry a ':', which RestAssured would otherwise percent-encode. */
    private static RequestSpecification json() {
        return given().urlEncodingEnabled(false).contentType("application/json");
    }

    private static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    private static String createKey(String id, String purpose, String algorithm) {
        json().queryParam("keyRingId", "ring").body("{}")
                .when().post("/v1/projects/kms-crc-rest-it/locations/global/keyRings");
        String template = algorithm != null ? ", \"versionTemplate\": {\"algorithm\": \"" + algorithm + "\"}" : "";
        json().queryParam("cryptoKeyId", id)
                .body("{\"purpose\": \"" + purpose + "\"" + template + "}")
                .when().post(KEY_RING + "/cryptoKeys")
                .then().statusCode(200);
        return KEY_RING + "/cryptoKeys/" + id;
    }

    @Test
    void encryptVerifiesPlaintextAndAadChecksums() {
        String key = createKey("encrypt", "ENCRYPT_DECRYPT", null);
        byte[] plaintext = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] aad = "context".getBytes(StandardCharsets.UTF_8);

        String ciphertext = json()
                .body("{\"plaintext\": \"" + b64(plaintext) + "\", \"plaintextCrc32c\": \"2591144780\","
                        + " \"additionalAuthenticatedData\": \"" + b64(aad) + "\","
                        + " \"additionalAuthenticatedDataCrc32c\": \"" + crc32c(aad) + "\"}")
                .when().post(key + ":encrypt")
                .then().statusCode(200)
                .body("verifiedPlaintextCrc32c", equalTo(true))
                .body("verifiedAdditionalAuthenticatedDataCrc32c", equalTo(true))
                .extract().path("ciphertext");

        json()
                .body("{\"ciphertext\": \"" + ciphertext + "\", \"additionalAuthenticatedData\": \"" + b64(aad) + "\"}")
                .when().post(key + ":decrypt")
                .then().statusCode(200)
                .body("plaintext", equalTo(b64(plaintext)));
    }

    @Test
    void encryptAcceptsEveryProto3JsonFormOfTheChecksum() {
        String key = createKey("encrypt-number", "ENCRYPT_DECRYPT", null);
        for (String crc : List.of("2591144780", "2.59114478e9", "\"2.59114478e9\"", "2591144780.0")) {
            json()
                    .body("{\"plaintext\": \"aGVsbG8=\", \"plaintextCrc32c\": " + crc + "}")
                    .when().post(key + ":encrypt")
                    .then().statusCode(200)
                    .body("verifiedPlaintextCrc32c", equalTo(true));
        }
    }

    @Test
    void encryptWithoutChecksumsReportsNothingVerified() {
        String key = createKey("encrypt-unset", "ENCRYPT_DECRYPT", null);
        json()
                .body("{\"plaintext\": \"aGVsbG8=\"}")
                .when().post(key + ":encrypt")
                .then().statusCode(200)
                .body("ciphertext", notNullValue())
                .body("verifiedPlaintextCrc32c", nullValue())
                .body("verifiedAdditionalAuthenticatedDataCrc32c", nullValue());
    }

    @Test
    void encryptRejectsAMismatchedChecksum() {
        String key = createKey("encrypt-mismatch", "ENCRYPT_DECRYPT", null);
        json()
                .body("{\"plaintext\": \"aGVsbG8=\", \"plaintextCrc32c\": \"1\"}")
                .when().post(key + ":encrypt")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"))
                .body("ciphertext", nullValue());
        json()
                .body("{\"plaintext\": \"aGVsbG8=\", \"additionalAuthenticatedData\": \"YWFk\","
                        + " \"additionalAuthenticatedDataCrc32c\": \"1\"}")
                .when().post(key + ":encrypt")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void encryptRejectsAChecksumThatIsNotAnInt64() {
        String key = createKey("encrypt-malformed", "ENCRYPT_DECRYPT", null);
        for (String crc : List.of("\"abc\"", "\"\"", "2591144780.5", "{\"value\": \"2591144780\"}")) {
            json()
                    .body("{\"plaintext\": \"aGVsbG8=\", \"plaintextCrc32c\": " + crc + "}")
                    .when().post(key + ":encrypt")
                    .then().statusCode(400)
                    .body("error.status", equalTo("INVALID_ARGUMENT"));
        }
        // An empty plaintext has CRC32C 0, so a literal that rounds to 0 must still be rejected.
        for (String crc : List.of("1e-324", "\"1e-324\"", "0.5")) {
            json()
                    .body("{\"plaintext\": \"\", \"plaintextCrc32c\": " + crc + "}")
                    .when().post(key + ":encrypt")
                    .then().statusCode(400)
                    .body("error.status", equalTo("INVALID_ARGUMENT"));
        }
    }

    @Test
    void decryptVerifiesCiphertextAndAadChecksums() {
        String key = createKey("decrypt-crc", "ENCRYPT_DECRYPT", null);
        byte[] aad = "context".getBytes(StandardCharsets.UTF_8);
        String ciphertext = json()
                .body("{\"plaintext\": \"aGVsbG8=\", \"additionalAuthenticatedData\": \"" + b64(aad) + "\"}")
                .when().post(key + ":encrypt")
                .then().statusCode(200)
                .extract().path("ciphertext");
        String ciphertextCrc = crc32c(Base64.getDecoder().decode(ciphertext));
        String request = "{\"ciphertext\": \"" + ciphertext + "\", \"additionalAuthenticatedData\": \"" + b64(aad)
                + "\", \"ciphertextCrc32c\": \"%s\", \"additionalAuthenticatedDataCrc32c\": \"%s\"}";

        json().body(request.formatted(ciphertextCrc, crc32c(aad)))
                .when().post(key + ":decrypt")
                .then().statusCode(200)
                .body("plaintext", equalTo("aGVsbG8="));
        json().body(request.formatted("1", crc32c(aad)))
                .when().post(key + ":decrypt")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"))
                .body("plaintext", nullValue());
        json().body(request.formatted(ciphertextCrc, "1"))
                .when().post(key + ":decrypt")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void bytesFieldsAcceptUrlSafeUnpaddedBase64() {
        String key = createKey("url-safe", "ENCRYPT_DECRYPT", null);
        // 0xfb 0xff encodes as "+/8=" in standard base64 and "-_8" in URL-safe unpadded form.
        byte[] plaintext = {(byte) 0xfb, (byte) 0xff};
        // The ciphertext carries a random IV, so about one in five has no '+' or '/' to convert;
        // encrypt until it does, so the decrypt below always sees a URL-safe character.
        String urlSafe = "";
        for (int attempt = 0; attempt < 50 && urlSafe.indexOf('-') < 0 && urlSafe.indexOf('_') < 0; attempt++) {
            String ciphertext = json()
                    .body("{\"plaintext\": \"-_8\"}")
                    .when().post(key + ":encrypt")
                    .then().statusCode(200)
                    .extract().path("ciphertext");
            urlSafe = ciphertext.replace('+', '-').replace('/', '_').replace("=", "");
        }
        assertTrue(urlSafe.indexOf('-') >= 0 || urlSafe.indexOf('_') >= 0, "no ciphertext with a URL-safe character");

        json().body("{\"ciphertext\": \"" + urlSafe + "\"}")
                .when().post(key + ":decrypt")
                .then().statusCode(200)
                .body("plaintext", equalTo(b64(plaintext)));
        json().body("{\"ciphertext\": \"not*base64\"}")
                .when().post(key + ":decrypt")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void asymmetricSignVerifiesTheDigestChecksum() throws Exception {
        String key = createKey("sign", "ASYMMETRIC_SIGN", "EC_SIGN_P256_SHA256");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest("payload".getBytes(StandardCharsets.UTF_8));
        String url = key + "/cryptoKeyVersions/1:asymmetricSign";

        json()
                .body("{\"digest\": {\"sha256\": \"" + b64(digest) + "\"}, \"digestCrc32c\": \"" + crc32c(digest) + "\"}")
                .when().post(url)
                .then().statusCode(200)
                .body("signature", notNullValue())
                .body("verifiedDigestCrc32c", equalTo(true));
        json()
                .body("{\"digest\": {\"sha256\": \"" + b64(digest) + "\"}, \"digestCrc32c\": \"1\"}")
                .when().post(url)
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void asymmetricSignSignsTheSha256OfData() throws Exception {
        String key = createKey("sign-data", "ASYMMETRIC_SIGN", "EC_SIGN_P256_SHA256");
        String version = key + "/cryptoKeyVersions/1";
        byte[] data = "payload".getBytes(StandardCharsets.UTF_8);

        String signature = json()
                .body("{\"data\": \"" + b64(data) + "\", \"dataCrc32c\": \"" + crc32c(data) + "\"}")
                .when().post(version + ":asymmetricSign")
                .then().statusCode(200)
                .body("verifiedDataCrc32c", equalTo(true))
                .extract().path("signature");
        String pem = given().when().get(version + "/publicKey").then().statusCode(200).extract().path("pem");
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(
                Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "")))));
        verifier.update(data);
        assertTrue(verifier.verify(Base64.getDecoder().decode(signature)), "the signature covers the data");

        json().body("{\"data\": \"" + b64(data) + "\", \"dataCrc32c\": \"1\"}")
                .when().post(version + ":asymmetricSign")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
        json().body("{}")
                .when().post(version + ":asymmetricSign")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
        json().body("{\"data\": \"not*base64\"}")
                .when().post(version + ":asymmetricSign")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void asymmetricSignRejectsDigestAndDataTogether() throws Exception {
        String key = createKey("sign-both", "ASYMMETRIC_SIGN", "EC_SIGN_P256_SHA256");
        byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);

        json()
                .body("{\"digest\": {\"sha256\": \"" + b64(digest) + "\"}, \"data\": \"" + b64(data) + "\"}")
                .when().post(key + "/cryptoKeyVersions/1:asymmetricSign")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void asymmetricDecryptVerifiesTheCiphertextChecksum() throws Exception {
        String key = createKey("decrypt", "ASYMMETRIC_DECRYPT", "RSA_DECRYPT_OAEP_2048_SHA256");
        String version = key + "/cryptoKeyVersions/1";
        String pem = given().when().get(version + "/publicKey").then().statusCode(200).extract().path("pem");
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(
                Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", ""))));
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, new OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        byte[] ciphertext = cipher.doFinal("secret".getBytes(StandardCharsets.UTF_8));

        json()
                .body("{\"ciphertext\": \"" + b64(ciphertext) + "\", \"ciphertextCrc32c\": \"" + crc32c(ciphertext) + "\"}")
                .when().post(version + ":asymmetricDecrypt")
                .then().statusCode(200)
                .body("plaintext", equalTo(b64("secret".getBytes(StandardCharsets.UTF_8))))
                .body("verifiedCiphertextCrc32c", equalTo(true));
        json()
                .body("{\"ciphertext\": \"" + b64(ciphertext) + "\", \"ciphertextCrc32c\": \"1\"}")
                .when().post(version + ":asymmetricDecrypt")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }
}
