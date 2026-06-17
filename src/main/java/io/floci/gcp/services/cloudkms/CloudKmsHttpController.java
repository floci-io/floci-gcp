package io.floci.gcp.services.cloudkms;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.cloudkms.model.StoredCryptoKey;
import io.floci.gcp.services.cloudkms.model.StoredCryptoKeyVersion;
import io.floci.gcp.services.cloudkms.model.StoredKeyRing;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.CRC32C;

/**
 * REST controller for the Cloud KMS v1 API (JSON transport), mirroring the URL shapes of the real
 * cloudkms.googleapis.com API so the GCP Terraform provider and REST clients work unchanged.
 *
 * <p>Base path is the location segment so the location-level {@code :generateRandomBytes} custom
 * method and the nested keyRings hierarchy share one resource. More specific than
 * {@code IamController}'s {@code /v1/projects} catch-all, so it wins.
 */
@Path("/v1/projects/{project}/locations")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CloudKmsHttpController {

    private static final Logger LOG = Logger.getLogger(CloudKmsHttpController.class);

    @Inject
    CloudKmsService service;

    // ── KeyRings ─────────────────────────────────────────────────────────────

    @POST
    @Path("/{location}/keyRings")
    public Response createKeyRing(@PathParam("project") String project, @PathParam("location") String location,
            @QueryParam("keyRingId") String keyRingId, Map<String, Object> body) {
        try {
            StoredKeyRing stored = service.createKeyRing(parent(project, location), keyRingId);
            return Response.ok(toKeyRingJson(stored)).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @GET
    @Path("/{location}/keyRings")
    public Response listKeyRings(@PathParam("project") String project, @PathParam("location") String location) {
        try {
            List<Map<String, Object>> list = service.listKeyRings(parent(project, location)).stream()
                    .map(CloudKmsHttpController::toKeyRingJson).collect(Collectors.toList());
            return Response.ok(Map.of("keyRings", list)).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @GET
    @Path("/{location}/keyRings/{keyRing}")
    public Response getKeyRing(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing) {
        try {
            return Response.ok(toKeyRingJson(service.getKeyRing(keyRingName(project, location, keyRing)))).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    // ── CryptoKeys ───────────────────────────────────────────────────────────

    @POST
    @Path("/{location}/keyRings/{keyRing}/cryptoKeys")
    @SuppressWarnings("unchecked")
    public Response createCryptoKey(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing, @QueryParam("cryptoKeyId") String cryptoKeyId,
            @QueryParam("skipInitialVersionCreation") @DefaultValue("false") boolean skip, Map<String, Object> body) {
        try {
            String purpose = body != null ? (String) body.get("purpose") : null;
            String algorithm = null;
            if (body != null && body.get("versionTemplate") instanceof Map<?, ?> vt) {
                algorithm = (String) ((Map<String, Object>) vt).get("algorithm");
            }
            StoredCryptoKey stored = service.createCryptoKey(
                    keyRingName(project, location, keyRing), cryptoKeyId, purpose, algorithm, skip);
            return Response.ok(toCryptoKeyJson(stored)).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @GET
    @Path("/{location}/keyRings/{keyRing}/cryptoKeys")
    public Response listCryptoKeys(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing) {
        try {
            List<Map<String, Object>> list = service.listCryptoKeys(keyRingName(project, location, keyRing)).stream()
                    .map(this::toCryptoKeyJson).collect(Collectors.toList());
            return Response.ok(Map.of("cryptoKeys", list)).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @GET
    @Path("/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}")
    public Response getCryptoKey(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing, @PathParam("cryptoKey") String cryptoKey) {
        try {
            return Response.ok(toCryptoKeyJson(
                    service.getCryptoKey(cryptoKeyName(project, location, keyRing, cryptoKey)))).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @PATCH
    @Path("/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}")
    @SuppressWarnings("unchecked")
    public Response updateCryptoKey(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing, @PathParam("cryptoKey") String cryptoKey, Map<String, Object> body) {
        try {
            Map<String, String> labels = body != null && body.get("labels") instanceof Map<?, ?> l
                    ? (Map<String, String>) l : null;
            String algorithm = null;
            if (body != null && body.get("versionTemplate") instanceof Map<?, ?> vt) {
                algorithm = (String) ((Map<String, Object>) vt).get("algorithm");
            }
            StoredCryptoKey stored = service.updateCryptoKey(
                    cryptoKeyName(project, location, keyRing, cryptoKey), labels, algorithm);
            return Response.ok(toCryptoKeyJson(stored)).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @POST
    @Path("/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}:updatePrimaryVersion")
    public Response updatePrimaryVersion(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing, @PathParam("cryptoKey") String cryptoKey, Map<String, Object> body) {
        try {
            String versionId = body != null ? (String) body.get("cryptoKeyVersionId") : null;
            StoredCryptoKey stored = service.updateCryptoKeyPrimaryVersion(
                    cryptoKeyName(project, location, keyRing, cryptoKey), versionId);
            return Response.ok(toCryptoKeyJson(stored)).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    // ── CryptoKeyVersions ────────────────────────────────────────────────────

    @POST
    @Path("/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}/cryptoKeyVersions")
    public Response createCryptoKeyVersion(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing, @PathParam("cryptoKey") String cryptoKey, Map<String, Object> body) {
        try {
            StoredCryptoKeyVersion v = service.createCryptoKeyVersion(
                    cryptoKeyName(project, location, keyRing, cryptoKey));
            return Response.ok(toVersionJson(v)).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @GET
    @Path("/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}/cryptoKeyVersions")
    public Response listCryptoKeyVersions(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing, @PathParam("cryptoKey") String cryptoKey) {
        try {
            List<Map<String, Object>> list = service.listCryptoKeyVersions(
                            cryptoKeyName(project, location, keyRing, cryptoKey)).stream()
                    .map(CloudKmsHttpController::toVersionJson).collect(Collectors.toList());
            return Response.ok(Map.of("cryptoKeyVersions", list)).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @GET
    @Path("/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}/cryptoKeyVersions/{version}")
    public Response getCryptoKeyVersion(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing, @PathParam("cryptoKey") String cryptoKey,
            @PathParam("version") String version) {
        try {
            return Response.ok(toVersionJson(service.getCryptoKeyVersion(
                    versionName(project, location, keyRing, cryptoKey, version)))).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @PATCH
    @Path("/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}/cryptoKeyVersions/{version}")
    public Response updateCryptoKeyVersion(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing, @PathParam("cryptoKey") String cryptoKey,
            @PathParam("version") String version, Map<String, Object> body) {
        try {
            String state = body != null ? (String) body.get("state") : null;
            StoredCryptoKeyVersion v = service.updateCryptoKeyVersionState(
                    versionName(project, location, keyRing, cryptoKey, version), state);
            return Response.ok(toVersionJson(v)).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @POST
    @Path("/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}/cryptoKeyVersions/{version}:destroy")
    public Response destroyCryptoKeyVersion(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing, @PathParam("cryptoKey") String cryptoKey,
            @PathParam("version") String version, Map<String, Object> body) {
        try {
            StoredCryptoKeyVersion v = service.destroyCryptoKeyVersion(
                    versionName(project, location, keyRing, cryptoKey, version));
            return Response.ok(toVersionJson(v)).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @POST
    @Path("/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}/cryptoKeyVersions/{version}:restore")
    public Response restoreCryptoKeyVersion(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing, @PathParam("cryptoKey") String cryptoKey,
            @PathParam("version") String version, Map<String, Object> body) {
        try {
            StoredCryptoKeyVersion v = service.restoreCryptoKeyVersion(
                    versionName(project, location, keyRing, cryptoKey, version));
            return Response.ok(toVersionJson(v)).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @GET
    @Path("/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}/cryptoKeyVersions/{version}/publicKey")
    public Response getPublicKey(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing, @PathParam("cryptoKey") String cryptoKey,
            @PathParam("version") String version) {
        try {
            StoredCryptoKeyVersion v = service.getPublicKeyVersion(
                    versionName(project, location, keyRing, cryptoKey, version));
            String pem = KmsCrypto.toPem(v.getPublicKeyBase64());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("pem", pem);
            response.put("algorithm", v.getAlgorithm());
            response.put("name", v.getName());
            response.put("pemCrc32c", String.valueOf(crc32c(pem.getBytes(StandardCharsets.UTF_8))));
            response.put("protectionLevel", "SOFTWARE");
            return Response.ok(response).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    // ── Crypto operations ────────────────────────────────────────────────────

    @POST
    @Path("/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}:encrypt")
    public Response encrypt(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing, @PathParam("cryptoKey") String cryptoKey, Map<String, Object> body) {
        try {
            byte[] plaintext = decodeField(body, "plaintext");
            byte[] aad = decodeField(body, "additionalAuthenticatedData");
            CloudKmsService.EncryptResult result = service.encrypt(
                    cryptoKeyName(project, location, keyRing, cryptoKey), plaintext, aad);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("name", result.versionName());
            response.put("ciphertext", Base64.getEncoder().encodeToString(result.ciphertext()));
            response.put("ciphertextCrc32c", String.valueOf(crc32c(result.ciphertext())));
            response.put("protectionLevel", "SOFTWARE");
            return Response.ok(response).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @POST
    @Path("/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}:decrypt")
    public Response decrypt(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing, @PathParam("cryptoKey") String cryptoKey, Map<String, Object> body) {
        try {
            byte[] ciphertext = decodeField(body, "ciphertext");
            byte[] aad = decodeField(body, "additionalAuthenticatedData");
            CloudKmsService.DecryptResult result = service.decrypt(
                    cryptoKeyName(project, location, keyRing, cryptoKey), ciphertext, aad);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("plaintext", Base64.getEncoder().encodeToString(result.plaintext()));
            response.put("plaintextCrc32c", String.valueOf(crc32c(result.plaintext())));
            response.put("usedPrimary", result.usedPrimary());
            response.put("protectionLevel", "SOFTWARE");
            return Response.ok(response).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @POST
    @Path("/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}/cryptoKeyVersions/{version}:asymmetricSign")
    @SuppressWarnings("unchecked")
    public Response asymmetricSign(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing, @PathParam("cryptoKey") String cryptoKey,
            @PathParam("version") String version, Map<String, Object> body) {
        try {
            byte[] digest = new byte[0];
            if (body != null && body.get("digest") instanceof Map<?, ?> d) {
                Object sha256 = ((Map<String, Object>) d).get("sha256");
                if (sha256 instanceof String s) {
                    digest = Base64.getDecoder().decode(s);
                }
            }
            byte[] signature = service.asymmetricSign(
                    versionName(project, location, keyRing, cryptoKey, version), digest);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("name", versionName(project, location, keyRing, cryptoKey, version));
            response.put("signature", Base64.getEncoder().encodeToString(signature));
            response.put("signatureCrc32c", String.valueOf(crc32c(signature)));
            response.put("protectionLevel", "SOFTWARE");
            return Response.ok(response).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @POST
    @Path("/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}/cryptoKeyVersions/{version}:asymmetricDecrypt")
    public Response asymmetricDecrypt(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing, @PathParam("cryptoKey") String cryptoKey,
            @PathParam("version") String version, Map<String, Object> body) {
        try {
            byte[] ciphertext = decodeField(body, "ciphertext");
            byte[] plaintext = service.asymmetricDecrypt(
                    versionName(project, location, keyRing, cryptoKey, version), ciphertext);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("plaintext", Base64.getEncoder().encodeToString(plaintext));
            response.put("plaintextCrc32c", String.valueOf(crc32c(plaintext)));
            response.put("protectionLevel", "SOFTWARE");
            return Response.ok(response).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @POST
    @Path("/{location}:generateRandomBytes")
    public Response generateRandomBytes(@PathParam("project") String project, @PathParam("location") String location,
            Map<String, Object> body) {
        try {
            int length = body != null && body.get("lengthBytes") instanceof Number n ? n.intValue() : 0;
            byte[] data = service.generateRandomBytes(length);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("data", Base64.getEncoder().encodeToString(data));
            response.put("dataCrc32c", String.valueOf(crc32c(data)));
            return Response.ok(response).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    // ── JSON builders ────────────────────────────────────────────────────────

    private static Map<String, Object> toKeyRingJson(StoredKeyRing stored) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", stored.getName());
        json.put("createTime", stored.getCreateTime());
        return json;
    }

    private Map<String, Object> toCryptoKeyJson(StoredCryptoKey stored) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", stored.getName());
        json.put("purpose", stored.getPurpose());
        json.put("createTime", stored.getCreateTime());
        Map<String, Object> versionTemplate = new LinkedHashMap<>();
        versionTemplate.put("protectionLevel", "SOFTWARE");
        versionTemplate.put("algorithm", stored.getAlgorithm());
        json.put("versionTemplate", versionTemplate);
        if (stored.getLabels() != null && !stored.getLabels().isEmpty()) {
            json.put("labels", stored.getLabels());
        }
        if (stored.getPrimaryVersion() != null) {
            service.getCryptoKeyVersionOptional(stored.getName() + "/cryptoKeyVersions/" + stored.getPrimaryVersion())
                    .ifPresent(v -> json.put("primary", toVersionJson(v)));
        }
        return json;
    }

    private static Map<String, Object> toVersionJson(StoredCryptoKeyVersion v) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", v.getName());
        json.put("state", v.getState());
        json.put("protectionLevel", "SOFTWARE");
        json.put("algorithm", v.getAlgorithm());
        json.put("createTime", v.getCreateTime());
        if (v.getGenerateTime() != null) {
            json.put("generateTime", v.getGenerateTime());
        }
        if (v.getDestroyTime() != null) {
            json.put("destroyTime", v.getDestroyTime());
        }
        return json;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String parent(String project, String location) {
        return "projects/" + project + "/locations/" + location;
    }

    private static String keyRingName(String project, String location, String keyRing) {
        return parent(project, location) + "/keyRings/" + keyRing;
    }

    private static String cryptoKeyName(String project, String location, String keyRing, String cryptoKey) {
        return keyRingName(project, location, keyRing) + "/cryptoKeys/" + cryptoKey;
    }

    private static String versionName(String project, String location, String keyRing, String cryptoKey,
            String version) {
        return cryptoKeyName(project, location, keyRing, cryptoKey) + "/cryptoKeyVersions/" + version;
    }

    private static byte[] decodeField(Map<String, Object> body, String field) {
        if (body == null || !(body.get(field) instanceof String s) || s.isEmpty()) {
            return new byte[0];
        }
        return Base64.getDecoder().decode(s);
    }

    private static long crc32c(byte[] data) {
        CRC32C crc = new CRC32C();
        crc.update(data);
        return crc.getValue();
    }

    private static Response error(GcpException e) {
        LOG.debugf("KMS REST error: %s", e.getMessage());
        return Response.status(e.getHttpStatus())
                .entity(Map.of("error", Map.of(
                        "code", e.getHttpStatus(),
                        "message", e.getMessage() != null ? e.getMessage() : "",
                        "status", e.getGcpStatus())))
                .build();
    }
}
