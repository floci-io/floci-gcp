package io.floci.gcp.services.cloudrun;

import com.google.protobuf.Message;
import com.google.rpc.Status;
import io.floci.gcp.services.operations.LongRunningOperationsService;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Completes a pending Cloud Run long-running operation at most once, whichever of the competing paths
 * (work, timeout, cancellation) gets there first.
 */
final class CloudRunOperationGuard {

    private final LongRunningOperationsService operations;
    private final String operationName;
    private final AtomicBoolean terminal = new AtomicBoolean(false);

    CloudRunOperationGuard(LongRunningOperationsService operations, String operationName) {
        this.operations = operations;
        this.operationName = operationName;
    }

    String operationName() {
        return operationName;
    }

    boolean isTerminal() {
        return terminal.get();
    }

    void complete(Message response, Message metadata) {
        if (terminal.compareAndSet(false, true)) {
            operations.complete(operationName, response, metadata);
        }
    }

    void fail(Status error, Message metadata) {
        if (terminal.compareAndSet(false, true)) {
            operations.fail(operationName, error, metadata);
        }
    }
}
