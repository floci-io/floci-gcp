package io.floci.gcp.core.common.docker;

import com.github.dockerjava.api.command.PullImageResultCallback;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class ImageCacheService {

    private static final Logger LOG = Logger.getLogger(ImageCacheService.class);

    private final DockerClientProducer dockerClients;
    private final Set<String> pulledImages = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    @Inject
    public ImageCacheService(DockerClientProducer dockerClients) {
        this.dockerClients = dockerClients;
    }

    public void ensureImageExists(String image) {
        if (pulledImages.contains(image)) {
            return;
        }
        Object lock = locks.computeIfAbsent(image, k -> new Object());
        synchronized (lock) {
            if (pulledImages.contains(image)) {
                return;
            }
            if (isLocalImagePresent(image)) {
                pulledImages.add(image);
                LOG.infov("Image already present locally, skipping pull: {0}", image);
                return;
            }
            LOG.infov("Pulling image: {0}", image);
            try {
                dockerClients.client().pullImageCmd(image)
                        .exec(new PullImageResultCallback())
                        .awaitCompletion(10, TimeUnit.MINUTES);
                pulledImages.add(image);
                LOG.infov("Image pulled successfully: {0}", image);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while pulling image: " + image, e);
            }
        }
    }

    private boolean isLocalImagePresent(String image) {
        try {
            dockerClients.client().inspectImageCmd(image).exec();
            return true;
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            return false;
        } catch (Exception e) {
            LOG.debugv("Could not check local image presence for {0}: {1}", image, e.getMessage());
            return false;
        }
    }
}
