package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.gcs.model.GcsHmacKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HMAC key lifecycle against the storage backend, independent of the REST surface. */
class GcsHmacKeyServiceTest {

    private static final String PROJECT = "test-project";
    private static final String EMAIL = "compat@test-project.iam.gserviceaccount.com";

    private InMemoryStorage<String, GcsHmacKey> store;
    private GcsHmacKeyService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryStorage<>();
        service = new GcsHmacKeyService(store);
    }

    @Test
    void createStoresTheMetadataButNotTheSecret() {
        GcsHmacKeyService.CreatedKey created = service.create(PROJECT, EMAIL);

        assertNotNull(created.secret());
        assertFalse(created.secret().isBlank());
        assertTrue(created.metadata().getAccessId().startsWith("GOOG"));
        assertEquals("ACTIVE", created.metadata().getState());
        assertEquals(PROJECT + "/" + created.metadata().getAccessId(), created.metadata().getId());

        GcsHmacKey stored = store.get(created.metadata().getAccessId()).orElseThrow();
        assertEquals(EMAIL, stored.getServiceAccountEmail());
    }

    @Test
    void createRequiresAServiceAccountEmail() {
        GcpException ex = assertThrows(GcpException.class, () -> service.create(PROJECT, " "));
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void anActiveKeyCannotBeDeleted() {
        String accessId = service.create(PROJECT, EMAIL).metadata().getAccessId();

        GcpException ex = assertThrows(GcpException.class, () -> service.delete(PROJECT, accessId));
        assertEquals(400, ex.getHttpStatus());
        assertTrue(store.get(accessId).isPresent());
    }

    @Test
    void deactivateThenDeleteRemovesTheKey() {
        String accessId = service.create(PROJECT, EMAIL).metadata().getAccessId();

        assertEquals("INACTIVE", service.updateState(PROJECT, accessId, "INACTIVE").getState());
        service.delete(PROJECT, accessId);

        assertTrue(store.get(accessId).isEmpty());
        GcpException ex = assertThrows(GcpException.class, () -> service.get(PROJECT, accessId));
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    void stateMustBeActiveOrInactive() {
        String accessId = service.create(PROJECT, EMAIL).metadata().getAccessId();
        assertThrows(GcpException.class, () -> service.updateState(PROJECT, accessId, "BANANA"));
        assertThrows(GcpException.class, () -> service.updateState(PROJECT, accessId, null));
    }

    @Test
    void listFiltersByProjectAndServiceAccount() {
        String mine = service.create(PROJECT, EMAIL).metadata().getAccessId();
        service.create(PROJECT, "other@test-project.iam.gserviceaccount.com");
        service.create("other-project", EMAIL);

        assertEquals(2, service.list(PROJECT, null, false).size());
        assertEquals(1, service.list(PROJECT, EMAIL, false).size());
        assertEquals(mine, service.list(PROJECT, EMAIL, false).get(0).getAccessId());
    }

    @Test
    void aKeyFromAnotherProjectIsNotVisible() {
        String accessId = service.create("other-project", EMAIL).metadata().getAccessId();
        GcpException ex = assertThrows(GcpException.class, () -> service.get(PROJECT, accessId));
        assertEquals(404, ex.getHttpStatus());
    }
}
