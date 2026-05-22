package io.floci.gcp.services.secretmanager;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.secretmanager.model.StoredSecret;
import io.floci.gcp.services.secretmanager.model.StoredSecretVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller for the Secret Manager v1 API (JSON transport).
 *
 * The GCP Terraform provider uses HTTP REST JSON to manage secrets via the
 * secret_manager_custom_endpoint. This controller exposes the same URL shapes
 * as the real secretmanager.googleapis.com API so the provider works unchanged.
 *
 * Path: /v1/projects/{project}/secrets/...
 * More specific than IamController's /v1/projects catch-all, so it wins.
 */
@Path("/v1/projects/{project}/secrets")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SecretManagerHttpController {

    private static final Logger LOG = Logger.getLogger(SecretManagerHttpController.class);

    @Inject
    SecretManagerService service;

    // ── Secrets ────────────────────────────────────────────────────────────────

    @POST
    public Response createSecret(@PathParam("project") String project,
                                 @QueryParam("secretId") String secretId,
                                 Map<String, Object> body) {
        LOG.debugf("REST createSecret project=%s secretId=%s", project, secretId);
        if (secretId == null || secretId.isBlank()) {
            return gcpError(400, "secretId is required", "INVALID_ARGUMENT");
        }
        try {
            String replicationType = extractReplicationType(body);
            StoredSecret stored = service.createSecret(project, secretId, replicationType);
            return Response.ok(toSecretJson(stored)).build();
        } catch (GcpException e) {
            return gcpError(grpcToHttpCode(e), e.getMessage(), e.getGrpcCode().name());
        }
    }

    @GET
    public Response listSecrets(@PathParam("project") String project) {
        LOG.debugf("REST listSecrets project=%s", project);
        try {
            List<StoredSecret> secrets = service.listSecrets(project);
            List<Map<String, Object>> list = secrets.stream()
                    .map(SecretManagerHttpController::toSecretJson)
                    .collect(Collectors.toList());
            return Response.ok(Map.of("secrets", list)).build();
        } catch (GcpException e) {
            return gcpError(grpcToHttpCode(e), e.getMessage(), e.getGrpcCode().name());
        }
    }

    @GET
    @Path("/{secretId}")
    public Response getSecret(@PathParam("project") String project,
                              @PathParam("secretId") String secretId) {
        LOG.debugf("REST getSecret project=%s secretId=%s", project, secretId);
        try {
            StoredSecret stored = service.getSecret("projects/" + project + "/secrets/" + secretId);
            return Response.ok(toSecretJson(stored)).build();
        } catch (GcpException e) {
            return gcpError(grpcToHttpCode(e), e.getMessage(), e.getGrpcCode().name());
        }
    }

    @PATCH
    @Path("/{secretId}")
    public Response updateSecret(@PathParam("project") String project,
                                 @PathParam("secretId") String secretId,
                                 Map<String, Object> body) {
        LOG.debugf("REST updateSecret project=%s secretId=%s", project, secretId);
        try {
            StoredSecret stored = service.updateSecret("projects/" + project + "/secrets/" + secretId);
            return Response.ok(toSecretJson(stored)).build();
        } catch (GcpException e) {
            return gcpError(grpcToHttpCode(e), e.getMessage(), e.getGrpcCode().name());
        }
    }

    @DELETE
    @Path("/{secretId}")
    public Response deleteSecret(@PathParam("project") String project,
                                 @PathParam("secretId") String secretId) {
        LOG.debugf("REST deleteSecret project=%s secretId=%s", project, secretId);
        try {
            service.deleteSecret("projects/" + project + "/secrets/" + secretId);
            return Response.ok(Map.of()).build();
        } catch (GcpException e) {
            return gcpError(grpcToHttpCode(e), e.getMessage(), e.getGrpcCode().name());
        }
    }

    // ── Versions ───────────────────────────────────────────────────────────────

    @POST
    @Path("/{secretId}:addVersion")
    public Response addSecretVersion(@PathParam("project") String project,
                                     @PathParam("secretId") String secretId,
                                     Map<String, Object> body) {
        LOG.debugf("REST addSecretVersion project=%s secretId=%s", project, secretId);
        try {
            String secretName = "projects/" + project + "/secrets/" + secretId;
            byte[] payload = extractPayloadData(body);
            Long crc32c = extractCrc32c(body);
            StoredSecretVersion version = service.addSecretVersion(secretName, payload, crc32c);
            return Response.ok(toVersionJson(version)).build();
        } catch (GcpException e) {
            return gcpError(grpcToHttpCode(e), e.getMessage(), e.getGrpcCode().name());
        }
    }

    @GET
    @Path("/{secretId}/versions")
    public Response listSecretVersions(@PathParam("project") String project,
                                       @PathParam("secretId") String secretId) {
        LOG.debugf("REST listSecretVersions project=%s secretId=%s", project, secretId);
        try {
            String secretName = "projects/" + project + "/secrets/" + secretId;
            List<StoredSecretVersion> versions = service.listSecretVersions(secretName);
            List<Map<String, Object>> list = versions.stream()
                    .map(SecretManagerHttpController::toVersionJson)
                    .collect(Collectors.toList());
            return Response.ok(Map.of("versions", list)).build();
        } catch (GcpException e) {
            return gcpError(grpcToHttpCode(e), e.getMessage(), e.getGrpcCode().name());
        }
    }

    @GET
    @Path("/{secretId}/versions/{version}")
    public Response getSecretVersion(@PathParam("project") String project,
                                     @PathParam("secretId") String secretId,
                                     @PathParam("version") String version) {
        LOG.debugf("REST getSecretVersion project=%s secretId=%s version=%s", project, secretId, version);
        try {
            String versionName = "projects/" + project + "/secrets/" + secretId + "/versions/" + version;
            StoredSecretVersion stored = service.getSecretVersion(versionName);
            return Response.ok(toVersionJson(stored)).build();
        } catch (GcpException e) {
            return gcpError(grpcToHttpCode(e), e.getMessage(), e.getGrpcCode().name());
        }
    }

    @GET
    @Path("/{secretId}/versions/{version}:access")
    public Response accessSecretVersion(@PathParam("project") String project,
                                        @PathParam("secretId") String secretId,
                                        @PathParam("version") String version) {
        LOG.debugf("REST accessSecretVersion project=%s secretId=%s version=%s", project, secretId, version);
        try {
            String versionName = "projects/" + project + "/secrets/" + secretId + "/versions/" + version;
            StoredSecretVersion stored = service.accessSecretVersion(versionName);
            return Response.ok(toAccessJson(stored)).build();
        } catch (GcpException e) {
            return gcpError(grpcToHttpCode(e), e.getMessage(), e.getGrpcCode().name());
        }
    }

    @POST
    @Path("/{secretId}/versions/{version}:destroy")
    public Response destroySecretVersion(@PathParam("project") String project,
                                         @PathParam("secretId") String secretId,
                                         @PathParam("version") String version,
                                         Map<String, Object> body) {
        LOG.debugf("REST destroySecretVersion project=%s secretId=%s version=%s", project, secretId, version);
        try {
            String versionName = "projects/" + project + "/secrets/" + secretId + "/versions/" + version;
            StoredSecretVersion stored = service.destroySecretVersion(versionName);
            return Response.ok(toVersionJson(stored)).build();
        } catch (GcpException e) {
            return gcpError(grpcToHttpCode(e), e.getMessage(), e.getGrpcCode().name());
        }
    }

    @POST
    @Path("/{secretId}/versions/{version}:disable")
    public Response disableSecretVersion(@PathParam("project") String project,
                                         @PathParam("secretId") String secretId,
                                         @PathParam("version") String version,
                                         Map<String, Object> body) {
        LOG.debugf("REST disableSecretVersion project=%s secretId=%s version=%s", project, secretId, version);
        try {
            String versionName = "projects/" + project + "/secrets/" + secretId + "/versions/" + version;
            StoredSecretVersion stored = service.disableSecretVersion(versionName);
            return Response.ok(toVersionJson(stored)).build();
        } catch (GcpException e) {
            return gcpError(grpcToHttpCode(e), e.getMessage(), e.getGrpcCode().name());
        }
    }

    @POST
    @Path("/{secretId}/versions/{version}:enable")
    public Response enableSecretVersion(@PathParam("project") String project,
                                        @PathParam("secretId") String secretId,
                                        @PathParam("version") String version,
                                        Map<String, Object> body) {
        LOG.debugf("REST enableSecretVersion project=%s secretId=%s version=%s", project, secretId, version);
        try {
            String versionName = "projects/" + project + "/secrets/" + secretId + "/versions/" + version;
            StoredSecretVersion stored = service.enableSecretVersion(versionName);
            return Response.ok(toVersionJson(stored)).build();
        } catch (GcpException e) {
            return gcpError(grpcToHttpCode(e), e.getMessage(), e.getGrpcCode().name());
        }
    }

    // ── IAM stubs (called by Terraform for IAM-aware resources) ───────────────

    @GET
    @Path("/{secretId}:getIamPolicy")
    public Response getIamPolicy(@PathParam("project") String project,
                                 @PathParam("secretId") String secretId) {
        LOG.debugf("REST getIamPolicy project=%s secretId=%s", project, secretId);
        return Response.ok(Map.of("version", 1, "bindings", List.of(), "etag", "")).build();
    }

    @POST
    @Path("/{secretId}:setIamPolicy")
    @SuppressWarnings("unchecked")
    public Response setIamPolicy(@PathParam("project") String project,
                                 @PathParam("secretId") String secretId,
                                 Map<String, Object> body) {
        LOG.debugf("REST setIamPolicy project=%s secretId=%s", project, secretId);
        Map<String, Object> policy = (body != null && body.containsKey("policy"))
                ? (Map<String, Object>) body.get("policy") : Map.of();
        return Response.ok(policy).build();
    }

    @POST
    @Path("/{secretId}:testIamPermissions")
    @SuppressWarnings("unchecked")
    public Response testIamPermissions(@PathParam("project") String project,
                                       @PathParam("secretId") String secretId,
                                       Map<String, Object> body) {
        LOG.debugf("REST testIamPermissions project=%s secretId=%s", project, secretId);
        List<?> permissions = (body != null) ? (List<?>) body.get("permissions") : List.of();
        return Response.ok(Map.of("permissions", permissions != null ? permissions : List.of())).build();
    }

    // ── JSON builders ──────────────────────────────────────────────────────────

    private static Map<String, Object> toSecretJson(StoredSecret stored) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("name", stored.getName());
        response.put("createTime", stored.getCreateTime());
        Map<String, Object> replication = new LinkedHashMap<>();
        if ("user_managed".equals(stored.getReplicationType())) {
            replication.put("userManaged", Map.of());
        } else {
            replication.put("automatic", Map.of());
        }
        response.put("replication", replication);
        if (stored.getLabels() != null && !stored.getLabels().isEmpty()) {
            response.put("labels", stored.getLabels());
        }
        return response;
    }

    private static Map<String, Object> toVersionJson(StoredSecretVersion version) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("name", version.getName());
        response.put("createTime", version.getCreateTime());
        response.put("state", version.getState());
        if (version.getDestroyTime() != null) {
            response.put("destroyTime", version.getDestroyTime());
        }
        return response;
    }

    private static Map<String, Object> toAccessJson(StoredSecretVersion version) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("name", version.getName());
        Map<String, Object> payload = new LinkedHashMap<>();
        if (version.getPayload() != null) {
            payload.put("data", Base64.getEncoder().encodeToString(version.getPayload()));
            if (version.getDataCrc32c() != null) {
                payload.put("dataCrc32c", String.valueOf(version.getDataCrc32c()));
            }
        }
        response.put("payload", payload);
        return response;
    }

    // ── Request body helpers ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static String extractReplicationType(Map<String, Object> body) {
        if (body == null) {
            return "automatic";
        }
        Map<String, Object> replication = (Map<String, Object>) body.get("replication");
        if (replication != null && replication.containsKey("userManaged")) {
            return "user_managed";
        }
        return "automatic";
    }

    @SuppressWarnings("unchecked")
    private static byte[] extractPayloadData(Map<String, Object> body) {
        if (body == null) {
            return new byte[0];
        }
        Map<String, Object> payload = (Map<String, Object>) body.get("payload");
        if (payload == null) {
            return new byte[0];
        }
        String data = (String) payload.get("data");
        if (data == null || data.isEmpty()) {
            return new byte[0];
        }
        return Base64.getDecoder().decode(data);
    }

    @SuppressWarnings("unchecked")
    private static Long extractCrc32c(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        Map<String, Object> payload = (Map<String, Object>) body.get("payload");
        if (payload == null) {
            return null;
        }
        Object crc = payload.get("dataCrc32c");
        if (crc instanceof Number) {
            return ((Number) crc).longValue();
        }
        if (crc instanceof String) {
            try {
                return Long.parseLong((String) crc);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Response gcpError(int code, String message, String status) {
        return Response.status(code)
                .entity(Map.of("error", Map.of(
                        "code", code,
                        "message", message != null ? message : "",
                        "status", status)))
                .build();
    }

    private static int grpcToHttpCode(GcpException e) {
        return switch (e.getGrpcCode().value()) {
            case 5 -> 404;
            case 6 -> 409;
            case 7 -> 403;
            case 3 -> 400;
            default -> 500;
        };
    }
}
