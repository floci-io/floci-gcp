package io.floci.gcp.core.common;

import com.google.protobuf.Any;
import com.google.rpc.Code;
import com.google.rpc.ErrorInfo;
import io.floci.gcp.config.EmulatorConfig;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rejects calls to APIs that are not ENABLED in Service Usage for the caller's project when
 * {@code floci-gcp.services.serviceusage.enforce} is on. The error follows
 * {@code google.api.ErrorReason.SERVICE_DISABLED}: 403 PERMISSION_DENIED with an
 * {@code ErrorInfo} (domain {@code googleapis.com}, metadata {@code service} and {@code consumer}).
 */
@ApplicationScoped
public class ServiceUsageGate {

    static final Set<String> EXEMPT_APIS = Set.of(
            "serviceusage.googleapis.com",
            "cloudresourcemanager.googleapis.com",
            "iamcredentials.googleapis.com",
            "sts.googleapis.com",
            "cloudbilling.googleapis.com");

    private static final String ERROR_INFO_TYPE = "type.googleapis.com/google.rpc.ErrorInfo";
    private static final String REASON = "SERVICE_DISABLED";
    private static final String DOMAIN = "googleapis.com";

    private final boolean enforced;
    private final ServiceStateProvider stateProvider;

    @Inject
    public ServiceUsageGate(EmulatorConfig config, ServiceStateProvider stateProvider) {
        this.enforced = config.services().serviceusage().enforce();
        this.stateProvider = stateProvider;
    }

    public boolean enforced() {
        return enforced;
    }

    public boolean gates(ServiceDescriptor descriptor) {
        return enforced && descriptor.apiName() != null && !EXEMPT_APIS.contains(descriptor.apiName());
    }

    public boolean isDenied(ServiceDescriptor descriptor, String project) {
        return gates(descriptor) && !stateProvider.isServiceEnabled(project, descriptor.apiName());
    }

    public Response restResponse(ServiceDescriptor descriptor, String project) {
        String message = message(descriptor, project);
        Map<String, Object> errorInfo = new LinkedHashMap<>();
        errorInfo.put("@type", ERROR_INFO_TYPE);
        errorInfo.put("reason", REASON);
        errorInfo.put("domain", DOMAIN);
        errorInfo.put("metadata", metadata(descriptor, project));
        GcpExceptionMapper.ErrorDetail detail = new GcpExceptionMapper.ErrorDetail(
                403, message, "PERMISSION_DENIED",
                List.of(new GcpExceptionMapper.ErrorItem(message, "usageLimits", "accessNotConfigured")),
                List.of(errorInfo));
        return Response.status(403)
                .type(MediaType.APPLICATION_JSON)
                .entity(new GcpExceptionMapper.ErrorWrapper(detail))
                .build();
    }

    public StatusRuntimeException grpcException(ServiceDescriptor descriptor, String project) {
        ErrorInfo errorInfo = ErrorInfo.newBuilder()
                .setReason(REASON)
                .setDomain(DOMAIN)
                .putAllMetadata(metadata(descriptor, project))
                .build();
        return StatusProto.toStatusRuntimeException(com.google.rpc.Status.newBuilder()
                .setCode(Code.PERMISSION_DENIED_VALUE)
                .setMessage(message(descriptor, project))
                .addDetails(Any.pack(errorInfo))
                .build());
    }

    static String message(ServiceDescriptor descriptor, String project) {
        return descriptor.apiTitle() + " has not been used in project " + project
                + " before or it is disabled. Enable it by visiting "
                + "https://console.developers.google.com/apis/api/" + descriptor.apiName()
                + "/overview?project=" + project + " then retry. If you enabled this API recently, "
                + "wait a few minutes for the action to propagate to our systems and retry.";
    }

    private static Map<String, String> metadata(ServiceDescriptor descriptor, String project) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("service", descriptor.apiName());
        metadata.put("consumer", "projects/" + project);
        return metadata;
    }
}
