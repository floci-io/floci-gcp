package io.floci.gcp.test;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.cloud.compute.v1.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class ComputeRoutingContractTest {
    @Test void globalLoadBalancerMembershipAndPathUpdates() throws Exception {
        String project = TestFixtures.uniqueName("java-routing"), zone = "us-central1-a", endpoint = TestFixtures.endpoint() + "/";
        var auth = NoCredentialsProvider.create();
        try (var networks = NetworksClient.create(NetworksSettings.newBuilder().setEndpoint(endpoint).setCredentialsProvider(auth).build());
             var subnets = SubnetworksClient.create(SubnetworksSettings.newBuilder().setEndpoint(endpoint).setCredentialsProvider(auth).build());
             var instances = InstancesClient.create(InstancesSettings.newBuilder().setEndpoint(endpoint).setCredentialsProvider(auth).build());
             var groups = NetworkEndpointGroupsClient.create(NetworkEndpointGroupsSettings.newBuilder().setEndpoint(endpoint).setCredentialsProvider(auth).build());
             var health = HealthChecksClient.create(HealthChecksSettings.newBuilder().setEndpoint(endpoint).setCredentialsProvider(auth).build());
             var backends = BackendServicesClient.create(BackendServicesSettings.newBuilder().setEndpoint(endpoint).setCredentialsProvider(auth).build());
             var maps = UrlMapsClient.create(UrlMapsSettings.newBuilder().setEndpoint(endpoint).setCredentialsProvider(auth).build());
             var proxies = TargetHttpProxiesClient.create(TargetHttpProxiesSettings.newBuilder().setEndpoint(endpoint).setCredentialsProvider(auth).build());
             var addresses = GlobalAddressesClient.create(GlobalAddressesSettings.newBuilder().setEndpoint(endpoint).setCredentialsProvider(auth).build());
             var forwarding = GlobalForwardingRulesClient.create(GlobalForwardingRulesSettings.newBuilder().setEndpoint(endpoint).setCredentialsProvider(auth).build())) {
            try {
                networks.insertAsync(project, Network.newBuilder().setName("net").setAutoCreateSubnetworks(false).build()).get(20, TimeUnit.SECONDS);
                subnets.insertAsync(project, "us-central1", Subnetwork.newBuilder().setName("subnet").setNetwork("global/networks/net").setIpCidrRange("10.50.0.0/24").build()).get(20, TimeUnit.SECONDS);
                instances.insertAsync(project, zone, Instance.newBuilder().setName("vm").setMachineType("zones/" + zone + "/machineTypes/n2-standard-4")
                        .addNetworkInterfaces(NetworkInterface.newBuilder().setSubnetwork("regions/us-central1/subnetworks/subnet"))
                        .addDisks(AttachedDisk.newBuilder().setBoot(true).setAutoDelete(true).setInitializeParams(AttachedDiskInitializeParams.newBuilder().setDiskSizeGb(20))).build()).get(20, TimeUnit.SECONDS);
                groups.insertAsync(project, zone, NetworkEndpointGroup.newBuilder().setName("neg").setNetworkEndpointType("GCE_VM_IP_PORT").setNetwork("global/networks/net").setSubnetwork("regions/us-central1/subnetworks/subnet").build()).get(20, TimeUnit.SECONDS);
                var member = NetworkEndpoint.newBuilder().setInstance("vm").setPort(8080).build();
                groups.attachNetworkEndpointsAsync(project, zone, "neg", NetworkEndpointGroupsAttachEndpointsRequest.newBuilder().addNetworkEndpoints(member).build()).get(20, TimeUnit.SECONDS);
                assertThat(groups.listNetworkEndpoints(project, zone, "neg", NetworkEndpointGroupsListEndpointsRequest.getDefaultInstance()).iterateAll()).hasSize(1);
                health.insertAsync(project, HealthCheck.newBuilder().setName("health").setType("HTTP").setHttpHealthCheck(HTTPHealthCheck.newBuilder().setPort(8081)).build()).get(20, TimeUnit.SECONDS);
                backends.insertAsync(project, BackendService.newBuilder().setName("backend").setProtocol("HTTP").setLoadBalancingScheme("EXTERNAL_MANAGED")
                        .addHealthChecks("global/healthChecks/health").addBackends(Backend.newBuilder().setGroup("zones/" + zone + "/networkEndpointGroups/neg").setBalancingMode("RATE").setMaxRatePerEndpoint(100)).build()).get(20, TimeUnit.SECONDS);
                var matcher = PathMatcher.newBuilder().setName("paths").setDefaultService("global/backendServices/backend")
                        .addPathRules(PathRule.newBuilder().addPaths("/one/*").addPaths("/two/*").setService("global/backendServices/backend"));
                maps.insertAsync(project, UrlMap.newBuilder().setName("routes").setDefaultService("global/backendServices/backend")
                        .addHostRules(HostRule.newBuilder().addHosts("*").setPathMatcher("paths")).addPathMatchers(matcher).build()).get(20, TimeUnit.SECONDS);
                proxies.insertAsync(project, TargetHttpProxy.newBuilder().setName("proxy").setUrlMap("global/urlMaps/routes").build()).get(20, TimeUnit.SECONDS);
                addresses.insertAsync(project, Address.newBuilder().setName("frontend").build()).get(20, TimeUnit.SECONDS);
                forwarding.insertAsync(project, ForwardingRule.newBuilder().setName("frontend").setIPProtocol("TCP").setIPAddress("global/addresses/frontend")
                        .setPortRange("80").setTarget("global/targetHttpProxies/proxy").setLoadBalancingScheme("EXTERNAL_MANAGED").build()).get(20, TimeUnit.SECONDS);
                var firstRule = forwarding.get(project, "frontend");
                assertThatThrownBy(() -> forwarding.insertAsync(project, firstRule.toBuilder().setName("frontend2").clearId().clearSelfLink().build()).get(20, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(com.google.api.gax.rpc.InvalidArgumentException.class);
                forwarding.insertAsync(project, firstRule.toBuilder().setName("frontend2").clearId().clearSelfLink().setPortRange("8080").build()).get(20, TimeUnit.SECONDS);
                assertThat(addresses.get(project, "frontend").getUsersList()).containsExactlyInAnyOrder(firstRule.getSelfLink(), forwarding.get(project, "frontend2").getSelfLink());
                addresses.insertAsync(project, Address.newBuilder().setName("other").build()).get(20, TimeUnit.SECONDS);
                assertThatThrownBy(() -> forwarding.patchAsync(project, "frontend", ForwardingRule.newBuilder().setFingerprint(firstRule.getFingerprint()).setIPAddress("global/addresses/other").build()).get(20, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(com.google.api.gax.rpc.InvalidArgumentException.class);
                assertThat(forwarding.get(project, "frontend")).isEqualTo(firstRule);
                assertThat(addresses.get(project, "other").getUsersList()).isEmpty();
                forwarding.deleteAsync(project, "frontend2").get(20, TimeUnit.SECONDS);
                assertThat(addresses.get(project, "frontend").getUsersList()).containsExactly(firstRule.getSelfLink());
                addresses.deleteAsync(project, "frontend").get(20, TimeUnit.SECONDS);
                assertThatThrownBy(() -> addresses.get(project, "frontend")).isInstanceOf(com.google.api.gax.rpc.NotFoundException.class);
                assertThat(forwarding.get(project, "frontend").getIPAddress()).isEqualTo(firstRule.getIPAddress());
                forwarding.patchAsync(project, "frontend", ForwardingRule.newBuilder().setFingerprint(firstRule.getFingerprint()).setDescription("released reservation").build()).get(20, TimeUnit.SECONDS);
                assertThatThrownBy(() -> addresses.insertAsync(project, Address.newBuilder().setName("reuse").setAddress(firstRule.getIPAddress()).build()).get(20, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(com.google.api.gax.rpc.InvalidArgumentException.class);
                String fingerprint = maps.get(project, "routes").getFingerprint();
                matcher.clearPathRules().addPathRules(PathRule.newBuilder().addPaths("/two/*").setService("global/backendServices/backend"));
                maps.patchAsync(project, "routes", UrlMap.newBuilder().setFingerprint(fingerprint).addPathMatchers(matcher).build()).get(20, TimeUnit.SECONDS);
                assertThat(maps.get(project, "routes").getPathMatchers(0).getPathRules(0).getPathsList()).containsExactly("/two/*");
                assertThat(backends.get(project, "backend").getName()).isEqualTo("backend");
                groups.detachNetworkEndpointsAsync(project, zone, "neg", NetworkEndpointGroupsDetachEndpointsRequest.newBuilder().addNetworkEndpoints(member).build()).get(20, TimeUnit.SECONDS);
            } finally {
                for (var r : forwarding.list(project).iterateAll()) { forwarding.deleteAsync(project, r.getName()).get(20, TimeUnit.SECONDS); }
                for (var r : addresses.list(project).iterateAll()) { addresses.deleteAsync(project, r.getName()).get(20, TimeUnit.SECONDS); }
                for (var r : proxies.list(project).iterateAll()) { proxies.deleteAsync(project, r.getName()).get(20, TimeUnit.SECONDS); }
                for (var r : maps.list(project).iterateAll()) { maps.deleteAsync(project, r.getName()).get(20, TimeUnit.SECONDS); }
                for (var r : backends.list(project).iterateAll()) { backends.deleteAsync(project, r.getName()).get(20, TimeUnit.SECONDS); }
                for (var r : health.list(project).iterateAll()) { health.deleteAsync(project, r.getName()).get(20, TimeUnit.SECONDS); }
                for (var r : groups.list(project, zone).iterateAll()) { groups.deleteAsync(project, zone, r.getName()).get(20, TimeUnit.SECONDS); }
                for (var r : instances.list(project, zone).iterateAll()) { instances.deleteAsync(project, zone, r.getName()).get(20, TimeUnit.SECONDS); }
                for (var r : subnets.list(project, "us-central1").iterateAll()) { subnets.deleteAsync(project, "us-central1", r.getName()).get(20, TimeUnit.SECONDS); }
                for (var r : networks.list(project).iterateAll()) { networks.deleteAsync(project, r.getName()).get(20, TimeUnit.SECONDS); }
            }
        }
    }
}
