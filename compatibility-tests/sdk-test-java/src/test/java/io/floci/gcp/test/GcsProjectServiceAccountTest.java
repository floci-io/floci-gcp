package io.floci.gcp.test;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.cloudresourcemanager.CloudResourceManager;
import com.google.cloud.storage.ServiceAccount;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

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
    void projectExposesItsStorageServiceAccount() throws IOException {
        ServiceAccount account = storage.getServiceAccount(TestFixtures.projectId());

        // GCP names the Cloud Storage agent by project number, not project ID.
        assertThat(account).isNotNull();
        assertThat(account.getEmail())
                .isEqualTo("service-" + projectNumber() + "@gs-project-accounts.iam.gserviceaccount.com");
    }

    @Test
    void serviceAccountIsStableAcrossCalls() {
        // Callers store this address in IAM bindings, so it must not move between calls.
        assertThat(storage.getServiceAccount(TestFixtures.projectId()).getEmail())
                .isEqualTo(storage.getServiceAccount(TestFixtures.projectId()).getEmail());
    }

    private static long projectNumber() throws IOException {
        CloudResourceManager resourceManager = new CloudResourceManager.Builder(
                new NetHttpTransport(), GsonFactory.getDefaultInstance(), request -> { })
                .setRootUrl(TestFixtures.endpoint() + "/")
                .setApplicationName("floci-gcs-compat")
                .build();
        return resourceManager.projects().get(TestFixtures.projectId()).execute().getProjectNumber();
    }
}
