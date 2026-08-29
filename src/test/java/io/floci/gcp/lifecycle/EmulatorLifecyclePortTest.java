package io.floci.gcp.lifecycle;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.tls.TlsConfigSource;
import io.floci.gcp.core.tls.TlsProxyServer;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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

    /**
     * When the proxy never reports the public port ready, the emulator is unreachable. Startup must
     * abort rather than run the hooks against a dead endpoint and publish readiness anyway.
     */
    @Test
    void unreadyPublicPortAbortsStartupInsteadOfRunningHooks() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.TlsConfig tls = mock(EmulatorConfig.TlsConfig.class);
        when(config.port()).thenReturn(4588);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(true);

        TlsProxyServer proxy = mock(TlsProxyServer.class);
        when(proxy.awaitPublicPortReady(anyLong(), any())).thenReturn(false);

        @SuppressWarnings("unchecked")
        Instance<TlsProxyServer> proxyInstance = mock(Instance.class);
        when(proxyInstance.isUnsatisfied()).thenReturn(false);
        when(proxyInstance.get()).thenReturn(proxy);

        AtomicBoolean aborted = new AtomicBoolean(false);
        EmulatorLifecycle lifecycle =
                new EmulatorLifecycle(null, null, config, null, null, null, null, proxyInstance) {
                    @Override
                    void abortStartup() {
                        aborted.set(true);
                    }
                };

        assertFalse(lifecycle.awaitPublicPortIfProxied(),
                "an unready public port must stop the startup sequence");
        assertTrue(aborted.get(), "startup must be aborted, not merely logged");
    }

    /** With TLS off nothing is proxied, so startup proceeds without waiting on anything. */
    @Test
    void withoutTlsStartupNeverWaitsOnTheProxy() {
        assertTrue(lifecycleWith(false, 4588).awaitPublicPortIfProxied());
    }
}
