package io.floci.gcp.services.iam;

import io.floci.gcp.core.common.ServiceUsageAdapter;

import java.util.List;
import java.util.Optional;

/**
 * A service account can be addressed as {@code projects/-/serviceAccounts/{EMAIL_ADDRESS|UNIQUE_ID}},
 * with or without a custom method or a {@code /keys} suffix. Such a request acts on the project
 * that owns the account. An account that does not exist is checked against the default project,
 * and the handler answers PERMISSION_DENIED.
 */
final class IamServiceUsageAdapter implements ServiceUsageAdapter {

    private static final String WILDCARD_ACCOUNT_PREFIX = "-/serviceAccounts/";

    private final IamService service;

    IamServiceUsageAdapter(IamService service) {
        this.service = service;
    }

    @Override
    public Optional<List<String>> restProjects(RestRequest request) {
        String rest = request.pathParameter("rest");
        if (rest == null || !rest.startsWith(WILDCARD_ACCOUNT_PREFIX)) {
            return Optional.empty();
        }
        String emailOrId = accountOf(rest.substring(WILDCARD_ACCOUNT_PREFIX.length()));
        return Optional.of(service.owningProject(emailOrId).map(List::of).orElse(List.of()));
    }

    /** The account of {@code {account}[/keys[/{key}]][:{method}]}. */
    static String accountOf(String identifier) {
        int end = identifier.length();
        int slash = identifier.indexOf('/');
        if (slash >= 0) {
            end = slash;
        }
        int method = identifier.indexOf(':');
        if (method >= 0 && method < end) {
            end = method;
        }
        return identifier.substring(0, end);
    }
}
