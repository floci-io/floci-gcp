package io.floci.gcp.test;

import com.google.api.client.http.HttpRequestInitializer;
import com.google.cloud.ServiceOptions;
import com.google.cloud.http.HttpTransportOptions;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GcsServiceAccountAuthenticationTest {

    @Test
    void serviceAccountCredentialsAuthenticateStorageRequests() throws Exception {
        var credentials = TestFixtures.serviceAccountCredentials();
        List<String> authorizationHeaders = new ArrayList<>();
        var transport = new HttpTransportOptions(HttpTransportOptions.newBuilder()) {
            @Override
            public HttpRequestInitializer getHttpRequestInitializer(ServiceOptions<?, ?> options) {
                var initializer = super.getHttpRequestInitializer(options);
                return request -> {
                    initializer.initialize(request);
                    var interceptor = request.getInterceptor();
                    request.setInterceptor(outgoing -> {
                        if (interceptor != null) {
                            interceptor.intercept(outgoing);
                        }
                        authorizationHeaders.add(outgoing.getHeaders().getAuthorization());
                    });
                };
            }
        };

        String bucketName = TestFixtures.uniqueName("service-account-auth");
        try (Storage storage = StorageOptions.newBuilder()
                .setHost(TestFixtures.endpoint())
                .setProjectId(TestFixtures.projectId())
                .setCredentials(credentials)
                .setTransportOptions(transport)
                .build()
                .getService()) {
            storage.create(BucketInfo.of(bucketName));

            try {
                assertThat(storage.get(bucketName)).isNotNull();
                // SDKs may use a credential copy or a self-signed JWT without populating
                // the original credential's OAuth token. Verify the outgoing requests.
                assertThat(authorizationHeaders).hasSize(2).allSatisfy(header ->
                        assertThat(header).startsWith("Bearer ").hasSizeGreaterThan(7));
            } finally {
                assertThat(storage.delete(bucketName)).isTrue();
            }
        }
    }
}
