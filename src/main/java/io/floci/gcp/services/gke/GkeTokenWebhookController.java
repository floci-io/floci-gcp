package io.floci.gcp.services.gke;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.OperatorRootAuth;
import io.floci.gcp.core.common.TokenRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Kubernetes token-authentication webhook for k3s-backed GKE clusters.
 *
 * <p>The k3s API server is configured (see {@code GkeClusterManager}) to POST a {@code TokenReview}
 * here for any bearer token it does not recognise — notably the GCP access token produced by
 * {@code gke-gcloud-auth-plugin}, i.e. the credential that {@code gcloud container clusters
 * get-credentials} wires into the kubeconfig.
 *
 * <p>When {@code floci-gcp.auth.validate-tokens} or IAM enforcement is enabled, only tokens
 * registered in {@link TokenRegistry} or matching {@link OperatorRootAuth} authenticate.
 * Operator root receives {@code system:masters}. Registered player tokens receive
 * {@code system:authenticated} only (username is the service-account email). Otherwise (local DX),
 * any non-empty token maps to {@code system:masters} (cluster-admin), matching upstream floci-gcp
 * behavior.
 *
 * <p>This is floci-gcp plumbing under the {@code _floci-gcp/...} namespace, not a GCP API.
 */
@ApplicationScoped
@Path("_floci-gcp/gke/token-webhook")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GkeTokenWebhookController {

    private static final Logger LOG = Logger.getLogger(GkeTokenWebhookController.class);

    private static final String DX_USERNAME = "floci:gcp-iam";
    private static final String DX_UID = "floci-gcp-iam";

    private static final List<String> MASTERS_GROUPS = List.of("system:masters");
    private static final List<String> AUTHENTICATED_GROUPS = List.of("system:authenticated");

    private final EmulatorConfig config;
    private final TokenRegistry tokenRegistry;

    @Inject
    public GkeTokenWebhookController(EmulatorConfig config, TokenRegistry tokenRegistry) {
        this.config = config;
        this.tokenRegistry = tokenRegistry;
    }

    @POST
    public Response review(Map<String, Object> tokenReview) {
        // The response apiVersion MUST match the request's (the kube-apiserver sends v1beta1 by
        // default and cannot convert a v1 response back). Echo whatever the apiserver sent.
        String apiVersion = tokenReview != null && tokenReview.get("apiVersion") instanceof String v
                ? v : "authentication.k8s.io/v1";

        String token = extractToken(tokenReview);
        if (token == null || token.isBlank()) {
            LOG.debug("GKE token-webhook: rejecting empty token");
            return unauthenticated(apiVersion);
        }

        if (requiresRegisteredToken()) {
            if (OperatorRootAuth.matches(config, token)) {
                String email = OperatorRootAuth.rootEmail(config);
                LOG.debugf("GKE token-webhook: authenticated operator root as %s", email);
                return authenticated(apiVersion, email, email, MASTERS_GROUPS);
            }
            Optional<String> email = tokenRegistry.resolve(token);
            if (email.isEmpty()) {
                LOG.debug("GKE token-webhook: rejecting unregistered token");
                return unauthenticated(apiVersion);
            }
            LOG.debugf("GKE token-webhook: authenticated registered token as %s", email.get());
            return authenticated(apiVersion, email.get(), email.get(), AUTHENTICATED_GROUPS);
        }

        LOG.debug("GKE token-webhook: authenticated token as cluster-admin (DX permissive mode)");
        return authenticated(apiVersion, DX_USERNAME, DX_UID, MASTERS_GROUPS);
    }

    private boolean requiresRegisteredToken() {
        return config.auth().validateTokens()
                || config.services().iam().enforcementEnabled();
    }

    private static Response authenticated(
            String apiVersion, String username, String uid, List<String> groups) {
        return Response.ok(Map.of(
                "apiVersion", apiVersion,
                "kind", "TokenReview",
                "status", Map.of(
                        "authenticated", true,
                        "user", Map.of(
                                "username", username,
                                "uid", uid,
                                "groups", groups)))).build();
    }

    private static Response unauthenticated(String apiVersion) {
        return Response.ok(Map.of(
                "apiVersion", apiVersion,
                "kind", "TokenReview",
                "status", Map.of("authenticated", false))).build();
    }

    @SuppressWarnings("unchecked")
    private String extractToken(Map<String, Object> tokenReview) {
        if (tokenReview == null) {
            return null;
        }
        Object spec = tokenReview.get("spec");
        if (spec instanceof Map<?, ?> specMap) {
            Object token = ((Map<String, Object>) specMap).get("token");
            if (token instanceof String s) {
                return s;
            }
        }
        return null;
    }
}
