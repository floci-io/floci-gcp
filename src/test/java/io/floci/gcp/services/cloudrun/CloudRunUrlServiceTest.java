package io.floci.gcp.services.cloudrun;

import io.floci.gcp.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudRunUrlServiceTest {

    @Test
    void generatesDefaultLocalCloudRunHostUrl() {
        CloudRunUrlService urls = new CloudRunUrlService(config("http://localhost:4588", Optional.empty(), Optional.empty()));

        String uri = urls.invocationUri("p1", "us-central1", "orders-api");

        assertEquals("http://orders-api-f64551fcd6f0.us-central1.run.localhost.floci.io:4588", uri);
    }

    @Test
    void hostnameOverridesDefaultCloudRunHostSuffix() {
        CloudRunUrlService urls = new CloudRunUrlService(config("http://localhost:4588",
                Optional.of("floci-gcp"), Optional.empty()));

        String uri = urls.invocationUri("p1", "us-central1", "orders-api");

        assertEquals("http://orders-api-f64551fcd6f0.us-central1.run.floci-gcp:4588", uri);
    }

    @Test
    void explicitExecutionSuffixOverridesHostname() {
        CloudRunUrlService urls = new CloudRunUrlService(config("https://localhost:4588",
                Optional.of("floci-gcp"), Optional.of("run.test")));

        String uri = urls.invocationUri("p1", "europe-west1", "orders-api");

        assertEquals("https://orders-api-f64551fcd6f0.europe-west1.run.run.test:4588", uri);
    }

    @Test
    void parsesGeneratedHostWithHyphenatedServiceName() {
        CloudRunUrlService urls = new CloudRunUrlService(config("http://localhost:4588", Optional.empty(), Optional.empty()));

        CloudRunUrlService.ParsedHost parsed = urls
                .parseHost("orders-api-f64551fcd6f0.us-central1.run.localhost.floci.io:4588")
                .orElseThrow();

        assertEquals("orders-api", parsed.serviceId());
        assertEquals("f64551fcd6f0", parsed.projectToken());
        assertEquals("us-central1", parsed.location());
        assertTrue(urls.matchesProjectToken("p1", parsed.projectToken()));
    }

    @Test
    void parsesServiceNameEndingWithTokenLookingSuffix() {
        CloudRunUrlService urls = new CloudRunUrlService(config("http://localhost:4588", Optional.empty(), Optional.empty()));

        CloudRunUrlService.ParsedHost parsed = urls
                .parseHost("my-svc-deadbeefcafe-f64551fcd6f0.us-central1.run.localhost.floci.io:4588")
                .orElseThrow();

        assertEquals("my-svc-deadbeefcafe", parsed.serviceId());
        assertEquals("f64551fcd6f0", parsed.projectToken());
        assertEquals("us-central1", parsed.location());
    }

    @Test
    void ignoresNonCloudRunHosts() {
        CloudRunUrlService urls = new CloudRunUrlService(config("http://localhost:4588", Optional.empty(), Optional.empty()));

        assertTrue(urls.parseHost("bucket.localhost.floci.io:4588").isEmpty());
        assertTrue(urls.parseHost("localhost:4588").isEmpty());
    }

    private static EmulatorConfig config(String baseUrl, Optional<String> hostname, Optional<String> urlHostSuffix) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.effectiveBaseUrl()).thenReturn(hostname
                .map(host -> baseUrl.replace("localhost", host))
                .orElse(baseUrl));
        when(config.hostname()).thenReturn(hostname);
        when(config.services().cloudrun().execution().urlHostSuffix()).thenReturn(urlHostSuffix);
        return config;
    }
}
