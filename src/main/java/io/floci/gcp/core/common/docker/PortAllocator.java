package io.floci.gcp.core.common.docker;

import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Allocates host ports from a configured range for callers that must know the port before the container
 * starts (GKE's k3s API server). A binding that only needs some free port uses a dynamic port instead
 * ({@code ContainerBuilder.Builder#withDynamicPort}), which leaves the choice to the Docker daemon.
 */
@ApplicationScoped
public class PortAllocator {

    private static final Logger LOG = Logger.getLogger(PortAllocator.class);

    /** Ports reserved by this process but not yet bound by Docker, so concurrent launches never share one. */
    private final Set<Integer> reserved = new ConcurrentSkipListSet<>();

    /** Null for the no-arg constructor, which then checks only this process's own sockets. */
    private final DockerClientProducer dockerClients;

    public PortAllocator() {
        this(null);
    }

    @Inject
    public PortAllocator(DockerClientProducer dockerClients) {
        this.dockerClients = dockerClients;
    }

    /**
     * Reserves the first port in the range that is not reserved here, not published by any container on the
     * Docker host, and free in this process's network namespace. The published ports are read once per call.
     */
    public synchronized int allocate(int basePort, int maxPort) {
        Set<Integer> published = publishedHostPorts();
        for (int port = basePort; port <= maxPort; port++) {
            if (!reserved.contains(port) && !published.contains(port) && isPortFree(port)) {
                reserved.add(port);
                LOG.debugv("Allocated port {0} from range {1}-{2}",
                        String.valueOf(port), String.valueOf(basePort), String.valueOf(maxPort));
                return port;
            }
        }
        throw new RuntimeException("No free port available in range " + basePort + "-" + maxPort);
    }

    /**
     * Marks a port as reserved without probing whether it is free. Used on restart to
     * re-reserve host ports already held by surviving containers so the allocator does
     * not hand them out again.
     */
    public synchronized void markReserved(int port) {
        reserved.add(port);
    }

    public void release(int port) {
        if (reserved.remove(port)) {
            LOG.debugv("Released port {0}", String.valueOf(port));
        }
    }

    /**
     * Whether the port can be bound in this process's network namespace. When floci-gcp runs in a container this
     * cannot see ports that sibling containers publish on the host; {@link #publishedHostPorts} covers those.
     */
    public boolean isPortFree(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Every host port a container on the Docker host publishes, running or not (a created container already holds
     * its binding). Empty when there is no Docker client or the call fails: the lookup never blocks allocation.
     */
    Set<Integer> publishedHostPorts() {
        if (dockerClients == null) {
            return Set.of();
        }
        try {
            List<Container> containers = dockerClients.client().listContainersCmd().withShowAll(true).exec();
            Set<Integer> published = new HashSet<>();
            for (Container container : containers) {
                ContainerPort[] ports = container.getPorts();
                if (ports == null) {
                    continue;
                }
                for (ContainerPort port : ports) {
                    if (port.getPublicPort() != null) {
                        published.add(port.getPublicPort());
                    }
                }
            }
            return published;
        } catch (RuntimeException e) {
            LOG.debugv("Could not list the host ports Docker containers publish: {0}", e.getMessage());
            return Set.of();
        }
    }
}
