package io.floci.gcp.services.cloudlogging;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.services.cloudlogging.model.StoredLogEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for the Cloud Logging v2 API (JSON transport), mirroring the URL shapes of the
 * real logging.googleapis.com API so REST clients and gcloud work unchanged. Cloud Logging is the
 * only {@code /v2} service, so there are no path conflicts with other controllers.
 */
@Path("/v2")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CloudLoggingHttpController {

    private static final Logger LOG = Logger.getLogger(CloudLoggingHttpController.class);

    @Inject
    CloudLoggingService service;

    @POST
    @Path("/entries:write")
    @SuppressWarnings("unchecked")
    public Response writeLogEntries(Map<String, Object> body) {
        try {
            String defaultLogName = (String) body.get("logName");
            String defaultResourceType = null;
            Map<String, String> defaultResourceLabels = null;
            if (body.get("resource") instanceof Map<?, ?> r) {
                defaultResourceType = (String) ((Map<String, Object>) r).get("type");
                if (((Map<String, Object>) r).get("labels") instanceof Map<?, ?> l) {
                    defaultResourceLabels = (Map<String, String>) l;
                }
            }
            Map<String, String> defaultLabels =
                    body.get("labels") instanceof Map<?, ?> l ? (Map<String, String>) l : null;
            boolean dryRun = Boolean.TRUE.equals(body.get("dryRun"));

            List<StoredLogEntry> entries = new ArrayList<>();
            if (body.get("entries") instanceof List<?> rawEntries) {
                for (Object o : rawEntries) {
                    entries.add(toStored((Map<String, Object>) o));
                }
            }
            service.writeLogEntries(defaultLogName, defaultResourceType, defaultResourceLabels,
                    defaultLabels, entries, dryRun);
            return Response.ok(Map.of()).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @POST
    @Path("/entries:list")
    @SuppressWarnings("unchecked")
    public Response listLogEntries(Map<String, Object> body) {
        try {
            List<String> resourceNames = body.get("resourceNames") instanceof List<?> l
                    ? (List<String>) l : List.of();
            String filter = (String) body.get("filter");
            String orderBy = (String) body.get("orderBy");
            int pageSize = body.get("pageSize") instanceof Number n ? n.intValue() : 0;
            String pageToken = (String) body.get("pageToken");

            PageToken.Page<StoredLogEntry> page =
                    service.listLogEntries(resourceNames, filter, orderBy, pageSize, pageToken);
            List<Map<String, Object>> entries = new ArrayList<>();
            for (StoredLogEntry entry : page.items()) {
                entries.add(toJson(entry));
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("entries", entries);
            if (page.nextPageToken() != null) {
                response.put("nextPageToken", page.nextPageToken());
            }
            return Response.ok(response).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @GET
    @Path("/projects/{project}/logs")
    public Response listLogs(@PathParam("project") String project) {
        try {
            List<String> logNames = service.listLogs("projects/" + project);
            return Response.ok(Map.of("logNames", logNames)).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @DELETE
    @Path("/projects/{project}/logs/{logId}")
    public Response deleteLog(@PathParam("project") String project, @PathParam("logId") String logId) {
        try {
            service.deleteLog("projects/" + project + "/logs/" + logId);
            return Response.ok(Map.of()).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    // ── JSON mapping ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static StoredLogEntry toStored(Map<String, Object> m) {
        StoredLogEntry e = new StoredLogEntry();
        e.setLogName((String) m.get("logName"));
        if (m.get("severity") != null) {
            e.setSeverity(String.valueOf(m.get("severity")));
        }
        e.setTextPayload((String) m.get("textPayload"));
        if (m.get("jsonPayload") != null) {
            e.setJsonPayload(m.get("jsonPayload"));
        }
        if (m.get("resource") instanceof Map<?, ?> r) {
            e.setResourceType((String) ((Map<String, Object>) r).get("type"));
            if (((Map<String, Object>) r).get("labels") instanceof Map<?, ?> l) {
                e.setResourceLabels((Map<String, String>) l);
            }
        }
        if (m.get("labels") instanceof Map<?, ?> l) {
            e.setLabels((Map<String, String>) l);
        }
        e.setTimestamp((String) m.get("timestamp"));
        e.setInsertId((String) m.get("insertId"));
        e.setTrace((String) m.get("trace"));
        e.setSpanId((String) m.get("spanId"));
        return e;
    }

    private static Map<String, Object> toJson(StoredLogEntry e) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("logName", e.getLogName());
        json.put("severity", e.getSeverity());
        if (e.getInsertId() != null) {
            json.put("insertId", e.getInsertId());
        }
        if (e.getTextPayload() != null) {
            json.put("textPayload", e.getTextPayload());
        } else if (e.getJsonPayload() != null) {
            json.put("jsonPayload", e.getJsonPayload());
        }
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("type", e.getResourceType());
        if (e.getResourceLabels() != null) {
            resource.put("labels", e.getResourceLabels());
        }
        json.put("resource", resource);
        if (e.getLabels() != null) {
            json.put("labels", e.getLabels());
        }
        if (e.getTimestamp() != null) {
            json.put("timestamp", e.getTimestamp());
        }
        if (e.getReceiveTimestamp() != null) {
            json.put("receiveTimestamp", e.getReceiveTimestamp());
        }
        if (e.getTrace() != null) {
            json.put("trace", e.getTrace());
        }
        if (e.getSpanId() != null) {
            json.put("spanId", e.getSpanId());
        }
        return json;
    }

    private static Response error(GcpException e) {
        LOG.debugf("Logging REST error: %s", e.getMessage());
        return Response.status(e.getHttpStatus())
                .entity(Map.of("error", Map.of(
                        "code", e.getHttpStatus(),
                        "message", e.getMessage() != null ? e.getMessage() : "",
                        "status", e.getGcpStatus())))
                .build();
    }
}
