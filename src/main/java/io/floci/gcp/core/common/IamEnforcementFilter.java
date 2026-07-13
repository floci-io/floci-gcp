package io.floci.gcp.core.common;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.services.iam.IamPermissionMapper;
import io.floci.gcp.services.iam.IamPolicyEvaluator;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Enforces project IAM allow policies when {@code floci-gcp.services.iam.enforcement-enabled} is on.
 * Operator root principals bypass evaluation.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION + 20)
public class IamEnforcementFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(IamEnforcementFilter.class);

    private final EmulatorConfig config;
    private final RequestContext requestContext;
    private final IamPermissionMapper permissionMapper;
    private final IamPolicyEvaluator policyEvaluator;
    private final IamService iamService;

    @Inject
    public IamEnforcementFilter(EmulatorConfig config,
                                RequestContext requestContext,
                                IamPermissionMapper permissionMapper,
                                IamPolicyEvaluator policyEvaluator,
                                IamService iamService) {
        this.config = config;
        this.requestContext = requestContext;
        this.permissionMapper = permissionMapper;
        this.policyEvaluator = policyEvaluator;
        this.iamService = iamService;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!config.services().iam().enforcementEnabled()) {
            return;
        }
        String path = SecurityBypassPaths.normalizePath(ctx.getUriInfo().getPath());
        if (SecurityBypassPaths.isGkeTokenWebhookPath(path)) {
            return;
        }
        if (requestContext.isOperatorRoot()) {
            return;
        }

        boolean strict = config.services().iam().strictEnforcementEnabled();
        String principal = requestContext.getPrincipalEmail();
        if (principal == null || principal.isBlank()) {
            LOG.debugf("IAM enforcement DENY: missing principal on %s", ctx.getUriInfo().getPath());
            ctx.abortWith(permissionDeniedResponse("Permission denied: missing authenticated principal."));
            return;
        }

        Optional<String> permission = permissionMapper.map(ctx);
        if (permission.isEmpty()) {
            if (strict) {
                LOG.debugf("IAM enforcement DENY: unmapped permission on %s", ctx.getUriInfo().getPath());
                ctx.abortWith(permissionDeniedResponse("Permission denied: no IAM mapping for this request."));
            }
            return;
        }

        String projectId = requestContext.getProjectId();
        if (projectId == null || projectId.isBlank()) {
            projectId = config.defaultProjectId();
        }
        String resource = "projects/" + projectId;
        StoredPolicy policy = iamService.getPolicy(resource);
        boolean allowed = policyEvaluator.isAllowed(policy.getBindings(), principal, permission.get());
        if (!allowed) {
            LOG.debugf("IAM enforcement DENY: principal=%s permission=%s resource=%s",
                    principal, permission.get(), resource);
            ctx.abortWith(permissionDeniedResponse(
                    "Permission '" + permission.get() + "' denied on resource '" + resource + "'."));
        }
    }

    private static Response permissionDeniedResponse(String message) {
        return Response.status(403)
                .type(MediaType.APPLICATION_JSON)
                .entity(new GcpExceptionMapper.ErrorWrapper(
                        GcpExceptionMapper.ErrorDetail.of(403, message, "PERMISSION_DENIED")))
                .build();
    }
}
