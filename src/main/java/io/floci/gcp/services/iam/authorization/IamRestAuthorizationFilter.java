package io.floci.gcp.services.iam.authorization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.RequestBody;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Provider
@Priority(Priorities.AUTHORIZATION)
@ApplicationScoped
public class IamRestAuthorizationFilter implements ContainerRequestFilter {
    @Context
    ResourceInfo resourceInfo;

    private final IamAuthorizationRegistry registry;
    private final IamAuthorizationService authorization;
    private final IamRequestIdentity identity;
    private final ObjectMapper mapper;

    @Inject
    public IamRestAuthorizationFilter(IamAuthorizationRegistry registry, IamAuthorizationService authorization,
            IamRequestIdentity identity, ObjectMapper mapper) {
        this.registry = registry;
        this.authorization = authorization;
        this.identity = identity;
        this.mapper = mapper;
    }

    @Override
    public void filter(ContainerRequestContext context) throws IOException {
        identity.setAuthorization(context.getHeaderString("Authorization"));
        Optional<IamAuthorizationAdapter> adapter = registry.rest(resourceInfo.getResourceClass());
        if (adapter.isEmpty() || !authorization.applies(identity.authorization(), adapter.get())) {
            return;
        }
        JsonNode body = mapper.createObjectNode();
        byte[] bytes = RequestBody.of(context);
        if (bytes.length > 0) {
            try {
                body = mapper.readTree(bytes);
            } catch (IOException e) {
                throw GcpException.invalidArgument("Invalid JSON request body");
            }
        }
        Map<String, String> path = new LinkedHashMap<>();
        context.getUriInfo().getPathParameters().forEach((key, values) -> path.put(key, values.getFirst()));
        authorization.authorize(identity.authorization(), adapter.get(), adapter.get().restOperation(
                resourceInfo.getResourceMethod().getName(), path, body));
    }
}
