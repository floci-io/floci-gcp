package io.floci.gcp.services.pubsub;

import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.StreamingPullRequest;
import com.google.pubsub.v1.StreamingPullResponse;
import com.google.pubsub.v1.SubscriberGrpc;
import io.floci.gcp.lifecycle.GrpcServerManager;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PubSubStreamingPullIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @Inject
    Vertx vertx;

    @Inject
    PubSubService service;

    @Inject
    GrpcServerManager grpcServer;

    @Test
    void tcpDisconnectRemovesListenerAndReplacementStreamReceivesMessages() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String topic = "projects/stream-disconnect/topics/" + suffix;
        String subscription = "projects/stream-disconnect/subscriptions/" + suffix;
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 10);
        CompletableFuture<Void> serverClosed = new CompletableFuture<>();
        grpcServer.bind(new PubSubSubscriberController(service), new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                    Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                ServerCall.Listener<ReqT> listener = next.startCall(call, headers);
                // Register after the controller so the barrier includes its cancellation cleanup.
                Context.current().addListener(context -> serverClosed.complete(null), Runnable::run);
                return listener;
            }
        });
        NetClient upstream = vertx.createNetClient();
        List<NetSocket> sockets = new CopyOnWriteArrayList<>();
        NetServer proxy = vertx.createNetServer().connectHandler(client -> {
            sockets.add(client);
            client.pause();
            upstream.connect(endpoint.getPort(), endpoint.getHost()).onSuccess(server -> {
                sockets.add(server);
                client.pipeTo(server);
                server.pipeTo(client);
            }).onFailure(error -> client.close());
        }).listen(0, "localhost").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        ManagedChannel disconnected = ManagedChannelBuilder.forAddress("localhost", proxy.actualPort())
                .usePlaintext().build();
        ManagedChannel replacement = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext().build();
        try {
            Responses oldResponses = new Responses();
            StreamObserver<StreamingPullRequest> oldRequests = SubscriberGrpc.newStub(disconnected)
                    .streamingPull(oldResponses);
            oldRequests.onNext(StreamingPullRequest.newBuilder().setSubscription(subscription)
                    .setStreamAckDeadlineSeconds(10).build());
            service.publish(topic, List.of(message("warmup")));
            StreamingPullResponse warmup = oldResponses.next();
            String ackId = warmup.getReceivedMessages(0).getAckId();

            // Close both ends of an established TCP proxy without sending a gRPC cancellation.
            for (NetSocket socket : sockets) {
                socket.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            oldResponses.failed.get(5, TimeUnit.SECONDS);
            serverClosed.get(5, TimeUnit.SECONDS);

            Responses newResponses = new Responses();
            StreamObserver<StreamingPullRequest> newRequests = SubscriberGrpc.newStub(replacement)
                    .streamingPull(newResponses);
            newRequests.onNext(StreamingPullRequest.newBuilder().setSubscription(subscription)
                    .setStreamAckDeadlineSeconds(10).build());
            service.publish(topic, List.of(message("recovery")));
            assertEquals("recovery", newResponses.next().getReceivedMessages(0).getMessage()
                    .getData().toStringUtf8());
            assertTrue(service.pull(subscription, 10).isEmpty(), "disconnect must preserve outstanding leases");

            service.modifyAckDeadline(subscription, List.of(ackId), 0);
            assertEquals(warmup.getReceivedMessages(0).getMessage().getMessageId(),
                    newResponses.next().getReceivedMessages(0).getMessage().getMessageId());
            newRequests.onCompleted();
        } finally {
            grpcServer.bind(new PubSubSubscriberController(service));
            disconnected.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            replacement.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            proxy.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            upstream.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            service.deleteSubscription(subscription);
            service.deleteTopic(topic);
        }
    }

    private static PubsubMessage message(String data) {
        return PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8(data)).build();
    }

    private static final class Responses implements StreamObserver<StreamingPullResponse> {
        private final BlockingQueue<StreamingPullResponse> messages = new LinkedBlockingQueue<>();
        private final CompletableFuture<Throwable> failed = new CompletableFuture<>();

        @Override
        public void onNext(StreamingPullResponse response) {
            messages.add(response);
        }

        @Override
        public void onError(Throwable error) {
            failed.complete(error);
        }

        @Override
        public void onCompleted() {}

        private StreamingPullResponse next() throws InterruptedException {
            StreamingPullResponse response = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(response, "stream should receive a message");
            return response;
        }
    }
}
