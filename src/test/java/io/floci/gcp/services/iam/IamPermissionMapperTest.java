package io.floci.gcp.services.iam;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IamPermissionMapperTest {

    private final IamPermissionMapper mapper = new IamPermissionMapper(null);

    @Test
    void mapsGenerateRandomBytesToLocationsPermission() {
        Optional<String> permission = map(
                "POST",
                "/v1/projects/test-project/locations/us-central1:generateRandomBytes");

        assertEquals(Optional.of("cloudkms.locations.generateRandomBytes"), permission);
    }

    @Test
    void mapsGcsObjectPatchToUpdate() {
        Optional<String> permission = map(
                "PATCH",
                "/storage/v1/b/my-bucket/o/path%2Fobject.txt");

        assertEquals(Optional.of("storage.objects.update"), permission);
    }

    @Test
    void mapsGcsObjectPutAndPostToCreate() {
        assertEquals(
                Optional.of("storage.objects.create"),
                map("PUT", "/storage/v1/b/my-bucket/o/obj"));
        assertEquals(
                Optional.of("storage.objects.create"),
                map("POST", "/storage/v1/b/my-bucket/o/obj"));
    }

    @Test
    void mapsServiceAccountKeysListCreateDelete() {
        String keysPath =
                "/v1/projects/test-project/serviceAccounts/sa@test-project.iam.gserviceaccount.com/keys";
        String keyPath = keysPath + "/key-1";

        assertEquals(Optional.of("iam.serviceAccountKeys.list"), map("GET", keysPath));
        assertEquals(Optional.of("iam.serviceAccountKeys.create"), map("POST", keysPath));
        assertEquals(Optional.of("iam.serviceAccountKeys.delete"), map("DELETE", keyPath));
    }

    @Test
    void mapsSignBlob() {
        Optional<String> permission = map(
                "POST",
                "/v1/projects/test-project/serviceAccounts/"
                        + "sa@test-project.iam.gserviceaccount.com:signBlob");

        assertEquals(Optional.of("iam.serviceAccounts.signBlob"), permission);
    }

    private Optional<String> map(String method, String path) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(ctx.getMethod()).thenReturn(method);
        when(uriInfo.getPath()).thenReturn(path);
        return mapper.map(ctx);
    }
}
