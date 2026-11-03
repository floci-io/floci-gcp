package io.floci.gcp.test;

import com.google.api.serviceusage.v1beta1.GenerateServiceIdentityRequest;
import com.google.api.serviceusage.v1beta1.ServiceIdentity;
import com.google.api.serviceusage.v1beta1.ServiceUsageClient;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code generateServiceIdentity} exists only in Service Usage v1beta1. Terraform's
 * {@code google_project_service_identity} and Pulumi's {@code gcp.projects.ServiceIdentity}
 * call it to learn a service agent's address before granting it roles.
 */
class ServiceUsageBetaTest {

    private static final String PARENT = "projects/" + TestFixtures.projectId();

    private static ServiceUsageClient client;
    private static Storage storage;

    @BeforeAll
    static void setUp() throws IOException {
        client = TestFixtures.serviceUsageBetaClient();
        storage = TestFixtures.storageClient();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
        if (storage instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Test
    void generateServiceIdentityReturnsTheServiceAgent() throws Exception {
        ServiceIdentity identity = generate("pubsub.googleapis.com");

        assertThat(identity.getEmail())
                .matches("service-\\d{12}@gcp-sa-pubsub\\.iam\\.gserviceaccount\\.com");
        assertThat(identity.getUniqueId()).matches("\\d{21}");
        assertThat(generate("pubsub.googleapis.com")).isEqualTo(identity);
    }

    @Test
    void storageServiceIdentityMatchesTheStorageServiceAccount() throws Exception {
        String storageAgent = storage.getServiceAccount(TestFixtures.projectId()).getEmail();

        assertThat(generate("storage.googleapis.com").getEmail()).isEqualTo(storageAgent);
    }

    @Test
    void generateServiceIdentityOnUnknownProjectNumberIsDenied() {
        // gax httpjson maps errors from the HTTP code alone; the status from the error body
        // survives in the cause chain.
        assertThatThrownBy(() -> client.generateServiceIdentityAsync(GenerateServiceIdentityRequest.newBuilder()
                        .setParent("projects/00000000000/services/pubsub.googleapis.com")
                        .build())
                .get(10, TimeUnit.SECONDS))
                .hasStackTraceContaining("PERMISSION_DENIED");
    }

    private static ServiceIdentity generate(String service) throws Exception {
        return client.generateServiceIdentityAsync(GenerateServiceIdentityRequest.newBuilder()
                        .setParent(PARENT + "/services/" + service)
                        .build())
                .get(10, TimeUnit.SECONDS);
    }
}
