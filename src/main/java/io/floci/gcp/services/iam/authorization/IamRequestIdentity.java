package io.floci.gcp.services.iam.authorization;

import jakarta.enterprise.context.RequestScoped;

/** REST request credential, never a process-global or thread-local identity. */
@RequestScoped
public class IamRequestIdentity {
    private String authorization;

    public String authorization() {
        return authorization;
    }

    public void setAuthorization(String authorization) {
        this.authorization = authorization;
    }
}
