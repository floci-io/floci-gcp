package io.floci.gcp.services.iam;

import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.floci.gcp.services.iam.model.StoredServiceAccount;
import io.floci.gcp.services.iam.model.StoredServiceAccountKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * REST controller for the IAM v1 API.
 *
 * Routes POST/GET/DELETE /v1/projects/{project}/serviceAccounts[/{email}[:{method}]]
 * Uses JSON transport; disambiguated from DatastoreHttpController by Content-Type.
 */
@Path("/v1/projects")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IamController {

    private static final Logger LOG = Logger.getLogger(IamController.class);

    private static final List<String> CUSTOM_METHODS =
            List.of("getIamPolicy", "setIamPolicy", "testIamPermissions", "signBlob");

    @Inject
    IamService service;

    @POST
    @Path("/{rest:.*}")
    public Response post(@PathParam("rest") String rest, Map<String, Object> body) {
        IamPath p = parsePath(rest);
        LOG.debugf("IAM POST %s project=%s id=%s method=%s", rest, p.project(), p.identifier(), p.customMethod());

        if (p.customMethod() != null) {
            return handleCustomMethod(p, body);
        }

        if ("serviceAccounts".equals(p.resourceType()) && p.identifier() == null) {
            return createServiceAccount(p.project(), body);
        }

        if ("serviceAccounts".equals(p.resourceType()) && p.identifier() != null) {
            KeySubPath ksp = parseKeySubPath(p.identifier());
            if (ksp.isKeyList()) {
                StoredServiceAccountKey key = service.createKey(p.project(), ksp.email());
                return Response.ok(key).build();
            }
        }

        return Response.status(Response.Status.NOT_FOUND).build();
    }

    // UpdateServiceAccount: PUT /v1/projects/{project}/serviceAccounts/{email} with the
    // ServiceAccount as the body.
    @PUT
    @Path("/{rest:.*}")
    public Response put(@PathParam("rest") String rest, Map<String, Object> body) {
        IamPath p = parsePath(rest);
        LOG.debugf("IAM PUT %s project=%s id=%s", rest, p.project(), p.identifier());
        if (!"serviceAccounts".equals(p.resourceType()) || p.identifier() == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return updateServiceAccount(p, body);
    }

    // PatchServiceAccount: PATCH /v1/projects/{project}/serviceAccounts/{email} with body
    // { "serviceAccount": {...}, "updateMask": "displayName,description" }.
    @PATCH
    @Path("/{rest:.*}")
    public Response patch(@PathParam("rest") String rest, Map<String, Object> body) {
        IamPath p = parsePath(rest);
        LOG.debugf("IAM PATCH %s project=%s id=%s", rest, p.project(), p.identifier());
        if (!"serviceAccounts".equals(p.resourceType()) || p.identifier() == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> sa = body.get("serviceAccount") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : body;
        return updateServiceAccount(p, sa);
    }

    private Response updateServiceAccount(IamPath p, Map<String, Object> saProps) {
        String displayName = (String) saProps.get("displayName");
        String description = (String) saProps.get("description");
        StoredServiceAccount sa = service.updateServiceAccount(
                p.project(), p.identifier(), displayName, description);
        return Response.ok(sa).build();
    }

    @GET
    @Path("/{rest:.*}")
    public Response get(@PathParam("rest") String rest,
            @QueryParam("pageSize") @DefaultValue("0") int pageSize,
            @QueryParam("pageToken") String pageToken) {
        IamPath p = parsePath(rest);
        LOG.debugf("IAM GET %s project=%s id=%s", rest, p.project(), p.identifier());

        if (!"serviceAccounts".equals(p.resourceType())) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (p.identifier() == null) {
            List<StoredServiceAccount> all = service.listServiceAccounts(p.project());
            PageToken.Page<StoredServiceAccount> page = PageToken.paginate(all, pageSize, pageToken);
            Map<String, Object> resp = new java.util.LinkedHashMap<>();
            resp.put("accounts", page.items());
            if (page.nextPageToken() != null) {
                resp.put("nextPageToken", page.nextPageToken());
            }
            return Response.ok(resp).build();
        }

        KeySubPath ksp = parseKeySubPath(p.identifier());
        if (ksp.isKeyList()) {
            List<StoredServiceAccountKey> all = service.listKeys(p.project(), ksp.email());
            PageToken.Page<StoredServiceAccountKey> page = PageToken.paginate(all, pageSize, pageToken);
            Map<String, Object> resp = new java.util.LinkedHashMap<>();
            resp.put("keys", page.items());
            if (page.nextPageToken() != null) {
                resp.put("nextPageToken", page.nextPageToken());
            }
            return Response.ok(resp).build();
        }
        if (ksp.keyId() != null) {
            return Response.ok(service.getKey(p.project(), ksp.email(), ksp.keyId())).build();
        }

        return Response.ok(service.getServiceAccount(p.project(), p.identifier())).build();
    }

    @DELETE
    @Path("/{rest:.*}")
    public Response delete(@PathParam("rest") String rest) {
        IamPath p = parsePath(rest);
        LOG.debugf("IAM DELETE %s project=%s id=%s", rest, p.project(), p.identifier());

        if (!"serviceAccounts".equals(p.resourceType()) || p.identifier() == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        KeySubPath ksp = parseKeySubPath(p.identifier());
        if (ksp.keyId() != null) {
            service.deleteKey(p.project(), ksp.email(), ksp.keyId());
            return Response.ok(Map.of()).build();
        }

        service.deleteServiceAccount(p.project(), p.identifier());
        return Response.ok(Map.of()).build();
    }

    // ── Routing helpers ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Response handleCustomMethod(IamPath p, Map<String, Object> body) {
        String resource = "projects/" + p.project() + "/serviceAccounts/" + p.identifier();
        return switch (p.customMethod()) {
            case "getIamPolicy" -> {
                StoredPolicy policy = service.getPolicy(resource);
                yield Response.ok(policy).build();
            }
            case "setIamPolicy" -> {
                Map<String, Object> policyMap = body != null ? (Map<String, Object>) body.get("policy") : null;
                StoredPolicy policy = parsePolicy(policyMap);
                yield Response.ok(service.setPolicy(resource, policy)).build();
            }
            case "testIamPermissions" -> {
                List<String> requested = body != null ? (List<String>) body.get("permissions") : List.of();
                List<String> granted = service.testPermissions(requested != null ? requested : List.of());
                yield Response.ok(Map.of("permissions", granted)).build();
            }
            case "signBlob" -> {
                String bytesToSign = body != null ? (String) body.get("bytesToSign") : null;
                if (bytesToSign == null || bytesToSign.isBlank()) {
                    yield Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of("error", Map.of("code", 400, "message", "bytesToSign is required", "status", "INVALID_ARGUMENT")))
                            .build();
                }
                Map<String, String> result = service.signBlob(p.project(), p.identifier(), bytesToSign);
                yield Response.ok(result).build();
            }
            default -> Response.status(Response.Status.NOT_FOUND).build();
        };
    }

    @SuppressWarnings("unchecked")
    private Response createServiceAccount(String project, Map<String, Object> body) {
        String accountId = body != null ? (String) body.get("accountId") : null;
        if (accountId == null || accountId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", Map.of("code", 400, "message", "accountId is required", "status", "INVALID_ARGUMENT")))
                    .build();
        }
        Map<String, Object> saProps = body.containsKey("serviceAccount")
                ? (Map<String, Object>) body.get("serviceAccount") : Map.of();
        String displayName = (String) saProps.get("displayName");
        String description = (String) saProps.get("description");
        StoredServiceAccount sa = service.createServiceAccount(project, accountId, displayName, description);
        return Response.ok(sa).build();
    }

    @SuppressWarnings("unchecked")
    private static StoredPolicy parsePolicy(Map<String, Object> policyMap) {
        StoredPolicy policy = new StoredPolicy();
        if (policyMap == null) {
            return policy;
        }
        if (policyMap.containsKey("version")) {
            policy.setVersion(((Number) policyMap.get("version")).intValue());
        }
        if (policyMap.containsKey("bindings")) {
            policy.setBindings((List<Map<String, Object>>) policyMap.get("bindings"));
        }
        if (policyMap.containsKey("etag")) {
            policy.setEtag((String) policyMap.get("etag"));
        }
        return policy;
    }

    // ── Path parsing ───────────────────────────────────────────────────────────

    private static IamPath parsePath(String rest) {
        String customMethod = null;
        String pathPart = rest;
        for (String method : CUSTOM_METHODS) {
            if (rest.endsWith(":" + method)) {
                customMethod = method;
                pathPart = rest.substring(0, rest.length() - method.length() - 1);
                break;
            }
        }
        String[] parts = pathPart.split("/", 3);
        String project = parts.length > 0 ? parts[0] : null;
        String resourceType = parts.length > 1 ? parts[1] : null;
        String identifier = parts.length > 2 ? parts[2] : null;
        return new IamPath(project, resourceType, identifier, customMethod);
    }

    private static KeySubPath parseKeySubPath(String identifier) {
        int keysIdx = identifier.indexOf("/keys");
        if (keysIdx < 0) {
            return new KeySubPath(identifier, false, null);
        }
        String email = identifier.substring(0, keysIdx);
        String after = identifier.substring(keysIdx + "/keys".length());
        if (after.isEmpty() || after.equals("/")) {
            return new KeySubPath(email, true, null);
        }
        String keyId = after.startsWith("/") ? after.substring(1) : after;
        return new KeySubPath(email, false, keyId);
    }

    private record IamPath(String project, String resourceType, String identifier, String customMethod) {}

    private record KeySubPath(String email, boolean isKeyList, String keyId) {}
}
