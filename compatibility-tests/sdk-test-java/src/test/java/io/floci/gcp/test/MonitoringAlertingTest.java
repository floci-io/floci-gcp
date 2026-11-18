package io.floci.gcp.test;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider;
import com.google.api.gax.rpc.FailedPreconditionException;
import com.google.api.gax.rpc.NotFoundException;
import com.google.cloud.monitoring.v3.AlertPolicyServiceClient;
import com.google.cloud.monitoring.v3.AlertPolicyServiceSettings;
import com.google.cloud.monitoring.v3.NotificationChannelServiceClient;
import com.google.cloud.monitoring.v3.NotificationChannelServiceSettings;
import com.google.monitoring.v3.AlertPolicy;
import com.google.monitoring.v3.ComparisonType;
import com.google.monitoring.v3.NotificationChannel;
import com.google.monitoring.v3.NotificationChannelDescriptor;
import com.google.monitoring.v3.ProjectName;
import com.google.protobuf.Duration;
import com.google.protobuf.FieldMask;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonitoringAlertingTest {
    private static final ProjectName PROJECT = ProjectName.of(TestFixtures.projectId());

    @Test
    void javaClientsManageChannelsAndPolicies() throws Exception {
        String suffix = TestFixtures.uniqueName("java-alerting");
        try (NotificationChannelServiceClient channels = channelClient(); AlertPolicyServiceClient policies = policyClient()) {
            assertThat(channels.listNotificationChannelDescriptors(PROJECT).iterateAll())
                    .extracting(NotificationChannelDescriptor::getType).contains("email", "pubsub", "webhook_tokenauth");

            NotificationChannel channel = channels.createNotificationChannel(PROJECT, NotificationChannel.newBuilder()
                    .setType("email").setDisplayName(suffix).putLabels("email_address", "ops@example.com").build());
            assertThat(channel.getName()).startsWith(PROJECT + "/notificationChannels/");
            assertThat(channel.getEnabled().getValue()).isTrue();

            AlertPolicy policy = policies.createAlertPolicy(PROJECT, AlertPolicy.newBuilder().setDisplayName(suffix)
                    .setCombiner(AlertPolicy.ConditionCombinerType.OR)
                    .addNotificationChannels(channel.getName())
                    .addConditions(AlertPolicy.Condition.newBuilder().setDisplayName("cpu")
                            .setConditionThreshold(AlertPolicy.Condition.MetricThreshold.newBuilder()
                                    .setFilter("metric.type=\"custom.googleapis.com/java_cpu\" AND resource.type=\"global\"")
                                    .setComparison(ComparisonType.COMPARISON_GT).setThresholdValue(0.9)
                                    .setDuration(Duration.newBuilder().setSeconds(60))))
                    .build());
            try {
                assertThat(policy.getName()).startsWith(PROJECT + "/alertPolicies/");
                assertThat(policy.getConditions(0).getName()).startsWith(policy.getName() + "/conditions/");
                assertThat(policy.getEnabled().getValue()).isTrue();
                assertThat(policies.getAlertPolicy(policy.getName())).isEqualTo(policy);
                assertThat(policies.listAlertPolicies(PROJECT).iterateAll())
                        .extracting(AlertPolicy::getName).contains(policy.getName());

                AlertPolicy renamed = policies.updateAlertPolicy(FieldMask.newBuilder().addPaths("display_name").build(),
                        policy.toBuilder().setDisplayName(suffix + "-renamed").build());
                assertThat(renamed.getDisplayName()).isEqualTo(suffix + "-renamed");
                assertThat(renamed.getConditions(0).getName()).isEqualTo(policy.getConditions(0).getName());

                assertThatThrownBy(() -> channels.deleteNotificationChannel(channel.getName(), false))
                        .isInstanceOf(FailedPreconditionException.class);
            } finally {
                policies.deleteAlertPolicy(policy.getName());
            }
            channels.deleteNotificationChannel(channel.getName(), false);
            assertThatThrownBy(() -> channels.getNotificationChannel(channel.getName()))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    private static NotificationChannelServiceClient channelClient() throws IOException {
        return NotificationChannelServiceClient.create(NotificationChannelServiceSettings.newBuilder()
                .setTransportChannelProvider(transport())
                .setCredentialsProvider(NoCredentialsProvider.create()).build());
    }

    private static AlertPolicyServiceClient policyClient() throws IOException {
        return AlertPolicyServiceClient.create(AlertPolicyServiceSettings.newBuilder()
                .setTransportChannelProvider(transport())
                .setCredentialsProvider(NoCredentialsProvider.create()).build());
    }

    private static InstantiatingGrpcChannelProvider transport() {
        URI uri = URI.create(TestFixtures.endpoint());
        int port = uri.getPort() > 0 ? uri.getPort() : 4588;
        return InstantiatingGrpcChannelProvider.newBuilder().setEndpoint(uri.getHost() + ":" + port)
                .setChannelConfigurator(builder -> builder.usePlaintext()).build();
    }
}
