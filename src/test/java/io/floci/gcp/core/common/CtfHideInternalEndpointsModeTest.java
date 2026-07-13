package io.floci.gcp.core.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CtfHideInternalEndpointsModeTest {

    @ParameterizedTest
    @ValueSource(strings = {"false", "FALSE", " false ", ""})
    void parseFalse(String raw) {
        assertEquals(CtfHideInternalEndpointsMode.OFF, CtfHideInternalEndpointsMode.parse(raw));
    }

    @Test
    void parseTrue() {
        assertEquals(CtfHideInternalEndpointsMode.PREFIXED, CtfHideInternalEndpointsMode.parse("true"));
    }

    @Test
    void parseAll() {
        assertEquals(CtfHideInternalEndpointsMode.ALL, CtfHideInternalEndpointsMode.parse("all"));
    }

    @Test
    void parseInvalidThrows() {
        assertThrows(IllegalArgumentException.class, () -> CtfHideInternalEndpointsMode.parse("yes"));
    }

    @Test
    void parseNullReturnsOff() {
        assertEquals(CtfHideInternalEndpointsMode.OFF, CtfHideInternalEndpointsMode.parse(null));
    }

    @Test
    void hidesAnythingOnlyWhenNotOff() {
        assertFalse(CtfHideInternalEndpointsMode.OFF.hidesAnything());
        assertTrue(CtfHideInternalEndpointsMode.PREFIXED.hidesAnything());
        assertTrue(CtfHideInternalEndpointsMode.ALL.hidesAnything());
    }

    @Test
    void offModeNeverHidesPaths() {
        assertFalse(CtfHideInternalEndpointsMode.OFF.isPathHidden("/_floci-gcp"));
        assertFalse(CtfHideInternalEndpointsMode.OFF.isPathHidden("/health"));
        assertFalse(CtfHideInternalEndpointsMode.OFF.isPathHidden("/_floci-gcp/info"));
    }

    @Test
    void prefixedHidesFlociGcpRoutesOnly() {
        CtfHideInternalEndpointsMode mode = CtfHideInternalEndpointsMode.PREFIXED;
        assertTrue(mode.isPathHidden("/_floci-gcp"));
        assertTrue(mode.isPathHidden("/_floci-gcp/info"));
        assertTrue(mode.isPathHidden("/_floci-gcp/init"));
        assertTrue(mode.isPathHidden("/_floci-gcp/health"));
        assertFalse(mode.isPathHidden("/_floci-gcp/gke/token-webhook"));
        assertFalse(mode.isPathHidden("/health"));
        assertFalse(mode.isPathHidden("health"));
        assertFalse(mode.isPathHidden("/"));
    }

    @Test
    void allAlsoHidesRootHealth() {
        CtfHideInternalEndpointsMode mode = CtfHideInternalEndpointsMode.ALL;
        assertTrue(mode.isPathHidden("/health"));
        assertTrue(mode.isPathHidden("health"));
        assertTrue(mode.isPathHidden("/_floci-gcp/info"));
        assertFalse(mode.isPathHidden("/_floci-gcp/gke/token-webhook"));
    }
}
