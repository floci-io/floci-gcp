package io.floci.gcp.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.iam.authorization.IamAuthorizationRegistry;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.iam.authorization.IamPermissionCheck;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.floci.gcp.services.iam.model.StoredServiceAccount;
import io.floci.gcp.services.iamcredentials.IamCredentialsController;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.HttpMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IamServiceAccountAuthorizationServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private IamServiceAccountAuthorizationAdapter adapter;
    private IamService iam;

    @BeforeEach
    void setUp() {
        iam = IamServices.inMemory();
        iam.createServiceAccount("p", "target", "target", "");
        Instance<IamService> instance = mock(Instance.class);
        when(instance.get()).thenReturn(iam);
        adapter = new IamServiceAccountAuthorizationAdapter(instance);
    }

    @Test
    void everyRestHandlerIsInventoriedAndUnknownMethodsFailClosed() {
        assertEquals(Set.of("get", "post", "put", "patch", "delete", "getIamPolicy"), handlers(IamController.class));
        assertEquals(Set.of("generateAccessToken", "signBlob", "signJwt"), handlers(IamCredentialsController.class));
        assertThrows(GcpException.class, () -> adapter.restOperation("newHandler",
                Map.of("serviceAccount", "target@p.iam.gserviceaccount.com"), mapper.createObjectNode()));
        assertThrows(GcpException.class, () -> adapter.checks(new IamOperation("NewOperation", "projects/p")));
        assertThrows(GcpException.class, () -> adapter.restOperation("post",
                Map.of("rest", "p/roles"), mapper.createObjectNode()));
        assertTrue(adapter.resource("projects/p").isEmpty());
        assertTrue(adapter.resource("projects/p/serviceAccounts/x:unknown").isEmpty());
    }

    @Test
    void routesUseExactPermissionsAndKeyChecksRetainAccountPolicyInheritance() {
        String account = "projects/p/serviceAccounts/target@p.iam.gserviceaccount.com";
        record Route(String method, String suffix, String permission, String target) {}
        List<Route> routes = List.of(
                new Route("post", "", "iam.serviceAccounts.create", "projects/p"),
                new Route("get", "", "iam.serviceAccounts.list", "projects/p"),
                new Route("get", "/target", "iam.serviceAccounts.get", account),
                new Route("put", "/target", "iam.serviceAccounts.update", account),
                new Route("patch", "/target", "iam.serviceAccounts.update", account),
                new Route("delete", "/target", "iam.serviceAccounts.delete", account),
                new Route("post", "/target/keys", "iam.serviceAccountKeys.create", account),
                new Route("get", "/target/keys", "iam.serviceAccountKeys.list", account),
                new Route("get", "/target/keys/k", "iam.serviceAccountKeys.get", account + "/keys/k"),
                new Route("delete", "/target/keys/k", "iam.serviceAccountKeys.delete", account + "/keys/k"),
                new Route("post", "/target:getIamPolicy", "iam.serviceAccounts.getIamPolicy", account),
                new Route("getIamPolicy", "/target", "iam.serviceAccounts.getIamPolicy", account),
                new Route("post", "/target:setIamPolicy", "iam.serviceAccounts.setIamPolicy", account),
                new Route("post", "/target:signBlob", "iam.serviceAccounts.signBlob", account));
        for (Route route : routes) {
            IamOperation operation = adapter.restOperation(route.method(),
                    Map.of("rest", "p/serviceAccounts" + route.suffix()), mapper.createObjectNode());
            IamPermissionCheck check = adapter.checks(operation).getFirst();
            assertEquals(route.permission(), check.permission(), route.toString());
            assertEquals(route.target(), check.resource().name(), route.toString());
            if (route.target().contains("/keys/")) {
                assertEquals("iam.googleapis.com/Key", check.resource().type());
            }
            assertEquals(route.target().equals("projects/p") ? "projects/p" : account,
                    check.resource().policyResource());
        }
    }

    @Test
    void credentialsSigningTargetsUseExactPermissions() {
        String account = "projects/p/serviceAccounts/target@p.iam.gserviceaccount.com";
        for (String method : List.of("signBlob", "signJwt")) {
            IamOperation operation = adapter.restOperation(method,
                    Map.of("serviceAccount", "target@p.iam.gserviceaccount.com"), mapper.createObjectNode());
            IamPermissionCheck check = adapter.checks(operation).getFirst();
            assertEquals("iam.serviceAccounts." + method, check.permission());
            assertEquals(account, check.resource().name());
            assertTrue(adapter.roles().get("roles/iam.serviceAccountTokenCreator").contains(check.permission()));
        }
    }

    @Test
    void accountAliasesResolveToOnePolicyWithoutMigratingPolicies() {
        String id = iam.getServiceAccount("p", "target").getUniqueId();
        String canonical = "projects/p/serviceAccounts/target@p.iam.gserviceaccount.com";
        for (String address : List.of("projects/p/serviceAccounts/target", "projects/-/serviceAccounts/" + id,
                "projects/p/serviceAccounts/" + id, "projects/-/serviceAccounts/target@p.iam.gserviceaccount.com")) {
            IamResource resource = adapter.requireResource(address);
            assertEquals(canonical, resource.name());
            assertEquals(canonical, resource.policyResource());
            assertEquals("projects/p", resource.projectResource());
        }
        IamOperation token = adapter.restOperation("generateAccessToken", Map.of("serviceAccount", id),
                mapper.createObjectNode());
        assertEquals(canonical, token.resource());
    }

    @Test
    void roleSlicesMatchUpstreamAndDoNotGrantImpersonationThroughBasicRoles() {
        IamRoleCatalog roles = new IamRoleCatalog(new IamAuthorizationRegistry(List.of(adapter)));
        for (String role : adapter.roles().keySet()) {
            assertTrue(roles.grants(role, "iam.serviceAccounts.get"));
            assertTrue(roles.grants(role, "iam.serviceAccounts.list"));
            assertTrue(roles.grants(role, "resourcemanager.projects.get"));
        }
        for (String role : List.of("roles/owner", "roles/editor", "roles/viewer")) {
            assertTrue(roles.grants(role, "iam.serviceAccountKeys.get"));
            assertFalse(roles.grants(role, "iam.serviceAccounts.getAccessToken"));
            assertFalse(roles.grants(role, "iam.serviceAccounts.signBlob"));
            assertFalse(roles.grants(role, "iam.serviceAccounts.signJwt"));
        }
        assertTrue(roles.grants("roles/owner", "iam.serviceAccounts.setIamPolicy"));
        assertFalse(roles.grants("roles/editor", "iam.serviceAccounts.setIamPolicy"));
        assertFalse(roles.grants("roles/viewer", "iam.serviceAccounts.create"));
        assertFalse(roles.grants("roles/iam.serviceAccountAdmin", "iam.serviceAccountKeys.create"));
    }

    @Test
    void canonicalEmptyPolicyOverridesLegacyGrantWithoutMigration() {
        String legacy = "projects/p/serviceAccounts/target";
        String canonical = "projects/p/serviceAccounts/target@p.iam.gserviceaccount.com";
        StoredPolicy grant = new StoredPolicy();
        grant.setBindings(List.of(Map.of("role", "roles/iam.serviceAccountAdmin",
                "members", List.of("serviceAccount:caller@p.iam.gserviceaccount.com"))));
        iam.setPolicy(legacy, grant);
        assertEquals(grant.getEtag(), iam.policyForEvaluation(canonical).getEtag());
        assertEquals(IamService.EMPTY_POLICY_ETAG, iam.getPolicy(canonical).getEtag());
        StoredPolicy revoked = iam.setPolicy(canonical, new StoredPolicy());
        assertEquals(revoked.getEtag(), iam.policyForEvaluation(canonical).getEtag());
        assertTrue(iam.policyForEvaluation(canonical).getBindings().isEmpty());
        assertEquals(grant.getEtag(), iam.getPolicy(legacy).getEtag());
    }

    @Test
    void accountUpdateCannotPublishAfterDeletionOrKeepItsPolicyAlive() throws Exception {
        for (boolean updateFirst : List.of(true, false)) {
            InMemoryStorage<String, StoredServiceAccount> accounts = spy(new InMemoryStorage<>());
            IamService service = new IamService(accounts, new InMemoryStorage<>(), new InMemoryStorage<>());
            String resource = service.createServiceAccount("p", "target", "original", "").getName();
            StoredPolicy policy = new StoredPolicy();
            policy.setBindings(List.of(Map.of("role", "roles/iam.serviceAccountAdmin",
                    "members", List.of("serviceAccount:caller@p.iam.gserviceaccount.com"))));
            service.setPolicy(resource, policy);
            CountDownLatch reached = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            if (updateFirst) {
                doAnswer(invocation -> {
                    reached.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return invocation.callRealMethod();
                }).when(accounts).put(anyString(), any());
            } else {
                doAnswer(invocation -> {
                    reached.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return invocation.callRealMethod();
                }).when(accounts).delete(anyString());
            }
            ExecutorService executor = Executors.newFixedThreadPool(3);
            try {
                Future<?> first = executor.submit(() -> {
                    if (updateFirst) {
                        service.updateServiceAccount("p", "target", "updated", null);
                    } else {
                        service.deleteServiceAccount("p", "target");
                    }
                });
                assertTrue(reached.await(5, TimeUnit.SECONDS));
                CountDownLatch started = new CountDownLatch(1);
                Future<?> competing = executor.submit(() -> {
                    started.countDown();
                    if (updateFirst) {
                        service.deleteServiceAccount("p", "target");
                    } else {
                        service.updateServiceAccount("p", "target", "resurrected", null);
                    }
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> competing.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                first.get(5, TimeUnit.SECONDS);
                if (updateFirst) {
                    competing.get(5, TimeUnit.SECONDS);
                } else {
                    ExecutionException failure = assertThrows(ExecutionException.class,
                            () -> competing.get(5, TimeUnit.SECONDS));
                    assertEquals("NOT_FOUND", ((GcpException) failure.getCause()).getGcpStatus());
                }
                assertThrows(GcpException.class, () -> service.getServiceAccount("p", "target"));
                assertTrue(service.policyForEvaluation(resource).getBindings().isEmpty());
                assertThrows(GcpException.class, () -> service.setPolicy(resource, new StoredPolicy()));
            } finally {
                release.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void accountDeletionSerializesWithCanonicalAndLegacyPolicyWrites() throws Exception {
        for (String aliasKind : List.of("id", "email", "uid", "wildcardEmail", "wildcardUid")) {
            InMemoryStorage<String, StoredPolicy> policies = spy(new InMemoryStorage<>());
            IamService service = new IamService(new InMemoryStorage<>(), new InMemoryStorage<>(), policies);
            String resource = service.createServiceAccount("p", "target", "target", "").getName();
            String uid = service.getServiceAccount("p", "target").getUniqueId();
            String policyResource = switch (aliasKind) {
                case "id" -> "projects/p/serviceAccounts/target";
                case "uid" -> "projects/p/serviceAccounts/" + uid;
                case "wildcardEmail" -> "projects/-/serviceAccounts/target@p.iam.gserviceaccount.com";
                case "wildcardUid" -> "projects/-/serviceAccounts/" + uid;
                default -> resource;
            };
            CountDownLatch reached = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(invocation -> {
                reached.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return invocation.callRealMethod();
            }).when(policies).put(anyString(), any());
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<?> writer = executor.submit(() -> service.setPolicy(
                        policyResource, new StoredPolicy()));
                assertTrue(reached.await(5, TimeUnit.SECONDS));
                CountDownLatch started = new CountDownLatch(1);
                Future<?> deletion = executor.submit(() -> {
                    started.countDown();
                    service.deleteServiceAccount("p", "target");
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> deletion.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                writer.get(5, TimeUnit.SECONDS);
                deletion.get(5, TimeUnit.SECONDS);
                assertTrue(service.policyForEvaluation(resource).getBindings().isEmpty());
                assertTrue(policies.keys().isEmpty());
                assertThrows(GcpException.class, () -> service.setPolicy(policyResource, new StoredPolicy()));
            } finally {
                release.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void legacyPolicyMigrationCannotRestoreGrantsAfterAccountDeletion() throws Exception {
        for (boolean migrationFirst : List.of(true, false)) {
            InMemoryStorage<String, StoredServiceAccount> accounts = spy(new InMemoryStorage<>());
            InMemoryStorage<String, StoredPolicy> policies = spy(new InMemoryStorage<>());
            IamService service = new IamService(accounts, new InMemoryStorage<>(), policies);
            String resource = service.createServiceAccount("p", "target", "target", "").getName();
            service.setPolicy("projects/-/serviceAccounts/target@p.iam.gserviceaccount.com", new StoredPolicy());
            CountDownLatch reached = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            if (migrationFirst) {
                doAnswer(invocation -> {
                    reached.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return invocation.callRealMethod();
                }).when(policies).put(anyString(), any());
            } else {
                doAnswer(invocation -> {
                    reached.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return invocation.callRealMethod();
                }).when(accounts).delete(anyString());
            }
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = executor.submit(() -> {
                    if (migrationFirst) {
                        service.serviceAccountResource("p", "target");
                    } else {
                        service.deleteServiceAccount("p", "target");
                    }
                });
                assertTrue(reached.await(5, TimeUnit.SECONDS));
                CountDownLatch started = new CountDownLatch(1);
                Future<?> competing = executor.submit(() -> {
                    started.countDown();
                    if (migrationFirst) {
                        service.deleteServiceAccount("p", "target");
                    } else {
                        service.serviceAccountResource("p", "target");
                    }
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> competing.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                first.get(5, TimeUnit.SECONDS);
                if (migrationFirst) {
                    competing.get(5, TimeUnit.SECONDS);
                } else {
                    ExecutionException failure = assertThrows(ExecutionException.class,
                            () -> competing.get(5, TimeUnit.SECONDS));
                    assertEquals("NOT_FOUND", ((GcpException) failure.getCause()).getGcpStatus());
                }
                assertTrue(service.policyForEvaluation(resource).getBindings().isEmpty());
                assertTrue(policies.keys().isEmpty());
            } finally {
                release.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    private Set<String> handlers(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(annotation -> annotation.annotationType().isAnnotationPresent(HttpMethod.class)))
                .map(Method::getName).collect(Collectors.toSet());
    }
}
