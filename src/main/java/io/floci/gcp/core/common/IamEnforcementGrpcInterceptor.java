package io.floci.gcp.core.common;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.services.iam.IamGrpcPermissionMapper;
import io.floci.gcp.services.iam.IamPolicyEvaluator;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enforces project IAM allow policies on Firestore, Datastore, Pub/Sub, Secret Manager,
 * Cloud Tasks, Cloud KMS, Cloud Scheduler, Cloud Logging, and Cloud Monitoring gRPC
 * methods when {@code floci-gcp.services.iam.enforcement-enabled} is on. Operator root
 * principals bypass evaluation. Other gRPC services remain ungated.
 *
 * <p>Project resolution for policy lookup (best-effort, no request-body buffering):
 * <ol>
 *   <li>{@code project=} / {@code project_id=} in {@code x-goog-request-params}</li>
 *   <li>Project segment from {@code parent=} / {@code name=} / {@code topic=} /
 *       {@code subscription=} resource names in that header (URL-decoded)</li>
 *   <li>{@link EmulatorConfig#defaultProjectId()} fallback</li>
 * </ol>
 * Empty project IDs are rejected. The interceptor does not read the protobuf body, so a
 * mismatched body project vs header is a residual CTF risk (see docs/services/iam.md).
 */
@ApplicationScoped
public class IamEnforcementGrpcInterceptor implements ServerInterceptor {

    private static final Logger LOG = Logger.getLogger(IamEnforcementGrpcInterceptor.class);

    private static final String FIRESTORE_PREFIX = "google.firestore.v1.Firestore/";
    private static final String DATASTORE_PREFIX = "google.datastore.v1.Datastore/";
    private static final String PUBSUB_PREFIX = "google.pubsub.v1.";
    private static final String SECRET_MANAGER_PREFIX = "google.cloud.secretmanager.v1.";
    private static final String CLOUD_TASKS_PREFIX = "google.cloud.tasks.v2.CloudTasks/";
    private static final String CLOUD_KMS_PREFIX = "google.cloud.kms.v1.KeyManagementService/";
    private static final String CLOUD_SCHEDULER_PREFIX = "google.cloud.scheduler.v1.CloudScheduler/";
    private static final String LOGGING_PREFIX = "google.logging.v2.LoggingServiceV2/";
    private static final String MONITORING_PREFIX = "google.monitoring.v3.MetricService/";

    private static final Pattern PARAM_PAIR = Pattern.compile("(?:^|&)([^=&]+)=([^&]*)");
    private static final Pattern PROJECTS_SEGMENT = Pattern.compile("(?:^|/)projects/([^/]+)");
    private static final String[] RESOURCE_PARAM_KEYS = {"parent", "name", "topic", "subscription"};

    static final Metadata.Key<String> GOOG_REQUEST_PARAMS_KEY =
            Metadata.Key.of("x-goog-request-params", Metadata.ASCII_STRING_MARSHALLER);

    private final EmulatorConfig config;
    private final IamService iamService;
    private final IamPolicyEvaluator policyEvaluator;
    private final IamGrpcPermissionMapper permissionMapper;

    @Inject
    public IamEnforcementGrpcInterceptor(EmulatorConfig config,
                                         IamService iamService,
                                         IamPolicyEvaluator policyEvaluator,
                                         IamGrpcPermissionMapper permissionMapper) {
        this.config = config;
        this.iamService = iamService;
        this.policyEvaluator = policyEvaluator;
        this.permissionMapper = permissionMapper;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        if (!config.services().iam().enforcementEnabled()) {
            return next.startCall(call, headers);
        }

        String fullMethod = call.getMethodDescriptor().getFullMethodName();
        if (!isEnforcedGrpcMethod(fullMethod)) {
            return next.startCall(call, headers);
        }

        if (Boolean.TRUE.equals(AuthGrpcContext.OPERATOR_ROOT.get())) {
            return next.startCall(call, headers);
        }

        boolean strict = config.services().iam().strictEnforcementEnabled();
        String principal = AuthGrpcContext.PRINCIPAL_EMAIL.get();
        if (principal == null || principal.isBlank()) {
            // Enforcement on always requires a principal for mapped gRPC surfaces (CTF).
            LOG.debugf("IAM gRPC DENY: missing principal on %s", fullMethod);
            return deny(call, "Permission denied: missing authenticated principal.");
        }

        Optional<String> permission = permissionMapper.map(fullMethod);
        if (permission.isEmpty()) {
            if (strict) {
                LOG.debugf("IAM gRPC DENY: unmapped permission on %s", fullMethod);
                return deny(call, "Permission denied: no IAM mapping for this request.");
            }
            return next.startCall(call, headers);
        }

        String projectId = resolveProjectId(headers);
        if (projectId == null || projectId.isBlank()) {
            LOG.debugf("IAM gRPC DENY: empty project on %s", fullMethod);
            return deny(call, "Permission denied: missing project for IAM evaluation.");
        }

        String resource = "projects/" + projectId;
        StoredPolicy policy = iamService.getPolicy(resource);
        boolean allowed = policyEvaluator.isAllowed(policy.getBindings(), principal, permission.get());
        if (!allowed) {
            LOG.debugf("IAM gRPC DENY: principal=%s permission=%s resource=%s method=%s",
                    principal, permission.get(), resource, fullMethod);
            return deny(call,
                    "Permission '" + permission.get() + "' denied on resource '" + resource + "'.");
        }
        return next.startCall(call, headers);
    }

    /**
     * Resolves the project used for {@code getPolicy("projects/{id}")}. Prefers
     * {@code x-goog-request-params} (including resource-name fields used by Secret Manager
     * and Pub/Sub clients). Does not inspect the request body.
     */
    String resolveProjectId(Metadata headers) {
        if (headers != null) {
            String params = headers.get(GOOG_REQUEST_PARAMS_KEY);
            String fromParams = projectFromRequestParams(params);
            if (fromParams != null && !fromParams.isBlank()) {
                return fromParams;
            }
        }
        String fallback = config.defaultProjectId();
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return null;
    }

    private static String projectFromRequestParams(String params) {
        if (params == null || params.isBlank()) {
            return null;
        }

        String projectKey = firstParamValue(params, "project");
        if (projectKey != null && !projectKey.isBlank()) {
            return projectKey;
        }
        String projectIdKey = firstParamValue(params, "project_id");
        if (projectIdKey != null && !projectIdKey.isBlank()) {
            return projectIdKey;
        }

        for (String key : RESOURCE_PARAM_KEYS) {
            String resourceName = firstParamValue(params, key);
            String fromResource = projectFromResourceName(resourceName);
            if (fromResource != null && !fromResource.isBlank()) {
                return fromResource;
            }
        }
        return null;
    }

    private static String firstParamValue(String params, String key) {
        Matcher m = PARAM_PAIR.matcher(params);
        while (m.find()) {
            String decodedKey = urlDecode(m.group(1));
            if (key.equals(decodedKey)) {
                return urlDecode(m.group(2));
            }
        }
        return null;
    }

    private static String projectFromResourceName(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            return null;
        }
        Matcher m = PROJECTS_SEGMENT.matcher(resourceName);
        if (m.find()) {
            String project = m.group(1);
            return project != null && !project.isBlank() ? project : null;
        }
        return null;
    }

    private static String urlDecode(String value) {
        if (value == null) {
            return null;
        }
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static boolean isEnforcedGrpcMethod(String fullMethod) {
        return fullMethod != null
                && (fullMethod.startsWith(FIRESTORE_PREFIX)
                || fullMethod.startsWith(DATASTORE_PREFIX)
                || fullMethod.startsWith(PUBSUB_PREFIX)
                || fullMethod.startsWith(SECRET_MANAGER_PREFIX)
                || fullMethod.startsWith(CLOUD_TASKS_PREFIX)
                || fullMethod.startsWith(CLOUD_KMS_PREFIX)
                || fullMethod.startsWith(CLOUD_SCHEDULER_PREFIX)
                || fullMethod.startsWith(LOGGING_PREFIX)
                || fullMethod.startsWith(MONITORING_PREFIX));
    }

    private static <ReqT, RespT> ServerCall.Listener<ReqT> deny(
            ServerCall<ReqT, RespT> call, String description) {
        Status status = Status.PERMISSION_DENIED.withDescription(description);
        // Defer close until the transport attaches this listener. Immediate call.close
        // races Vert.x GrpcIoServer and yields NPE on listener.onComplete().
        return new ServerCall.Listener<>() {
            private boolean closed;

            private void closeOnce() {
                if (!closed) {
                    closed = true;
                    call.close(status, new Metadata());
                }
            }

            @Override
            public void onHalfClose() {
                closeOnce();
            }

            @Override
            public void onCancel() {
                closeOnce();
            }

            @Override
            public void onReady() {
                closeOnce();
            }
        };
    }
}
