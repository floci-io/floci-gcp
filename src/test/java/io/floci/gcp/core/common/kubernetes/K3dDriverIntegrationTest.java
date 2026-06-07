package io.floci.gcp.core.common.kubernetes;

import io.floci.gcp.core.common.kubernetes.drivers.K3dDriver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class K3dDriverIntegrationTest {

        @Test
        void shouldCreateGetAndDeleteCluster() {

                K3dDriver driver = new K3dDriver();

                String clusterName = "test-" +
                                UUID.randomUUID()
                                                .toString()
                                                .substring(0, 8);

                try {

                        Cluster created = driver.create(clusterName);

                        waitForCluster(
                                        driver,
                                        clusterName);

                        assertNotNull(created);

                        assertEquals(
                                        clusterName,
                                        created.getName());

                        assertTrue(
                                        driver.exists(clusterName));

                        Cluster fetched = driver.get(clusterName);

                        assertNotNull(fetched);

                        assertEquals(
                                        clusterName,
                                        fetched.getName());

                } finally {

                        try {

                                if (driver.exists(clusterName)) {

                                        driver.delete(clusterName);

                                        waitForDeletion(
                                                        driver,
                                                        clusterName);
                                }

                        } catch (Exception ignored) {
                        }
                }

                assertFalse(
                                driver.exists(clusterName));
        }

        @Test
        void shouldReturnFalseForMissingCluster() {

                K3dDriver driver = new K3dDriver();

                assertFalse(
                                driver.exists(
                                                "missing-" +
                                                                UUID.randomUUID()));
        }

        @Test
        void shouldDeleteCluster() {

                K3dDriver driver = new K3dDriver();

                String clusterName = "test-" +
                                UUID.randomUUID()
                                                .toString()
                                                .substring(0, 8);

                try {

                        driver.create(clusterName);

                        waitForCluster(
                                        driver,
                                        clusterName);

                        assertTrue(
                                        driver.exists(clusterName));

                        driver.delete(clusterName);

                        waitForDeletion(
                                        driver,
                                        clusterName);

                        assertFalse(
                                        driver.exists(clusterName));

                } finally {

                        try {

                                if (driver.exists(clusterName)) {

                                        driver.delete(clusterName);

                                        waitForDeletion(
                                                        driver,
                                                        clusterName);
                                }

                        } catch (Exception ignored) {
                        }
                }
        }

        private void waitForCluster(
                        K3dDriver driver,
                        String name) {

                for (int i = 0; i < 30; i++) {

                        if (driver.exists(name)) {
                                return;
                        }

                        sleep();
                }

                fail(
                                "Cluster never became available: "
                                                + name);
        }

        private void waitForDeletion(
                        K3dDriver driver,
                        String name) {

                for (int i = 0; i < 30; i++) {

                        if (!driver.exists(name)) {
                                return;
                        }

                        sleep();
                }

                fail(
                                "Cluster still exists: "
                                                + name);
        }

        private void sleep() {

                try {

                        Thread.sleep(1000);

                } catch (InterruptedException e) {

                        Thread.currentThread()
                                        .interrupt();

                        fail(
                                        "Interrupted while waiting");
                }
        }
}