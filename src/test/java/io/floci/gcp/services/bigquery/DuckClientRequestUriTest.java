package io.floci.gcp.services.bigquery;

import io.floci.gcp.core.common.GcpException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuckClientRequestUriTest {

    private static InetAddress loopback(String host) throws UnknownHostException {
        if (!host.equals("floci-duck.myproject_default")) {
            throw new UnknownHostException(host);
        }
        return InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1});
    }

    @Test
    void aHostWithAnUnderscoreIsResolvedToItsAddress() {
        URI uri = DuckClient.requestUri("http://floci-duck.myproject_default:3000", "/query",
                DuckClientRequestUriTest::loopback);
        assertEquals(URI.create("http://127.0.0.1:3000/query"), uri);
    }

    @Test
    void aHostWithoutAnUnderscoreIsLeftAsConfigured() {
        URI uri = DuckClient.requestUri("http://floci-duck:3000", "/query", DuckClientRequestUriTest::loopback);
        assertEquals(URI.create("http://floci-duck:3000/query"), uri);
    }

    @Test
    void anUnresolvableNameReportsTheConfiguredAuthority() {
        GcpException error = assertThrows(GcpException.class,
                () -> DuckClient.requestUri("http://other.project_default:3000", "/query",
                        DuckClientRequestUriTest::loopback));
        assertTrue(error.getMessage().contains("other.project_default:3000"), error.getMessage());
    }
}
