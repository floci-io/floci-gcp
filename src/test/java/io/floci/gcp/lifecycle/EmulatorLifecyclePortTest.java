package io.floci.gcp.lifecycle;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.tls.TlsConfigSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for TLS silently skipping the START and READY hooks.
 *
 * <p>{@code onHttpStart} only runs the hooks for the listener whose port it recognises. With TLS
 * enabled the public port belongs to the TLS proxy and Quarkus binds internal loopback ports
 * instead, so matching the public port meant the observer returned early every time: the hooks
 * never ran and {@code /_floci-gcp/init} never reported ready.
 */
class EmulatorLifecyclePortTest {

    private static EmulatorLifecycle lifecycleWith(boolean tlsEnabled, int publicPort) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.TlsConfig tls = mock(EmulatorConfig.TlsConfig.class);
        when(config.port()).thenReturn(publicPort);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(tlsEnabled);
        return new EmulatorLifecycle(null, null, config, null, null, null, null, null);
    }

    @Test
    void withoutTlsTheHooksTriggerOnThePublicPort() {
        assertEquals(4588, lifecycleWith(false, 4588).primaryHttpPort());
    }

    @Test
    void withTlsTheHooksTriggerOnTheInternalHttpPort() {
        assertEquals(TlsConfigSource.HTTP_INTERNAL_PORT, lifecycleWith(true, 4588).primaryHttpPort(),
                "with TLS on, Quarkus binds the internal port and the public port belongs to the proxy");
    }

    @Test
    void withTlsTheHooksDoNotTriggerOnThePublicPort() {
        // The exact regression: the observer must not be waiting for 4588, because with TLS on
        // no Quarkus listener ever reports that port.
        assertNotEquals(4588, lifecycleWith(true, 4588).primaryHttpPort());
    }

    @Test
    void theInternalHttpPortIsNotTheTlsPortSoOnlyOneListenerFiresTheHooks() {
        assertEquals(4580, TlsConfigSource.HTTP_INTERNAL_PORT);
        assertEquals(4581, TlsConfigSource.HTTPS_INTERNAL_PORT);
    }
}
