package io.floci.gcp.services.iam;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.floci.gcp.services.iam.model.StoredServiceAccount;
import io.floci.gcp.services.iam.model.StoredServiceAccountKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IamServiceTest {

    private IamService service;

    @BeforeEach
    void setUp() {
        service = new IamService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>());
    }

    @Test
    void createServiceAccountStoredAndRetrievable() {
        service.createServiceAccount("p1", "sa1", "Test SA", "");

        StoredServiceAccount sa = service.getServiceAccount("p1", "sa1@p1.iam.gserviceaccount.com");
        assertEquals("sa1@p1.iam.gserviceaccount.com", sa.getEmail());
        assertEquals("Test SA", sa.getDisplayName());
    }

    @Test
    void createServiceAccountDuplicateThrowsAlreadyExists() {
        service.createServiceAccount("p1", "sa1", "Test SA", "");

        GcpException ex = assertThrows(GcpException.class,
                () -> service.createServiceAccount("p1", "sa1", "Duplicate", ""));
        assertEquals("ALREADY_EXISTS", ex.getGcpStatus());
    }

    @Test
    void getServiceAccountMissingThrowsNotFound() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.getServiceAccount("p1", "missing@p1.iam.gserviceaccount.com"));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void listServiceAccountsFiltersByProject() {
        service.createServiceAccount("p1", "sa1", "SA1", "");
        service.createServiceAccount("p1", "sa2", "SA2", "");

        List<StoredServiceAccount> accounts = service.listServiceAccounts("p1");
        assertEquals(2, accounts.size());
    }

    @Test
    void deleteServiceAccountRemovedFromList() {
        service.createServiceAccount("p1", "sa1", "SA1", "");
        service.deleteServiceAccount("p1", "sa1@p1.iam.gserviceaccount.com");

        GcpException ex = assertThrows(GcpException.class,
                () -> service.getServiceAccount("p1", "sa1@p1.iam.gserviceaccount.com"));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void createKeyAndListKeys() {
        service.createServiceAccount("p1", "sa1", "SA1", "");
        StoredServiceAccountKey key = service.createKey("p1", "sa1@p1.iam.gserviceaccount.com");

        assertNotNull(key.getKeyId());
        assertNotNull(key.getName());

        List<StoredServiceAccountKey> keys = service.listKeys("p1", "sa1@p1.iam.gserviceaccount.com");
        assertEquals(1, keys.size());
        assertEquals(key.getKeyId(), keys.get(0).getKeyId());
    }

    @Test
    void deleteKeyRemovedFromList() {
        service.createServiceAccount("p1", "sa1", "SA1", "");
        StoredServiceAccountKey key = service.createKey("p1", "sa1@p1.iam.gserviceaccount.com");

        service.deleteKey("p1", "sa1@p1.iam.gserviceaccount.com", key.getKeyId());

        List<StoredServiceAccountKey> keys = service.listKeys("p1", "sa1@p1.iam.gserviceaccount.com");
        assertTrue(keys.isEmpty());
    }

    @Test
    void getPolicyReturnsEmptyBindingsByDefault() {
        service.createServiceAccount("p1", "sa1", "SA1", "");
        StoredPolicy policy = service.getPolicy("projects/p1/serviceAccounts/sa1@p1.iam.gserviceaccount.com");

        assertNotNull(policy);
        assertEquals(1, policy.getVersion());
        assertTrue(policy.getBindings() == null || policy.getBindings().isEmpty());
    }

    @Test
    void unsetPolicyCarriesEmptyPolicyEtag() {
        assertEquals("ACAB", service.getPolicy("projects/p1/resource/r1").getEtag());
    }

    @Test
    void setPolicyRotatesEtagOnEveryWrite() {
        StoredPolicy first = service.setPolicy("projects/p1/resource/r1", new StoredPolicy());
        String firstEtag = first.getEtag();
        assertNotNull(firstEtag);
        assertFalse(firstEtag.isEmpty());
        assertNotEquals("ACAB", firstEtag);

        StoredPolicy second = service.setPolicy("projects/p1/resource/r1", new StoredPolicy());
        assertNotEquals(firstEtag, second.getEtag());
    }

    @Test
    void setPolicyWithoutEtagIsBlindWrite() {
        service.setPolicy("projects/p1/resource/r1", new StoredPolicy());

        StoredPolicy blind = new StoredPolicy();
        assertEquals("", blind.getEtag());
        assertDoesNotThrow(() -> service.setPolicy("projects/p1/resource/r1", blind));
    }

    @Test
    void setPolicyWithCurrentEtagSucceeds() {
        service.setPolicy("projects/p1/resource/r1", new StoredPolicy());
        String current = service.getPolicy("projects/p1/resource/r1").getEtag();

        StoredPolicy update = new StoredPolicy();
        update.setEtag(current);
        assertDoesNotThrow(() -> service.setPolicy("projects/p1/resource/r1", update));
    }

    @Test
    void setPolicyWithStaleEtagIsAborted() {
        StoredPolicy reader = service.setPolicy("projects/p1/resource/r1", new StoredPolicy());
        String stale = reader.getEtag();
        service.setPolicy("projects/p1/resource/r1", new StoredPolicy());

        StoredPolicy lostUpdate = new StoredPolicy();
        lostUpdate.setEtag(stale);
        GcpException e = assertThrows(GcpException.class,
                () -> service.setPolicy("projects/p1/resource/r1", lostUpdate));
        assertEquals(409, e.getHttpStatus());
        assertEquals("ABORTED", e.getGcpStatus());
    }

    @Test
    void setPolicyAgainstUnsetPolicyAcceptsEmptyPolicyEtag() {
        StoredPolicy update = new StoredPolicy();
        update.setEtag("ACAB");
        assertDoesNotThrow(() -> service.setPolicy("projects/p1/resource/r1", update));
    }

    @Test
    void deletePolicyRemovesStoredPolicy() {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of("role", "roles/viewer", "members", List.of("user:x@example.com"))));
        service.setPolicy("projects/p1/resource/r1", policy);

        service.deletePolicy("projects/p1/resource/r1");

        assertTrue(service.getPolicy("projects/p1/resource/r1").getBindings().isEmpty());
        assertEquals("ACAB", service.getPolicy("projects/p1/resource/r1").getEtag());
    }

    @Test
    void policiesSurviveServiceRestart() {
        InMemoryStorage<String, StoredPolicy> policyStore = new InMemoryStorage<>();
        IamService first = new IamService(new InMemoryStorage<>(), new InMemoryStorage<>(), policyStore);
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of("role", "roles/pubsub.publisher", "members", List.of("user:x@example.com"))));
        first.setPolicy("projects/p1/topics/t1", policy);

        IamService second = new IamService(new InMemoryStorage<>(), new InMemoryStorage<>(), policyStore);

        assertEquals("roles/pubsub.publisher",
                second.getPolicy("projects/p1/topics/t1").getBindings().get(0).get("role"));
    }

    @Test
    void resolverEnforcesExistenceOnlyForMatchingResources() {
        service.registerPolicyResourceResolver("projects/*/widgets/*", resource -> {
            throw GcpException.notFound("Widget not found: " + resource);
        });

        GcpException e = assertThrows(GcpException.class,
                () -> service.getPolicy("projects/p1/widgets/w1"));
        assertEquals(404, e.getHttpStatus());
        assertThrows(GcpException.class,
                () -> service.setPolicy("projects/p1/widgets/w1", new StoredPolicy()));

        assertDoesNotThrow(() -> service.getPolicy("projects/p1/gadgets/g1"));
        assertDoesNotThrow(() -> service.getPolicy("projects/p1/widgets/w1/sub/s1"));
    }
}
