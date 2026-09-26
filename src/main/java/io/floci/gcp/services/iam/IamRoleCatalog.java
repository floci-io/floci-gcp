package io.floci.gcp.services.iam;

import io.floci.gcp.services.iam.authorization.IamAuthorizationAdapter;
import io.floci.gcp.services.iam.authorization.IamAuthorizationRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Finite predefined-role catalog with service-owned permission contributions. */
@ApplicationScoped
public class IamRoleCatalog {

    private static final Logger LOG = Logger.getLogger(IamRoleCatalog.class);

    private static final Set<String> OBJECT_VIEWER = Set.of("storage.objects.get", "storage.objects.list");
    private static final Set<String> OBJECT_CREATOR = Set.of("storage.objects.create");
    private static final Set<String> OBJECT_ADMIN = Set.of(
            "storage.objects.get", "storage.objects.list", "storage.objects.create",
            "storage.objects.delete", "storage.objects.update", "storage.objects.move");
    private static final Set<String> STORAGE_ADMIN = Set.of(
            "storage.buckets.get", "storage.buckets.update", "storage.buckets.delete",
            "storage.buckets.getIamPolicy", "storage.buckets.setIamPolicy",
            "storage.objects.get", "storage.objects.list", "storage.objects.create",
            "storage.objects.delete", "storage.objects.update", "storage.objects.move");

    private final Map<String, Set<String>> permissionsByRole;

    public IamRoleCatalog() {
        this(Map.of(
                "roles/storage.objectViewer", OBJECT_VIEWER,
                "roles/storage.objectCreator", OBJECT_CREATOR,
                "roles/storage.objectAdmin", OBJECT_ADMIN,
                "roles/storage.admin", STORAGE_ADMIN));
    }

    @Inject
    public IamRoleCatalog(IamAuthorizationRegistry registry) {
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

    public boolean contains(String role) {
        return permissionsByRole.containsKey(role);
    }

    IamRoleCatalog(Map<String, Set<String>> permissionsByRole) {
        Map<String, Set<String>> copiedPermissions = new LinkedHashMap<>();
        permissionsByRole.forEach((role, permissions) -> copiedPermissions.put(role, Set.copyOf(permissions)));
        this.permissionsByRole = Map.copyOf(copiedPermissions);
    }

    public boolean grants(String role, String permission) {
        if (!contains(role)) {
            LOG.warnf("IAM evaluator does not support role=%s", role);
            return false;
        }
        return permissionsByRole.get(role).contains(permission);
    }
}
