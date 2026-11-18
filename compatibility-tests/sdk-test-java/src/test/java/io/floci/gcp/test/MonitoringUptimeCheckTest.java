package io.floci.gcp.test;

import com.google.api.MonitoredResource;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider;
import com.google.api.gax.rpc.InvalidArgumentException;
import com.google.api.gax.rpc.NotFoundException;
import com.google.cloud.monitoring.v3.UptimeCheckServiceClient;
import com.google.cloud.monitoring.v3.UptimeCheckServiceSettings;
import com.google.monitoring.v3.ProjectName;
import com.google.monitoring.v3.UptimeCheckConfig;
import com.google.protobuf.Duration;
import com.google.protobuf.FieldMask;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonitoringUptimeCheckTest {
    private static final ProjectName PROJECT = ProjectName.of(TestFixtures.projectId());

    @Test
    void javaClientManagesUptimeChecks() throws Exception {
        URI uri = URI.create(TestFixtures.endpoint());
        int port = uri.getPort() > 0 ? uri.getPort() : 4588;
        try (UptimeCheckServiceClient client = UptimeCheckServiceClient.create(UptimeCheckServiceSettings.newBuilder()
                .setTransportChannelProvider(InstantiatingGrpcChannelProvider.newBuilder().setEndpoint(uri.getHost() + ":" + port)
                        .setChannelConfigurator(builder -> builder.usePlaintext()).build())
                .setCredentialsProvider(NoCredentialsProvider.create()).build())) {
            UptimeCheckConfig requested = UptimeCheckConfig.newBuilder()
                    .setDisplayName(TestFixtures.uniqueName("java-uptime"))
                    .setMonitoredResource(MonitoredResource.newBuilder().setType("uptime_url").putLabels("host", uri.getHost()))
                    .setHttpCheck(UptimeCheckConfig.HttpCheck.newBuilder().setPath("/_floci-gcp/health").setPort(port))
                    .setTimeout(Duration.newBuilder().setSeconds(10)).build();
            UptimeCheckConfig created = client.createUptimeCheckConfig(PROJECT.toString(), requested);
            try {
                assertThat(created.getName()).startsWith(PROJECT + "/uptimeCheckConfigs/");
                assertThat(created.getPeriod().getSeconds()).isEqualTo(60);
                assertThat(client.getUptimeCheckConfig(created.getName())).isEqualTo(created);
                assertThat(client.listUptimeCheckConfigs(PROJECT.toString()).iterateAll())
                        .extracting(UptimeCheckConfig::getName).contains(created.getName());
                assertThat(client.listUptimeCheckIps(com.google.monitoring.v3.ListUptimeCheckIpsRequest.getDefaultInstance())
                        .iterateAll()).isNotEmpty();
                UptimeCheckConfig renamed = client.updateUptimeCheckConfig(com.google.monitoring.v3.UpdateUptimeCheckConfigRequest
                        .newBuilder().setUpdateMask(FieldMask.newBuilder().addPaths("display_name"))
                        .setUptimeCheckConfig(created.toBuilder().setDisplayName(created.getDisplayName() + "-renamed")).build());
                assertThat(renamed.getDisplayName()).endsWith("-renamed");
                assertThatThrownBy(() -> client.createUptimeCheckConfig(PROJECT.toString(),
                        requested.toBuilder().setPeriod(Duration.newBuilder().setSeconds(120)).build()))
                        .isInstanceOf(InvalidArgumentException.class);
            } finally {
                client.deleteUptimeCheckConfig(created.getName());
            }
            assertThatThrownBy(() -> client.getUptimeCheckConfig(created.getName())).isInstanceOf(NotFoundException.class);
        }
    }
}
