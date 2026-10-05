package io.floci.gcp.core.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocationCatalogTest {

    private final LocationCatalog catalog = LocationCatalog.lenient();

    @Test
    void bundledCatalogHasAllRegionsAndZones() {
        assertEquals(43, catalog.regions().size());
        assertEquals(130, catalog.zones().size());
        assertEquals(List.of("us-central1-a", "us-central1-b", "us-central1-c", "us-central1-f"),
                catalog.zones("us-central1"));
        assertEquals(List.of("europe-west1-b", "europe-west1-c", "europe-west1-d"), catalog.zones("europe-west1"));
        assertEquals(List.of(), catalog.zones("mars-north1"));
    }

    @Test
    void zonesMapBackToTheirRegion() {
        assertEquals(Optional.of("us-central1"), catalog.regionOfZone("us-central1-f"));
        assertEquals(Optional.of("europe-west1"), catalog.regionOfZone("europe-west1-d"));
        assertEquals(Optional.empty(), catalog.regionOfZone("europe-west1-a"));
        assertTrue(catalog.isRegion("asia-northeast1"));
        assertFalse(catalog.isRegion("asia-northeast1-a"));
        assertTrue(catalog.isZone("asia-northeast1-a"));
    }

    @Test
    void displayNameIsTheCityFromTheReferenceTable() {
        assertEquals(Optional.of("Tokyo"), catalog.displayName("asia-northeast1"));
        assertEquals(Optional.of("Council Bluffs"), catalog.displayName("us-central1"));
        assertEquals(Optional.empty(), catalog.displayName("global"));
    }

    @Test
    void multiAndDualRegionsAreLoaded() {
        assertEquals(List.of("US", "EU", "ASIA"), catalog.multiRegions().get("storage"));
        assertEquals(List.of("us-central1", "us-east1"), catalog.predefinedDualRegions().get("NAM4"));
    }

    @Test
    void lenientCatalogAcceptsAnything() {
        assertDoesNotThrow(() -> catalog.requireLocation("mars-north1", LocationCatalog.Kind.REGION));
        assertDoesNotThrow(() -> catalog.requireParentLocation("projects/p/locations/nowhere", LocationCatalog.Kind.REGION));
    }

    @Test
    void strictCatalogOnlyAcceptsAllowedKinds() {
        LocationCatalog strict = LocationCatalog.strictCatalog();
        assertDoesNotThrow(() -> strict.requireLocation("us-central1", LocationCatalog.Kind.REGION));
        assertDoesNotThrow(() -> strict.requireLocation("us-central1-a", LocationCatalog.Kind.REGION, LocationCatalog.Kind.ZONE));
        assertDoesNotThrow(() -> strict.requireLocation("global", LocationCatalog.Kind.GLOBAL));
        assertDoesNotThrow(() -> strict.requireLocation("europe", LocationCatalog.Kind.KMS_MULTI_REGION));

        GcpException error = assertThrows(GcpException.class,
                () -> strict.requireLocation("us-central1-a", LocationCatalog.Kind.REGION));
        assertEquals(400, error.getHttpStatus());
        assertThrows(GcpException.class, () -> strict.requireLocation("global", LocationCatalog.Kind.REGION));
        assertThrows(GcpException.class, () -> strict.requireLocation(null, LocationCatalog.Kind.REGION));
        assertThrows(GcpException.class,
                () -> strict.requireParentLocation("projects/p/locations/mars-north1/keyRings/k", LocationCatalog.Kind.REGION));
    }

    @Test
    void strictListChecksSkipTheWildcard() {
        LocationCatalog strict = LocationCatalog.strictCatalog();
        assertDoesNotThrow(() -> strict.requireListLocation("-", LocationCatalog.Kind.REGION));
        assertDoesNotThrow(() -> strict.requireListLocation("us-east1", LocationCatalog.Kind.REGION));
        assertThrows(GcpException.class, () -> strict.requireListLocation("mars-north1", LocationCatalog.Kind.REGION));
    }

    @Test
    void locationOfParsesResourceNames() {
        assertEquals("us-east1", LocationCatalog.locationOf("projects/p/locations/us-east1"));
        assertEquals("global", LocationCatalog.locationOf("projects/p/locations/global/keyRings/k"));
        assertNull(LocationCatalog.locationOf("projects/p"));
    }
}
