package io.floci.gcp.services.locations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProtoJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * Shared REST handling for the Locations mixin. JAX-RS picks the resource class with the longest
 * literal prefix, so list and get each live on the class path that already hosts the regional APIs
 * ({@code .../locations} for KMS, {@code .../locations/{location}} for Scheduler, Eventarc, Kafka,
 * Cloud Run and Cloud Functions); RESTEasy merges same-path classes.
 */
@ApplicationScoped
public class LocationsRestHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LocationsService service;

    @Inject
    public LocationsRestHandler(LocationsService service) {
        this.service = service;
    }

    public Response list(String project, int pageSize, String pageToken, HttpHeaders headers, UriInfo uriInfo) {
        String json = ProtoJson.print(service.listLocations(project, api(headers, uriInfo), pageSize, pageToken));
        try {
            ObjectNode body = (ObjectNode) JSON.readTree(json);
            if (!body.has("locations")) {
                body.set("locations", JSON.createArrayNode());
            }
            return Response.ok(JSON.writeValueAsString(body), MediaType.APPLICATION_JSON_TYPE).build();
        } catch (JsonProcessingException e) {
            throw GcpException.internal("Failed to serialize locations: " + e.getMessage());
        }
    }

    public Response get(String project, String location, HttpHeaders headers, UriInfo uriInfo) {
        return Response.ok(ProtoJson.print(service.getLocation(project, location, api(headers, uriInfo))),
                MediaType.APPLICATION_JSON_TYPE).build();
    }

    private static String api(HttpHeaders headers, UriInfo uriInfo) {
        String host = headers.getHeaderString(HttpHeaders.HOST);
        if (host == null || host.isBlank()) {
            host = uriInfo.getRequestUri().getHost();
        }
        return LocationsService.apiFromHost(host);
    }
}
