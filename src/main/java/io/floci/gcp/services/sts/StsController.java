package io.floci.gcp.services.sts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Path("/v1")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class StsController {

    private final StsService service;
    private final ObjectMapper mapper;

    @Inject
    public StsController(StsService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response tokenForm(@FormParam("grant_type") String grantType,
            @FormParam("subject_token_type") String subjectTokenType,
            @FormParam("subject_token") String subjectToken,
            @FormParam("requested_token_type") String requestedTokenType,
            @FormParam("options") String options,
            @FormParam("audience") String audience,
            @FormParam("scope") String scope) {
        return handle(() -> service.exchangeToken(
                grantType, subjectTokenType, subjectToken, requestedTokenType, options, audience, scope));
    }

    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response tokenJson(String body) {
        return handle(() -> {
            JsonNode root = parseJson(body);
            String grantType = text(root, "grant_type", "grantType");
            String subjectTokenType = text(root, "subject_token_type", "subjectTokenType");
            String subjectToken = text(root, "subject_token", "subjectToken");
            String requestedTokenType = text(root, "requested_token_type", "requestedTokenType");
            String options = optionsText(root);
            String audience = text(root, "audience", "audience");
            String scope = text(root, "scope", "scope");
            return service.exchangeToken(
                    grantType, subjectTokenType, subjectToken, requestedTokenType, options, audience, scope);
        });
    }

    private Response handle(ExchangeSupplier action) {
        try {
            return Response.ok(action.get()).build();
        } catch (StsException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.error());
            error.put("error_description", e.getMessage());
            return Response.status(400).type(MediaType.APPLICATION_JSON).entity(error).build();
        }
    }

    private JsonNode parseJson(String body) {
        if (body == null || body.isBlank()) {
            throw new StsException("invalid_request", "Request body is required");
        }
        try {
            JsonNode node = mapper.readTree(body);
            if (node == null || !node.isObject()) {
                throw new StsException("invalid_request", "Request body must be a JSON object");
            }
            return node;
        } catch (IOException e) {
            throw new StsException("invalid_request", "Malformed JSON request body");
        }
    }

    private static String text(JsonNode root, String snakeCase, String camelCase) {
        JsonNode node = root.get(snakeCase);
        if (node == null || node.isNull()) {
            node = root.get(camelCase);
        }
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static String optionsText(JsonNode root) {
        JsonNode node = root.get("options");
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    @FunctionalInterface
    private interface ExchangeSupplier {
        Map<String, Object> get();
    }
}
