package io.floci.gcp.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProjectRecord;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.core.storage.PersistentStorage;
import io.floci.gcp.services.iam.model.StoredRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IamRoleServiceTest {

    private static final String ROLE = "projects/p/roles/reader";
    private static final String ORG_ROLE = "organizations/42/roles/reader";

    private final Map<String, ProjectRecord> projectRecords = new HashMap<>();
    private ProjectRegistry projects;
    private IamRoleService service;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectRegistry.class);
        when(projects.find(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(projectRecords.get(invocation.<String>getArgument(0))));
        service = new IamRoleService(new InMemoryStorage<>(), projects);
    }

    @Test
    void createReturnsRoleWithEtagAndOmitsAlphaStageAndFalseDeleted() throws Exception {
        StoredRole created = service.create("projects/p", "reader", role("Reader", "pubsub.topics.get"));

        assertEquals(ROLE, created.getName());
        assertEquals(List.of("pubsub.topics.get"), created.getIncludedPermissions());
        assertNotNull(created.getEtag());
        Map<String, Object> json = new ObjectMapper().convertValue(created, new TypeReference<>() {});
        assertEquals(Set.of("name", "title", "includedPermissions", "etag"), json.keySet());

        StoredRole ga = service.create("organizations/42", "reader", Map.of("stage", "GA"));
        assertEquals("GA", ga.getStage());
        assertEquals(ORG_ROLE, ga.getName());
    }

    @Test
    void createValidatesRoleIdParentAndFieldsAndReservesExistingIds() {
        for (String roleId : List.of("ab", "a".repeat(65), "has-dash", "has/slash")) {
            assertStatus("INVALID_ARGUMENT", () -> service.create("projects/p", roleId, Map.of()));
        }
        for (String parent : List.of("projects/-", "projects/_", "folders/1", "projects")) {
            assertStatus("INVALID_ARGUMENT", () -> service.create(parent, "reader", Map.of()));
        }
        assertStatus("INVALID_ARGUMENT", () -> service.create("projects/p", "reader",
                Map.of("includedPermissions", List.of("pubsub.*"))));
        assertStatus("INVALID_ARGUMENT", () -> service.create("projects/p", "reader",
                Map.of("title", "t".repeat(101))));
        assertStatus("INVALID_ARGUMENT", () -> service.create("projects/p", "reader",
                Map.of("stage", "UNKNOWN")));
        assertStatus("INVALID_ARGUMENT", () -> service.create("projects/p", "reader", Map.of("stage", 3)));
        assertEquals("GA", service.create("projects/p", "numeric", Map.of("stage", 2)).getStage());

        service.create("projects/p", "reader", Map.of());
        assertStatus("ALREADY_EXISTS", () -> service.create("projects/p", "reader", Map.of()));
        service.delete(ROLE, null);
        assertStatus("FAILED_PRECONDITION", () -> service.create("projects/p", "reader", Map.of()));
        assertStatus("NOT_FOUND", () -> service.get("projects/p/roles/missing"));
    }

    @Test
    void listUsesBasicViewByDefaultAndHidesDeletedRolesUnlessRequested() {
        service.create("projects/p", "a_role", role("A", "pubsub.topics.get"));
        service.create("projects/p", "b_role", role("B", "pubsub.topics.list"));
        service.create("projects/other", "c_role", role("C", "pubsub.topics.list"));
        service.delete("projects/p/roles/b_role", null);

        IamRoleService.RolePage basic = service.list("projects/p", null, false, 0, null);
        assertEquals(List.of("projects/p/roles/a_role"), basic.roles().stream().map(StoredRole::getName).toList());
        assertTrue(basic.roles().getFirst().getIncludedPermissions().isEmpty());

        IamRoleService.RolePage full = service.list("projects/p", "FULL", true, 1, null);
        assertEquals(List.of("pubsub.topics.get"), full.roles().getFirst().getIncludedPermissions());
        assertNotNull(full.nextPageToken());
        IamRoleService.RolePage next = service.list("projects/p", "FULL", true, 1, full.nextPageToken());
        assertEquals(Boolean.TRUE, next.roles().getFirst().getDeleted());
        assertNull(next.nextPageToken());
        assertStatus("INVALID_ARGUMENT", () -> service.list("projects/p", "PARTIAL", false, 0, null));
    }

    @Test
    void updateHonorsMaskAndEtagInEitherBase64Alphabet() {
        StoredRole created = service.create("projects/p", "reader", role("Reader", "pubsub.topics.get"));

        StoredRole masked = service.update(ROLE, Map.of("title", "Ignored", "description", "Applied",
                "etag", created.getEtag()), "description");
        assertEquals("Reader", masked.getTitle());
        assertEquals("Applied", masked.getDescription());
        assertNotEquals(created.getEtag(), masked.getEtag());

        assertStatus("ABORTED", () -> service.update(ROLE, Map.of("etag", created.getEtag()), "title"));
        assertStatus("INVALID_ARGUMENT", () -> service.update(ROLE, Map.of("etag", 123), "title"));
        assertStatus("INVALID_ARGUMENT", () -> service.update(ROLE, Map.of(), "name"));

        String urlSafe = Base64.getUrlEncoder().encodeToString(Base64.getDecoder().decode(masked.getEtag()));
        StoredRole unmasked = service.update(ROLE, Map.of("includedPermissions",
                List.of("pubsub.topics.list"), "etag", urlSafe), null);
        assertEquals(List.of("pubsub.topics.list"), unmasked.getIncludedPermissions());
        assertNull(unmasked.getTitle());
        assertNull(unmasked.getDescription());
        assertStatus("INVALID_ARGUMENT", () -> service.update(ROLE, Map.of("name", "projects/p/roles/other"), null));
    }

    @Test
    void deleteAndUndeleteToggleStateAndRejectInvalidTransitions() {
        StoredRole created = service.create("projects/p", "reader", Map.of());
        assertStatus("FAILED_PRECONDITION", () -> service.undelete(ROLE, null));
        assertStatus("ABORTED", () -> service.delete(ROLE, "AAAAAAAAAAA="));

        StoredRole deleted = service.delete(ROLE, created.getEtag());
        assertEquals(Boolean.TRUE, deleted.getDeleted());
        assertStatus("FAILED_PRECONDITION", () -> service.delete(ROLE, null));
        assertStatus("FAILED_PRECONDITION", () -> service.update(ROLE, Map.of("title", "x"), "title"));
        assertEquals(Boolean.TRUE, service.get(ROLE).getDeleted());

        StoredRole restored = service.undelete(ROLE, deleted.getEtag());
        assertNull(restored.getDeleted());
    }

    @Test
    void validationErrorsMatchLiveGcpMessages() {
        StoredRole created = service.create("projects/p", "reader", role("Reader", "pubsub.topics.get"));
        String stale = created.getEtag();
        service.update(ROLE, Map.of("title", "Renamed"), "title");
        record Case(String message, Runnable call) {}
        List<Case> cases = List.of(
                new Case("The field page_size is out of range. It must be between 0 and 1,000.",
                        () -> service.list("projects/p", null, false, -1, null)),
                new Case("The field page_size is out of range. It must be between 0 and 1,000.",
                        () -> service.list("projects/p", null, false, 1001, null)),
                new Case("Invalid value at 'etag' (TYPE_BYTES), Base64 decoding failed for \"!!!\"",
                        () -> service.delete(ROLE, "!!!")),
                new Case("Invalid value at 'role.etag' (TYPE_BYTES), 123",
                        () -> service.update(ROLE, Map.of("title", "x", "etag", 123), "title")),
                new Case("Invalid field mask for Role.", () -> service.update(ROLE, Map.of("title", "x"), "bogus")),
                new Case("Invalid value at 'role.title' (TYPE_STRING), 123",
                        () -> service.update(ROLE, Map.of("title", 123), "title")),
                new Case("The role name must be in form \"roles/{role}\", \"organizations/{organization_id}/roles/{role}\", "
                        + "\"projects/{project_id}/roles/{role}\".", () -> service.get("projects/p/roles/bad:name")),
                new Case("The display_name length (120) is longer than the maximum allowed length 100.",
                        () -> service.create("projects/p", "longtitle", Map.of("title", "\u00e9".repeat(60)))),
                new Case("The description length (400) is longer than the maximum allowed length 300.",
                        () -> service.create("projects/p", "longdesc", Map.of("description", "\u00e9".repeat(200)))),
                new Case("The number of permissions in the role is greater than the maximum of 3,000.",
                        () -> service.create("projects/p", "toomany", Map.of("includedPermissions",
                                Collections.nCopies(3001, "pubsub.topics.get")))));
        for (Case c : cases) {
            GcpException error = assertThrows(GcpException.class, c.call()::run, c.message());
            assertEquals(c.message(), error.getMessage());
        }
        assertMessage("There were concurrent policy changes. Please retry the whole read-modify-write with "
                + "exponential backoff.", () -> service.update(ROLE, Map.of("title", "x", "etag", stale), "title"));
        assertMessage("A role that is not deleted cannot be undeleted.", () -> service.undelete(ROLE, stale));
        service.delete(ROLE, null);
        assertMessage("You can't delete role_id (" + ROLE + ") as it is already deleted.", () -> service.delete(ROLE, stale));
        assertMessage("You can't update a role (" + ROLE + ") which is in a deleted state.",
                () -> service.update(ROLE, Map.of("title", "x"), "title"));
        assertStatus("ABORTED", () -> service.update(ROLE, Map.of("title", "x", "etag", stale), "title"));
        assertMessage("There were concurrent policy changes. Please retry the whole read-modify-write with "
                + "exponential backoff.", () -> service.undelete(ROLE, stale));
        assertMessage("You can't create a role with role_id (reader) where there is an existing role with that "
                + "role_id in a deleted state.", () -> service.create("projects/p", "reader", Map.of()));
        service.undelete(ROLE, null);
        assertEquals(List.of("pubsub.topics.list"), service.update(ROLE,
                Map.of("includedPermissions", "pubsub.topics.list"), "includedPermissions").getIncludedPermissions());
    }

    @Test
    void projectRolesGrantOnlyThroughPoliciesOfTheirProject() {
        service.create("projects/p", "reader", role("Reader", "pubsub.topics.get"));

        assertEquals(Set.of("pubsub.topics.get"), service.grantedPermissions(ROLE, "projects/p"));
        assertEquals(Set.of("pubsub.topics.get"), service.grantedPermissions(ROLE, "projects/p/topics/t"));
        for (String outside : List.of("projects/other", "projects/other/topics/t", "projects/-/serviceAccounts/x",
                "projects/_/buckets/b", "buckets/b", "organizations/42")) {
            assertTrue(service.grantedPermissions(ROLE, outside).isEmpty(), outside);
            assertFalse(service.applies(ROLE, outside), outside);
        }
        assertTrue(service.grantedPermissions("projects/p/roles/missing", "projects/p").isEmpty());
        // A project number and its ID are different names, as for every other path-keyed resource.
        ProjectRecord numbered = project("p", null, null);
        numbered.setProjectNumber("123456789012");
        projectRecords.put("123456789012", numbered);
        assertTrue(service.grantedPermissions(ROLE, "projects/123456789012").isEmpty());
        service.create("projects/123456789012", "numeric", role("Numeric", "pubsub.topics.get"));
        String numericRole = "projects/123456789012/roles/numeric";
        assertEquals(Set.of("pubsub.topics.get"), service.grantedPermissions(numericRole, "projects/123456789012"));
        assertTrue(service.grantedPermissions(numericRole, "projects/p").isEmpty());
        assertTrue(service.grantedPermissions("roles/viewer", "projects/p").isEmpty());
    }

    @Test
    void organizationRolesRequireRecordedOrganizationAncestryAndFailClosedOtherwise() {
        service.create("organizations/42", "reader", role("Reader", "pubsub.topics.get"));
        projectRecords.put("in-org", project("in-org", "organization", "42"));
        projectRecords.put("other-org", project("other-org", "organization", "7"));
        projectRecords.put("in-folder", project("in-folder", "folder", "42"));
        projectRecords.put("no-parent", project("no-parent", null, null));

        assertEquals(Set.of("pubsub.topics.get"), service.grantedPermissions(ORG_ROLE, "organizations/42"));
        assertEquals(Set.of("pubsub.topics.get"), service.grantedPermissions(ORG_ROLE, "projects/in-org/topics/t"));
        ProjectRecord numbered = project("in-org", "organization", "42");
        numbered.setProjectNumber("123456789012");
        projectRecords.put("123456789012", numbered);
        assertTrue(service.grantedPermissions(ORG_ROLE, "projects/123456789012").isEmpty());
        for (String outside : List.of("organizations/7", "projects/other-org", "projects/in-folder",
                "projects/no-parent", "projects/unknown", "buckets/b")) {
            assertTrue(service.grantedPermissions(ORG_ROLE, outside).isEmpty(), outside);
        }
    }

    @Test
    void requireBindableRejectsOnlyPlacementsTheRecordedHierarchySettles() {
        service.create("projects/p", "reader", role("Reader", "pubsub.topics.get"));
        service.create("organizations/42", "reader", role("Reader", "pubsub.topics.get"));
        projectRecords.put("in-org", project("in-org", "organization", "42"));
        projectRecords.put("other-org", project("other-org", "organization", "7"));
        projectRecords.put("in-folder", project("in-folder", "folder", "42"));
        projectRecords.put("implicit", project("implicit", null, null));
        ProjectRecord created = project("created", null, null);
        created.setCreated(true);
        projectRecords.put("created", created);
        ProjectRecord numbered = project("q", null, null);
        numbered.setProjectNumber("123456789012");
        projectRecords.put("123456789012", numbered);

        for (String resource : List.of("projects/p", "projects/p/topics/t", "projects/-/serviceAccounts/x",
                "projects/999999999999", "buckets/b")) {
            service.requireBindable(ROLE, resource);
        }
        for (String resource : List.of("projects/in-org", "projects/in-folder/topics/t", "projects/implicit",
                "projects/unknown", "organizations/42")) {
            service.requireBindable(ORG_ROLE, resource);
        }
        service.requireBindable("roles/viewer", "projects/other");

        String outside = "Role (%s) does not exist in the resource's hierarchy.";
        for (String resource : List.of("projects/other", "projects/other/topics/t", "projects/123456789012")) {
            assertMessage(outside.formatted(ROLE), () -> service.requireBindable(ROLE, resource));
        }
        for (String resource : List.of("projects/other-org", "projects/created", "organizations/7")) {
            assertMessage(outside.formatted(ORG_ROLE), () -> service.requireBindable(ORG_ROLE, resource));
        }
        assertMessage("Role (projects/p/roles/missing) does not exist in resource's hierarchy.",
                () -> service.requireBindable("projects/p/roles/missing", "projects/p"));
        assertMessage("Role (organizations/42/roles/missing) does not exist in resource's hierarchy.",
                () -> service.requireBindable("organizations/42/roles/missing", "projects/in-folder"));

        service.delete(ROLE, null);
        assertMessage("Cannot add new grants to deleted role (" + ROLE + ").",
                () -> service.requireBindable(ROLE, "projects/p"));
        assertStatus("INVALID_ARGUMENT", () -> service.requireBindable(ROLE, "projects/p"));
    }

    @Test
    void permissionEditsDisablingAndDeletionAffectSubsequentDecisions() {
        StoredRole created = service.create("projects/p", "reader", role("Reader", "pubsub.topics.get"));
        StoredRole edited = service.update(ROLE, Map.of("includedPermissions", List.of("pubsub.topics.list")),
                "includedPermissions");
        assertEquals(Set.of("pubsub.topics.list"), service.grantedPermissions(ROLE, "projects/p"));

        StoredRole disabled = service.update(ROLE, Map.of("stage", "DISABLED"), "stage");
        assertTrue(service.grantedPermissions(ROLE, "projects/p").isEmpty());
        assertFalse(service.applies(ROLE, "projects/p"));
        service.update(ROLE, Map.of("stage", "GA", "etag", disabled.getEtag()), "stage");
        assertTrue(service.applies(ROLE, "projects/p"));

        service.delete(ROLE, null);
        assertTrue(service.grantedPermissions(ROLE, "projects/p").isEmpty());
        service.undelete(ROLE, null);
        assertEquals(Set.of("pubsub.topics.list"), service.grantedPermissions(ROLE, "projects/p"));
        assertNotEquals(created.getEtag(), edited.getEtag());
    }

    @Test
    void definitionsAndRevocationStatePersistAcrossRestart(@TempDir Path directory) {
        Path file = directory.resolve("iam-roles.json");
        PersistentStorage<String, StoredRole> store = new PersistentStorage<>(file, new TypeReference<>() {});
        store.load();
        IamRoleService first = new IamRoleService(store, projects);
        first.create("projects/p", "reader", role("Reader", "pubsub.topics.get"));
        first.create("projects/p", "revoked", role("Revoked", "pubsub.topics.get"));
        first.delete("projects/p/roles/revoked", null);
        first.create("projects/p", "disabled", Map.of("includedPermissions", List.of("pubsub.topics.get"),
                "stage", "DISABLED"));

        PersistentStorage<String, StoredRole> reloaded = new PersistentStorage<>(file, new TypeReference<>() {});
        reloaded.load();
        IamRoleService restarted = new IamRoleService(reloaded, projects);

        assertEquals(Set.of("pubsub.topics.get"), restarted.grantedPermissions(ROLE, "projects/p"));
        assertEquals(Boolean.TRUE, restarted.get("projects/p/roles/revoked").getDeleted());
        assertTrue(restarted.grantedPermissions("projects/p/roles/revoked", "projects/p").isEmpty());
        assertEquals("DISABLED", restarted.get("projects/p/roles/disabled").getStage());
        assertTrue(restarted.grantedPermissions("projects/p/roles/disabled", "projects/p").isEmpty());
    }

    @Test
    void competingMutationsSerializeInEitherOrder() throws Exception {
        record Race(String label, Function<IamRoleService, Object> first, Function<IamRoleService, Object> second,
                String secondStatus, Boolean deletedAfter) {}
        List<Race> races = List.of(
                new Race("stale update behind delete", s -> s.delete(ROLE, etag(s)),
                        s -> s.update(ROLE, Map.of("title", "x", "etag", initialEtag), "title"), "ABORTED", true),
                new Race("stale delete behind update", s -> s.update(ROLE, Map.of("title", "x"), "title"),
                        s -> s.delete(ROLE, initialEtag), "ABORTED", null),
                new Race("create behind create", s -> s.create("projects/p", "twin", Map.of()),
                        s -> s.create("projects/p", "twin", Map.of()), "ALREADY_EXISTS", null),
                new Race("undelete behind delete", s -> s.delete(ROLE, null),
                        s -> s.undelete(ROLE, null), null, null));
        for (Race race : races) {
            InMemoryStorage<String, StoredRole> store = spy(new InMemoryStorage<>());
            IamRoleService racing = new IamRoleService(store, projects);
            initialEtag = racing.create("projects/p", "reader", role("Reader", "pubsub.topics.get")).getEtag();
            CountDownLatch reached = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(invocation -> {
                if (reached.getCount() > 0) {
                    reached.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                }
                return invocation.callRealMethod();
            }).when(store).put(anyString(), any());
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = executor.submit(() -> race.first().apply(racing));
                assertTrue(reached.await(5, TimeUnit.SECONDS), race.label());
                AtomicReference<Thread> competitor = new AtomicReference<>();
                Future<?> second = executor.submit(() -> {
                    competitor.set(Thread.currentThread());
                    return race.second().apply(racing);
                });
                awaitBlockedOnMonitor(competitor);
                assertFalse(second.isDone(), race.label());
                release.countDown();
                first.get(5, TimeUnit.SECONDS);
                if (race.secondStatus() == null) {
                    second.get(5, TimeUnit.SECONDS);
                } else {
                    ExecutionException failure = assertThrows(ExecutionException.class,
                            () -> second.get(5, TimeUnit.SECONDS), race.label());
                    assertEquals(race.secondStatus(), ((GcpException) failure.getCause()).getGcpStatus(), race.label());
                }
                assertEquals(race.deletedAfter(), racing.get(ROLE).getDeleted(), race.label());
                assertEquals(List.of("pubsub.topics.get"), racing.get(ROLE).getIncludedPermissions(), race.label());
            } finally {
                release.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    private String initialEtag;

    private static String etag(IamRoleService service) {
        return service.get(ROLE).getEtag();
    }

    private static void awaitBlockedOnMonitor(AtomicReference<Thread> thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.get() == null || thread.get().getState() != Thread.State.BLOCKED) {
            assertTrue(System.nanoTime() < deadline, "competing mutation never blocked on the role monitor");
            Thread.sleep(5);
        }
    }

    private static Map<String, Object> role(String title, String permission) {
        return Map.of("title", title, "includedPermissions", List.of(permission));
    }

    private static ProjectRecord project(String id, String parentType, String parentId) {
        ProjectRecord record = new ProjectRecord();
        record.setProjectId(id);
        record.setParentType(parentType);
        record.setParentId(parentId);
        return record;
    }

    private static void assertMessage(String message, Runnable call) {
        GcpException error = assertThrows(GcpException.class, call::run, message);
        assertEquals(message, error.getMessage());
    }

    private static void assertStatus(String status, Runnable call) {
        GcpException error = assertThrows(GcpException.class, call::run);
        assertEquals(status, error.getGcpStatus());
    }
}
