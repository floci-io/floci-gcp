package io.floci.gcp.services.gcs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.gcs.model.GcsHmacKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Cloud Storage HMAC key lifecycle.
 *
 * <p>Keys are the credentials S3-compatible clients present to GCS (boto3, the AWS SDKs, gsutil in
 * interop mode). Nothing here signs or verifies anything, in line with the rest of the emulator's
 * credential handling; what a client depends on is the resource lifecycle, in particular that a
 * key must be {@code INACTIVE} before it can be deleted.
 *
 * <p>State lives in a {@link StorageFactory} backend so keys survive a restart in persistent mode,
 * are namespaced by project like every other resource, and are cleared by the reset endpoint.
 * The secret is returned once on create and never stored: nothing ever reads it back.
 */
@ApplicationScoped
public class GcsHmacKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ACCESS_ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final StorageBackend<String, GcsHmacKey> keyStore;

    @Inject
    public GcsHmacKeyService(StorageFactory storageFactory) {
        this(storageFactory.create("gcs", "gcs-hmac-keys.json",
                new TypeReference<Map<String, GcsHmacKey>>() {}));
    }

    GcsHmacKeyService(StorageBackend<String, GcsHmacKey> keyStore) {
        this.keyStore = keyStore;
    }

    /** The metadata half plus the secret, which exists only in this return value. */
    public record CreatedKey(GcsHmacKey metadata, String secret) {}

    public CreatedKey create(String project, String serviceAccountEmail) {
        if (serviceAccountEmail == null || serviceAccountEmail.isBlank()) {
            throw GcpException.invalidArgument("serviceAccountEmail is required");
        }
        String accessId = randomAccessId();
        String now = now();
        GcsHmacKey key = new GcsHmacKey();
        key.setId(project + "/" + accessId);
        key.setAccessId(accessId);
        key.setProjectId(project);
        key.setServiceAccountEmail(serviceAccountEmail);
        key.setState("ACTIVE");
        key.setTimeCreated(now);
        key.setUpdated(now);
        key.setEtag("etag-" + accessId);
        keyStore.put(accessId, key);

        byte[] raw = new byte[28];
        RANDOM.nextBytes(raw);
        return new CreatedKey(key, Base64.getEncoder().encodeToString(raw));
    }

    public List<GcsHmacKey> list(String project, String serviceAccountEmail, boolean showDeletedKeys) {
        List<GcsHmacKey> items = new ArrayList<>();
        for (GcsHmacKey key : keyStore.scan(k -> true)) {
            if (!project.equals(key.getProjectId())) {
                continue;
            }
            if (serviceAccountEmail != null && !serviceAccountEmail.isBlank()
                    && !serviceAccountEmail.equals(key.getServiceAccountEmail())) {
                continue;
            }
            if (!showDeletedKeys && "DELETED".equals(key.getState())) {
                continue;
            }
            items.add(key);
        }
        items.sort(Comparator.comparing(GcsHmacKey::getAccessId));
        return items;
    }

    public GcsHmacKey get(String project, String accessId) {
        GcsHmacKey key = keyStore.get(accessId).orElse(null);
        if (key == null || !project.equals(key.getProjectId())) {
            throw GcpException.notFound("HMAC key not found: " + accessId);
        }
        return key;
    }

    public GcsHmacKey updateState(String project, String accessId, Object requestedState) {
        GcsHmacKey key = get(project, accessId);
        if (!(requestedState instanceof String state)) {
            throw GcpException.invalidArgument("state is required");
        }
        if (!state.equals("ACTIVE") && !state.equals("INACTIVE")) {
            throw GcpException.invalidArgument("state must be ACTIVE or INACTIVE, got: " + state);
        }
        key.setState(state);
        key.setUpdated(now());
        keyStore.put(accessId, key);
        return key;
    }

    public void delete(String project, String accessId) {
        GcsHmacKey key = get(project, accessId);
        if ("ACTIVE".equals(key.getState())) {
            throw GcpException.invalidArgument(
                    "This key must be in the INACTIVE state before it can be deleted.");
        }
        keyStore.delete(accessId);
    }

    // GCS access ids look like a GOOG-prefixed uppercase alphanumeric string.
    private static String randomAccessId() {
        StringBuilder sb = new StringBuilder("GOOG1E");
        for (int i = 0; i < 55; i++) {
            sb.append(ACCESS_ID_ALPHABET.charAt(RANDOM.nextInt(ACCESS_ID_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }
}
