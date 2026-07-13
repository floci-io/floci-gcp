package io.floci.gcp.core.common.docker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DockerClientProducerTest {

    @Test
    void windowsDefaultUnixSocketUsesNamedPipe() {
        String host = DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", null, true);
        assertEquals("npipe:////./pipe/docker_engine", host);
    }

    @Test
    void dockerHostEnvOverridesUnixDefault() {
        String host = DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", "npipe:////./pipe/custom", true);
        assertEquals("npipe:////./pipe/custom", host);
    }

    @Test
    void nonWindowsKeepsUnixSocket() {
        String host = DockerClientProducer.resolveEffectiveDockerHost(
                "unix:///var/run/docker.sock", null, false);
        assertEquals("unix:///var/run/docker.sock", host);
    }
}
