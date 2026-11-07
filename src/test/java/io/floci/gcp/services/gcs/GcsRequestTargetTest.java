package io.floci.gcp.services.gcs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.floci.gcp.core.common.ServiceUsageAdapter;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Service Usage and IAM must act on the same project for the same bucket. */
class GcsRequestTargetTest {

    @Test
    @SuppressWarnings("unchecked")
    void bothPoliciesActOnTheProjectThatOwnsTheBucket() {
        GcsService service = mock(GcsService.class);
        when(service.bucketProject("b")).thenReturn("owner");
        Instance<GcsService> services = mock(Instance.class);
        when(services.get()).thenReturn(service);

        Optional<List<String>> usage = new GcsServiceUsageAdapter(service).restProjects(bucketRequest("b"));
        GcsIamAuthorizationAdapter iam = new GcsIamAuthorizationAdapter(services);

        assertEquals(Optional.of(List.of("owner")), usage);
        assertEquals("projects/owner", iam.bucketResource("b").projectResource());
        assertEquals("projects/owner", iam.objectResource("b", "o").projectResource());
    }

    private static ServiceUsageAdapter.RestRequest bucketRequest(String bucket) {
        return new ServiceUsageAdapter.RestRequest() {
            @Override
            public Class<?> resourceClass() {
                return GcsBucketController.class;
            }

            @Override
            public String path() {
                return "/storage/v1/b/" + bucket;
            }

            @Override
            public String pathParameter(String name) {
                return name.equals("bucket") ? bucket : null;
            }

            @Override
            public String queryParameter(String name) {
                return null;
            }

            @Override
            public JsonNode jsonBody() {
                return JsonNodeFactory.instance.objectNode();
            }
        };
    }
}
