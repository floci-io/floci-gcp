package io.floci.gcp.test;

import com.google.cloud.storage.HmacKey;
import com.google.cloud.storage.HmacKey.HmacKeyMetadata;
import com.google.cloud.storage.HmacKey.HmacKeyState;
import com.google.cloud.storage.ServiceAccount;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HMAC keys are the credentials S3-compatible clients present to GCS (boto3, the AWS SDKs,
 * gsutil in interop mode). The lifecycle matters as much as the payload: a key must be moved to
 * INACTIVE before it can be deleted, and code that does not handle that 400 gets stuck.
 */
class GcsHmacKeyTest {

    private static final String SERVICE_ACCOUNT =
            "hmac-test@" + TestFixtures.projectId() + ".iam.gserviceaccount.com";

    private static Storage storage;

    @BeforeAll
    static void setUp() {
        storage = TestFixtures.storageClient();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (storage instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Test
    void keyLifecycleRequiresDeactivationBeforeDeletion() {
        HmacKey created = storage.createHmacKey(ServiceAccount.of(SERVICE_ACCOUNT));

        // The secret is returned once, on create, and never again.
        assertThat(created.getSecretKey()).isNotBlank();

        HmacKeyMetadata metadata = created.getMetadata();
        assertThat(metadata.getState()).isEqualTo(HmacKeyState.ACTIVE);
        assertThat(metadata.getAccessId()).isNotBlank();
        assertThat(metadata.getServiceAccount().getEmail()).isEqualTo(SERVICE_ACCOUNT);

        // A get returns the metadata but carries no secret to return.
        HmacKeyMetadata fetched = storage.getHmacKey(metadata.getAccessId());
        assertThat(fetched.getAccessId()).isEqualTo(metadata.getAccessId());
        assertThat(fetched.getState()).isEqualTo(HmacKeyState.ACTIVE);

        assertThat(accessIds()).contains(metadata.getAccessId());

        // Deleting an ACTIVE key is refused, which is the step callers most often miss.
        assertThatThrownBy(() -> storage.deleteHmacKey(fetched))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> assertThat(((StorageException) e).getCode()).isEqualTo(400));

        HmacKeyMetadata deactivated = storage.updateHmacKeyState(fetched, HmacKeyState.INACTIVE);
        assertThat(deactivated.getState()).isEqualTo(HmacKeyState.INACTIVE);

        storage.deleteHmacKey(deactivated);

        assertThat(accessIds()).doesNotContain(metadata.getAccessId());
        assertThatThrownBy(() -> storage.getHmacKey(metadata.getAccessId()))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> assertThat(((StorageException) e).getCode()).isEqualTo(404));
    }

    @Test
    void deactivatedKeyCanBeReactivated() {
        HmacKey created = storage.createHmacKey(ServiceAccount.of(SERVICE_ACCOUNT));
        HmacKeyMetadata metadata = created.getMetadata();
        try {
            HmacKeyMetadata inactive = storage.updateHmacKeyState(metadata, HmacKeyState.INACTIVE);
            assertThat(inactive.getState()).isEqualTo(HmacKeyState.INACTIVE);

            HmacKeyMetadata active = storage.updateHmacKeyState(inactive, HmacKeyState.ACTIVE);
            assertThat(active.getState()).isEqualTo(HmacKeyState.ACTIVE);
        } finally {
            storage.deleteHmacKey(
                    storage.updateHmacKeyState(storage.getHmacKey(metadata.getAccessId()),
                            HmacKeyState.INACTIVE));
        }
    }

    private static java.util.List<String> accessIds() {
        return StreamSupport.stream(storage.listHmacKeys().iterateAll().spliterator(), false)
                .map(HmacKeyMetadata::getAccessId)
                .toList();
    }
}
