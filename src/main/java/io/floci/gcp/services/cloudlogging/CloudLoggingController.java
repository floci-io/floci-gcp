package io.floci.gcp.services.cloudlogging;

import com.google.api.MonitoredResource;
import com.google.logging.type.LogSeverity;
import com.google.logging.v2.*;
import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import io.floci.gcp.core.common.GcpGrpcController;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.services.cloudlogging.model.StoredLogEntry;
import io.grpc.stub.StreamObserver;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CloudLoggingController extends LoggingServiceV2Grpc.LoggingServiceV2ImplBase {

    private static final Logger LOG = Logger.getLogger(CloudLoggingController.class);

    private final CloudLoggingService service;

    CloudLoggingController(CloudLoggingService service) {
        this.service = service;
    }

    @Override
    public void writeLogEntries(WriteLogEntriesRequest request,
            StreamObserver<WriteLogEntriesResponse> responseObserver) {
        LOG.debugf("writeLogEntries logName=%s entries=%d", request.getLogName(), request.getEntriesCount());
        try {
            String defaultResourceType = request.hasResource() ? request.getResource().getType() : null;
            Map<String, String> defaultResourceLabels =
                    request.hasResource() ? request.getResource().getLabelsMap() : null;

            List<StoredLogEntry> entries = new ArrayList<>();
            for (LogEntry proto : request.getEntriesList()) {
                entries.add(toStored(proto));
            }
            service.writeLogEntries(emptyToNull(request.getLogName()), defaultResourceType,
                    defaultResourceLabels, request.getLabelsMap(), entries, request.getDryRun());

            responseObserver.onNext(WriteLogEntriesResponse.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void listLogEntries(ListLogEntriesRequest request,
            StreamObserver<ListLogEntriesResponse> responseObserver) {
        LOG.debugf("listLogEntries resourceNames=%s", request.getResourceNamesList());
        try {
            PageToken.Page<StoredLogEntry> page = service.listLogEntries(request.getResourceNamesList(),
                    request.getFilter(), request.getOrderBy(), request.getPageSize(), request.getPageToken());
            ListLogEntriesResponse.Builder response = ListLogEntriesResponse.newBuilder();
            for (StoredLogEntry entry : page.items()) {
                response.addEntries(toProto(entry));
            }
            if (page.nextPageToken() != null) {
                response.setNextPageToken(page.nextPageToken());
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void listLogs(ListLogsRequest request, StreamObserver<ListLogsResponse> responseObserver) {
        LOG.debugf("listLogs parent=%s", request.getParent());
        try {
            List<String> all = service.listLogs(request.getParent());
            PageToken.Page<String> page = PageToken.paginate(all, request.getPageSize(), request.getPageToken());
            ListLogsResponse.Builder response = ListLogsResponse.newBuilder().addAllLogNames(page.items());
            if (page.nextPageToken() != null) {
                response.setNextPageToken(page.nextPageToken());
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void deleteLog(DeleteLogRequest request, StreamObserver<Empty> responseObserver) {
        LOG.debugf("deleteLog logName=%s", request.getLogName());
        try {
            service.deleteLog(request.getLogName());
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void listMonitoredResourceDescriptors(ListMonitoredResourceDescriptorsRequest request,
            StreamObserver<ListMonitoredResourceDescriptorsResponse> responseObserver) {
        responseObserver.onNext(ListMonitoredResourceDescriptorsResponse.getDefaultInstance());
        responseObserver.onCompleted();
    }

    // ── proto mapping ────────────────────────────────────────────────────────

    private static StoredLogEntry toStored(LogEntry proto) {
        StoredLogEntry stored = new StoredLogEntry();
        stored.setLogName(emptyToNull(proto.getLogName()));
        if (proto.getSeverity() != LogSeverity.DEFAULT) {
            stored.setSeverity(proto.getSeverity().name());
        }
        switch (proto.getPayloadCase()) {
            case TEXT_PAYLOAD -> stored.setTextPayload(proto.getTextPayload());
            case JSON_PAYLOAD -> stored.setJsonPayload(Structs.toJava(proto.getJsonPayload()));
            default -> { /* proto_payload and unset are out of MVP scope */ }
        }
        if (proto.hasResource()) {
            stored.setResourceType(proto.getResource().getType());
            if (!proto.getResource().getLabelsMap().isEmpty()) {
                stored.setResourceLabels(proto.getResource().getLabelsMap());
            }
        }
        if (!proto.getLabelsMap().isEmpty()) {
            stored.setLabels(proto.getLabelsMap());
        }
        if (proto.hasTimestamp()) {
            stored.setTimestamp(toIso(proto.getTimestamp()));
        }
        stored.setInsertId(emptyToNull(proto.getInsertId()));
        stored.setTrace(emptyToNull(proto.getTrace()));
        stored.setSpanId(emptyToNull(proto.getSpanId()));
        return stored;
    }

    private static LogEntry toProto(StoredLogEntry stored) {
        LogEntry.Builder builder = LogEntry.newBuilder()
                .setLogName(stored.getLogName())
                .setSeverity(LogSeverity.valueOf(stored.getSeverity() == null ? "DEFAULT" : stored.getSeverity()));
        if (stored.getInsertId() != null) {
            builder.setInsertId(stored.getInsertId());
        }
        if (stored.getTextPayload() != null) {
            builder.setTextPayload(stored.getTextPayload());
        } else if (stored.getJsonPayload() != null) {
            builder.setJsonPayload(Structs.toStruct(stored.getJsonPayload()));
        }
        MonitoredResource.Builder resource = MonitoredResource.newBuilder();
        if (stored.getResourceType() != null) {
            resource.setType(stored.getResourceType());
        }
        if (stored.getResourceLabels() != null) {
            resource.putAllLabels(stored.getResourceLabels());
        }
        builder.setResource(resource.build());
        if (stored.getLabels() != null) {
            builder.putAllLabels(stored.getLabels());
        }
        if (stored.getTimestamp() != null) {
            builder.setTimestamp(fromIso(stored.getTimestamp()));
        }
        if (stored.getReceiveTimestamp() != null) {
            builder.setReceiveTimestamp(fromIso(stored.getReceiveTimestamp()));
        }
        if (stored.getTrace() != null) {
            builder.setTrace(stored.getTrace());
        }
        if (stored.getSpanId() != null) {
            builder.setSpanId(stored.getSpanId());
        }
        return builder.build();
    }

    private static String toIso(Timestamp ts) {
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos()).toString();
    }

    private static Timestamp fromIso(String iso) {
        try {
            Instant instant = Instant.parse(iso);
            return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
        } catch (Exception e) {
            return Timestamp.getDefaultInstance();
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
