package io.floci.gcp.services.iam;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** The finite predefined-role subset supported by the IAM evaluation milestone. */
@ApplicationScoped
public class IamRoleCatalog {

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

    IamRoleCatalog(Map<String, Set<String>> permissionsByRole) {
        Map<String, Set<String>> copiedPermissions = new LinkedHashMap<>();
        permissionsByRole.forEach((role, permissions) -> copiedPermissions.put(role, Set.copyOf(permissions)));
        this.permissionsByRole = Map.copyOf(copiedPermissions);
    }

    public boolean grants(String role, String permission) {
        return permissionsByRole.getOrDefault(role, Set.of()).contains(permission);
    }
}
