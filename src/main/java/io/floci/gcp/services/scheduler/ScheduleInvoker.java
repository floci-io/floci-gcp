package io.floci.gcp.services.scheduler;

import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import io.floci.gcp.services.pubsub.PubSubService;
import io.floci.gcp.services.scheduler.model.StoredJob;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Delivers a Cloud Scheduler job to its configured target when the job fires (either via the
 * background {@link ScheduleDispatcher} tick or an explicit {@code RunJob}).
 *
 * <ul>
 *   <li>{@code PubsubTarget} publishes a message into the local Pub/Sub backend
 *       ({@link PubSubService#publish}), so end-to-end pull works.</li>
 *   <li>{@code HttpTarget} performs a real outbound HTTP request.</li>
 *   <li>{@code AppEngineHttpTarget} is best-effort/recorded — there is no App Engine backend
 *       in the emulator.</li>
 * </ul>
 */
@ApplicationScoped
public class ScheduleInvoker {

    private static final Logger LOG = Logger.getLogger(ScheduleInvoker.class);

    // google.rpc.Code values used for the job's last-attempt status.
    private static final int CODE_OK = 0;
    private static final int CODE_UNKNOWN = 2;

    private final PubSubService pubSubService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Inject
    public ScheduleInvoker(PubSubService pubSubService) {
        this.pubSubService = pubSubService;
    }

    // Test constructor.
    ScheduleInvoker(PubSubService pubSubService, boolean ignored) {
        this.pubSubService = pubSubService;
    }

    /** Result of a dispatch attempt, mapped onto the job's google.rpc.Status. */
    public record InvokeResult(int code, String message) {
        static InvokeResult ok() {
            return new InvokeResult(CODE_OK, "");
        }

        static InvokeResult error(String message) {
            return new InvokeResult(CODE_UNKNOWN, message);
        }
    }

    public InvokeResult invoke(StoredJob job) {
        String targetType = job.getTargetType();
        if (targetType == null) {
            return InvokeResult.error("Job has no target");
        }
        try {
            return switch (targetType) {
                case "PUBSUB" -> invokePubsub(job);
                case "HTTP" -> invokeHttp(job);
                case "APP_ENGINE" -> invokeAppEngine(job);
                default -> InvokeResult.error("Unknown target type: " + targetType);
            };
        } catch (Exception e) {
            LOG.warnf("Job %s dispatch failed: %s", job.getName(), e.getMessage());
            return InvokeResult.error(e.getMessage());
        }
    }

    private InvokeResult invokePubsub(StoredJob job) {
        PubsubMessage.Builder msg = PubsubMessage.newBuilder();
        if (job.getPubsubData() != null) {
            msg.setData(ByteString.copyFrom(job.getPubsubData()));
        }
        if (job.getPubsubAttributes() != null) {
            msg.putAllAttributes(job.getPubsubAttributes());
        }
        pubSubService.publish(job.getPubsubTopic(), List.of(msg.build()));
        LOG.infof("Job %s published to topic %s", job.getName(), job.getPubsubTopic());
        return InvokeResult.ok();
    }

    private InvokeResult invokeHttp(StoredJob job) throws Exception {
        String method = (job.getHttpMethod() == null || job.getHttpMethod().isBlank()
                || "HTTP_METHOD_UNSPECIFIED".equals(job.getHttpMethod()))
                ? "POST" : job.getHttpMethod();
        byte[] body = job.getHttpBody();
        HttpRequest.BodyPublisher publisher = (body != null && body.length > 0)
                ? HttpRequest.BodyPublishers.ofByteArray(body)
                : HttpRequest.BodyPublishers.noBody();

        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(job.getHttpUri()))
                .timeout(Duration.ofSeconds(attemptDeadlineOr(job, 180)))
                .method(method, publisher);
        if (job.getHttpHeaders() != null) {
            for (Map.Entry<String, String> h : job.getHttpHeaders().entrySet()) {
                try {
                    req.header(h.getKey(), h.getValue());
                } catch (IllegalArgumentException ignored) {
                    // java.net.http disallows some headers (Host, Content-Length, …); skip them.
                }
            }
        }
        HttpResponse<Void> resp = httpClient.send(req.build(), HttpResponse.BodyHandlers.discarding());
        int status = resp.statusCode();
        LOG.infof("Job %s dispatched to %s -> HTTP %d", job.getName(), job.getHttpUri(), status);
        return (status >= 200 && status < 300)
                ? InvokeResult.ok()
                : InvokeResult.error("HTTP " + status);
    }

    private InvokeResult invokeAppEngine(StoredJob job) {
        // No App Engine backend exists in the emulator; record the attempt as a no-op.
        LOG.infof("Job %s app-engine target recorded (dispatch not emulated)", job.getName());
        return InvokeResult.ok();
    }

    private static long attemptDeadlineOr(StoredJob job, long fallbackSeconds) {
        long d = job.getAttemptDeadlineSeconds();
        return d > 0 ? d : fallbackSeconds;
    }
}
