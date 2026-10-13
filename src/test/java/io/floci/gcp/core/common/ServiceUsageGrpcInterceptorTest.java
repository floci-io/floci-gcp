package io.floci.gcp.core.common;

import com.google.cloud.bigquery.storage.v1.CreateReadSessionRequest;
import com.google.cloud.bigquery.storage.v1.ReadSession;
import com.google.cloud.scheduler.v1.CreateJobRequest;
import com.google.cloud.scheduler.v1.Job;
import com.google.cloud.scheduler.v1.PubsubTarget;
import com.google.cloud.secretmanager.v1.Secret;
import com.google.cloud.secretmanager.v1.UpdateSecretRequest;
import com.google.datastore.v1.LookupRequest;
import com.google.logging.v2.ListLogEntriesRequest;
import com.google.protobuf.Message;
import com.google.pubsub.v1.PublishRequest;
import com.google.pubsub.v1.Subscription;
import com.google.pubsub.v1.Topic;
import com.google.storage.v2.CreateBucketRequest;
import com.google.storage.v2.GetBucketRequest;
import com.google.storage.v2.RewriteObjectRequest;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceUsageGrpcInterceptorTest {

    @Test
    @SuppressWarnings("unchecked")
    void routingHeaderDenialIsDeliveredAfterTheListenerIsReturned() {
        ServiceDescriptor descriptor = ServiceDescriptor.builder("pubsub")
                .api("pubsub.googleapis.com", "Cloud Pub/Sub API").build();
        ServiceUsageGate gate = mock(ServiceUsageGate.class);
        when(gate.isDenied(descriptor, "off")).thenReturn(true);
        when(gate.grpcException(descriptor, "off")).thenReturn(Status.PERMISSION_DENIED.asRuntimeException());
        ServerCall<Topic, Topic> call = mock(ServerCall.class);
        ServerCallHandler<Topic, Topic> next = mock(ServerCallHandler.class);
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("x-goog-request-params", Metadata.ASCII_STRING_MARSHALLER),
                "name=projects%2Foff%2Ftopics%2Ft");

        ServerCall.Listener<Topic> listener = new ServiceUsageGrpcInterceptor(gate, descriptor, "default")
                .interceptCall(call, headers, next);
        verify(call, never()).close(any(), any());
        verify(call).request(1);

        listener.onMessage(Topic.newBuilder().setName("projects/off/topics/t").build());
        verify(call).close(eq(Status.PERMISSION_DENIED), any());
        verify(next, never()).startCall(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void routingHeaderProjectIsReadFromTheGateOnce() {
        ServiceDescriptor descriptor = ServiceDescriptor.builder("pubsub")
                .api("pubsub.googleapis.com", "Cloud Pub/Sub API").build();
        ServiceUsageGate gate = mock(ServiceUsageGate.class);
        ServerCall<Topic, Topic> call = mock(ServerCall.class);
        ServerCallHandler<Topic, Topic> next = mock(ServerCallHandler.class);
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("x-goog-request-params", Metadata.ASCII_STRING_MARSHALLER),
                "name=projects%2Fon%2Ftopics%2Ft");

        new ServiceUsageGrpcInterceptor(gate, descriptor, "default").interceptCall(call, headers, next);

        verify(gate, times(1)).isDenied(descriptor, "on");
        verify(next).startCall(any(), any());
        verify(call, never()).close(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCallTheHandlerClosedIsNotClosedAgainByALaterDenial() {
        ServiceDescriptor descriptor = ServiceDescriptor.builder("pubsub")
                .api("pubsub.googleapis.com", "Cloud Pub/Sub API").build();
        ServiceUsageGate gate = mock(ServiceUsageGate.class);
        when(gate.isDenied(descriptor, "off")).thenReturn(true);
        when(gate.grpcException(descriptor, "off")).thenReturn(Status.PERMISSION_DENIED.asRuntimeException());
        ServerCall<Topic, Topic> call = mock(ServerCall.class);
        ServerCall.Listener<Topic> handlerListener = mock(ServerCall.Listener.class);
        ServerCall<Topic, Topic>[] gated = new ServerCall[1];
        ServerCallHandler<Topic, Topic> next = (handlerCall, handlerHeaders) -> {
            gated[0] = handlerCall;
            return handlerListener;
        };

        ServerCall.Listener<Topic> listener = new ServiceUsageGrpcInterceptor(gate, descriptor, "default")
                .interceptCall(call, new Metadata(), next);
        listener.onMessage(Topic.newBuilder().setName("projects/on/topics/t").build());
        gated[0].close(Status.OK, new Metadata());
        Topic denied = Topic.newBuilder().setName("projects/off/topics/t").build();
        listener.onMessage(denied);

        verify(call, times(1)).close(any(), any());
        verify(call).close(eq(Status.OK), any());
        verify(handlerListener, never()).onMessage(denied);
    }

    @Test
    void projectFromRoutingHeader() {
        assertEquals("p1", ServiceUsageGrpcInterceptor.projectFromHeader("topic=projects%2Fp1%2Ftopics%2Ft"));
        assertEquals("p2", ServiceUsageGrpcInterceptor.projectFromHeader("project_id=p2&database_id="));
        assertEquals("p3", ServiceUsageGrpcInterceptor.projectFromHeader("parent=projects/p3/locations/us"));
        assertNull(ServiceUsageGrpcInterceptor.projectFromHeader("bucket=projects%2F_%2Fbuckets%2Fb"));
        assertNull(ServiceUsageGrpcInterceptor.projectFromHeader(null));
    }

    @Test
    void projectsFromRequestMessage() {
        assertEquals(List.of("p1"), projects("pubsub.googleapis.com",
                Topic.newBuilder().setName("projects/p1/topics/t").build()));
        assertEquals(List.of("p2"), projects("datastore.googleapis.com",
                LookupRequest.newBuilder().setProjectId("p2").build()));
        assertEquals(List.of(), projects("storage.googleapis.com",
                GetBucketRequest.newBuilder().setName("projects/_/buckets/b").build()));
    }

    @Test
    void projectsFromRepeatedAndNestedFields() {
        assertEquals(List.of("p1", "p2"), projects("logging.googleapis.com",
                ListLogEntriesRequest.newBuilder()
                        .addResourceNames("projects/p1")
                        .addResourceNames("projects/p2/locations/global/buckets/b/views/v")
                        .addResourceNames("projects/p1").build()));
        assertEquals(List.of("p3"), projects("secretmanager.googleapis.com",
                UpdateSecretRequest.newBuilder()
                        .setSecret(Secret.newBuilder().setName("projects/p3/secrets/s")).build()));
        assertEquals(List.of(), projects("secretmanager.googleapis.com",
                UpdateSecretRequest.getDefaultInstance()));
    }

    @Test
    void everyProjectNamedByAResourceFieldIsReturned() {
        CreateReadSessionRequest session = CreateReadSessionRequest.newBuilder()
                .setParent("projects/a")
                .setReadSession(ReadSession.newBuilder().setTable("projects/b/datasets/d/tables/t")).build();
        assertEquals(List.of("a", "b"), ServiceUsageGrpcInterceptor.projectsFromMessage(session,
                Set.of("bigquerystorage.googleapis.com", "bigquery.googleapis.com")));
        assertEquals(List.of("a"), projects("bigquerystorage.googleapis.com", session));
        assertEquals(List.of("a", "b"), projects("pubsub.googleapis.com", PublishRequest.newBuilder()
                .setTopic("projects/a/topics/t").build(),
                Subscription.newBuilder().setName("projects/a/subscriptions/s")
                        .setTopic("projects/b/topics/t").build()));
    }

    @Test
    void referencesToAnotherApiAndPlainStringsAreNotGatedOn() {
        assertEquals(List.of("s"), projects("cloudscheduler.googleapis.com", CreateJobRequest.newBuilder()
                .setParent("projects/s/locations/l")
                .setJob(Job.newBuilder().setPubsubTarget(
                        PubsubTarget.newBuilder().setTopicName("projects/t/topics/x"))).build()));
        assertEquals(List.of("p"), projects("storage.googleapis.com", CreateBucketRequest.newBuilder()
                .setParent("projects/p").setBucketId("projects/x/buckets/y").build()));
        assertEquals(List.of(), projects("storage.googleapis.com", RewriteObjectRequest.newBuilder()
                .setDestinationBucket("projects/_/buckets/d")
                .setDestinationKmsKey("projects/k/locations/l/keyRings/r/cryptoKeys/c")
                .setSourceObject("projects/x/buckets/z").build()));
    }

    private static List<String> projects(String api, Message... messages) {
        Set<String> projects = new LinkedHashSet<>();
        for (Message message : messages) {
            projects.addAll(ServiceUsageGrpcInterceptor.projectsFromMessage(message, Set.of(api)));
        }
        return List.copyOf(projects);
    }
}
