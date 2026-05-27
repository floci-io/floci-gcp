package io.floci.gcp.services.iam;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.floci.gcp.services.iam.model.StoredServiceAccount;
import io.floci.gcp.services.iam.model.StoredServiceAccountKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
