package io.floci.gcp.core.common;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.services.iam.IamGrpcPermissionMapper;
import io.floci.gcp.services.iam.IamPolicyEvaluator;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IamEnforcementGrpcInterceptorTest {

    private static final String LOOKUP = "google.datastore.v1.Datastore/Lookup";
    private static final String PUBLISH = "google.pubsub.v1.Publisher/Publish";
    private static final String LIST_SECRETS =
            "google.cloud.secretmanager.v1.SecretManagerService/ListSecrets";
    private static final String LIST_QUEUES = "google.cloud.tasks.v2.CloudTasks/ListQueues";
    private static final String LIST_KEY_RINGS =
            "google.cloud.kms.v1.KeyManagementService/ListKeyRings";
    private static final String LIST_JOBS = "google.cloud.scheduler.v1.CloudScheduler/ListJobs";
    private static final String LIST_LOG_ENTRIES =
            "google.logging.v2.LoggingServiceV2/ListLogEntries";
    private static final String LIST_TIME_SERIES =
            "google.monitoring.v3.MetricService/ListTimeSeries";
    private static final String PLAYER = "player@example.com";

    @Mock
    EmulatorConfig config;
    @Mock
    EmulatorConfig.ServicesConfig servicesConfig;
    @Mock
    EmulatorConfig.IamServiceConfig iamConfig;
    @Mock
    IamService iamService;
    @Mock
    ServerCall<String, String> call;
    @Mock
    ServerCallHandler<String, String> next;
    @Mock
    MethodDescriptor<String, String> methodDescriptor;

    private final IamPolicyEvaluator policyEvaluator = new IamPolicyEvaluator();
    private final IamGrpcPermissionMapper permissionMapper = new IamGrpcPermissionMapper();
    private IamEnforcementGrpcInterceptor interceptor;
    private Context attachedContext;
    private Context previousContext;

    @BeforeEach
    void setUp() {
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.iam()).thenReturn(iamConfig);
        when(iamConfig.enforcementEnabled()).thenReturn(true);
        when(iamConfig.strictEnforcementEnabled()).thenReturn(true);
        when(config.defaultProjectId()).thenReturn("floci-local");
        when(call.getMethodDescriptor()).thenReturn(methodDescriptor);
        when(methodDescriptor.getFullMethodName()).thenReturn(LOOKUP);
        when(iamService.getPolicy(any())).thenReturn(new StoredPolicy());
        interceptor = new IamEnforcementGrpcInterceptor(
                config, iamService, policyEvaluator, permissionMapper);
        attachedContext = null;
        previousContext = null;
    }

    @AfterEach
    void tearDown() {
        if (attachedContext != null) {
            attachedContext.detach(previousContext);
            attachedContext = null;
            previousContext = null;
        }
    }

    @Test
    void enforcementDisabledPassesThrough() {
        when(iamConfig.enforcementEnabled()).thenReturn(false);
        Metadata metadata = new Metadata();

        interceptor.interceptCall(call, metadata, next);

        verify(next).startCall(call, metadata);
        verify(call, never()).close(any(), any());
    }

    @Test
    void denyMissingPrincipalEvenWhenStrictOff() {
        when(iamConfig.strictEnforcementEnabled()).thenReturn(false);
        Metadata metadata = new Metadata();

        ServerCall.Listener<String> listener = interceptor.interceptCall(call, metadata, next);

        verify(next, never()).startCall(any(), any());
        listener.onHalfClose();

        ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
        verify(call).close(statusCaptor.capture(), any(Metadata.class));
        assertEquals(Status.Code.PERMISSION_DENIED, statusCaptor.getValue().getCode());
    }

    @Test
    void operatorRootBypassesEnforcement() {
        attachContext(PLAYER, true);
        Metadata metadata = new Metadata();

        interceptor.interceptCall(call, metadata, next);

        verify(next).startCall(call, metadata);
        verify(call, never()).close(any(), any());
        verify(iamService, never()).getPolicy(any());
    }

    @Test
    void denyWithoutBinding() {
        attachContext(PLAYER, false);
        Metadata metadata = new Metadata();

        ServerCall.Listener<String> listener = interceptor.interceptCall(call, metadata, next);

        verify(next, never()).startCall(any(), any());
        verify(call, never()).close(any(), any());

        listener.onHalfClose();

        ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
        verify(call).close(statusCaptor.capture(), any(Metadata.class));
        assertEquals(Status.Code.PERMISSION_DENIED, statusCaptor.getValue().getCode());
    }

    @Test
    void allowWithDatastoreViewerOnLookup() {
        attachContext(PLAYER, false);
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", "roles/datastore.viewer",
                "members", List.of("serviceAccount:" + PLAYER))));
        when(iamService.getPolicy("projects/floci-local")).thenReturn(policy);
        Metadata metadata = new Metadata();

        interceptor.interceptCall(call, metadata, next);

        verify(next).startCall(call, metadata);
        verify(call, never()).close(any(), any());
    }

    @Test
    void usesRequestParamsProjectForPolicyLookup() {
        attachContext(PLAYER, false);
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", "roles/datastore.viewer",
                "members", List.of("serviceAccount:" + PLAYER))));
        when(iamService.getPolicy("projects/foo")).thenReturn(policy);
        Metadata metadata = new Metadata();
        metadata.put(IamEnforcementGrpcInterceptor.GOOG_REQUEST_PARAMS_KEY, "project=foo");

        interceptor.interceptCall(call, metadata, next);

        verify(iamService).getPolicy("projects/foo");
        verify(next).startCall(call, metadata);
        verify(call, never()).close(any(), any());
    }

    @Test
    void usesParentResourceNameProjectFromRequestParams() {
        when(methodDescriptor.getFullMethodName()).thenReturn(LIST_SECRETS);
        attachContext(PLAYER, false);
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", "roles/secretmanager.admin",
                "members", List.of("serviceAccount:" + PLAYER))));
        when(iamService.getPolicy("projects/foo")).thenReturn(policy);
        Metadata metadata = new Metadata();
        metadata.put(IamEnforcementGrpcInterceptor.GOOG_REQUEST_PARAMS_KEY,
                "parent=projects%2Ffoo");

        interceptor.interceptCall(call, metadata, next);

        verify(iamService).getPolicy("projects/foo");
        verify(next).startCall(call, metadata);
    }

    @Test
    void denyWhenProjectCannotBeResolved() {
        when(config.defaultProjectId()).thenReturn("");
        attachContext(PLAYER, false);
        Metadata metadata = new Metadata();
        metadata.put(IamEnforcementGrpcInterceptor.GOOG_REQUEST_PARAMS_KEY, "project=");

        ServerCall.Listener<String> listener = interceptor.interceptCall(call, metadata, next);

        verify(next, never()).startCall(any(), any());
        verify(iamService, never()).getPolicy(any());
        listener.onHalfClose();

        ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
        verify(call).close(statusCaptor.capture(), any(Metadata.class));
        assertEquals(Status.Code.PERMISSION_DENIED, statusCaptor.getValue().getCode());
        assertTrue(statusCaptor.getValue().getDescription() != null
                && statusCaptor.getValue().getDescription().contains("missing project"));
    }

    @Test
    void denyPublishWithoutPubSubBinding() {
        when(methodDescriptor.getFullMethodName()).thenReturn(PUBLISH);
        attachContext(PLAYER, false);
        Metadata metadata = new Metadata();

        ServerCall.Listener<String> listener = interceptor.interceptCall(call, metadata, next);

        verify(next, never()).startCall(any(), any());
        listener.onHalfClose();

        ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
        verify(call).close(statusCaptor.capture(), any(Metadata.class));
        assertEquals(Status.Code.PERMISSION_DENIED, statusCaptor.getValue().getCode());
        assertTrue(statusCaptor.getValue().getDescription() != null
                && statusCaptor.getValue().getDescription().contains("pubsub.topics.publish"));
    }

    @Test
    void allowPublishWithPublisherRole() {
        when(methodDescriptor.getFullMethodName()).thenReturn(PUBLISH);
        attachContext(PLAYER, false);
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", "roles/pubsub.publisher",
                "members", List.of("serviceAccount:" + PLAYER))));
        when(iamService.getPolicy("projects/floci-local")).thenReturn(policy);
        Metadata metadata = new Metadata();

        interceptor.interceptCall(call, metadata, next);

        verify(next).startCall(call, metadata);
        verify(call, never()).close(any(), any());
    }

    @Test
    void denyListSecretsWithoutSecretManagerBinding() {
        when(methodDescriptor.getFullMethodName()).thenReturn(LIST_SECRETS);
        attachContext(PLAYER, false);
        Metadata metadata = new Metadata();

        ServerCall.Listener<String> listener = interceptor.interceptCall(call, metadata, next);

        verify(next, never()).startCall(any(), any());
        listener.onHalfClose();

        ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
        verify(call).close(statusCaptor.capture(), any(Metadata.class));
        assertEquals(Status.Code.PERMISSION_DENIED, statusCaptor.getValue().getCode());
        assertTrue(statusCaptor.getValue().getDescription() != null
                && statusCaptor.getValue().getDescription().contains("secretmanager.secrets.list"));
    }

    @Test
    void allowListSecretsWithSecretAdmin() {
        when(methodDescriptor.getFullMethodName()).thenReturn(LIST_SECRETS);
        attachContext(PLAYER, false);
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", "roles/secretmanager.admin",
                "members", List.of("serviceAccount:" + PLAYER))));
        when(iamService.getPolicy("projects/floci-local")).thenReturn(policy);
        Metadata metadata = new Metadata();

        interceptor.interceptCall(call, metadata, next);

        verify(next).startCall(call, metadata);
        verify(call, never()).close(any(), any());
    }

    @Test
    void denyListQueuesWithoutCloudTasksBinding() {
        when(methodDescriptor.getFullMethodName()).thenReturn(LIST_QUEUES);
        attachContext(PLAYER, false);
        Metadata metadata = new Metadata();

        ServerCall.Listener<String> listener = interceptor.interceptCall(call, metadata, next);

        verify(next, never()).startCall(any(), any());
        listener.onHalfClose();

        ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
        verify(call).close(statusCaptor.capture(), any(Metadata.class));
        assertEquals(Status.Code.PERMISSION_DENIED, statusCaptor.getValue().getCode());
        assertTrue(statusCaptor.getValue().getDescription() != null
                && statusCaptor.getValue().getDescription().contains("cloudtasks.queues.list"));
    }

    @Test
    void allowListQueuesWithCloudTasksViewer() {
        when(methodDescriptor.getFullMethodName()).thenReturn(LIST_QUEUES);
        attachContext(PLAYER, false);
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", "roles/cloudtasks.viewer",
                "members", List.of("serviceAccount:" + PLAYER))));
        when(iamService.getPolicy("projects/floci-local")).thenReturn(policy);
        Metadata metadata = new Metadata();

        interceptor.interceptCall(call, metadata, next);

        verify(next).startCall(call, metadata);
        verify(call, never()).close(any(), any());
    }

    @Test
    void denyListKeyRingsWithoutCloudKmsBinding() {
        when(methodDescriptor.getFullMethodName()).thenReturn(LIST_KEY_RINGS);
        attachContext(PLAYER, false);
        Metadata metadata = new Metadata();

        ServerCall.Listener<String> listener = interceptor.interceptCall(call, metadata, next);

        verify(next, never()).startCall(any(), any());
        listener.onHalfClose();

        ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
        verify(call).close(statusCaptor.capture(), any(Metadata.class));
        assertEquals(Status.Code.PERMISSION_DENIED, statusCaptor.getValue().getCode());
        assertTrue(statusCaptor.getValue().getDescription() != null
                && statusCaptor.getValue().getDescription().contains("cloudkms.keyRings.list"));
    }

    @Test
    void allowListKeyRingsWithCloudKmsAdmin() {
        when(methodDescriptor.getFullMethodName()).thenReturn(LIST_KEY_RINGS);
        attachContext(PLAYER, false);
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", "roles/cloudkms.admin",
                "members", List.of("serviceAccount:" + PLAYER))));
        when(iamService.getPolicy("projects/floci-local")).thenReturn(policy);
        Metadata metadata = new Metadata();

        interceptor.interceptCall(call, metadata, next);

        verify(next).startCall(call, metadata);
        verify(call, never()).close(any(), any());
    }

    @Test
    void denyListJobsWithoutCloudSchedulerBinding() {
        when(methodDescriptor.getFullMethodName()).thenReturn(LIST_JOBS);
        attachContext(PLAYER, false);
        Metadata metadata = new Metadata();

        ServerCall.Listener<String> listener = interceptor.interceptCall(call, metadata, next);

        verify(next, never()).startCall(any(), any());
        listener.onHalfClose();

        ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
        verify(call).close(statusCaptor.capture(), any(Metadata.class));
        assertEquals(Status.Code.PERMISSION_DENIED, statusCaptor.getValue().getCode());
        assertTrue(statusCaptor.getValue().getDescription() != null
                && statusCaptor.getValue().getDescription().contains("cloudscheduler.jobs.list"));
    }

    @Test
    void allowListJobsWithCloudSchedulerViewer() {
        when(methodDescriptor.getFullMethodName()).thenReturn(LIST_JOBS);
        attachContext(PLAYER, false);
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", "roles/cloudscheduler.viewer",
                "members", List.of("serviceAccount:" + PLAYER))));
        when(iamService.getPolicy("projects/floci-local")).thenReturn(policy);
        Metadata metadata = new Metadata();

        interceptor.interceptCall(call, metadata, next);

        verify(next).startCall(call, metadata);
        verify(call, never()).close(any(), any());
    }

    @Test
    void denyListLogEntriesWithoutLoggingBinding() {
        when(methodDescriptor.getFullMethodName()).thenReturn(LIST_LOG_ENTRIES);
        attachContext(PLAYER, false);
        Metadata metadata = new Metadata();

        ServerCall.Listener<String> listener = interceptor.interceptCall(call, metadata, next);

        verify(next, never()).startCall(any(), any());
        listener.onHalfClose();

        ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
        verify(call).close(statusCaptor.capture(), any(Metadata.class));
        assertEquals(Status.Code.PERMISSION_DENIED, statusCaptor.getValue().getCode());
        assertTrue(statusCaptor.getValue().getDescription() != null
                && statusCaptor.getValue().getDescription().contains("logging.logEntries.list"));
    }

    @Test
    void allowListLogEntriesWithLoggingViewer() {
        when(methodDescriptor.getFullMethodName()).thenReturn(LIST_LOG_ENTRIES);
        attachContext(PLAYER, false);
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", "roles/logging.viewer",
                "members", List.of("serviceAccount:" + PLAYER))));
        when(iamService.getPolicy("projects/floci-local")).thenReturn(policy);
        Metadata metadata = new Metadata();

        interceptor.interceptCall(call, metadata, next);

        verify(next).startCall(call, metadata);
        verify(call, never()).close(any(), any());
    }

    @Test
    void denyListTimeSeriesWithoutMonitoringBinding() {
        when(methodDescriptor.getFullMethodName()).thenReturn(LIST_TIME_SERIES);
        attachContext(PLAYER, false);
        Metadata metadata = new Metadata();

        ServerCall.Listener<String> listener = interceptor.interceptCall(call, metadata, next);

        verify(next, never()).startCall(any(), any());
        listener.onHalfClose();

        ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
        verify(call).close(statusCaptor.capture(), any(Metadata.class));
        assertEquals(Status.Code.PERMISSION_DENIED, statusCaptor.getValue().getCode());
        assertTrue(statusCaptor.getValue().getDescription() != null
                && statusCaptor.getValue().getDescription().contains("monitoring.timeSeries.list"));
    }

    @Test
    void allowListTimeSeriesWithMonitoringViewer() {
        when(methodDescriptor.getFullMethodName()).thenReturn(LIST_TIME_SERIES);
        attachContext(PLAYER, false);
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", "roles/monitoring.viewer",
                "members", List.of("serviceAccount:" + PLAYER))));
        when(iamService.getPolicy("projects/floci-local")).thenReturn(policy);
        Metadata metadata = new Metadata();

        interceptor.interceptCall(call, metadata, next);

        verify(next).startCall(call, metadata);
        verify(call, never()).close(any(), any());
    }

    private void attachContext(String email, boolean operatorRoot) {
        attachedContext = Context.current()
                .withValue(AuthGrpcContext.PRINCIPAL_EMAIL, email)
                .withValue(AuthGrpcContext.OPERATOR_ROOT, operatorRoot);
        previousContext = attachedContext.attach();
    }
}
