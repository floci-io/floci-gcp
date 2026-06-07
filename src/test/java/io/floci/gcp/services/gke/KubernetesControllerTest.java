package io.floci.gcp.services.gke;

import io.floci.gcp.core.common.kubernetes.Cluster;
import io.floci.gcp.core.common.kubernetes.ClusterDriverType;
import io.floci.gcp.core.common.kubernetes.ClusterStatus;
import io.floci.gcp.core.common.kubernetes.drivers.K3dDriver;
import io.floci.gcp.core.common.kubernetes.metadata.ClusterMetadata;
import io.floci.gcp.services.gke.operations.GkeOperationService;
import io.floci.gcp.services.gke.operations.OperationType;
import io.floci.gcp.services.gke.operations.StoredOperation;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KubernetesControllerTest {

    private KubernetesController controller;
    private K3dDriver driver;
    private GkeOperationService operationService;

    @BeforeEach
    void setUp() throws Exception {

        controller = new KubernetesController();

        driver = mock(K3dDriver.class);
        operationService = mock(GkeOperationService.class);

        Field driverField =
                KubernetesController.class.getDeclaredField("driver");

        driverField.setAccessible(true);
        driverField.set(controller, driver);

        Field operationField =
                KubernetesController.class.getDeclaredField(
                        "operationService");

        operationField.setAccessible(true);
        operationField.set(controller, operationService);
    }

    @Test
    void shouldCreateCluster() {

        Cluster cluster = new Cluster(
                "dev",
                "dev",
                ClusterStatus.RUNNING,
                ClusterDriverType.K3D);

        StoredOperation operation =
                mock(StoredOperation.class);

        when(driver.create("dev"))
                .thenReturn(cluster);

        when(operationService.createOperation(
                "test-project",
                "us-central1",
                "dev",
                OperationType.CREATE_CLUSTER))
                .thenReturn(operation);

        Response response =
                controller.createCluster(
                        "test-project",
                        "us-central1",
                        Map.of(
                                "cluster",
                                Map.of(
                                        "name",
                                        "dev")));

        assertEquals(
                200,
                response.getStatus());

        assertSame(
                operation,
                response.getEntity());

        verify(driver)
                .create("dev");

        verify(operationService)
                .createOperation(
                        "test-project",
                        "us-central1",
                        "dev",
                        OperationType.CREATE_CLUSTER);
    }

    @Test
    void shouldRejectMissingClusterObject() {

        Response response =
                controller.createCluster(
                        "test-project",
                        "us-central1",
                        Map.of());

        assertEquals(
                400,
                response.getStatus());

        verifyNoInteractions(driver);
    }

    @Test
    void shouldRejectMissingClusterName() {

        Response response =
                controller.createCluster(
                        "test-project",
                        "us-central1",
                        Map.of(
                                "cluster",
                                Map.of()));

        assertEquals(
                400,
                response.getStatus());

        verifyNoInteractions(driver);
    }

    @Test
    void shouldGetCluster() {

        Cluster cluster = new Cluster(
                "dev",
                "dev",
                ClusterStatus.RUNNING,
                ClusterDriverType.K3D);

        ClusterMetadata metadata =
                new ClusterMetadata(
                        "us-central1",
                        "127.0.0.1:6443",
                        "ca-cert",
                        "v1.33.1",
                        List.of(),
                        "default",
                        "default",
                        Map.of());

        when(driver.get("dev"))
                .thenReturn(cluster);

        when(driver.metadata(
                "dev",
                "us-central1"))
                .thenReturn(metadata);

        Response response =
                controller.getCluster(
                        "us-central1",
                        "dev");

        assertEquals(
                200,
                response.getStatus());

        Map<?, ?> body =
                (Map<?, ?>) response.getEntity();

        assertEquals(
                "dev",
                body.get("name"));

        assertEquals(
                "RUNNING",
                body.get("status"));

        assertEquals(
                "127.0.0.1:6443",
                body.get("endpoint"));

        verify(driver)
                .get("dev");

        verify(driver)
                .metadata(
                        "dev",
                        "us-central1");
    }

    @Test
    void shouldDeleteCluster() {

        StoredOperation operation =
                mock(StoredOperation.class);

        doNothing()
                .when(driver)
                .delete("dev");

        when(operationService.createOperation(
                "test-project",
                "us-central1",
                "dev",
                OperationType.DELETE_CLUSTER))
                .thenReturn(operation);

        Response response =
                controller.deleteCluster(
                        "test-project",
                        "us-central1",
                        "dev");

        assertEquals(
                200,
                response.getStatus());

        assertSame(
                operation,
                response.getEntity());

        verify(driver)
                .delete("dev");

        verify(operationService)
                .createOperation(
                        "test-project",
                        "us-central1",
                        "dev",
                        OperationType.DELETE_CLUSTER);
    }

    @Test
    void shouldReturnKubeConfig() {

        when(driver.kubeConfig("dev"))
                .thenReturn("apiVersion: v1");

        Response response =
                controller.kubeConfig("dev");

        assertEquals(
                200,
                response.getStatus());

        assertEquals(
                "apiVersion: v1",
                response.getEntity());

        verify(driver)
                .kubeConfig("dev");
    }
}