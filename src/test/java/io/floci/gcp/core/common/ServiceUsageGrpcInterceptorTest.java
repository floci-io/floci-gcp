package io.floci.gcp.core.common;

import com.google.datastore.v1.LookupRequest;
import com.google.pubsub.v1.Topic;
import com.google.storage.v2.GetBucketRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ServiceUsageGrpcInterceptorTest {

    @Test
    void projectFromRoutingHeader() {
        assertEquals("p1", ServiceUsageGrpcInterceptor.projectFromHeader("topic=projects%2Fp1%2Ftopics%2Ft"));
        assertEquals("p2", ServiceUsageGrpcInterceptor.projectFromHeader("project_id=p2&database_id="));
        assertEquals("p3", ServiceUsageGrpcInterceptor.projectFromHeader("parent=projects/p3/locations/us"));
        assertNull(ServiceUsageGrpcInterceptor.projectFromHeader("bucket=projects%2F_%2Fbuckets%2Fb"));
        assertNull(ServiceUsageGrpcInterceptor.projectFromHeader(null));
    }

    @Test
    void projectFromRequestMessage() {
        assertEquals("p1", ServiceUsageGrpcInterceptor.projectFromMessage(
                Topic.newBuilder().setName("projects/p1/topics/t").build()));
        assertEquals("p2", ServiceUsageGrpcInterceptor.projectFromMessage(
                LookupRequest.newBuilder().setProjectId("p2").build()));
        assertNull(ServiceUsageGrpcInterceptor.projectFromMessage(
                GetBucketRequest.newBuilder().setName("projects/_/buckets/b").build()));
    }
}
