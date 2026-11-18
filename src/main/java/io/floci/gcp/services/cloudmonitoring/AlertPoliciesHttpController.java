package io.floci.gcp.services.cloudmonitoring;

import com.google.monitoring.v3.AlertPolicy;
import com.google.protobuf.FieldMask;
import com.google.protobuf.util.FieldMaskUtil;
import io.floci.gcp.core.common.ProtoJson;
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

/** Monitoring v3 REST {@code projects.alertPolicies}. */
@Path("/v3/projects/{project}/alertPolicies")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AlertPoliciesHttpController {

    private final AlertPolicyService policies;

    @Inject
    public AlertPoliciesHttpController(AlertPolicyService policies) {
        this.policies = policies;
    }

    @GET
    public Response listAlertPolicies(@PathParam("project") String project, @QueryParam("filter") String filter,
            @QueryParam("pageSize") @DefaultValue("0") int pageSize, @QueryParam("pageToken") String pageToken) {
        return json(ProtoJson.print(policies.list("projects/" + project, filter, pageSize, pageToken)));
    }

    @POST
    public Response createAlertPolicy(@PathParam("project") String project, String body) {
        AlertPolicy policy = ProtoJson.merge(body, AlertPolicy.newBuilder()).build();
        return json(ProtoJson.print(policies.create("projects/" + project, policy)));
    }

    @GET
    @Path("/{policy}")
    public Response getAlertPolicy(@PathParam("project") String project, @PathParam("policy") String policy) {
        return json(ProtoJson.print(policies.get(policyName(project, policy))));
    }

    @PATCH
    @Path("/{policy}")
    public Response updateAlertPolicy(@PathParam("project") String project, @PathParam("policy") String policy,
            @QueryParam("updateMask") String updateMask, String body) {
        AlertPolicy requested = ProtoJson.merge(body, AlertPolicy.newBuilder()).setName(policyName(project, policy)).build();
        return json(ProtoJson.print(policies.update(requested, mask(updateMask))));
    }

    @DELETE
    @Path("/{policy}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteAlertPolicy(@PathParam("project") String project, @PathParam("policy") String policy) {
        policies.delete(policyName(project, policy));
        return json("{}");
    }

    private static String policyName(String project, String policy) {
        return "projects/" + project + "/alertPolicies/" + policy;
    }

    private static FieldMask mask(String updateMask) {
        return FieldMaskUtil.fromJsonString(updateMask == null ? "" : updateMask);
    }

    private static Response json(String body) {
        return Response.ok(body, MediaType.APPLICATION_JSON).build();
    }
}
