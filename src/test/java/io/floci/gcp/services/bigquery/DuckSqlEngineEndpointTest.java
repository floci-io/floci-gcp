package io.floci.gcp.services.bigquery;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.docker.ContainerDetector;
import io.floci.gcp.core.common.docker.DockerHostResolver;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The sidecar reads staged rows back from floci-gcp, so the callback address has to be one the
 * sidecar can actually reach. The resolved docker host is right for a container this process
 * started and wrong for a pre-configured one somewhere else.
 */
class DuckSqlEngineEndpointTest {

    private static DuckSqlEngine engine(String callbackUrl) {
        EmulatorConfig.BigQueryDuckConfig duck = mock(EmulatorConfig.BigQueryDuckConfig.class);
        when(duck.callbackUrl()).thenReturn(Optional.ofNullable(callbackUrl));
        EmulatorConfig.BigQueryServiceConfig bigquery = mock(EmulatorConfig.BigQueryServiceConfig.class);
        when(bigquery.duck()).thenReturn(duck);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        when(services.bigquery()).thenReturn(bigquery);
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.services()).thenReturn(services);
        when(config.port()).thenReturn(4588);

        DockerHostResolver resolver = new DockerHostResolver(mock(ContainerDetector.class));
        return new DuckSqlEngine(mock(DuckClient.class), resolver, config, null);
    }

    @Test
    void withoutACallbackUrlTheResolvedDockerHostIsUsed() {
        assertEquals("http://" + new DockerHostResolver(mock(ContainerDetector.class)).resolve() + ":4588",
                engine(null).flociEndpoint());
    }

    @Test
    void aConfiguredCallbackUrlWins() {
        assertEquals("http://floci-gcp.test:9999", engine("http://floci-gcp.test:9999").flociEndpoint());
    }

    @Test
    void aTrailingSlashIsTrimmedSoThePathIsNotDoubled() {
        assertEquals("http://floci-gcp.test:9999", engine("http://floci-gcp.test:9999/").flociEndpoint());
    }

    @Test
    void aBlankCallbackUrlFallsBackRatherThanProducingAnEmptyHost() {
        assertEquals("http://" + new DockerHostResolver(mock(ContainerDetector.class)).resolve() + ":4588",
                engine("   ").flociEndpoint());
    }
}
