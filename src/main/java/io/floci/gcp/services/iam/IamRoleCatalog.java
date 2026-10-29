package io.floci.gcp.services.iam;

import io.floci.gcp.services.iam.authorization.IamAuthorizationAdapter;
import io.floci.gcp.services.iam.authorization.IamAuthorizationRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finite predefined-role catalog with service-owned permission contributions, plus the stored
 * custom roles that apply to the policy being evaluated.
 */
@ApplicationScoped
public class IamRoleCatalog {

    private static final Set<String> OBJECT_VIEWER = Set.of("storage.objects.get", "storage.objects.list");
    private static final Set<String> OBJECT_CREATOR = Set.of(
            "storage.multipartUploads.abort", "storage.multipartUploads.create",
            "storage.multipartUploads.listParts", "storage.objects.create");
    // Same permissions as OBJECT_ADMIN for now. In GCP, objectAdmin also has
    // storage.objects.getIamPolicy, setIamPolicy, setRetention and overrideUnlockedRetention.
    // floci does not check these yet. When it does, add them to OBJECT_ADMIN only.
    private static final Set<String> OBJECT_USER = Set.of(
            "storage.multipartUploads.abort", "storage.multipartUploads.create",
            "storage.multipartUploads.list", "storage.multipartUploads.listParts",
            "storage.objects.get", "storage.objects.list", "storage.objects.create",
            "storage.objects.delete", "storage.objects.update", "storage.objects.move", "storage.objects.restore");
    private static final Set<String> OBJECT_ADMIN = Set.of(
            "storage.multipartUploads.abort", "storage.multipartUploads.create",
            "storage.multipartUploads.list", "storage.multipartUploads.listParts",
            "storage.objects.get", "storage.objects.list", "storage.objects.create",
            "storage.objects.delete", "storage.objects.update", "storage.objects.move", "storage.objects.restore");
    private static final Set<String> STORAGE_ADMIN = Set.of(
            "storage.buckets.get", "storage.buckets.update", "storage.buckets.delete",
            "storage.buckets.getIamPolicy", "storage.buckets.setIamPolicy",
            "storage.multipartUploads.abort", "storage.multipartUploads.create",
            "storage.multipartUploads.list", "storage.multipartUploads.listParts",
            "storage.objects.get", "storage.objects.list", "storage.objects.create",
            "storage.objects.delete", "storage.objects.update", "storage.objects.move", "storage.objects.restore");

    private final Map<String, Set<String>> permissionsByRole;
    private final IamRoleService customRoles;

    public IamRoleCatalog() {
        this(Map.of(
                "roles/storage.objectViewer", OBJECT_VIEWER,
                "roles/storage.objectCreator", OBJECT_CREATOR,
                "roles/storage.objectUser", OBJECT_USER,
                "roles/storage.objectAdmin", OBJECT_ADMIN,
                "roles/storage.admin", STORAGE_ADMIN));
    }

    public IamRoleCatalog(IamAuthorizationRegistry registry) {
        this(registry, null);
    }

    @Inject
    public IamRoleCatalog(IamAuthorizationRegistry registry, IamRoleService customRoles) {
        this.customRoles = customRoles;
        Map<String, Set<String>> permissions = new LinkedHashMap<>(new IamRoleCatalog().permissionsByRole);
        for (IamAuthorizationAdapter adapter : registry.adapters()) {
            adapter.roles().forEach((role, grants) -> {
                if (permissions.putIfAbsent(role, Set.copyOf(grants)) != null) {
                    throw new IllegalStateException("Duplicate IAM role: " + role);
                }
            });
        }
        for (IamAuthorizationAdapter adapter : registry.adapters()) {
            adapter.basicRoles().forEach((role, grants) -> permissions.merge(role, Set.copyOf(grants),
                    (existing, additional) -> Stream.concat(existing.stream(), additional.stream())
                            .collect(Collectors.toUnmodifiableSet())));
        }
        permissionsByRole = Map.copyOf(permissions);
    }

    boolean contains(String role) {
        return permissionsByRole.containsKey(role);
    }

    /** Whether a binding of {@code role} in the policy stored under {@code policyResource} can grant. */
    public boolean contains(String role, String policyResource) {
        return contains(role) || (customRoles != null && customRoles.applies(role, policyResource));
    }

    /** Rejects a new grant of a custom role that cannot be bound in the policy stored under {@code policyResource}. */
    public void requireBindable(String role, String policyResource) {
        if (customRoles != null) {
            customRoles.requireBindable(role, policyResource);
        }
    }

    IamRoleCatalog(Map<String, Set<String>> permissionsByRole) {
        this.customRoles = null;
        Map<String, Set<String>> copiedPermissions = new LinkedHashMap<>();
        permissionsByRole.forEach((role, permissions) -> copiedPermissions.put(role, Set.copyOf(permissions)));
        this.permissionsByRole = Map.copyOf(copiedPermissions);
    }

    /** Predefined roles only; evaluation uses the overload that also resolves custom roles. */
    public boolean grants(String role, String permission) {
        return permissionsByRole.getOrDefault(role, Set.of()).contains(permission);
    }

    public boolean grants(String role, String permission, String policyResource) {
        if (permissionsByRole.containsKey(role)) {
            return grants(role, permission);
        }
        return customRoles != null && customRoles.grantedPermissions(role, policyResource).contains(permission);
    }
}
