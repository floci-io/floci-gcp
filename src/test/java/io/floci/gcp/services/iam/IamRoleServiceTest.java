package io.floci.gcp.services.iam;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.iam.model.StoredRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IamRoleServiceTest {

    private IamRoleService service;

    @BeforeEach
    void setUp() {
        service = new IamRoleService(new InMemoryStorage<String, StoredRole>());
        service.create("p", "role1", Map.of("title", "Original", "stage", "BETA",
                "includedPermissions", List.of("a.b.c")));
    }

    @Test
    void rejectedPatchDoesNotMutateTheStoredRole() {
        String etag = service.get("p", "role1").getEtag();

        assertThrows(GcpException.class, () -> service.update("p", "role1",
                Map.of("title", "Changed", "stage", "NOPE"), "title,stage"));

        StoredRole after = service.get("p", "role1");
        assertEquals("Original", after.getTitle());
        assertEquals("BETA", after.getStage());
        assertEquals(etag, after.getEtag());
    }

    @Test
    void returnedRolesAreCopiesOfTheStoredState() {
        StoredRole returned = service.get("p", "role1");
        returned.setTitle("Tampered");
        returned.getIncludedPermissions().add("x.y.z");

        StoredRole again = service.get("p", "role1");
        assertNotSame(returned, again);
        assertEquals("Original", again.getTitle());
        assertEquals(List.of("a.b.c"), again.getIncludedPermissions());
    }

    @Test
    void deleteAndUndeleteReturnCopiesOfTheStoredState() {
        StoredRole deleted = service.delete("p", "role1", null);
        deleted.setTitle("Tampered");
        assertEquals("Original", service.get("p", "role1").getTitle());

        StoredRole restored = service.undelete("p", "role1", null);
        assertNotSame(restored, service.get("p", "role1"));
    }
}
