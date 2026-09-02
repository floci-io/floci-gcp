package io.floci.gcp.test;

import com.google.cloud.storage.ServiceAccount;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Clients call {@code projects.serviceAccount} before wiring Pub/Sub notifications, to learn
 * which principal needs publish rights on the topic. It used to 404 even though
 * notificationConfigs itself worked, which broke that setup path.
 */
class GcsProjectServiceAccountTest {

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
    void projectExposesItsStorageServiceAccount() {
        ServiceAccount account = storage.getServiceAccount(TestFixtures.projectId());

        assertThat(account).isNotNull();
        assertThat(account.getEmail())
                .contains(TestFixtures.projectId())
                .endsWith("gs-project-accounts.iam.gserviceaccount.com");
    }

    @Test
    void serviceAccountIsStableAcrossCalls() {
        // Callers store this address in IAM bindings, so it must not move between calls.
        assertThat(storage.getServiceAccount(TestFixtures.projectId()).getEmail())
                .isEqualTo(storage.getServiceAccount(TestFixtures.projectId()).getEmail());
    }
}
