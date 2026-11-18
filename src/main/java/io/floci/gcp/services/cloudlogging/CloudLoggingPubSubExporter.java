package io.floci.gcp.services.cloudlogging;

import com.google.logging.v2.LogSink;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.services.cloudlogging.model.StoredLogEntry;
import io.floci.gcp.services.iam.IamPrincipal;
import io.floci.gcp.services.iam.authorization.IamAuthorizationService;
import io.floci.gcp.services.iam.authorization.IamPermissionCheck;
import io.floci.gcp.services.pubsub.PubSubIamAuthorizationAdapter;
import io.floci.gcp.services.pubsub.PubSubService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/** Publishes each routed entry as one message, authorized as the sink writer. */
@ApplicationScoped
public class CloudLoggingPubSubExporter {
    static final String DESTINATION_PREFIX = "pubsub.googleapis.com/";
    static final String TIMESTAMP_ATTRIBUTE = "logging.googleapis.com/timestamp";

    private final PubSubService pubsub;
    private final IamAuthorizationService authorization;
    private final PubSubIamAuthorizationAdapter adapter;
    private final SinkRouting routing;
    private final EmulatorConfig config;

    @Inject
    public CloudLoggingPubSubExporter(PubSubService pubsub, IamAuthorizationService authorization,
            PubSubIamAuthorizationAdapter adapter, ProjectRegistry projects, EmulatorConfig config) {
        this.pubsub = pubsub;
        this.authorization = authorization;
        this.adapter = adapter;
        this.routing = new SinkRouting(projects);
        this.config = config;
    }

    public void export(String parent, LogSink sink, List<StoredLogEntry> entries) {
        List<StoredLogEntry> routed = routing.select(parent, sink, entries);
        if (routed.isEmpty()) {
            return;
        }
        if (!config.services().pubsub().enabled()) {
            throw GcpException.failedPrecondition("Pub/Sub is disabled");
        }
        String topic = sink.getDestination().substring(DESTINATION_PREFIX.length());
        authorization.authorizeServicePrincipal(new IamPrincipal(sink.getWriterIdentity()),
                new IamPermissionCheck("pubsub.topics.publish", adapter.requireResource(topic)));
        List<PubsubMessage> messages = routed.stream().map(CloudLoggingPubSubExporter::message).toList();
        pubsub.publish(topic, messages);
    }

    private static PubsubMessage message(StoredLogEntry entry) {
        return PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8(ProtoJson.print(CloudLoggingController.toProto(entry))))
                .putAttributes(TIMESTAMP_ATTRIBUTE, entry.getTimestamp())
                .build();
    }
}
