package io.floci.gcp.services.sts;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

@Path("/v1")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class StsController {

	@Inject
	StsService service;

	@POST
	@Path("/token")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response token(@FormParam("grant_type") String grantType,
			@FormParam("subject_token_type") String subjectTokenType,
			@FormParam("subject_token") String subjectToken,
			@FormParam("requested_token_type") String requestedTokenType,
			@FormParam("options") String options) {
		try {
			return Response.ok(service.exchangeToken(
					grantType, subjectTokenType, subjectToken, requestedTokenType, options)).build();
		} catch (StsException e) {
			Map<String, Object> error = new LinkedHashMap<>();
			error.put("error", e.error());
			error.put("error_description", e.getMessage());
			return Response.status(400).type(MediaType.APPLICATION_JSON).entity(error).build();
		}
	}
}
