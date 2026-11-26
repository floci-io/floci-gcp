package io.floci.gcp.services.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.core.common.GcpException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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

import java.util.List;
import java.util.Map;

/**
 * REST controller for the IAM Workload Identity Pools API. Every write returns a completed
 * {@code google.longrunning.Operation}; pool and provider operation names are nested under the
 * resource, matching the IAM discovery document.
 */
@Path("/v1/projects/{project}/locations/{location}/workloadIdentityPools")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IamWorkloadIdentityPoolController {

    private final IamWorkloadIdentityPoolService service;
    private final ObjectMapper mapper;

    @Inject
    public IamWorkloadIdentityPoolController(IamWorkloadIdentityPoolService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    // ── Pools ─────────────────────────────────────────────────────────────────

    @POST
    public Response createPool(@PathParam("project") String project,
            @PathParam("location") String location,
            @QueryParam("workloadIdentityPoolId") String poolId,
            String body) {
        return json(service.createPool(project, location, poolId, parse(body)));
    }

    @GET
    public Response listPools(@PathParam("project") String project,
            @PathParam("location") String location,
            @QueryParam("showDeleted") @DefaultValue("false") boolean showDeleted,
            @QueryParam("pageSize") @DefaultValue("0") int pageSize,
            @QueryParam("pageToken") String pageToken) {
        return json(service.listPools(project, location, showDeleted, pageSize, pageToken));
    }

    @GET
    @Path("/{poolId}")
    public Response getPool(@PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("poolId") String poolId) {
        return json(service.getPool(project, location, poolId));
    }

    @GET
    @Path("/{poolId}:listAttestationRules")
    public Response listAttestationRules(@PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("poolId") String poolId) {
        return json(service.listAttestationRules(project, location, poolId));
    }

    @PATCH
    @Path("/{poolId}")
    public Response patchPool(@PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("poolId") String poolId,
            @QueryParam("updateMask") String updateMask,
            String body) {
        return json(service.patchPool(project, location, poolId, updateMask, parse(body)));
    }

    @DELETE
    @Path("/{poolId}")
    public Response deletePool(@PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("poolId") String poolId) {
        return json(service.deletePool(project, location, poolId));
    }

    @POST
    @Path("/{poolId}:undelete")
    public Response undeletePool(@PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("poolId") String poolId) {
        return json(service.undeletePool(project, location, poolId));
    }

    @POST
    @Path("/{poolId}:getIamPolicy")
    public Response getPoolIamPolicy(@PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("poolId") String poolId) {
        return Response.ok(service.getPoolPolicy(project, location, poolId)).build();
    }

    @POST
    @Path("/{poolId}:setIamPolicy")
    public Response setPoolIamPolicy(@PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("poolId") String poolId,
            Map<String, Object> body) {
        return Response.ok(service.setPoolPolicy(project, location, poolId, body)).build();
    }

    @POST
    @Path("/{poolId}:testIamPermissions")
    public Response testPoolIamPermissions(@PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("poolId") String poolId,
            Map<String, Object> body) {
        List<String> requested = body != null && body.get("permissions") instanceof List<?> values
                ? values.stream().map(String::valueOf).toList() : List.of();
        return Response.ok(Map.of("permissions",
                service.testPoolPermissions(project, location, poolId, requested))).build();
    }

    // ── Providers ─────────────────────────────────────────────────────────────

    @POST
    @Path("/{poolId}/providers")
    public Response createProvider(@PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("poolId") String poolId,
            @QueryParam("workloadIdentityPoolProviderId") String providerId,
            String body) {
        return json(service.createProvider(project, location, poolId, providerId, parse(body)));
    }

    @GET
    @Path("/{poolId}/providers")
    public Response listProviders(@PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("poolId") String poolId,
            @QueryParam("showDeleted") @DefaultValue("false") boolean showDeleted,
            @QueryParam("pageSize") @DefaultValue("0") int pageSize,
            @QueryParam("pageToken") String pageToken) {
        return json(service.listProviders(project, location, poolId, showDeleted, pageSize, pageToken));
    }

    @GET
    @Path("/{poolId}/providers/{providerId}")
    public Response getProvider(@PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("poolId") String poolId,
            @PathParam("providerId") String providerId) {
        return json(service.getProvider(project, location, poolId, providerId));
    }

    @PATCH
    @Path("/{poolId}/providers/{providerId}")
    public Response patchProvider(@PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("poolId") String poolId,
            @PathParam("providerId") String providerId,
            @QueryParam("updateMask") String updateMask,
            String body) {
        return json(service.patchProvider(project, location, poolId, providerId, updateMask, parse(body)));
    }

    @DELETE
    @Path("/{poolId}/providers/{providerId}")
    public Response deleteProvider(@PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("poolId") String poolId,
            @PathParam("providerId") String providerId) {
        return json(service.deleteProvider(project, location, poolId, providerId));
    }

    @POST
    @Path("/{poolId}/providers/{providerId}:undelete")
    public Response undeleteProvider(@PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("poolId") String poolId,
            @PathParam("providerId") String providerId) {
        return json(service.undeleteProvider(project, location, poolId, providerId));
    }

    // ── Operations ────────────────────────────────────────────────────────────

    @GET
    @Path("/{poolId}/operations/{operationId}")
    public Response getPoolOperation(@PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("poolId") String poolId,
            @PathParam("operationId") String operationId) {
        return json(service.getPoolOperation(project, location, poolId, operationId));
    }

    @GET
    @Path("/{poolId}/providers/{providerId}/operations/{operationId}")
    public Response getProviderOperation(@PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("poolId") String poolId,
            @PathParam("providerId") String providerId,
            @PathParam("operationId") String operationId) {
        return json(service.getProviderOperation(project, location, poolId, providerId, operationId));
    }

    private ObjectNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(body);
            if (node instanceof ObjectNode object) {
                return object;
            }
        } catch (JsonProcessingException e) {
            // Fall through to the API's invalid-body answer.
        }
        throw GcpException.invalidArgument("Invalid JSON request body");
    }

    private static Response json(JsonNode body) {
        return Response.ok(body.toString(), MediaType.APPLICATION_JSON).build();
    }
}
