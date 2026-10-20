package io.floci.gcp.services.cloudlogging;

import com.google.logging.v2.BigQueryOptions;
import com.google.logging.v2.ListSinksResponse;
import com.google.logging.v2.LogExclusion;
import com.google.logging.v2.LogSink;
import com.google.protobuf.FieldMask;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.cloudlogging.model.StoredLogSink;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloudLoggingSinkServiceTest {

    private static final String PARENT = "organizations/123456789";
    private static final String NAME = PARENT + "/sinks/workspace-events";
    private static final String DESTINATION = "pubsub.googleapis.com/projects/core/topics/gws-events";
    private static final LogSink REQUEST = LogSink.newBuilder().setName("workspace-events")
            .setDestination(DESTINATION).setFilter("protoPayload.methodName:\"Group\"").build();

    @TempDir
    Path tempDir;

    @Test
    void lifecycleKeepsShortIdsAndParentNamespacesIsolated() {
        CloudLoggingSinkService service = new CloudLoggingSinkService(new InMemoryStorage<>());
        LogSink created = service.createSink(PARENT, REQUEST, true);
        service.createSink("projects/123456789", REQUEST.toBuilder().setFilter("project").build(), true);
        service.createSink("organizations/987654321", REQUEST.toBuilder().setFilter("other-org").build(), true);

        assertEquals("workspace-events", created.getName());
        assertTrue(created.getWriterIdentity().startsWith("serviceAccount:"));
        assertEquals(created, service.getSink(NAME));
        assertEquals(List.of(created), service.listSinks(PARENT, 0, null).getSinksList());
        assertEquals("project", service.getSink("projects/123456789/sinks/workspace-events").getFilter());
        assertEquals("other-org", service.getSink("organizations/987654321/sinks/workspace-events").getFilter());
        assertEquals(Status.Code.ALREADY_EXISTS, assertThrows(GcpException.class,
                () -> service.createSink(PARENT, REQUEST, true)).getGrpcCode());

        LogSink updated = service.updateSink(NAME, REQUEST.toBuilder().setFilter("severity>=ERROR").build(),
                mask("filter"), true);
        assertEquals("severity>=ERROR", updated.getFilter());
        assertEquals(DESTINATION, updated.getDestination());
        assertEquals(created.getWriterIdentity(), updated.getWriterIdentity());
        assertEquals(created.getCreateTime(), updated.getCreateTime());
        service.deleteSink(NAME);
        assertEquals(Status.Code.NOT_FOUND, assertThrows(GcpException.class, () -> service.getSink(NAME)).getGrpcCode());
        assertEquals(1, service.listSinks("projects/123456789", 0, null).getSinksCount());
    }

    @Test
    void masksRejectInvalidPathsAndFailedUpdatesLeaveEveryFieldUnchanged() {
        CloudLoggingSinkService service = new CloudLoggingSinkService(new InMemoryStorage<>());
        LogSink created = service.createSink(PARENT, REQUEST, true);
        for (String invalid : List.of("name", "writer_identity", "create_time", "update_time", "unknown",
                "filter.child", "includeChildren", "bigquery_options.uses_timestamp_column_partitioning", "*", "")) {
            assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(GcpException.class,
                    () -> service.updateSink(NAME, REQUEST.toBuilder().setFilter("changed").build(),
                            mask("filter", invalid), true)).getGrpcCode());
            assertEquals(created, service.getSink(NAME));
        }
        assertThrows(GcpException.class, () -> service.updateSink(NAME,
                REQUEST.toBuilder().clearDestination().setFilter("changed").build(), mask("filter", "destination"), true));
        assertEquals(created, service.getSink(NAME));
        assertThrows(GcpException.class, () -> service.updateSink(NAME,
                REQUEST.toBuilder().setName("other-sink").build(), mask("filter"), true));
        assertEquals(created, service.getSink(NAME));
    }

    @Test
    void defaultMaskAndExplicitScalarClearsFollowTheSinkContract() {
        CloudLoggingSinkService service = new CloudLoggingSinkService(new InMemoryStorage<>());
        service.createSink(PARENT, REQUEST.toBuilder().setDescription("keep").setDisabled(true)
                .setIncludeChildren(true).build(), true);
        LogSink updated = service.updateSink(NAME, REQUEST.toBuilder().clearFilter().build(),
                FieldMask.getDefaultInstance(), true);
        assertEquals("", updated.getFilter());
        assertFalse(updated.getIncludeChildren());
        assertTrue(updated.getDisabled());
        assertEquals("keep", updated.getDescription());
        LogSink cleared = service.updateSink(NAME, LogSink.getDefaultInstance(), mask("disabled", "description"), true);
        assertFalse(cleared.getDisabled());
        assertEquals("", cleared.getDescription());
    }

    @Test
    void invalidAndMissingParentsNamesAndDestinationsFailWithoutCreatingState() {
        CloudLoggingSinkService service = new CloudLoggingSinkService(new InMemoryStorage<>());
        for (String parent : List.of("", "organizations/", "organizations/123/extra", "unknown/123", "projects/..")) {
            assertEquals(Status.Code.INVALID_ARGUMENT,
                    assertThrows(GcpException.class, () -> service.createSink(parent, REQUEST, true)).getGrpcCode());
            assertThrows(GcpException.class, () -> service.listSinks(parent, 0, null));
        }
        for (String id : List.of("", ".start", "bad/sink", "bad space", "a".repeat(101))) {
            assertThrows(GcpException.class, () -> service.createSink(PARENT, REQUEST.toBuilder().setName(id).build(), true));
        }
        assertThrows(GcpException.class, () -> service.createSink(PARENT, REQUEST.toBuilder().clearDestination().build(), true));
        assertThrows(GcpException.class, () -> service.getSink("organizations/123/sinks/a/extra"));
        assertTrue(service.listSinks(PARENT, 0, null).getSinksList().isEmpty());
    }

    @Test
    void projectLegacyIdentityCanBeUpgradedButUniqueIdentityCannotBeDowngraded() {
        CloudLoggingSinkService service = new CloudLoggingSinkService(new InMemoryStorage<>());
        String name = "projects/core/sinks/workspace-events";
        LogSink legacy = service.createSink("projects/core", REQUEST, false);
        LogSink upgraded = service.updateSink(name, REQUEST, mask("filter"), true);
        assertNotEquals(legacy.getWriterIdentity(), upgraded.getWriterIdentity());
        assertEquals(upgraded.getWriterIdentity(), service.updateSink(name, REQUEST, mask("filter"), true).getWriterIdentity());
        assertThrows(GcpException.class, () -> service.updateSink(name, REQUEST, mask("filter"), false));
        assertEquals(upgraded.getWriterIdentity(), service.getSink(name).getWriterIdentity());
    }

    @Test
    void paginationIsSortedAndBoundedToItsParent() {
        CloudLoggingSinkService service = new CloudLoggingSinkService(new InMemoryStorage<>());
        service.createSink(PARENT, REQUEST.toBuilder().setName("b").build(), true);
        service.createSink(PARENT, REQUEST.toBuilder().setName("a").build(), true);
        service.createSink("organizations/1234567890", REQUEST.toBuilder().setName("c").build(), true);
        ListSinksResponse first = service.listSinks(PARENT, 1, null);
        assertEquals("a", first.getSinks(0).getName());
        assertFalse(first.getNextPageToken().isEmpty());
        ListSinksResponse second = service.listSinks(PARENT, 1, first.getNextPageToken());
        assertEquals("b", second.getSinks(0).getName());
        assertEquals("", second.getNextPageToken());
        assertEquals("b", service.listSinks(PARENT, Integer.MAX_VALUE, first.getNextPageToken()).getSinks(0).getName());
        for (int pageSize : List.of(0, -1)) {
            ListSinksResponse remaining = service.listSinks(PARENT, pageSize, first.getNextPageToken());
            assertEquals(List.of("b"), remaining.getSinksList().stream().map(LogSink::getName).toList());
            assertEquals("", remaining.getNextPageToken());
        }
    }

    @Test
    void legacyProjectIdentitiesRejectExplicitCrossProjectDestinationsWithoutMutation() {
        CloudLoggingSinkService service = new CloudLoggingSinkService(new InMemoryStorage<>());
        String parent = "projects/core";
        String name = parent + "/sinks/" + REQUEST.getName();
        LogSink created = service.createSink(parent, REQUEST, false);
        for (String destination : List.of("pubsub.googleapis.com/projects/other/topics/logs",
                "bigquery.googleapis.com/projects/other/datasets/logs")) {
            LogSink invalid = REQUEST.toBuilder().setDestination(destination).build();
            assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(GcpException.class,
                    () -> service.createSink(parent, invalid.toBuilder().setName("invalid").build(), false)).getGrpcCode());
            assertEquals(Status.Code.NOT_FOUND, assertThrows(GcpException.class,
                    () -> service.getSink(parent + "/sinks/invalid")).getGrpcCode());
            assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(GcpException.class,
                    () -> service.updateSink(name, invalid, mask("destination"), false)).getGrpcCode());
            assertEquals(created, service.getSink(name));
        }
        LogSink upgraded = service.updateSink(name, REQUEST.toBuilder()
                .setDestination("pubsub.googleapis.com/projects/other/topics/logs").build(), mask("destination"), true);
        assertNotEquals(created.getWriterIdentity(), upgraded.getWriterIdentity());
    }

    @Test
    void nonProjectIdentitiesStayStableAcrossWriterIdentityFlagChanges() {
        CloudLoggingSinkService service = new CloudLoggingSinkService(new InMemoryStorage<>());
        for (String parent : List.of(PARENT, "folders/123456789", "billingAccounts/123456789")) {
            for (boolean createFlag : List.of(false, true)) {
                LogSink requested = REQUEST.toBuilder().setName("flag-" + createFlag).setDisabled(true).build();
                LogSink created = service.createSink(parent, requested, createFlag);
                String name = parent + "/sinks/" + requested.getName();
                for (boolean updateFlag : List.of(false, true, false)) {
                    LogSink updated = service.updateSink(name, LogSink.newBuilder()
                                    .setDescription("Updated with flag " + updateFlag).build(),
                            mask("description"), updateFlag);
                    assertEquals(created.getWriterIdentity(), updated.getWriterIdentity());
                    assertEquals(created.getCreateTime(), updated.getCreateTime());
                    assertEquals(created.getFilter(), updated.getFilter());
                    assertTrue(updated.getDisabled());
                    assertEquals("Updated with flag " + updateFlag, updated.getDescription());
                    assertEquals(updated, service.getSink(name));
                }
            }
        }
    }

    @Test
    void nestedOptionsAndExclusionsReplaceConfigurationAndIgnoreCallerOwnedOutputFields() {
        CloudLoggingSinkService service = new CloudLoggingSinkService(new InMemoryStorage<>());
        LogExclusion first = LogExclusion.newBuilder().setName("first").setFilter("severity<ERROR").build();
        LogExclusion second = LogExclusion.newBuilder().setName("second").setFilter("severity<WARNING").build();
        service.createSink(PARENT, REQUEST.toBuilder().setDestination("bigquery.googleapis.com/projects/core/datasets/logs")
                .addExclusions(first)
                .setWriterIdentity("serviceAccount:forged@example.test")
                .setBigqueryOptions(BigQueryOptions.newBuilder().setUsePartitionedTables(true)).build(), true);
        LogSink changed = service.updateSink(NAME, REQUEST.toBuilder().addExclusions(second)
                        .setBigqueryOptions(BigQueryOptions.newBuilder().setUsePartitionedTables(false)).build(),
                mask("exclusions", "bigquery_options.use_partitioned_tables"), true);
        assertEquals(List.of(second), changed.getExclusionsList());
        assertFalse(changed.getBigqueryOptions().getUsePartitionedTables());
        assertFalse(changed.getBigqueryOptions().getUsesTimestampColumnPartitioning());
        assertNotEquals("serviceAccount:forged@example.test", changed.getWriterIdentity());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void restartPreservesConfigurationIdentityAndDeletion(String mode) {
        StorageFactory first = factory(mode);
        CloudLoggingSinkService service = new CloudLoggingSinkService(first);
        LogSink created = service.createSink(PARENT, REQUEST, true);
        service.updateSink(NAME, REQUEST.toBuilder().setIncludeChildren(true).build(), mask("include_children"), true);
        first.flushAll();
        first.shutdownAll();

        StorageFactory second = factory(mode);
        CloudLoggingSinkService restarted = new CloudLoggingSinkService(second);
        assertEquals(created.getWriterIdentity(), restarted.getSink(NAME).getWriterIdentity());
        assertEquals(REQUEST.getFilter(), restarted.getSink(NAME).getFilter());
        assertEquals(DESTINATION, restarted.getSink(NAME).getDestination());
        assertTrue(restarted.getSink(NAME).getIncludeChildren());
        restarted.deleteSink(NAME);
        second.flushAll();
        second.shutdownAll();

        StorageFactory third = factory(mode);
        assertTrue(new CloudLoggingSinkService(third).listSinks(PARENT, 0, null).getSinksList().isEmpty());
        third.shutdownAll();
    }

    @Test
    void competingCreatesCannotReplaceTheFirstWritersIdentity() throws Exception {
        StorageBackend<String, StoredLogSink> backend = spy(new InMemoryStorage<>());
        CloudLoggingSinkService service = new CloudLoggingSinkService(backend);
        CountDownLatch putting = new CountDownLatch(1);
        CountDownLatch releasePut = new CountDownLatch(1);
        doAnswer(invocation -> {
            putting.countDown();
            assertTrue(releasePut.await(5, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(backend).put(eq(NAME), any());
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<LogSink> first = executor.submit(() -> service.createSink(PARENT, REQUEST, true));
            assertTrue(putting.await(5, TimeUnit.SECONDS));
            CountDownLatch secondStarted = new CountDownLatch(1);
            Future<GcpException> second = executor.submit(() -> {
                secondStarted.countDown();
                return assertThrows(GcpException.class, () -> service.createSink(PARENT, REQUEST, true));
            });
            try {
                assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            } finally {
                releasePut.countDown();
            }
            LogSink winner = first.get(5, TimeUnit.SECONDS);
            assertEquals(Status.Code.ALREADY_EXISTS, second.get(5, TimeUnit.SECONDS).getGrpcCode());
            assertEquals(winner, service.getSink(NAME));
        } finally {
            releasePut.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void updateAndDeleteCannotRepublishADeletedSink(boolean updateFirst) throws Exception {
        StorageBackend<String, StoredLogSink> backend = spy(new InMemoryStorage<>());
        CloudLoggingSinkService service = new CloudLoggingSinkService(backend);
        service.createSink(PARENT, REQUEST, true);
        CountDownLatch mutating = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);
        if (updateFirst) {
            doAnswer(invocation -> {
                mutating.countDown();
                assertTrue(releaseMutation.await(5, TimeUnit.SECONDS));
                return invocation.callRealMethod();
            }).when(backend).put(eq(NAME), any());
        } else {
            doAnswer(invocation -> {
                mutating.countDown();
                assertTrue(releaseMutation.await(5, TimeUnit.SECONDS));
                return invocation.callRealMethod();
            }).when(backend).delete(NAME);
        }
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> {
                if (updateFirst) {
                    service.updateSink(NAME, REQUEST.toBuilder().setFilter("changed").build(), mask("filter"), true);
                } else {
                    service.deleteSink(NAME);
                }
            });
            assertTrue(mutating.await(5, TimeUnit.SECONDS));
            CountDownLatch secondStarted = new CountDownLatch(1);
            Future<?> second = executor.submit(() -> {
                secondStarted.countDown();
                if (updateFirst) {
                    service.deleteSink(NAME);
                } else {
                    assertEquals(Status.Code.NOT_FOUND, assertThrows(GcpException.class,
                            () -> service.updateSink(NAME, REQUEST, mask("filter"), true)).getGrpcCode());
                }
            });
            try {
                assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            } finally {
                releaseMutation.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertTrue(service.listSinks(PARENT, 0, null).getSinksList().isEmpty());
            assertTrue(backend.get(NAME).isEmpty());
        } finally {
            releaseMutation.countDown();
        }
    }

    @Test
    void listingDecodesOnlyTheRequestedPage() {
        StorageBackend<String, StoredLogSink> backend = spy(new InMemoryStorage<>());
        CloudLoggingSinkService service = new CloudLoggingSinkService(backend);
        service.createSink(PARENT, REQUEST.toBuilder().setName("a").build(), true);
        backend.put(PARENT + "/sinks/z", new StoredLogSink("not JSON", true));
        clearInvocations(backend);

        ListSinksResponse first = service.listSinks(PARENT, 1, null);
        assertEquals("a", first.getSinks(0).getName());
        verify(backend, never()).get(PARENT + "/sinks/z");
        verify(backend, never()).scan(any());
        assertThrows(GcpException.class, () -> service.listSinks(PARENT, 1, first.getNextPageToken()));
    }

    @Test
    void resourceWritersAreSharedAndSurviveDeletingEverySink() {
        CloudLoggingSinkService service = new CloudLoggingSinkService(new InMemoryStorage<>());
        for (String parent : List.of("projects/core", PARENT, "folders/123", "billingAccounts/abc")) {
            LogSink first = service.createSink(parent, REQUEST, true);
            LogSink second = service.createSink(parent, REQUEST.toBuilder().setName("second").build(), true);
            assertEquals(first.getWriterIdentity(), second.getWriterIdentity());
            service.deleteSink(parent + "/sinks/" + REQUEST.getName());
            service.deleteSink(parent + "/sinks/second");
            assertEquals(first.getWriterIdentity(), service.createSink(parent, REQUEST, true).getWriterIdentity());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete", "disable", "destination", "filter", "exclusions"})
    void deliveryAndSinkMutationsShareOneBoundary(String mutation) throws Exception {
        CloudLoggingGcsExporter exporter = mock(CloudLoggingGcsExporter.class);
        CloudLoggingSinkService service = new CloudLoggingSinkService(new InMemoryStorage<>(), exporter);
        LogSink request = REQUEST.toBuilder().setDestination("storage.googleapis.com/logs").build();
        service.createSink(PARENT, request, true);
        CountDownLatch delivering = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            delivering.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(exporter).export(eq(PARENT), any(), any());
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> delivery = executor.submit(() -> service.exportEntries(List.of()));
            assertTrue(delivering.await(5, TimeUnit.SECONDS));
            CountDownLatch started = new CountDownLatch(1);
            Future<?> change = executor.submit(() -> {
                started.countDown();
                switch (mutation) {
                    case "delete" -> service.deleteSink(NAME);
                    case "disable" -> service.updateSink(NAME, request.toBuilder().setDisabled(true).build(), mask("disabled"), true);
                    case "destination" -> service.updateSink(NAME, request.toBuilder().setDestination("storage.googleapis.com/other").build(), mask("destination"), true);
                    case "filter" -> service.updateSink(NAME, request.toBuilder().setFilter("severity=ERROR").build(), mask("filter"), true);
                    case "exclusions" -> service.updateSink(NAME, request.toBuilder().addExclusions(LogExclusion.newBuilder().setName("all").setFilter("severity>=DEFAULT")).build(), mask("exclusions"), true);
                    default -> fail(mutation);
                }
            });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> change.get(100, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            delivery.get(5, TimeUnit.SECONDS);
            change.get(5, TimeUnit.SECONDS);
            clearInvocations(exporter);
            service.exportEntries(List.of());
            if (mutation.equals("delete") || mutation.equals("disable")) {
                verifyNoInteractions(exporter);
            } else {
                verify(exporter).export(eq(PARENT), eq(service.getSink(NAME)), any());
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    void storedIdentitiesDoNotDetermineTheWriterForNewSinksOrLegacyUpgrades() {
        InMemoryStorage<String, StoredLogSink> backend = new InMemoryStorage<>();
        CloudLoggingSinkService service = new CloudLoggingSinkService(backend);
        String parent = "projects/123456789";
        LogSink existing = REQUEST.toBuilder().setName("existing")
                .setWriterIdentity("serviceAccount:persisted@example.test").build();
        backend.put(parent + "/sinks/existing", new StoredLogSink(ProtoJson.print(existing), true));
        String expected = "serviceAccount:service-123456789@gcp-sa-logging.iam.gserviceaccount.com";
        LogSink created = service.createSink(parent, REQUEST, true);
        assertEquals(expected, created.getWriterIdentity());
        assertEquals(expected, service.createSink(parent, REQUEST.toBuilder().setName("second").build(), true).getWriterIdentity());
        assertEquals(existing, service.getSink(parent + "/sinks/existing"));
        assertEquals(existing.getWriterIdentity(), service.updateSink(parent + "/sinks/existing",
                LogSink.newBuilder().setDescription("updated").build(), mask("description"), true).getWriterIdentity());
        service.createSink(parent, REQUEST.toBuilder().setName("legacy")
                .setDestination("pubsub.googleapis.com/" + parent + "/topics/events").build(), false);
        assertEquals(expected, service.updateSink(parent + "/sinks/legacy", LogSink.newBuilder().build(),
                mask("description"), true).getWriterIdentity());
    }

    private StorageFactory factory(String mode) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().mode()).thenReturn(mode);
        when(config.storage().persistentPath()).thenReturn(tempDir.resolve(mode).toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(60_000L);
        return new StorageFactory(config);
    }

    private static FieldMask mask(String... paths) {
        return FieldMask.newBuilder().addAllPaths(List.of(paths)).build();
    }
}
