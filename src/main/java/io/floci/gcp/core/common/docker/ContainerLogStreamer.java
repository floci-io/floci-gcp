package io.floci.gcp.core.common.docker;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;

/**
 * Streams Docker container logs to the floci-gcp console logger.
 */
@ApplicationScoped
public class ContainerLogStreamer {

    private static final Logger LOG = Logger.getLogger(ContainerLogStreamer.class);

    private final DockerClientProducer dockerClients;

    @Inject
    public ContainerLogStreamer(DockerClientProducer dockerClients) {
        this.dockerClients = dockerClients;
    }

    /**
     * Attaches a log stream to a container and forwards lines to the console.
     * Returns a Closeable handle that must be closed when the container is stopped.
     */
    public Closeable attach(String containerId, String logPrefix) {
        try {
            return dockerClients.client().logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true)
                    .withTimestamps(false)
                    .exec(new ResultCallback.Adapter<>() {
                        @Override
                        public void onNext(Frame frame) {
                            String line = new String(frame.getPayload(), StandardCharsets.UTF_8).stripTrailing();
                            if (!line.isEmpty()) {
                                LOG.infov("[{0}] {1}", logPrefix, line);
                            }
                        }
                    });
        } catch (Exception e) {
            LOG.warnv("Could not attach log stream for container {0}: {1}", containerId, e.getMessage());
            return null;
        }
    }
}
