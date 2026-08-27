package io.floci.gcp.core.tls;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsConfigSourceHostnameExtractionTest {

    @AfterEach
    void cleanup() {
        System.clearProperty("floci-gcp.hostname");
        System.clearProperty("floci-gcp.base-url");
    }

    @Test
    void extractsFromFlociGcpHostname() throws Exception {
        System.setProperty("floci-gcp.hostname", "floci-gcp");
        assertTrue(extractCustomHostnames().contains("floci-gcp"));
    }

    @Test
    void extractsFromBaseUrlDnsName() throws Exception {
        System.setProperty("floci-gcp.base-url", "https://myhost:4588");
        assertTrue(extractCustomHostnames().contains("myhost"));
    }

    @Test
    void extractsFromBaseUrlIpAddress() throws Exception {
        System.setProperty("floci-gcp.base-url", "https://192.168.1.100:4588");
        assertTrue(extractCustomHostnames().contains("192.168.1.100"));
    }

    @Test
    void extractsFromBothSources() throws Exception {
        System.setProperty("floci-gcp.hostname", "newhost");
        System.setProperty("floci-gcp.base-url", "http://oldhost:4588");
        List<String> hostnames = extractCustomHostnames();
        assertTrue(hostnames.contains("newhost"));
        assertTrue(hostnames.contains("oldhost"));
        assertEquals(2, hostnames.size());
    }

    @Test
    void filtersDefaultLocalhostFromHostname() throws Exception {
        System.setProperty("floci-gcp.hostname", "localhost");
        assertTrue(extractCustomHostnames().isEmpty());
    }

    @Test
    void filtersDefaultLoopbackIp() throws Exception {
        System.setProperty("floci-gcp.base-url", "http://127.0.0.1:4588");
        assertTrue(extractCustomHostnames().isEmpty());
    }

    @Test
    void filtersDefaultAllInterfaces() throws Exception {
        System.setProperty("floci-gcp.base-url", "http://0.0.0.0:4588");
        assertTrue(extractCustomHostnames().isEmpty());
    }

    @Test
    void deduplicatesWhenSameHostnameInBothSources() throws Exception {
        System.setProperty("floci-gcp.hostname", "myhost");
        System.setProperty("floci-gcp.base-url", "http://myhost:4588");
        List<String> hostnames = extractCustomHostnames();
        assertEquals(1, hostnames.size());
        assertTrue(hostnames.contains("myhost"));
    }

    @Test
    void malformedUrlReturnsEmptyList() throws Exception {
        System.setProperty("floci-gcp.base-url", "not-a-valid-url");
        assertTrue(extractCustomHostnames().isEmpty());
    }

    @Test
    void emptyConfigurationReturnsEmptyList() throws Exception {
        assertTrue(extractCustomHostnames().isEmpty());
    }

    @Test
    void blankHostnameIsIgnored() throws Exception {
        System.setProperty("floci-gcp.hostname", "   ");
        assertTrue(extractCustomHostnames().isEmpty());
    }

    @SuppressWarnings("unchecked")
    private List<String> extractCustomHostnames() throws Exception {
        TlsConfigSource instance = new TlsConfigSource();
        Method method = TlsConfigSource.class.getDeclaredMethod("extractCustomHostnames");
        method.setAccessible(true);
        return (List<String>) method.invoke(instance);
    }
}
