package io.floci.gcp.services.compute;

import io.floci.gcp.services.compute.ComputeInstanceRuntime.Intent;
import io.floci.gcp.services.compute.ComputeInstanceRuntime.Kind;
import io.floci.gcp.services.compute.ComputeInstanceRuntime.Phase;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** How Compute operations and instance statuses follow the container runtime, with the runtime itself mocked. */
@QuarkusTest
class ComputeContainerRuntimeIntegrationTest extends ComputeTestSupport {
    private static final String ZONE = "/zones/us-central1-a";

    @InjectMock ComputeInstanceRuntime runtime;
    @Inject ComputeService compute;

    @BeforeEach
    void enableRuntime() {
        when(runtime.enabled()).thenReturn(true);
        when(runtime.phase(anyString())).thenReturn(Phase.DONE);
    }

    @Test void insertWaitsForTheContainerAndShowsStagingWhileItIsCreated() throws Exception {
        String root = network();
        done(root, post(root + ZONE + "/disks", Map.of("name", "seed", "sizeGb", "10")));
        done(root, post(root + "/global/images", Map.of("name", "golden-v1", "family", "ubuntu-2204-lts",
                "sourceDisk", "zones/us-central1-a/disks/seed")));
        when(runtime.phase(anyString())).thenReturn(Phase.PROVISIONING);

        var insert = post(root + ZONE + "/instances", vm("vm", "global/images/golden-v1"));
        insert.then().statusCode(200);
        String operation = insert.jsonPath().getString("selfLink").replaceFirst("https://www.googleapis.com", "");
        String key = "zones/us-central1-a/operations/" + insert.jsonPath().getString("name");
        Thread.sleep(150);
        assertEquals("RUNNING", given().get(operation).jsonPath().getString("status"));
        assertEquals("PROVISIONING", given().get(root + ZONE + "/instances/vm").jsonPath().getString("status"));

        when(runtime.phase(key)).thenReturn(Phase.STAGING);
        assertEquals("STAGING", given().get(root + ZONE + "/instances/vm").jsonPath().getString("status"));
        assertEquals("RUNNING", given().get(operation).jsonPath().getString("status"));

        when(runtime.phase(key)).thenReturn(Phase.DONE);
        assertEquals("DONE", given().get(operation).jsonPath().getString("status"));
        assertEquals("RUNNING", given().get(root + ZONE + "/instances/vm").jsonPath().getString("status"));
        verify(runtime).forget(key);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Intent>> intents = ArgumentCaptor.forClass(List.class);
        verify(runtime).submit(eq(key), intents.capture());
        String project = root.substring(root.lastIndexOf('/') + 1);
        assertEquals(List.of(new Intent(Kind.LAUNCH, project, "us-central1-a", "vm", "ubuntu:22.04", 2, 8192, Map.of(),
                List.of(22))), intents.getValue());
    }

    @Test void lifecycleMethodsRecordTheirContainerSteps() throws Exception {
        String root = network();
        done(root, post(root + ZONE + "/instances", vm("vm", null)));
        done(root, post(root + ZONE + "/instances/vm/reset", Map.of()));
        done(root, post(root + ZONE + "/instances/vm/stop", Map.of()));
        done(root, post(root + ZONE + "/instances/vm/setMachineType",
                Map.of("machineType", "zones/us-central1-a/machineTypes/n2-standard-4")));
        done(root, post(root + ZONE + "/instances/vm/start", Map.of()));
        done(root, given().delete(root + ZONE + "/instances/vm"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Intent>> intents = ArgumentCaptor.forClass(List.class);
        verify(runtime, times(6)).submit(anyString(), intents.capture());
        assertEquals(List.of(Kind.LAUNCH, Kind.RESTART, Kind.STOP, Kind.REMOVE, Kind.START, Kind.REMOVE),
                intents.getAllValues().stream().map(list -> list.getFirst().kind()).toList());
        Intent start = intents.getAllValues().get(4).getFirst();
        assertEquals(4, start.cpus());
        assertEquals(16384, start.memoryMb());
        assertEquals("debian:12", intents.getAllValues().getFirst().getFirst().image());
    }

    @Test void publicBootImagesPickTheirFamilysDockerImage() throws Exception {
        String root = network();
        done(root, post(root + ZONE + "/instances", vm("family-vm", "projects/debian-cloud/global/images/family/debian-12")));
        done(root, post(root + ZONE + "/instances", vm("image-vm",
                "https://www.googleapis.com/compute/v1/projects/ubuntu-os-cloud/global/images/ubuntu-2404-noble-amd64-v20261001")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Intent>> intents = ArgumentCaptor.forClass(List.class);
        verify(runtime, times(2)).submit(anyString(), intents.capture());
        assertEquals(List.of("debian:12", "ubuntu:24.04"),
                intents.getAllValues().stream().map(list -> list.getFirst().image()).toList());
    }

    @Test void rejectedMutationsStartNoContainer() {
        String root = network();
        post(root + ZONE + "/instances", Map.of("name", "vm", "machineType", "zones/us-central1-a/machineTypes/e2-standard-2",
                "networkInterfaces", List.of(Map.of("subnetwork", "regions/us-central1/subnetworks/subnet")),
                "disks", List.of(Map.of("boot", false, "initializeParams", Map.of("diskSizeGb", "10")))))
                .then().statusCode(400);

        verify(runtime, never()).submit(anyString(), anyList());
    }

    @Test void mutationsWithoutContainerStepsDoNotWaitForTheRuntime() throws Exception {
        String root = network();
        done(root, post(root + ZONE + "/instances", vm("vm", null)));
        clearInvocations(runtime);
        when(runtime.phase(anyString())).thenReturn(Phase.PROVISIONING);
        String fingerprint = given().get(root + ZONE + "/instances/vm").jsonPath().getString("labelFingerprint");

        done(root, post(root + ZONE + "/instances/vm/setLabels", Map.of("labelFingerprint", fingerprint,
                "labels", Map.of("team", "a"))));

        verify(runtime, never()).submit(anyString(), anyList());
    }

    @Test void instanceMetadataOverridesProjectMetadataKeyByKey() throws Exception {
        String root = network();
        String fingerprint = given().get(root).jsonPath().getString("commonInstanceMetadata.fingerprint");
        done(root, post(root + "/setCommonInstanceMetadata", Map.of("fingerprint", fingerprint, "items", List.of(
                Map.of("key", "startup-script", "value", "echo project"),
                Map.of("key", "shutdown-script", "value", "echo project-shutdown")))));
        Map<String, Object> vm = new HashMap<>(vm("vm", null));
        vm.put("metadata", Map.of("items", List.of(Map.of("key", "startup-script", "value", "echo instance"))));
        done(root, post(root + ZONE + "/instances", vm));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Intent>> intents = ArgumentCaptor.forClass(List.class);
        verify(runtime).submit(anyString(), intents.capture());
        assertEquals(Map.of("startup-script", "echo instance", "shutdown-script", "echo project-shutdown"),
                intents.getValue().getFirst().metadata());
    }

    @Test void firewallRulesOnTheInstanceNetworkPickThePublishedPorts() throws Exception {
        String root = network();
        done(root, post(root + "/global/firewalls", Map.of("name", "web", "network", "global/networks/net",
                "targetTags", List.of("web"), "allowed", List.of(Map.of("IPProtocol", "tcp", "ports", List.of("80", "8080-8081")),
                        Map.of("IPProtocol", "udp", "ports", List.of("53"))))));
        done(root, post(root + "/global/firewalls", Map.of("name", "db", "network", "global/networks/net",
                "targetTags", List.of("db"), "allowed", List.of(Map.of("IPProtocol", "tcp", "ports", List.of("5432"))))));
        done(root, post(root + "/global/firewalls", Map.of("name", "off", "network", "global/networks/net",
                "disabled", true, "allowed", List.of(Map.of("IPProtocol", "tcp", "ports", List.of("9000"))))));
        Map<String, Object> vm = new HashMap<>(vm("vm", null));
        vm.put("tags", Map.of("items", List.of("web")));

        done(root, post(root + ZONE + "/instances", vm));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Intent>> intents = ArgumentCaptor.forClass(List.class);
        verify(runtime).submit(anyString(), intents.capture());
        assertEquals(List.of(22, 80, 8080, 8081), intents.getValue().getFirst().ports());
    }

    @Test void sshKeyChangesReachRunningInstances() throws Exception {
        String root = network();
        done(root, post(root + ZONE + "/instances", vm("vm", null)));
        clearInvocations(runtime);
        String fingerprint = given().get(root + ZONE + "/instances/vm").jsonPath().getString("metadata.fingerprint");
        done(root, post(root + ZONE + "/instances/vm/setMetadata", Map.of("fingerprint", fingerprint, "items",
                List.of(Map.of("key", "ssh-keys", "value", "alice:ssh-ed25519 AAAAC3Nza alice")))));
        String projectFingerprint = given().get(root).jsonPath().getString("commonInstanceMetadata.fingerprint");
        done(root, post(root + "/setCommonInstanceMetadata", Map.of("fingerprint", projectFingerprint, "items",
                List.of(Map.of("key", "ssh-keys", "value", "bob:ssh-ed25519 AAAAC3Nzb bob")))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Intent>> intents = ArgumentCaptor.forClass(List.class);
        verify(runtime, times(2)).submit(anyString(), intents.capture());
        Intent instanceChange = intents.getAllValues().get(0).getFirst();
        Intent projectChange = intents.getAllValues().get(1).getFirst();
        assertEquals(Kind.METADATA, instanceChange.kind());
        assertEquals(Kind.METADATA, projectChange.kind());
        assertEquals("us-central1-a", projectChange.zone());
        String project = root.substring(root.lastIndexOf('/') + 1);
        assertEquals(List.of("alice:ssh-ed25519 AAAAC3Nza alice", "bob:ssh-ed25519 AAAAC3Nzb bob"),
                compute.instanceSshKeys(project, "us-central1-a", "vm"));
        assertNull(compute.instanceSshKeys(project, "us-central1-a", "missing"));
    }

    @Test void cloudInitReadsOnlyInstanceUserDataAndOnlyOnImagesThatShipIt() throws Exception {
        String root = network();
        String fingerprint = given().get(root).jsonPath().getString("commonInstanceMetadata.fingerprint");
        done(root, post(root + "/setCommonInstanceMetadata", Map.of("fingerprint", fingerprint, "items",
                List.of(Map.of("key", "user-data", "value", "#cloud-config\nproject: true")))));
        List<Map<String, String>> userData = List.of(Map.of("key", "user-data", "value", "I2Nsb3VkLWNvbmZpZwo="),
                Map.of("key", "user-data-encoding", "value", "base64"));
        String ubuntu = "projects/ubuntu-os-cloud/global/images/family/ubuntu-2404-lts-amd64";
        done(root, post(root + ZONE + "/instances", withMetadata(vm("ubuntu", ubuntu), userData)));
        done(root, post(root + ZONE + "/instances", withMetadata(vm("cos", "projects/cos-cloud/global/images/family/cos-stable"), userData)));
        done(root, post(root + ZONE + "/instances", withMetadata(vm("debian", "projects/debian-cloud/global/images/family/debian-12"), userData)));
        done(root, post(root + ZONE + "/instances", vm("project-only", ubuntu)));
        String project = root.substring(root.lastIndexOf('/') + 1);

        ComputeGuestScripts.CloudInit seed = compute.instanceCloudInit(project, "us-central1-a", "ubuntu");
        assertEquals(new ComputeGuestScripts.CloudInit(
                given().get(root + ZONE + "/instances/ubuntu").jsonPath().getString("id"), "ubuntu",
                "I2Nsb3VkLWNvbmZpZwo=", "base64", false), seed);
        assertTrue(compute.instanceCloudInit(project, "us-central1-a", "cos").everyBoot());
        assertNull(compute.instanceCloudInit(project, "us-central1-a", "debian"), "Debian images ship no cloud-init");
        assertNull(compute.instanceCloudInit(project, "us-central1-a", "project-only"), "project user-data is not read");
        assertNull(compute.instanceCloudInit(project, "us-central1-a", "missing"));
    }

    @Test void aDiskKeepsItsImageCloudInitSettingAfterThatImageIsDeleted() throws Exception {
        String root = network();
        done(root, post(root + ZONE + "/disks", Map.of("name", "seed", "sizeGb", "10")));
        done(root, post(root + "/global/images", Map.of("name", "golden-v2", "family", "ubuntu-2404-lts-amd64",
                "sourceDisk", "zones/us-central1-a/disks/seed")));
        done(root, post(root + ZONE + "/instances", withMetadata(vm("vm", "global/images/golden-v2"),
                List.of(Map.of("key", "user-data", "value", "#cloud-config")))));
        String project = root.substring(root.lastIndexOf('/') + 1);
        assertNotNull(compute.instanceCloudInit(project, "us-central1-a", "vm"));

        done(root, given().delete(root + "/global/images/golden-v2"));

        assertNotNull(compute.instanceCloudInit(project, "us-central1-a", "vm"),
                "cloud-init is on the disk, not in the image record");
    }

    private static Map<String, Object> withMetadata(Map<String, Object> vm, List<Map<String, String>> items) {
        Map<String, Object> copy = new HashMap<>(vm);
        copy.put("metadata", Map.of("items", items));
        return copy;
    }

    @Test void projectKeyChangesAlsoReachInstancesStillStarting() throws Exception {
        String root = network();
        when(runtime.phase(anyString())).thenReturn(Phase.PROVISIONING);
        post(root + ZONE + "/instances", vm("vm", null)).then().statusCode(200);
        clearInvocations(runtime);
        String projectFingerprint = given().get(root).jsonPath().getString("commonInstanceMetadata.fingerprint");

        post(root + "/setCommonInstanceMetadata", Map.of("fingerprint", projectFingerprint, "items",
                List.of(Map.of("key", "ssh-keys", "value", "bob:ssh-ed25519 AAAAC3Nzb bob")))).then().statusCode(200);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Intent>> intents = ArgumentCaptor.forClass(List.class);
        verify(runtime).submit(anyString(), intents.capture());
        assertEquals(Kind.METADATA, intents.getValue().getFirst().kind());
        when(runtime.phase(anyString())).thenReturn(Phase.DONE);
    }

    @Test void serialPortOutputServesTheRuntimeBuffer() throws Exception {
        String root = network();
        done(root, post(root + ZONE + "/instances", vm("vm", null)));
        String project = root.substring(root.lastIndexOf('/') + 1);
        when(runtime.serialOutput(project, "us-central1-a", "vm", 1, 5L))
                .thenReturn(new ComputeSerialPort.Output("lo\n", 5, 8));

        var output = given().queryParam("start", 5).get(root + ZONE + "/instances/vm/serialPort").then().statusCode(200)
                .extract().jsonPath();
        assertEquals("compute#serialPortOutput", output.getString("kind"));
        assertEquals("lo\n", output.getString("contents"));
        assertEquals("5", output.getString("start"));
        assertEquals("8", output.getString("next"));
        assertEquals("https://www.googleapis.com" + root + ZONE + "/instances/vm/serialPort", output.getString("selfLink"));
        given().queryParam("port", 5).get(root + ZONE + "/instances/vm/serialPort").then().statusCode(400);
        given().queryParam("start", "x").get(root + ZONE + "/instances/vm/serialPort").then().statusCode(400);
        given().get(root + ZONE + "/instances/missing/serialPort").then().statusCode(404);
    }

    private String network() {
        String root = root();
        try {
            done(root, post(root + "/global/networks", Map.of("name", "net", "autoCreateSubnetworks", false)));
            done(root, post(root + "/regions/us-central1/subnetworks", Map.of("name", "subnet",
                    "network", "global/networks/net", "ipCidrRange", "10.10.0.0/24")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return root;
    }

    private static Map<String, Object> vm(String name, String sourceImage) {
        Map<String, Object> params = sourceImage == null ? Map.of("diskSizeGb", "20")
                : Map.of("diskSizeGb", "20", "sourceImage", sourceImage);
        return Map.of("name", name, "machineType", "zones/us-central1-a/machineTypes/e2-standard-2",
                "networkInterfaces", List.of(Map.of("subnetwork", "regions/us-central1/subnetworks/subnet")),
                "disks", List.of(Map.of("boot", true, "initializeParams", params)));
    }
}
