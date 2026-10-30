package io.floci.gcp.services.cloudkms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProtoJsonBytes;
import io.floci.gcp.services.cloudkms.model.StoredCryptoKey;
import io.floci.gcp.services.cloudkms.model.StoredCryptoKeyVersion;
import io.floci.gcp.services.cloudkms.model.StoredImportJob;
import io.floci.gcp.services.cloudkms.model.StoredKeyRing;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final ObjectMapper EXACT_NUMBERS = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

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
    public Response createCryptoKey(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing, @QueryParam("cryptoKeyId") String cryptoKeyId,
            @QueryParam("skipInitialVersionCreation") @DefaultValue("false") boolean skip, Map<String, Object> body) {
        try {
            String purpose = body != null ? (String) body.get("purpose") : null;
            boolean importOnly = body != null && Boolean.TRUE.equals(body.get("importOnly"));
            StoredCryptoKey stored = service.createCryptoKey(
                    keyRingName(project, location, keyRing), cryptoKeyId, purpose, versionTemplateAlgorithm(body), skip,
                    cryptoKeyFields(body), importOnly);
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
    public Response updateCryptoKey(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing, @PathParam("cryptoKey") String cryptoKey,
            @QueryParam("updateMask") String updateMask, Map<String, Object> body) {
        try {
            Set<String> paths;
            if (updateMask != null && !updateMask.isBlank()) {
                paths = CloudKmsService.snakeCasePaths(Arrays.asList(updateMask.split(",")));
            } else {
                paths = body != null ? CloudKmsService.snakeCasePaths(body.keySet()) : Set.of();
            }
            StoredCryptoKey stored = service.updateCryptoKey(cryptoKeyName(project, location, keyRing, cryptoKey),
                    cryptoKeyFields(body), versionTemplateAlgorithm(body), paths);
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

    @POST
    @Path("/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}/cryptoKeyVersions:import")
    public Response importCryptoKeyVersion(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing, @PathParam("cryptoKey") String cryptoKey, Map<String, Object> body) {
        try {
            if (body != null && body.get("cryptoKeyVersion") instanceof String existing && !existing.isEmpty()) {
                throw GcpException.unimplemented("Re-importing into an existing CryptoKeyVersion is not supported");
            }
            String algorithm = body != null ? (String) body.get("algorithm") : null;
            String importJob = body != null ? (String) body.get("importJob") : null;
            byte[] wrappedKey = decodeField(body, "wrappedKey");
            if (wrappedKey.length == 0) {
                wrappedKey = decodeField(body, "rsaAesWrappedKey");
            }
            StoredCryptoKeyVersion v = service.importCryptoKeyVersion(
                    cryptoKeyName(project, location, keyRing, cryptoKey), algorithm, importJob, wrappedKey);
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

    // ── ImportJobs ───────────────────────────────────────────────────────────

    @POST
    @Path("/{location}/keyRings/{keyRing}/importJobs")
    public Response createImportJob(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing, @QueryParam("importJobId") String importJobId,
            Map<String, Object> body) {
        try {
            String importMethod = body != null ? (String) body.get("importMethod") : null;
            String protectionLevel = body != null ? (String) body.get("protectionLevel") : null;
            StoredImportJob stored = service.createImportJob(
                    keyRingName(project, location, keyRing), importJobId, importMethod, protectionLevel);
            return Response.ok(toImportJobJson(stored)).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @GET
    @Path("/{location}/keyRings/{keyRing}/importJobs")
    public Response listImportJobs(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing) {
        try {
            List<Map<String, Object>> list = service.listImportJobs(keyRingName(project, location, keyRing)).stream()
                    .map(CloudKmsHttpController::toImportJobJson).collect(Collectors.toList());
            return Response.ok(Map.of("importJobs", list)).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @GET
    @Path("/{location}/keyRings/{keyRing}/importJobs/{importJob}")
    public Response getImportJob(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing, @PathParam("importJob") String importJob) {
        try {
            return Response.ok(toImportJobJson(service.getImportJob(
                    keyRingName(project, location, keyRing) + "/importJobs/" + importJob))).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    // ── Crypto operations ────────────────────────────────────────────────────

    @POST
    @Path("/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}:encrypt")
    public Response encrypt(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing, @PathParam("cryptoKey") String cryptoKey, String rawBody) {
        try {
            Map<String, Object> body = parseBody(rawBody);
            byte[] plaintext = decodeField(body, "plaintext");
            byte[] aad = decodeField(body, "additionalAuthenticatedData");
            boolean verifiedPlaintext = verifyCrc32c(body, "plaintextCrc32c", plaintext);
            boolean verifiedAad = verifyCrc32c(body, "additionalAuthenticatedDataCrc32c", aad);
            CloudKmsService.EncryptResult result = service.encrypt(
                    cryptoKeyName(project, location, keyRing, cryptoKey), plaintext, aad);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("name", result.versionName());
            response.put("ciphertext", Base64.getEncoder().encodeToString(result.ciphertext()));
            response.put("ciphertextCrc32c", String.valueOf(crc32c(result.ciphertext())));
            putIfVerified(response, "verifiedPlaintextCrc32c", verifiedPlaintext);
            putIfVerified(response, "verifiedAdditionalAuthenticatedDataCrc32c", verifiedAad);
            response.put("protectionLevel", "SOFTWARE");
            return Response.ok(response).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @POST
    @Path("/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}:decrypt")
    public Response decrypt(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing, @PathParam("cryptoKey") String cryptoKey, String rawBody) {
        try {
            Map<String, Object> body = parseBody(rawBody);
            byte[] ciphertext = decodeField(body, "ciphertext");
            byte[] aad = decodeField(body, "additionalAuthenticatedData");
            verifyCrc32c(body, "ciphertextCrc32c", ciphertext);
            verifyCrc32c(body, "additionalAuthenticatedDataCrc32c", aad);
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
            @PathParam("version") String version, String rawBody) {
        try {
            Map<String, Object> body = parseBody(rawBody);
            byte[] digest = new byte[0];
            boolean digestSupplied = false;
            if (body != null && body.get("digest") instanceof Map<?, ?> d) {
                Object sha256 = ((Map<String, Object>) d).get("sha256");
                if (sha256 instanceof String s) {
                    digest = ProtoJsonBytes.decode(s, "digest.sha256");
                }
                digestSupplied = List.of("sha256", "sha384", "sha512").stream()
                        .anyMatch(k -> d.get(k) instanceof String s && !s.isEmpty());
            }
            byte[] data = decodeField(body, "data");
            boolean verifiedDigest = verifyCrc32c(body, "digestCrc32c", digest);
            boolean verifiedData = verifyCrc32c(body, "dataCrc32c", data);
            byte[] signature = service.asymmetricSign(
                    versionName(project, location, keyRing, cryptoKey, version), resolveDigest(digest, digestSupplied, data));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("name", versionName(project, location, keyRing, cryptoKey, version));
            response.put("signature", Base64.getEncoder().encodeToString(signature));
            response.put("signatureCrc32c", String.valueOf(crc32c(signature)));
            putIfVerified(response, "verifiedDigestCrc32c", verifiedDigest);
            putIfVerified(response, "verifiedDataCrc32c", verifiedData);
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
            @PathParam("version") String version, String rawBody) {
        try {
            Map<String, Object> body = parseBody(rawBody);
            byte[] ciphertext = decodeField(body, "ciphertext");
            boolean verifiedCiphertext = verifyCrc32c(body, "ciphertextCrc32c", ciphertext);
            byte[] plaintext = service.asymmetricDecrypt(
                    versionName(project, location, keyRing, cryptoKey, version), ciphertext);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("plaintext", Base64.getEncoder().encodeToString(plaintext));
            response.put("plaintextCrc32c", String.valueOf(crc32c(plaintext)));
            putIfVerified(response, "verifiedCiphertextCrc32c", verifiedCiphertext);
            response.put("protectionLevel", "SOFTWARE");
            return Response.ok(response).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @POST
    @Path("/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}/cryptoKeyVersions/{version}:macSign")
    public Response macSign(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing, @PathParam("cryptoKey") String cryptoKey,
            @PathParam("version") String version, String rawBody) {
        try {
            Map<String, Object> body = parseBody(rawBody);
            byte[] data = decodeField(body, "data");
            boolean verifiedData = verifyCrc32c(body, "dataCrc32c", data);
            String name = versionName(project, location, keyRing, cryptoKey, version);
            byte[] mac = service.macSign(name, data);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("name", name);
            response.put("mac", Base64.getEncoder().encodeToString(mac));
            response.put("macCrc32c", String.valueOf(crc32c(mac)));
            putIfVerified(response, "verifiedDataCrc32c", verifiedData);
            response.put("protectionLevel", "SOFTWARE");
            return Response.ok(response).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @POST
    @Path("/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}/cryptoKeyVersions/{version}:macVerify")
    public Response macVerify(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("keyRing") String keyRing, @PathParam("cryptoKey") String cryptoKey,
            @PathParam("version") String version, String rawBody) {
        try {
            Map<String, Object> body = parseBody(rawBody);
            byte[] data = decodeField(body, "data");
            byte[] mac = decodeField(body, "mac");
            boolean verifiedData = verifyCrc32c(body, "dataCrc32c", data);
            boolean verifiedMac = verifyCrc32c(body, "macCrc32c", mac);
            String name = versionName(project, location, keyRing, cryptoKey, version);
            boolean success = service.macVerify(name, data, mac);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("name", name);
            putIfVerified(response, "success", success);
            putIfVerified(response, "verifiedDataCrc32c", verifiedData);
            putIfVerified(response, "verifiedMacCrc32c", verifiedMac);
            // Repeats success so a client can detect a corrupted success field.
            putIfVerified(response, "verifiedSuccessIntegrity", success);
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
        if (stored.isImportOnly()) {
            json.put("importOnly", true);
        }
        if (stored.getLabels() != null && !stored.getLabels().isEmpty()) {
            json.put("labels", stored.getLabels());
        }
        if (stored.getNextRotationTime() != null) {
            json.put("nextRotationTime", stored.getNextRotationTime());
        }
        if (stored.getRotationPeriod() != null) {
            json.put("rotationPeriod", stored.getRotationPeriod());
        }
        json.put("destroyScheduledDuration", CloudKmsService.destroyScheduledDurationOf(stored));
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
        if (v.getImportJob() != null) {
            json.put("importJob", v.getImportJob());
        }
        if (v.getImportTime() != null) {
            json.put("importTime", v.getImportTime());
        }
        if (v.getImportFailureReason() != null) {
            json.put("importFailureReason", v.getImportFailureReason());
        }
        return json;
    }

    /** The public key is only returned while the job is ACTIVE; expireEventTime only once it is EXPIRED. */
    private static Map<String, Object> toImportJobJson(StoredImportJob job) {
        String state = CloudKmsService.importJobState(job);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", job.getName());
        json.put("importMethod", job.getImportMethod());
        json.put("protectionLevel", job.getProtectionLevel());
        json.put("createTime", job.getCreateTime());
        json.put("generateTime", job.getGenerateTime());
        json.put("expireTime", job.getExpireTime());
        json.put("state", state);
        if ("ACTIVE".equals(state)) {
            json.put("publicKey", Map.of("pem", KmsCrypto.toPem(job.getPublicKeyBase64())));
        } else if ("EXPIRED".equals(state)) {
            json.put("expireEventTime", job.getExpireTime());
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

    private static String versionTemplateAlgorithm(Map<String, Object> body) {
        if (body == null || !(body.get("versionTemplate") instanceof Map<?, ?> template)) {
            return null;
        }
        Object algorithm = template.get("algorithm");
        if (algorithm != null && !(algorithm instanceof String)) {
            throw GcpException.invalidArgument("versionTemplate.algorithm must be a string");
        }
        return (String) algorithm;
    }

    private static CloudKmsService.CryptoKeyFields cryptoKeyFields(Map<String, Object> body) {
        if (body == null) {
            return new CloudKmsService.CryptoKeyFields(null, null, null, null);
        }
        return new CloudKmsService.CryptoKeyFields(durationField(body, "rotationPeriod"),
                timestampField(body, "nextRotationTime"), durationField(body, "destroyScheduledDuration"),
                labelsField(body));
    }

    private static Duration durationField(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            throw GcpException.invalidArgument(field + " must be a duration string such as \"86400s\"");
        }
        try {
            // Qualified: com.google.protobuf.Duration clashes with the imported java.time.Duration.
            com.google.protobuf.Duration parsed = Durations.parse(s);
            return Duration.ofSeconds(parsed.getSeconds(), parsed.getNanos());
        } catch (ParseException e) {
            throw GcpException.invalidArgument("Invalid duration for " + field + ": " + s);
        }
    }

    private static Instant timestampField(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            throw GcpException.invalidArgument(field + " must be an RFC 3339 timestamp string");
        }
        try {
            Timestamp parsed = Timestamps.parse(s);
            return Instant.ofEpochSecond(parsed.getSeconds(), parsed.getNanos());
        } catch (ParseException e) {
            throw GcpException.invalidArgument("Invalid timestamp for " + field + ": " + s);
        }
    }

    private static Map<String, String> labelsField(Map<String, Object> body) {
        Object value = body.get("labels");
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw GcpException.invalidArgument("labels must be an object of string values");
        }
        Map<String, String> labels = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof String labelValue)) {
                throw GcpException.invalidArgument("Label " + entry.getKey() + " must have a string value");
            }
            labels.put(String.valueOf(entry.getKey()), labelValue);
        }
        return labels;
    }

    private static byte[] decodeField(Map<String, Object> body, String field) {
        if (body == null || !(body.get(field) instanceof String s) || s.isEmpty()) {
            return new byte[0];
        }
        return ProtoJsonBytes.decode(s, field);
    }

    private static long crc32c(byte[] data) {
        CRC32C crc = new CRC32C();
        crc.update(data);
        return crc.getValue();
    }

    /**
     * Checks an optional request checksum the way the gRPC controller does: absent means not
     * verified, a mismatch is INVALID_ARGUMENT. The field is an int64, which proto3 JSON carries as
     * a string or a number, in either form possibly with an exponent; it must be integral.
     */
    private static boolean verifyCrc32c(Map<String, Object> body, String field, byte[] data) {
        Object value = body != null ? body.get(field) : null;
        if (value == null) {
            return false;
        }
        long expected;
        try {
            if (!(value instanceof String) && !(value instanceof Number)) {
                throw new NumberFormatException();
            }
            expected = new BigDecimal(value.toString()).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw GcpException.invalidArgument("Invalid value at '" + field + "' (TYPE_INT64): " + value);
        }
        if (crc32c(data) != expected) {
            throw GcpException.invalidArgument("Checksum verification failed");
        }
        return true;
    }

    /**
     * Parses a request whose int64 checksums must be read exactly. The shared mapper turns a JSON
     * number with a fraction or exponent into a double, which can round a non-integral literal such
     * as {@code 1e-324} to {@code 0}; reading floats as BigDecimal keeps the literal.
     */
    private static Map<String, Object> parseBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            return EXACT_NUMBERS.readValue(rawBody, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw GcpException.invalidArgument("Invalid JSON payload received: " + e.getOriginalMessage());
        }
    }

    /**
     * The digest to sign: the one supplied, else the SHA-256 of the data, as the gRPC controller does.
     * The proto lets a request carry one or the other, never both, whichever digest variant it is.
     */
    private static byte[] resolveDigest(byte[] digest, boolean digestSupplied, byte[] data) {
        if (digestSupplied && data.length > 0) {
            throw GcpException.invalidArgument("Only one of digest or data may be supplied for AsymmetricSign");
        }
        if (digest.length > 0) {
            return digest;
        }
        if (data.length > 0) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(data);
            } catch (NoSuchAlgorithmException e) {
                throw GcpException.internal("Digest computation failed: " + e.getMessage());
            }
        }
        throw GcpException.invalidArgument("A SHA-256 digest or data is required for AsymmetricSign");
    }

    /** proto3 JSON leaves a false bool out, so a verified flag only appears when it is true. */
    private static void putIfVerified(Map<String, Object> response, String field, boolean verified) {
        if (verified) {
            response.put(field, true);
        }
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
