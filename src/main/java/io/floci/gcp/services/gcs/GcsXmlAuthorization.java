package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.iam.IamPrincipalResolver;
import io.floci.gcp.services.iam.authorization.IamIdentityKind;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.function.Supplier;

/** Converts only authorization failures, leaving other service failures to the XML mapper. */
@ApplicationScoped
public class GcsXmlAuthorization {

    private final IamPrincipalResolver principalResolver;

    @Inject
    public GcsXmlAuthorization(IamPrincipalResolver principalResolver) {
        this.principalResolver = principalResolver;
    }

    public void require(String authorization, Runnable check) {
        call(authorization, () -> {
            check.run();
            return null;
        });
    }

    public <T> T call(String authorization, Supplier<T> check) {
        try {
            return check.get();
        } catch (GcpException error) {
            if (!GcsXmlErrorResponse.handlesAuthentication(error) || error instanceof GcsResourceException) {
                throw error;
            }
            if (error.getHttpStatus() == 403
                    && principalResolver.resolve(authorization).kind() == IamIdentityKind.EXTERNAL) {
                throw new GcsXmlException(GcsXmlErrorResponse.authentication(
                        GcpException.unauthenticated("The supplied credentials could not be authenticated.")));
            }
            throw new GcsXmlException(GcsXmlErrorResponse.authentication(error));
        }
    }
}
