package io.floci.gcp.test;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.cloud.compute.v1.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class ComputeContractTest {
    private static String endpoint() { return TestFixtures.endpoint() + "/"; }
    @Test void lifecycleImagesSnapshotsAndRetainedDisks() throws Exception {
        String project = TestFixtures.uniqueName("java-compute"), zone = "us-central1-a";
        var auth = NoCredentialsProvider.create();
        try (var networks = NetworksClient.create(NetworksSettings.newBuilder().setEndpoint(endpoint()).setCredentialsProvider(auth).build());
             var subnets = SubnetworksClient.create(SubnetworksSettings.newBuilder().setEndpoint(endpoint()).setCredentialsProvider(auth).build());
             var instances = InstancesClient.create(InstancesSettings.newBuilder().setEndpoint(endpoint()).setCredentialsProvider(auth).build());
             var disks = DisksClient.create(DisksSettings.newBuilder().setEndpoint(endpoint()).setCredentialsProvider(auth).build());
             var images = ImagesClient.create(ImagesSettings.newBuilder().setEndpoint(endpoint()).setCredentialsProvider(auth).build());
             var snapshots = SnapshotsClient.create(SnapshotsSettings.newBuilder().setEndpoint(endpoint()).setCredentialsProvider(auth).build())) {
            try {
                networks.insertAsync(project, Network.newBuilder().setName("net").setAutoCreateSubnetworks(false).build()).get(20, TimeUnit.SECONDS);
                subnets.insertAsync(project, "us-central1", Subnetwork.newBuilder().setName("subnet").setNetwork("global/networks/net").setIpCidrRange("10.40.0.0/24").build()).get(20, TimeUnit.SECONDS);
                var vm = Instance.newBuilder().setName("vm").setMachineType("zones/" + zone + "/machineTypes/n2-standard-4")
                        .addNetworkInterfaces(NetworkInterface.newBuilder().setSubnetwork("regions/us-central1/subnetworks/subnet"))
                        .addDisks(AttachedDisk.newBuilder().setBoot(true).setAutoDelete(false).setInitializeParams(AttachedDiskInitializeParams.newBuilder().setDiskSizeGb(20))).build();
                instances.insertAsync(project, zone, vm).get(20, TimeUnit.SECONDS);
                assertThat(instances.get(project, zone, "vm").getStatus()).isEqualTo("RUNNING");
                instances.stopAsync(project, zone, "vm").get(20, TimeUnit.SECONDS);
                instances.setMachineTypeAsync(project, zone, "vm", InstancesSetMachineTypeRequest.newBuilder().setMachineType("zones/" + zone + "/machineTypes/n2-standard-8").build()).get(20, TimeUnit.SECONDS);
                instances.startAsync(project, zone, "vm").get(20, TimeUnit.SECONDS);
                instances.resetAsync(project, zone, "vm").get(20, TimeUnit.SECONDS);
                disks.insertAsync(project, zone, Disk.newBuilder().setName("data").setSizeGb(100).setType("zones/" + zone + "/diskTypes/hyperdisk-balanced").setProvisionedIops(4000).setProvisionedThroughput(200).build()).get(20, TimeUnit.SECONDS);
                disks.updateAsync(project, zone, "data", Disk.newBuilder().setProvisionedIops(6000).setProvisionedThroughput(300).build()).get(20, TimeUnit.SECONDS);
                assertThat(disks.get(project, zone, "data").getProvisionedIops()).isEqualTo(6000);
                instances.attachDiskAsync(project, zone, "vm", AttachedDisk.newBuilder().setSource("zones/" + zone + "/disks/data").setDeviceName("data").build()).get(20, TimeUnit.SECONDS);
                assertThatThrownBy(() -> disks.deleteAsync(project, zone, "data").get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(com.google.api.gax.rpc.InvalidArgumentException.class);
                instances.detachDiskAsync(project, zone, "vm", "data").get(20, TimeUnit.SECONDS);
                snapshots.insertAsync(project, Snapshot.newBuilder().setName("snapshot").setSourceDisk("zones/" + zone + "/disks/data").build()).get(20, TimeUnit.SECONDS);
                disks.insertAsync(project, "us-central1-b", Disk.newBuilder().setName("restored").setSourceSnapshot("global/snapshots/snapshot").build()).get(20, TimeUnit.SECONDS);
                assertThat(disks.get(project, "us-central1-b", "restored").getSizeGb()).isEqualTo(100);
                images.insertAsync(project, Image.newBuilder().setName("image").setFamily("desktop").setSourceDisk("zones/" + zone + "/disks/data").build()).get(20, TimeUnit.SECONDS);
                images.insertAsync(project, Image.newBuilder().setName("copy").setFamily("desktop").setSourceImage("global/images/image").build()).get(20, TimeUnit.SECONDS);
                assertThat(images.getFromFamily(project, "desktop").getName()).isEqualTo("copy");
                assertThat(images.list(ListImagesRequest.newBuilder().setProject(project).setMaxResults(1).build()).iterateAll()).hasSize(2);
                instances.deleteAsync(project, zone, "vm").get(20, TimeUnit.SECONDS);
                assertThat(disks.get(project, zone, "vm").getSizeGb()).isEqualTo(20);
            } finally {
                for (var vm : instances.list(project, zone).iterateAll()) { instances.deleteAsync(project, zone, vm.getName()).get(20, TimeUnit.SECONDS); }
                for (var image : images.list(project).iterateAll()) { images.deleteAsync(project, image.getName()).get(20, TimeUnit.SECONDS); }
                for (var snapshot : snapshots.list(project).iterateAll()) { snapshots.deleteAsync(project, snapshot.getName()).get(20, TimeUnit.SECONDS); }
                for (String z : new String[]{zone, "us-central1-b"}) {
                    for (var disk : disks.list(project, z).iterateAll()) { disks.deleteAsync(project, z, disk.getName()).get(20, TimeUnit.SECONDS); }
                }
                for (var subnet : subnets.list(project, "us-central1").iterateAll()) { subnets.deleteAsync(project, "us-central1", subnet.getName()).get(20, TimeUnit.SECONDS); }
                for (var network : networks.list(project).iterateAll()) { networks.deleteAsync(project, network.getName()).get(20, TimeUnit.SECONDS); }
            }
        }
    }
}
