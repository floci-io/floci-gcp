package io.floci.gcp.core.storage;

import io.floci.gcp.core.common.RequestContext;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectAwareStorageBackendTest {

    @Mock
    Instance<RequestContext> contextInstance;

    @Mock
    RequestContext requestContext;

    private InMemoryStorage<String, String> delegate;
    private ProjectAwareStorageBackend<String> backend;

    @BeforeEach
    void setUp() {
        delegate = new InMemoryStorage<>();
        backend = new ProjectAwareStorageBackend<>(delegate, contextInstance, "default-project");
    }

    private void withProject(String projectId) {
        when(contextInstance.get()).thenReturn(requestContext);
        when(requestContext.getProjectId()).thenReturn(projectId);
    }

    @Test
    void putPrefixesKeyWithProject() {
        withProject("proj-a");
        backend.put("topics/t1", "value");
        assertEquals(Optional.of("value"), delegate.get("proj-a/topics/t1"));
    }

    @Test
    void getResolvesWithProjectPrefix() {
        delegate.put("proj-a/topics/t1", "value");
        withProject("proj-a");
        assertEquals(Optional.of("value"), backend.get("topics/t1"));
    }

    @Test
    void getMissingReturnsEmpty() {
        withProject("proj-a");
        assertEquals(Optional.empty(), backend.get("topics/missing"));
    }

    @Test
    void deleteRemovesPrefixedKey() {
        delegate.put("proj-a/topics/t1", "value");
        withProject("proj-a");
        backend.delete("topics/t1");
        assertEquals(Optional.empty(), delegate.get("proj-a/topics/t1"));
    }

    @Test
    void scanFiltersToCurrentProject() {
        delegate.put("proj-a/topics/t1", "v1");
        delegate.put("proj-a/topics/t2", "v2");
        delegate.put("proj-b/topics/t3", "v3");
        withProject("proj-a");

        List<String> results = backend.scan(k -> k.startsWith("topics/"));
        assertEquals(2, results.size());
        assertTrue(results.containsAll(List.of("v1", "v2")));
    }

    @Test
    void scanPredicateReceivesUnprefixedKey() {
        delegate.put("proj-a/topics/t1", "v1");
        delegate.put("proj-a/subs/s1", "v2");
        withProject("proj-a");

        List<String> results = backend.scan(k -> k.startsWith("topics/"));
        assertEquals(List.of("v1"), results);
    }

    @Test
    void keysReturnsUnprefixedKeysForCurrentProject() {
        delegate.put("proj-a/topics/t1", "v1");
        delegate.put("proj-a/topics/t2", "v2");
        delegate.put("proj-b/topics/t3", "v3");
        withProject("proj-a");

        var keys = backend.keys();
        assertEquals(2, keys.size());
        assertTrue(keys.containsAll(List.of("topics/t1", "topics/t2")));
    }

    @Test
    void fallsBackToDefaultProjectWhenContextNotActive() {
        when(contextInstance.get()).thenThrow(new ContextNotActiveException());
        backend.put("topics/t1", "value");
        assertEquals(Optional.of("value"), delegate.get("default-project/topics/t1"));
    }

    @Test
    void fallsBackToDefaultProjectWhenProjectIdIsNull() {
        when(contextInstance.get()).thenReturn(requestContext);
        when(requestContext.getProjectId()).thenReturn(null);
        backend.put("topics/t1", "value");
        assertEquals(Optional.of("value"), delegate.get("default-project/topics/t1"));
    }

    @Test
    void nullContextInstanceFallsBackToDefault() {
        var backendNoCtx = new ProjectAwareStorageBackend<>(delegate, null, "default-project");
        backendNoCtx.put("k", "v");
        assertEquals(Optional.of("v"), delegate.get("default-project/k"));
    }

    @Test
    void getForProject_ignoresRequestContext() {
        delegate.put("proj-x/topics/t1", "value");
        // no context set up; getForProject bypasses it
        assertEquals(Optional.of("value"), backend.getForProject("proj-x", "topics/t1"));
    }

    @Test
    void putForProject_ignoresRequestContext() {
        backend.putForProject("proj-x", "topics/t1", "v");
        assertEquals(Optional.of("v"), delegate.get("proj-x/topics/t1"));
    }

    @Test
    void deleteForProject_ignoresRequestContext() {
        delegate.put("proj-x/topics/t1", "v");
        backend.deleteForProject("proj-x", "topics/t1");
        assertEquals(Optional.empty(), delegate.get("proj-x/topics/t1"));
    }

    @Test
    void scanForProject_scopedToGivenProject() {
        delegate.put("proj-x/topics/t1", "v1");
        delegate.put("proj-y/topics/t2", "v2");

        List<String> results = backend.scanForProject("proj-x", k -> true);
        assertEquals(List.of("v1"), results);
    }

    @Test
    void keysForProject_scopedToGivenProject() {
        delegate.put("proj-x/topics/t1", "v1");
        delegate.put("proj-x/topics/t2", "v2");
        delegate.put("proj-y/topics/t3", "v3");

        var keys = backend.keysForProject("proj-x");
        assertEquals(2, keys.size());
        assertTrue(keys.containsAll(List.of("topics/t1", "topics/t2")));
    }

    @Test
    void scanAllProjectsAsMap_returnsLogicalKeysAcrossProjects() {
        delegate.put("proj-a/topics/t1", "v1");
        delegate.put("proj-b/topics/t2", "v2");

        Map<String, String> all = backend.scanAllProjectsAsMap();
        assertEquals(2, all.size());
        assertEquals("v1", all.get("topics/t1"));
        assertEquals("v2", all.get("topics/t2"));
    }

    @Test
    void scanAllProjectsAsMap_skipsKeysWithoutSlash() {
        delegate.put("badkey", "v");
        Map<String, String> all = backend.scanAllProjectsAsMap();
        assertTrue(all.isEmpty());
    }

    @Test
    void scanAllProjectsPreservesValuesWithSameLogicalKeyAcrossProjects() {
        delegate.put("proj-a/instances/pg-main", "a");
        delegate.put("proj-b/instances/pg-main", "b");
        delegate.put("proj-b/topics/t1", "topic");

        List<String> all = backend.scanAllProjects(k -> k.startsWith("instances/"));

        assertEquals(2, all.size());
        assertTrue(all.containsAll(List.of("a", "b")));
    }

    @Test
    void scanAllProjectsSkipsKeysWithoutSlash() {
        delegate.put("badkey", "v");

        assertTrue(backend.scanAllProjects(k -> true).isEmpty());
    }
}
