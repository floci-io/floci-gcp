package io.floci.gcp.services.bigquery;

import com.google.cloud.bigquery.storage.v1.AppendRowsRequest;
import com.google.cloud.bigquery.storage.v1.CreateWriteStreamRequest;
import com.google.cloud.bigquery.storage.v1.WriteStream;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.lifecycle.GrpcServerManager;
import io.floci.gcp.services.bigquery.model.Dataset;
import io.floci.gcp.services.bigquery.model.StoredWriteStream;
import io.floci.gcp.services.bigquery.model.Table;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;
import io.floci.gcp.services.bigquery.model.TableReference;
import io.floci.gcp.services.bigquery.model.TableSchema;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BigQueryStorageWriteTest {

    private final BigQueryService service = mock(BigQueryService.class);

    @Test
    void stopsAppendWorkersBeforeStorageShutdownAndWaitsForThem() throws Exception {
        BigQueryStorageWrite write = newWrite(service);
        CountDownLatch appendStarted = new CountDownLatch(1);
        CountDownLatch releaseAppend = new CountDownLatch(1);
        write.appendExecutor().execute(() -> {
            appendStarted.countDown();
            awaitIgnoringInterrupts(releaseAppend);
        });
        assertTrue(appendStarted.await(5, TimeUnit.SECONDS));

        Thread shutdown = new Thread(write::stopManagedContainers);
        shutdown.start();
        shutdown.join(200);
        assertTrue(shutdown.isAlive(), "shutdown must wait for the append that is still writing");

        releaseAppend.countDown();
        shutdown.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(shutdown.isAlive(), "shutdown must return once no append worker runs");
        assertTrue(((ThreadPoolExecutor) write.appendExecutor()).isTerminated());
        verify(service).stopAppends();
        assertThrows(RejectedExecutionException.class, () -> write.appendExecutor().execute(() -> { }));
        // The emulator lifecycle and bean destruction can both reach the teardown.
        write.stopManagedContainers();
    }

    @Test
    void stopsAppendsBeforeInterruptingTheWorkers() {
        BigQueryService stoppingService = mock(BigQueryService.class);
        BigQueryStorageWrite write = newWrite(stoppingService);
        doAnswer(invocation -> {
            // A worker waiting for the table write lock only gives up once appends are stopped, so the stop has
            // to come first for the join to mean anything.
            assertFalse(((ThreadPoolExecutor) write.appendExecutor()).isShutdown(),
                    "appends must be stopped before the workers are interrupted");
            return null;
        }).when(stoppingService).stopAppends();

        write.stopManagedContainers();

        verify(stoppingService).stopAppends();
    }

    @Test
    void wakesConnectionsParkedWithoutASlotOnShutdown() {
        BigQueryStorageWrite write = newWrite(service);
        AtomicBoolean stoppingWhenWoken = new AtomicBoolean();
        CountDownLatch woken = new CountDownLatch(1);
        while (write.acquireAppendRequestSlotOrWait(() -> { })) {
            // Exhaust the pool; the call that fails parks a waiter that declines every slot.
        }
        assertFalse(write.acquireAppendRequestSlotOrWait(() -> {
            stoppingWhenWoken.set(write.stopping());
            woken.countDown();
        }));

        write.stopManagedContainers();

        assertEquals(0, woken.getCount(), "shutdown must wake a connection parked without a slot");
        assertTrue(stoppingWhenWoken.get(), "a parked connection is woken only once appends are stopped");
    }

    /**
     * A release used to wake one waiter, so a connection that had been cancelled took the release and left the
     * connection behind it parked with a free slot.
     */
    @Test
    void aWaiterThatDeclinesTheSlotDoesNotStrandTheConnectionBehindIt() {
        BigQueryStorageWrite write = newWrite(service);
        while (write.acquireAppendRequestSlotOrWait(() -> { })) {
            // Exhaust the pool; the call that fails parks a waiter that declines every slot.
        }
        AtomicBoolean liveAcquired = new AtomicBoolean();
        assertFalse(write.acquireAppendRequestSlotOrWait(
                () -> liveAcquired.set(write.acquireAppendRequestSlotOrWait(() -> { }))));

        write.releaseAppendRequestSlot();

        assertTrue(liveAcquired.get(), "a release reaches the connection behind a waiter that declined it");
    }

    /** Taking a slot and registering for one is a single step, so no release can land in between and be lost. */
    @Test
    void aConnectionThatTakesASlotRegistersNoWaiter() {
        BigQueryStorageWrite write = newWrite(service);
        AtomicBoolean woken = new AtomicBoolean();

        assertTrue(write.acquireAppendRequestSlotOrWait(() -> woken.set(true)));
        write.releaseAppendRequestSlot();

        assertFalse(woken.get(), "a call that took its slot leaves nothing for a release to wake");
    }

    @Test
    void rejectsAppendsOnceShutdownHasStarted() {
        BigQueryStorageWrite write = newWrite(service);
        write.stopManagedContainers();

        BigQueryStorageWrite.AppendException rejected = assertThrows(BigQueryStorageWrite.AppendException.class,
                () -> write.append(new BigQueryStorageWrite.Connection(), AppendRowsRequest.newBuilder().build()));

        assertEquals(Status.Code.UNAVAILABLE, rejected.code);
    }

    /**
     * A save is the last step of a write. Suppressing it once shutdown starts would acknowledge rows that the
     * restart cannot recover, so shutdown refuses new work instead and leaves running writes to save.
     */
    @Test
    void savesStreamStateWhileShutdownIsRunningSoTheFinalFlushCapturesIt() {
        StorageBackend<String, StoredWriteStream> streamStore = mock(StorageBackend.class);
        when(service.getTable(any(), any(), any())).thenReturn(newStorageTable());
        when(service.getDataset(any(), any())).thenReturn(new Dataset());
        BigQueryStorageWrite write = newWrite(service, streamStore);
        String streamName = write.createWriteStream(CreateWriteStreamRequest.newBuilder()
                .setParent("projects/p1/datasets/ds1/tables/t1")
                .setWriteStream(WriteStream.newBuilder().setType(WriteStream.Type.PENDING))
                .build()).getName();
        clearInvocations(streamStore);

        write.stopManagedContainers();
        write.finalizeWriteStream(streamName);

        ArgumentCaptor<StoredWriteStream> saved = ArgumentCaptor.forClass(StoredWriteStream.class);
        verify(streamStore).put(eq(streamName), saved.capture());
        assertNotNull(saved.getValue().getFinalized(), "a stream changed while shutdown runs must still be saved");
    }

    @Test
    void refusesToCreateStreamsOnceShutdownHasStarted() {
        StorageBackend<String, StoredWriteStream> streamStore = mock(StorageBackend.class);
        when(service.getTable(any(), any(), any())).thenReturn(newStorageTable());
        when(service.getDataset(any(), any())).thenReturn(new Dataset());
        BigQueryStorageWrite write = newWrite(service, streamStore);

        write.stopManagedContainers();

        GcpException rejected = assertThrows(GcpException.class,
                () -> write.createWriteStream(CreateWriteStreamRequest.newBuilder()
                        .setParent("projects/p1/datasets/ds1/tables/t1")
                        .setWriteStream(WriteStream.newBuilder().setType(WriteStream.Type.PENDING))
                        .build()));
        assertEquals(503, rejected.getHttpStatus());
        verifyNoInteractions(streamStore);
    }

    @SuppressWarnings("unchecked")
    private static BigQueryStorageWrite newWrite(BigQueryService service) {
        return newWrite(service, mock(StorageBackend.class));
    }

    @SuppressWarnings("unchecked")
    private static BigQueryStorageWrite newWrite(BigQueryService service,
                                                 StorageBackend<String, StoredWriteStream> streamStore) {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.createGlobal(any(), any(), any())).thenReturn((StorageBackend) streamStore);
        return new BigQueryStorageWrite(service, mock(GrpcServerManager.class),
                mock(EmulatorConfig.class), storageFactory);
    }

    private static Table newStorageTable() {
        Table table = new Table();
        table.setTableReference(new TableReference("p1", "ds1", "t1"));
        table.setCreationTime("0");
        TableFieldSchema name = new TableFieldSchema();
        name.setName("name");
        name.setType("STRING");
        table.setSchema(new TableSchema(List.of(name)));
        return table;
    }

    /** A worker that ignores interruption, as one waiting for the table write lock does. */
    private static void awaitIgnoringInterrupts(CountDownLatch release) {
        boolean released = false;
        while (!released) {
            try {
                release.await();
                released = true;
            } catch (InterruptedException ignored) {
                // Entering a synchronized block does not respond to interruption, so the worker waits on.
            }
        }
    }
}
