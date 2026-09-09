package io.floci.gcp.services.iam;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.services.iam.model.StoredPolicy;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Seeds a new bucket policy with the resolvable creator and an explicitly configured administrator.
 *
 * <p>This is the emulator's bootstrap mechanism for IAM enforcement. It is intentionally applied
 * at bucket creation while authorization remains disabled as well, so a later restart in enforce
 * mode does not lock out the bucket's creator.</p>
 */
@ApplicationScoped
public class IamBucketPolicyBootstrapService {

    private static final String STORAGE_ADMIN = "roles/storage.admin";

    private final EmulatorConfig config;
    private final IamPrincipalResolver principalResolver;
    private final IamService iamService;

    @Inject
    public IamBucketPolicyBootstrapService(EmulatorConfig config, IamPrincipalResolver principalResolver,
            IamService iamService) {
        this.config = config;
        this.principalResolver = principalResolver;
        this.iamService = iamService;
    }

    @PostConstruct
    void validateConfiguration() {
        config.services().iam().bootstrapAdminMember().ifPresent(member -> {
            if (!isSupportedBootstrapMember(member)) {
                throw new IllegalArgumentException("IAM bootstrap admin member must be allUsers, "
                        + "allAuthenticatedUsers, or a non-blank serviceAccount member");
            }
        });
    }

    public void initializeBucketPolicy(String bucket, String authorization) {
        List<String> members = new ArrayList<>();
        try {
            IamPrincipalResolver.Resolution creator = principalResolver.resolve(authorization);
            if (!creator.downscoped() && creator.principal().member() != null) {
                members.add(creator.principal().member());
            }
        } catch (IllegalArgumentException e) {
            // Existing credential-bypass behavior is preserved when no usable Floci identity exists.
        }

        String bootstrapAdminMember = config.services().iam().bootstrapAdminMember().orElse(null);
        if (bootstrapAdminMember != null
                && !members.contains(bootstrapAdminMember)) {
            members.add(bootstrapAdminMember);
        }
        if (members.isEmpty()) {
            return;
        }

        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of("role", STORAGE_ADMIN, "members", members)));
        iamService.setPolicy(IamResource.gcsBucket(bucket).policyResource(), policy);
    }

    private static boolean isSupportedBootstrapMember(String member) {
        return "allUsers".equals(member) || "allAuthenticatedUsers".equals(member)
                || (member.startsWith("serviceAccount:")
                        && !member.substring("serviceAccount:".length()).isBlank());
    }
}
