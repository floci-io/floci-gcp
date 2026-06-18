package io.floci.gcp.services.cloudrun;

import io.floci.gcp.core.common.GcpException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloudRunUrlRoutingFilterTest {

    @Test
    void generatedHostRewritesToInternalInvocationPath() {
        CloudRunService service = mock(CloudRunService.class);
        String host = "orders-api-f64551fcd6f0.us-central1.run.localhost.floci.io:4588";
        when(service.resolveInvocationHost(host)).thenReturn(Optional.of(
                new CloudRunService.InvocationRoute("p1", "us-central1", "orders-api")));
        CloudRunUrlRoutingFilter filter = new CloudRunUrlRoutingFilter(service);
        ContainerRequestContext ctx = context("http://localhost:4588/api/database?x=1", host);

        filter.filter(ctx);

        verify(ctx).setRequestUri(URI.create("http://localhost:4588/run/v2/projects/p1"
                + "/locations/us-central1/services/orders-api/api/database?x=1"));
        verify(ctx).setProperty(CloudRunUrlRoutingFilter.ORIGINAL_SCHEME, "http");
        verify(ctx).setProperty(CloudRunUrlRoutingFilter.ORIGINAL_AUTHORITY, host);
        verify(ctx).setProperty(CloudRunUrlRoutingFilter.ORIGINAL_PATH_QUERY, "/api/database?x=1");
    }

    @Test
    void ignoresNonCloudRunHosts() {
        CloudRunService service = mock(CloudRunService.class);
        when(service.resolveInvocationHost("localhost:4588")).thenReturn(Optional.empty());
        when(service.isGeneratedInvocationHost("localhost:4588")).thenReturn(false);
        CloudRunUrlRoutingFilter filter = new CloudRunUrlRoutingFilter(service);
        ContainerRequestContext ctx = context("http://localhost:4588/v2/projects/p1", "localhost:4588");

        filter.filter(ctx);

        verify(ctx, never()).setRequestUri(any(URI.class));
    }

    @Test
    void unknownGeneratedHostReturnsNotFound() {
        CloudRunService service = mock(CloudRunService.class);
        String host = "missing-f64551fcd6f0.us-central1.run.localhost.floci.io:4588";
        when(service.resolveInvocationHost(host)).thenReturn(Optional.empty());
        when(service.isGeneratedInvocationHost(host)).thenReturn(true);
        CloudRunUrlRoutingFilter filter = new CloudRunUrlRoutingFilter(service);
        ContainerRequestContext ctx = context("http://localhost:4588/", host);

        GcpException error = assertThrows(GcpException.class, () -> filter.filter(ctx));

        assertEquals("NOT_FOUND", error.getGcpStatus());
    }

    private static ContainerRequestContext context(String uri, String host) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getRequestUri()).thenReturn(URI.create(uri));
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        MultivaluedHashMap<String, String> headers = new MultivaluedHashMap<>();
        headers.add(HttpHeaders.HOST, host);
        when(ctx.getHeaders()).thenReturn(headers);
        return ctx;
    }
}
