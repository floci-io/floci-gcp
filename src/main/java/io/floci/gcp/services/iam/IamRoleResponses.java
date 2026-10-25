package io.floci.gcp.services.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.common.GcpException;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/** Request-body handling shared by the project and organization role controllers. */
final class IamRoleResponses {

    private IamRoleResponses() {}

    @SuppressWarnings("unchecked")
    static Response create(IamRoleService service, String parent, Map<String, Object> body) {
        Map<String, Object> request = body != null ? body : Map.of();
        Object roleId = request.get("roleId");
        Object role = request.get("role");
        if (roleId != null && !(roleId instanceof String)) {
            throw GcpException.invalidArgument("Invalid value at 'role_id' (TYPE_STRING), " + roleId);
        }
        if (role != null && !(role instanceof Map<?, ?>)) {
            throw GcpException.invalidArgument("Invalid value at 'role' (type.googleapis.com/google.iam.admin.v1.Role), "
                    + (role instanceof String text ? "\"" + text + "\"" : role));
        }
        return Response.ok(service.create(parent, (String) roleId, (Map<String, Object>) role)).build();
    }

    static Response list(IamRoleService service, String parent, String view, boolean showDeleted,
            int pageSize, String pageToken) {
        IamRoleService.RolePage page = service.list(parent, view, showDeleted, pageSize, pageToken);
        Map<String, Object> response = new LinkedHashMap<>();
        if (!page.roles().isEmpty()) {
            response.put("roles", page.roles());
        }
        if (page.nextPageToken() != null) {
            response.put("nextPageToken", page.nextPageToken());
        }
        return Response.ok(response).build();
    }

    /** The IAM v1 Java client may send an empty body with a form content type, so JSON is parsed here. */
    static Response undelete(IamRoleService service, String name, String body, ObjectMapper mapper) {
        JsonNode request;
        try {
            request = body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw GcpException.invalidArgument("Invalid JSON request body");
        }
        JsonNode etag = request.path("etag");
        if (!etag.isMissingNode() && !etag.isNull() && !etag.isTextual()) {
            throw GcpException.invalidArgument("Invalid value at 'etag' (TYPE_BYTES), " + etag);
        }
        return Response.ok(service.undelete(name, etag.isTextual() ? etag.asText() : null)).build();
    }
}
