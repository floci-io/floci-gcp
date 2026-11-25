package io.floci.gcp.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProjectRecord;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.iam.authorization.IamAuthorizationRegistry;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.iam.authorization.IamPermissionCheck;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.floci.gcp.services.iam.model.StoredServiceAccount;
import io.floci.gcp.services.iamcredentials.IamCredentialsController;
import io.floci.gcp.testing.ThreadStates;
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
import java.util.concurrent.atomic.AtomicReference;
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
        assertEquals(Set.of("generateAccessToken", "generateIdToken", "signBlob", "signJwt"),
                handlers(IamCredentialsController.class));
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
    void generateIdTokenUsesTheOpenIdTokenPermission() {
        IamOperation operation = adapter.restOperation("generateIdToken",
                Map.of("serviceAccount", "target@p.iam.gserviceaccount.com"), mapper.createObjectNode());
        IamPermissionCheck check = adapter.checks(operation).getFirst();
        assertEquals("iam.serviceAccounts.getOpenIdToken", check.permission());
        assertEquals("projects/p/serviceAccounts/target@p.iam.gserviceaccount.com", check.resource().name());
        assertTrue(adapter.roles().get("roles/iam.serviceAccountTokenCreator").contains(check.permission()));
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
        for (String role : List.of("roles/iam.serviceAccountAdmin", "roles/iam.serviceAccountKeyAdmin",
                "roles/iam.serviceAccountUser", "roles/iam.serviceAccountTokenCreator")) {
            assertTrue(roles.grants(role, "iam.serviceAccounts.get"));
            assertTrue(roles.grants(role, "iam.serviceAccounts.list"));
            assertTrue(roles.grants(role, "resourcemanager.projects.get"));
        }
        for (String role : List.of("roles/iam.workloadIdentityPoolAdmin",
                "roles/iam.workloadIdentityPoolViewer")) {
            assertTrue(roles.grants(role, "iam.googleapis.com/workloadIdentityPools.get"));
            assertTrue(roles.grants(role, "iam.googleapis.com/workloadIdentityPools.list"));
            assertTrue(roles.grants(role, "resourcemanager.projects.get"));
        }
        assertTrue(roles.grants("roles/iam.workloadIdentityPoolAdmin",
                "iam.googleapis.com/workloadIdentityPools.create"));
        assertTrue(roles.grants("roles/iam.workloadIdentityPoolAdmin",
                "iam.googleapis.com/workloadIdentityPoolProviders.create"));
        assertFalse(roles.grants("roles/iam.workloadIdentityPoolViewer",
                "iam.googleapis.com/workloadIdentityPools.create"));
        assertFalse(roles.grants("roles/iam.workloadIdentityPoolViewer",
                "iam.googleapis.com/workloadIdentityPools.update"));
        for (String role : List.of("roles/owner", "roles/editor", "roles/viewer")) {
            assertTrue(roles.grants(role, "iam.serviceAccountKeys.get"));
            assertFalse(roles.grants(role, "iam.serviceAccounts.getAccessToken"));
            assertFalse(roles.grants(role, "iam.serviceAccounts.signBlob"));
            assertFalse(roles.grants(role, "iam.serviceAccounts.signJwt"));
            assertFalse(roles.grants(role, "iam.serviceAccounts.getOpenIdToken"));
        }
        assertTrue(roles.grants("roles/owner", "iam.serviceAccounts.setIamPolicy"));
        assertFalse(roles.grants("roles/editor", "iam.serviceAccounts.setIamPolicy"));
        assertFalse(roles.grants("roles/viewer", "iam.serviceAccounts.create"));
        assertFalse(roles.grants("roles/iam.serviceAccountAdmin", "iam.serviceAccountKeys.create"));
    }

    @Test
    void workloadIdentityPoolRoutesUseExactPermissions() {
        ProjectRegistry registry = mock(ProjectRegistry.class);
        ProjectRecord record = new ProjectRecord();
        record.setProjectId("p");
        when(registry.require(anyString())).thenReturn(record);
        IamServiceAccountAuthorizationAdapter resolved = new IamServiceAccountAuthorizationAdapter(
                mock(Instance.class), registry);
        String pool = IamWorkloadIdentityPoolNames.pool("p", "pool");
        String poolPolicy = IamWorkloadIdentityPoolNames.policyPool("p", "pool");
        String provider = IamWorkloadIdentityPoolNames.provider("p", "pool", "provider");
        record Route(String method, Map<String, String> path, String permission, String target, String policy) {}
        List<Route> routes = List.of(
                new Route("createPool", Map.of("project", "p", "location", "global"),
                        "iam.googleapis.com/workloadIdentityPools.create", "projects/p", "projects/p"),
                new Route("listPools", Map.of("project", "p", "location", "global"),
                        "iam.googleapis.com/workloadIdentityPools.list", "projects/p", "projects/p"),
                new Route("getPool", Map.of("project", "p", "poolId", "pool"),
                        "iam.googleapis.com/workloadIdentityPools.get", pool, poolPolicy),
                new Route("patchPool", Map.of("project", "p", "poolId", "pool"),
                        "iam.googleapis.com/workloadIdentityPools.update", pool, poolPolicy),
                new Route("deletePool", Map.of("project", "p", "poolId", "pool"),
                        "iam.googleapis.com/workloadIdentityPools.delete", pool, poolPolicy),
                new Route("undeletePool", Map.of("project", "p", "poolId", "pool"),
                        "iam.googleapis.com/workloadIdentityPools.undelete", pool, poolPolicy),
                new Route("getPoolIamPolicy", Map.of("project", "p", "poolId", "pool"),
                        "iam.googleapis.com/workloadIdentityPools.getIamPolicy", pool, poolPolicy),
                new Route("setPoolIamPolicy", Map.of("project", "p", "poolId", "pool"),
                        "iam.googleapis.com/workloadIdentityPools.setIamPolicy", pool, poolPolicy),
                new Route("createProvider", Map.of("project", "p", "poolId", "pool"),
                        "iam.googleapis.com/workloadIdentityPoolProviders.create", pool, poolPolicy),
                new Route("listProviders", Map.of("project", "p", "poolId", "pool"),
                        "iam.googleapis.com/workloadIdentityPoolProviders.list", pool, poolPolicy),
                new Route("getProvider", Map.of("project", "p", "poolId", "pool", "providerId", "provider"),
                        "iam.googleapis.com/workloadIdentityPoolProviders.get", provider, poolPolicy),
                new Route("patchProvider", Map.of("project", "p", "poolId", "pool", "providerId", "provider"),
                        "iam.googleapis.com/workloadIdentityPoolProviders.update", provider, poolPolicy),
                new Route("deleteProvider", Map.of("project", "p", "poolId", "pool", "providerId", "provider"),
                        "iam.googleapis.com/workloadIdentityPoolProviders.delete", provider, poolPolicy),
                new Route("undeleteProvider", Map.of("project", "p", "poolId", "pool", "providerId", "provider"),
                        "iam.googleapis.com/workloadIdentityPoolProviders.undelete", provider, poolPolicy));
        for (Route route : routes) {
            IamOperation operation = resolved.restOperation(route.method(), route.path(), mapper.createObjectNode());
            IamPermissionCheck check = resolved.checks(operation).getFirst();
            assertEquals(route.permission(), check.permission(), route.toString());
            assertEquals(route.target(), check.resource().name(), route.toString());
            assertEquals(route.policy(), check.resource().policyResource(), route.toString());
            assertTrue(adapter.roles().get("roles/iam.workloadIdentityPoolAdmin")
                    .contains(route.permission()), route.toString());
        }
        assertTrue(resolved.checks(resolved.restOperation("getPoolOperation",
                Map.of("project", "p", "poolId", "pool", "operationId", "op"), mapper.createObjectNode())).isEmpty());
        assertTrue(resolved.checks(resolved.restOperation("getProviderOperation",
                Map.of("project", "p", "poolId", "pool", "providerId", "provider", "operationId", "op"),
                mapper.createObjectNode())).isEmpty());
        assertTrue(resolved.checks(resolved.restOperation("testPoolIamPermissions",
                Map.of("project", "p", "poolId", "pool"), mapper.createObjectNode())).isEmpty());

        IamPermissionCheck getPolicy = resolved.checks(new IamOperation("GetIamPolicy", pool)).getFirst();
        assertEquals("iam.googleapis.com/workloadIdentityPools.getIamPolicy", getPolicy.permission());
        assertEquals(poolPolicy, getPolicy.resource().policyResource());
        IamPermissionCheck setPolicy = resolved.checks(new IamOperation("SetIamPolicy", pool)).getFirst();
        assertEquals("iam.googleapis.com/workloadIdentityPools.setIamPolicy", setPolicy.permission());
        assertEquals(poolPolicy, setPolicy.resource().policyResource());
        assertTrue(resolved.supportsIamPolicyMethod("GetIamPolicy", pool));
        assertFalse(resolved.supportsIamPolicyMethod("GetIamPolicy", provider));
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
    void accountCreationClearsStalePoliciesWithoutPriorDeletion() {
        String canonical = "projects/p/serviceAccounts/target@p.iam.gserviceaccount.com";
        for (String staleResource : List.of(canonical, "projects/p/serviceAccounts/target")) {
            InMemoryStorage<String, StoredServiceAccount> accounts = new InMemoryStorage<>();
            InMemoryStorage<String, StoredPolicy> policies = new InMemoryStorage<>();
            StoredPolicy grant = new StoredPolicy();
            grant.setBindings(List.of(Map.of("role", "roles/iam.serviceAccountTokenCreator",
                    "members", List.of("serviceAccount:caller@p.iam.gserviceaccount.com"))));
            policies.put("policy:" + staleResource, grant);
            IamService service = new IamService(accounts, new InMemoryStorage<>(), policies);
            assertTrue(accounts.keys().isEmpty());
            assertFalse(service.policyForEvaluation(canonical).getBindings().isEmpty(), staleResource);

            StoredServiceAccount created = service.createServiceAccount("p", "target", "target", "");

            assertEquals(canonical, created.getName());
            assertTrue(service.policyForEvaluation(canonical).getBindings().isEmpty(), staleResource);
            assertTrue(policies.keys().isEmpty(), staleResource);
        }
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
                AtomicReference<Thread> competitor = new AtomicReference<>();
                Future<?> competing = executor.submit(() -> {
                    competitor.set(Thread.currentThread());
                    if (updateFirst) {
                        service.deleteServiceAccount("p", "target");
                    } else {
                        service.updateServiceAccount("p", "target", "resurrected", null);
                    }
                });
                ThreadStates.awaitBlockedOrDone(competitor, competing);
                assertFalse(competing.isDone());
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
                AtomicReference<Thread> deleter = new AtomicReference<>();
                Future<?> deletion = executor.submit(() -> {
                    deleter.set(Thread.currentThread());
                    service.deleteServiceAccount("p", "target");
                });
                ThreadStates.awaitBlockedOrDone(deleter, deletion);
                assertFalse(deletion.isDone());
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
                AtomicReference<Thread> competitor = new AtomicReference<>();
                Future<?> competing = executor.submit(() -> {
                    competitor.set(Thread.currentThread());
                    if (migrationFirst) {
                        service.deleteServiceAccount("p", "target");
                    } else {
                        service.serviceAccountResource("p", "target");
                    }
                });
                ThreadStates.awaitBlockedOrDone(competitor, competing);
                assertFalse(competing.isDone());
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
