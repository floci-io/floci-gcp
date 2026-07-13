package io.floci.gcp.services.tasks;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.services.tasks.model.StoredQueue;
import io.floci.gcp.services.tasks.model.StoredTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST (JSON) controller for Cloud Tasks v2 queue/task paths used by CTF IAM enforcement.
 * Primary transport remains gRPC ({@link CloudTasksController}).
 */
@Path("/v2/projects/{project}/locations/{location}")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CloudTasksHttpController {

    private static final Logger LOG = Logger.getLogger(CloudTasksHttpController.class);

    @Inject
    CloudTasksService service;

    @GET
    @Path("/queues")
    public Response listQueues(@PathParam("project") String project,
                               @PathParam("location") String location,
                               @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                               @QueryParam("pageToken") String pageToken) {
        try {
            List<StoredQueue> all = service.listQueues(project, location);
            PageToken.Page<StoredQueue> page = PageToken.paginate(all, pageSize, pageToken);
            List<Map<String, Object>> queues = new ArrayList<>();
            for (StoredQueue q : page.items()) {
                queues.add(queueJson(q));
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("queues", queues);
            if (page.nextPageToken() != null) {
                response.put("nextPageToken", page.nextPageToken());
            }
            return Response.ok(response).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @POST
    @Path("/queues")
    @SuppressWarnings("unchecked")
    public Response createQueue(@PathParam("project") String project,
                                @PathParam("location") String location,
                                @QueryParam("queueId") String queueId,
                                Map<String, Object> body) {
        try {
            String id = resolveQueueId(queueId, body);
            double maxDispatches = 0;
            int maxConcurrent = 0;
            int maxAttempts = 0;
            if (body != null) {
                if (body.get("rateLimits") instanceof Map<?, ?> rl) {
                    Map<String, Object> rateLimits = (Map<String, Object>) rl;
                    if (rateLimits.get("maxDispatchesPerSecond") instanceof Number n) {
                        maxDispatches = n.doubleValue();
                    }
                    if (rateLimits.get("maxConcurrentDispatches") instanceof Number n) {
                        maxConcurrent = n.intValue();
                    }
                }
                if (body.get("retryConfig") instanceof Map<?, ?> rc) {
                    Map<String, Object> retryConfig = (Map<String, Object>) rc;
                    if (retryConfig.get("maxAttempts") instanceof Number n) {
                        maxAttempts = n.intValue();
                    }
                }
            }
            StoredQueue created = service.createQueue(
                    project, location, id, maxDispatches, maxConcurrent, maxAttempts);
            return Response.ok(queueJson(created)).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @GET
    @Path("/queues/{queueId}")
    public Response getQueue(@PathParam("project") String project,
                             @PathParam("location") String location,
                             @PathParam("queueId") String queueId) {
        try {
            StoredQueue queue = service.getQueue(queueName(project, location, queueId));
            return Response.ok(queueJson(queue)).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @DELETE
    @Path("/queues/{queueId}")
    public Response deleteQueue(@PathParam("project") String project,
                                @PathParam("location") String location,
                                @PathParam("queueId") String queueId) {
        try {
            service.deleteQueue(queueName(project, location, queueId));
            return Response.ok(Map.of()).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @POST
    @Path("/queues/{queueId}:pause")
    public Response pauseQueue(@PathParam("project") String project,
                               @PathParam("location") String location,
                               @PathParam("queueId") String queueId) {
        try {
            return Response.ok(queueJson(service.pauseQueue(queueName(project, location, queueId)))).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @POST
    @Path("/queues/{queueId}:resume")
    public Response resumeQueue(@PathParam("project") String project,
                                @PathParam("location") String location,
                                @PathParam("queueId") String queueId) {
        try {
            return Response.ok(queueJson(service.resumeQueue(queueName(project, location, queueId)))).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @POST
    @Path("/queues/{queueId}:purge")
    public Response purgeQueue(@PathParam("project") String project,
                               @PathParam("location") String location,
                               @PathParam("queueId") String queueId) {
        try {
            return Response.ok(queueJson(service.purgeQueue(queueName(project, location, queueId)))).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @GET
    @Path("/queues/{queueId}/tasks")
    public Response listTasks(@PathParam("project") String project,
                              @PathParam("location") String location,
                              @PathParam("queueId") String queueId,
                              @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                              @QueryParam("pageToken") String pageToken) {
        try {
            List<StoredTask> all = service.listTasks(queueName(project, location, queueId));
            PageToken.Page<StoredTask> page = PageToken.paginate(all, pageSize, pageToken);
            List<Map<String, Object>> tasks = new ArrayList<>();
            for (StoredTask t : page.items()) {
                tasks.add(taskJson(t));
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("tasks", tasks);
            if (page.nextPageToken() != null) {
                response.put("nextPageToken", page.nextPageToken());
            }
            return Response.ok(response).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @POST
    @Path("/queues/{queueId}/tasks")
    @SuppressWarnings("unchecked")
    public Response createTask(@PathParam("project") String project,
                               @PathParam("location") String location,
                               @PathParam("queueId") String queueId,
                               Map<String, Object> body) {
        try {
            String queue = queueName(project, location, queueId);
            String taskId = null;
            String taskType = "HTTP";
            String httpMethod = null;
            String url = null;
            Map<String, String> headers = null;
            byte[] bodyBytes = null;
            String appEngineHttpMethod = null;
            String relativeUri = null;
            String scheduleTime = null;

            if (body != null) {
                if (body.get("name") instanceof String name && !name.isBlank()) {
                    int idx = name.lastIndexOf('/');
                    taskId = idx >= 0 ? name.substring(idx + 1) : name;
                }
                if (body.get("scheduleTime") instanceof String st) {
                    scheduleTime = st;
                }
                if (body.get("httpRequest") instanceof Map<?, ?> hr) {
                    Map<String, Object> httpRequest = (Map<String, Object>) hr;
                    taskType = "HTTP";
                    if (httpRequest.get("httpMethod") != null) {
                        httpMethod = String.valueOf(httpRequest.get("httpMethod"));
                    }
                    if (httpRequest.get("url") instanceof String u) {
                        url = u;
                    }
                    if (httpRequest.get("headers") instanceof Map<?, ?> h) {
                        headers = (Map<String, String>) h;
                    }
                    if (httpRequest.get("body") instanceof String b) {
                        bodyBytes = java.util.Base64.getDecoder().decode(b);
                    }
                } else if (body.get("appEngineHttpRequest") instanceof Map<?, ?> ae) {
                    Map<String, Object> appEngine = (Map<String, Object>) ae;
                    taskType = "APP_ENGINE";
                    if (appEngine.get("httpMethod") != null) {
                        appEngineHttpMethod = String.valueOf(appEngine.get("httpMethod"));
                    }
                    if (appEngine.get("relativeUri") instanceof String ru) {
                        relativeUri = ru;
                    }
                    if (appEngine.get("headers") instanceof Map<?, ?> h) {
                        headers = (Map<String, String>) h;
                    }
                    if (appEngine.get("body") instanceof String b) {
                        bodyBytes = java.util.Base64.getDecoder().decode(b);
                    }
                }
            }

            StoredTask created = service.createTask(
                    queue, taskId, taskType, httpMethod, url, headers, bodyBytes,
                    appEngineHttpMethod, relativeUri, scheduleTime);
            return Response.ok(taskJson(created)).build();
        } catch (IllegalArgumentException e) {
            return error(GcpException.invalidArgument(e.getMessage()));
        } catch (GcpException e) {
            return error(e);
        }
    }

    @GET
    @Path("/queues/{queueId}/tasks/{taskId}")
    public Response getTask(@PathParam("project") String project,
                            @PathParam("location") String location,
                            @PathParam("queueId") String queueId,
                            @PathParam("taskId") String taskId) {
        try {
            StoredTask task = service.getTask(taskName(project, location, queueId, taskId));
            return Response.ok(taskJson(task)).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @DELETE
    @Path("/queues/{queueId}/tasks/{taskId}")
    public Response deleteTask(@PathParam("project") String project,
                               @PathParam("location") String location,
                               @PathParam("queueId") String queueId,
                               @PathParam("taskId") String taskId) {
        try {
            service.deleteTask(taskName(project, location, queueId, taskId));
            return Response.ok(Map.of()).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    @POST
    @Path("/queues/{queueId}/tasks/{taskId}:run")
    public Response runTask(@PathParam("project") String project,
                            @PathParam("location") String location,
                            @PathParam("queueId") String queueId,
                            @PathParam("taskId") String taskId) {
        try {
            StoredTask task = service.runTask(taskName(project, location, queueId, taskId));
            return Response.ok(taskJson(task)).build();
        } catch (GcpException e) {
            return error(e);
        }
    }

    private static String resolveQueueId(String queueId, Map<String, Object> body) {
        if (queueId != null && !queueId.isBlank()) {
            return queueId;
        }
        if (body != null && body.get("name") instanceof String name && !name.isBlank()) {
            int idx = name.lastIndexOf('/');
            return idx >= 0 ? name.substring(idx + 1) : name;
        }
        throw GcpException.invalidArgument("queueId is required");
    }

    private static String queueName(String project, String location, String queueId) {
        return "projects/" + project + "/locations/" + location + "/queues/" + queueId;
    }

    private static String taskName(String project, String location, String queueId, String taskId) {
        return queueName(project, location, queueId) + "/tasks/" + taskId;
    }

    private static Map<String, Object> queueJson(StoredQueue q) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", q.getName());
        json.put("state", q.getState());
        Map<String, Object> rateLimits = new LinkedHashMap<>();
        rateLimits.put("maxDispatchesPerSecond", q.getMaxDispatchesPerSecond());
        rateLimits.put("maxConcurrentDispatches", q.getMaxConcurrentDispatches());
        rateLimits.put("maxBurstSize", q.getMaxBurstSize());
        json.put("rateLimits", rateLimits);
        json.put("retryConfig", Map.of("maxAttempts", q.getMaxAttempts()));
        if (q.getPurgeTime() != null) {
            json.put("purgeTime", q.getPurgeTime());
        }
        return json;
    }

    private static Map<String, Object> taskJson(StoredTask t) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", t.getName());
        json.put("createTime", t.getCreateTime());
        json.put("scheduleTime", t.getScheduleTime());
        json.put("dispatchCount", t.getDispatchCount());
        json.put("responseCount", t.getResponseCount());
        if ("APP_ENGINE".equals(t.getTaskType())) {
            Map<String, Object> ae = new LinkedHashMap<>();
            if (t.getAppEngineHttpMethod() != null) {
                ae.put("httpMethod", t.getAppEngineHttpMethod());
            }
            if (t.getRelativeUri() != null) {
                ae.put("relativeUri", t.getRelativeUri());
            }
            if (t.getHeaders() != null) {
                ae.put("headers", t.getHeaders());
            }
            if (t.getBody() != null && t.getBody().length > 0) {
                ae.put("body", java.util.Base64.getEncoder().encodeToString(t.getBody()));
            }
            json.put("appEngineHttpRequest", ae);
        } else {
            Map<String, Object> hr = new LinkedHashMap<>();
            if (t.getHttpMethod() != null) {
                hr.put("httpMethod", t.getHttpMethod());
            }
            if (t.getUrl() != null) {
                hr.put("url", t.getUrl());
            }
            if (t.getHeaders() != null) {
                hr.put("headers", t.getHeaders());
            }
            if (t.getBody() != null && t.getBody().length > 0) {
                hr.put("body", java.util.Base64.getEncoder().encodeToString(t.getBody()));
            }
            json.put("httpRequest", hr);
        }
        return json;
    }

    private static Response error(GcpException e) {
        LOG.debugf("Cloud Tasks REST error: %s", e.getMessage());
        return Response.status(e.getHttpStatus())
                .entity(Map.of("error", Map.of(
                        "code", e.getHttpStatus(),
                        "message", e.getMessage() != null ? e.getMessage() : "",
                        "status", e.getGcpStatus())))
                .build();
    }
}
