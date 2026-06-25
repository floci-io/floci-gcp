package io.floci.gcp.services.gke;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Kubernetes token-authentication webhook for k3s-backed GKE clusters.
 *
 * <p>The k3s API server is configured (see {@code GkeClusterManager}) to POST a {@code TokenReview}
 * here for any bearer token it does not recognise — notably the GCP access token produced by
 * {@code gke-gcloud-auth-plugin}, i.e. the credential that {@code gcloud container clusters
 * get-credentials} wires into the kubeconfig. floci-gcp does not validate credentials, so it accepts
 * any non-empty token and maps it to the {@code system:masters} group (bound to {@code cluster-admin}
 * by default). This is what makes the native {@code gcloud get-credentials} + {@code kubectl} flow
 * authenticate against a floci-gcp GKE cluster, mirroring the EKS token-webhook in the AWS emulator.
 *
 * <p>This is floci-gcp plumbing under the {@code _floci-gcp/...} namespace, not a GCP API.
 */
@ApplicationScoped
@Path("_floci-gcp/gke/token-webhook")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GkeTokenWebhookController {

    private static final Logger LOG = Logger.getLogger(GkeTokenWebhookController.class);

    @POST
    public Response review(Map<String, Object> tokenReview) {
        // The response apiVersion MUST match the request's (the kube-apiserver sends v1beta1 by
        // default and cannot convert a v1 response back). Echo whatever the apiserver sent.
        String apiVersion = tokenReview != null && tokenReview.get("apiVersion") instanceof String v
                ? v : "authentication.k8s.io/v1";

        String token = extractToken(tokenReview);
        boolean authenticated = token != null && !token.isBlank();

        if (authenticated) {
            LOG.debug("GKE token-webhook: authenticated token as cluster-admin");
            return Response.ok(Map.of(
                    "apiVersion", apiVersion,
                    "kind", "TokenReview",
                    "status", Map.of(
                            "authenticated", true,
                            "user", Map.of(
                                    "username", "floci:gcp-iam",
                                    "uid", "floci-gcp-iam",
                                    "groups", List.of("system:masters"))))).build();
        }

        LOG.debug("GKE token-webhook: rejecting empty token");
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
