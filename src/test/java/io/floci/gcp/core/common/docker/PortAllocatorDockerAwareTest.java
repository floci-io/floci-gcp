package io.floci.gcp.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A port another container publishes on the Docker host is invisible to {@link PortAllocator#isPortFree} when
 * floci-gcp runs in a container, so {@link PortAllocator#allocate} also asks Docker. Two floci-gcp instances on
 * one host would otherwise both pick the first port of a range.
 */
class PortAllocatorDockerAwareTest {

    @Test
    void aPortAnotherContainerPublishesIsSkipped() {
        DockerClient docker = dockerPublishing(19900);
        PortAllocator allocator = new HostBlindPortAllocator(producer(docker));

        int allocated = allocator.allocate(19900, 19999);

        assertEquals(19901, allocated);
        allocator.release(allocated);
    }

    @Test
    void withoutADockerClientTheFirstFreePortIsChosen() {
        PortAllocator allocator = new HostBlindPortAllocator(null);

        int port = allocator.allocate(19900, 19999);

        assertEquals(19900, port);
        allocator.release(port);
    }

    @Test
    void dockerIsAskedOncePerAllocation() {
        DockerClient docker = dockerPublishing(19900, 19901, 19902);
        PortAllocator allocator = new HostBlindPortAllocator(producer(docker));

        int allocated = allocator.allocate(19900, 19999);

        assertEquals(19903, allocated);
        verify(docker, times(1)).listContainersCmd();
        allocator.release(allocated);
    }

    @Test
    void aDockerErrorDoesNotBlockAllocation() {
        DockerClient docker = mock(DockerClient.class);
        when(docker.listContainersCmd()).thenThrow(new RuntimeException("docker daemon unreachable"));
        PortAllocator allocator = new HostBlindPortAllocator(producer(docker));

        int port = allocator.allocate(19900, 19999);

        assertEquals(19900, port);
        allocator.release(port);
    }

    private static DockerClient dockerPublishing(int... ports) {
        DockerClient docker = mock(DockerClient.class);
        ListContainersCmd list = mock(ListContainersCmd.class);
        when(docker.listContainersCmd()).thenReturn(list);
        when(list.withShowAll(anyBoolean())).thenReturn(list);
        Container sibling = mock(Container.class);
        ContainerPort[] published = new ContainerPort[ports.length];
        for (int i = 0; i < ports.length; i++) {
            published[i] = new ContainerPort().withPublicPort(ports[i]);
        }
        when(sibling.getPorts()).thenReturn(published);
        when(list.exec()).thenReturn(List.of(sibling));
        return docker;
    }

    private static DockerClientProducer producer(DockerClient docker) {
        DockerClientProducer producer = mock(DockerClientProducer.class);
        when(producer.client()).thenReturn(docker);
        return producer;
    }

    /** Sees every port free on this machine, so only reservations and Docker's answer decide the port. */
    private static final class HostBlindPortAllocator extends PortAllocator {
        HostBlindPortAllocator(DockerClientProducer dockerClients) {
            super(dockerClients);
        }

        @Override
        public boolean isPortFree(int port) {
            return true;
        }
    }
}
