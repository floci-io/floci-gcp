package io.floci.gcp.services.iam;

import java.util.Objects;

/**
 * Canonical resource data used for IAM Conditions and policy lookup.
 *
 * <p>The policy lookup key is deliberately distinct from the IAM resource name. Storage keys
 * remain an implementation detail and must not be exposed to CEL expressions.</p>
 */
public record IamResource(String service, String type, String name, String policyResource, String projectResource) {

    private static final String STORAGE_SERVICE = "storage.googleapis.com";
    private static final String BUCKET_TYPE = STORAGE_SERVICE + "/Bucket";
    private static final String OBJECT_TYPE = STORAGE_SERVICE + "/Object";

    public IamResource {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(policyResource, "policyResource");
    }

    public IamResource(String service, String type, String name, String policyResource) {
        this(service, type, name, policyResource, null);
    }

    public static IamResource project(String name) {
        String project = projectName(name);
        return new IamResource("cloudresourcemanager.googleapis.com",
                "cloudresourcemanager.googleapis.com/Project", project, project, project);
    }

    public static IamResource projectChild(String service, String type, String name, String policyResource) {
        return new IamResource(service, service + "/" + type, name, policyResource, projectName(name));
    }

    public static String projectName(String name) {
        String[] parts = name.split("/", -1);
        if (parts.length < 2 || !parts[0].equals("projects") || parts[1].isBlank()) {
            throw new IllegalArgumentException("Expected projects/{project} resource: " + name);
        }
        return "projects/" + parts[1];
    }

    public static IamResource gcsBucket(String bucket) {
        requireBucket(bucket);
        return new IamResource(STORAGE_SERVICE, BUCKET_TYPE,
                "projects/_/buckets/" + bucket, "buckets/" + bucket);
    }

    public static IamResource gcsObject(String bucket, String object) {
        requireBucket(bucket);
        Objects.requireNonNull(object, "object");
        return new IamResource(STORAGE_SERVICE, OBJECT_TYPE,
                "projects/_/buckets/" + bucket + "/objects/" + object, "buckets/" + bucket);
    }

    private static void requireBucket(String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("bucket must not be blank");
        }
    }
}
