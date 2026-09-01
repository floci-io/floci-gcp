package io.floci.gcp.core.common;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestBaseUrlTest {

    private static final String CONFIGURED = "http://localhost:4588";

    @Test
    void prefersTheRequestAuthorityOverTheConfiguredBaseUrl() {
        assertEquals("http://localhost:9000",
                RequestBaseUrl.resolve(uriInfo("http://localhost:9000/storage/v1/b"),
                        headers("localhost:9000"), CONFIGURED, 4588));
    }

    @Test
    void usesTheRequestAuthorityWhenNoHostHeaderIsPresent() {
        // The HTTP/2 shape: the authority arrives as :authority and no Host header is sent, so a
        // Host-only lookup would fall back to the configured base URL and name the wrong port.
        assertEquals("http://localhost:9000",
                RequestBaseUrl.resolve(uriInfo("http://localhost:9000/storage/v1/b"),
                        headers(null), CONFIGURED, 4588));
    }

    @Test
    void fallsBackToTheHostHeaderWhenTheRequestUriHasNoAuthority() {
        assertEquals("http://gcs.example:8080",
                RequestBaseUrl.resolve(uriInfo("/storage/v1/b"),
                        headers("gcs.example:8080"), CONFIGURED, 4588));
    }

    @Test
    void fallsBackToTheConfiguredBaseUrlWhenNeitherIsAvailable() {
        assertEquals(CONFIGURED,
                RequestBaseUrl.resolve(uriInfo("/storage/v1/b"), headers(null), CONFIGURED, 4588));
    }

    @Test
    void addsTheConfiguredPortToAPortlessAuthority() {
        // A proxy fronting the emulator on 80 sends a portless authority; dropping the port would
        // produce a session URL the client cannot dial back.
        assertEquals("http://gcs.example:4588",
                RequestBaseUrl.resolve(uriInfo("http://gcs.example/storage/v1/b"),
                        headers("gcs.example"), CONFIGURED, 4588));
    }

    @Test
    void keepsThePortOfAnIpv6Authority() {
        assertEquals("http://[::1]:9000",
                RequestBaseUrl.resolve(uriInfo("http://[::1]:9000/storage/v1/b"),
                        headers("[::1]:9000"), CONFIGURED, 4588));
    }

    @Test
    void addsTheConfiguredPortToAPortlessIpv6Authority() {
        assertEquals("http://[::1]:4588",
                RequestBaseUrl.resolve(uriInfo("http://[::1]/storage/v1/b"),
                        headers("[::1]"), CONFIGURED, 4588));
    }

    @Test
    void takesTheSchemeFromTheConfiguredBaseUrl() {
        assertEquals("https://localhost:9000",
                RequestBaseUrl.resolve(uriInfo("http://localhost:9000/storage/v1/b"),
                        headers("localhost:9000"), "https://localhost:4588", 4588));
    }

    private static UriInfo uriInfo(String requestUri) {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getRequestUri()).thenReturn(URI.create(requestUri));
        when(uriInfo.getBaseUri()).thenReturn(URI.create("http://localhost:4588/"));
        return uriInfo;
    }

    private static HttpHeaders headers(String host) {
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getHeaderString(HttpHeaders.HOST)).thenReturn(host);
        return headers;
    }
}
