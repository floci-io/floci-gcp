package io.floci.gcp.services.tasks;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.lifecycle.GrpcServerManager;
import io.floci.gcp.services.tasks.model.StoredQueue;
import io.floci.gcp.services.tasks.model.StoredTask;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class CloudTasksService {

    private static final Logger LOG = Logger.getLogger(CloudTasksService.class);

    private final StorageBackend<String, StoredQueue> queueStore;
    private final StorageBackend<String, StoredTask> taskStore;

    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final GrpcServerManager grpcServerManager;

    @Inject
    public CloudTasksService(ServiceRegistry serviceRegistry, EmulatorConfig config,
            StorageFactory storageFactory, GrpcServerManager grpcServerManager) {
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.grpcServerManager = grpcServerManager;
        this.queueStore = storageFactory.createGlobal("cloudtasks-queues", "cloudtasks-queues.json",
                new TypeReference<Map<String, StoredQueue>>() {});
        this.taskStore = storageFactory.createGlobal("cloudtasks-tasks", "cloudtasks-tasks.json",
                new TypeReference<Map<String, StoredTask>>() {});
    }

    CloudTasksService(StorageBackend<String, StoredQueue> queueStore,
            StorageBackend<String, StoredTask> taskStore) {
        this.queueStore = queueStore;
        this.taskStore = taskStore;
        this.serviceRegistry = null;
        this.config = null;
        this.grpcServerManager = null;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("cloudtasks")
                .enabled(config.services().cloudtasks().enabled())
                .storageKey("cloudtasks")
                .protocol(ServiceProtocol.GRPC)
                .resourceClasses(CloudTasksController.class)
                .build());
        grpcServerManager.bind(new CloudTasksController(this));
    }

    // ── Queues ─────────────────────────────────────────────────────────────────

    public StoredQueue createQueue(String project, String location, String queueId,
            double maxDispatchesPerSecond, int maxConcurrentDispatches, int maxAttempts) {
        String name = "projects/" + project + "/locations/" + location + "/queues/" + queueId;
        LOG.infof("createQueue name=%s", name);
        if (queueStore.get(name).isPresent()) {
            throw GcpException.alreadyExists("Queue already exists: " + name);
        }
        StoredQueue queue = new StoredQueue(name, Instant.now().toString());
        if (maxDispatchesPerSecond > 0) {
            queue.setMaxDispatchesPerSecond(maxDispatchesPerSecond);
        }
        if (maxConcurrentDispatches > 0) {
            queue.setMaxConcurrentDispatches(maxConcurrentDispatches);
        }
        if (maxAttempts > 0) {
            queue.setMaxAttempts(maxAttempts);
        }
        queueStore.put(name, queue);
        return queue;
    }

    public StoredQueue getQueue(String name) {
        LOG.debugf("getQueue name=%s", name);
        return queueStore.get(name)
                .orElseThrow(() -> GcpException.notFound("Queue not found: " + name));
    }

    public List<StoredQueue> listQueues(String project, String location) {
        LOG.debugf("listQueues project=%s location=%s", project, location);
        String prefix = "projects/" + project + "/locations/" + location + "/queues/";
        return queueStore.scan(k -> k.startsWith(prefix));
    }

    public StoredQueue updateQueue(String name, double maxDispatchesPerSecond,
            int maxConcurrentDispatches, int maxAttempts) {
        LOG.infof("updateQueue name=%s", name);
        StoredQueue queue = queueStore.get(name)
                .orElseThrow(() -> GcpException.notFound("Queue not found: " + name));
        if (maxDispatchesPerSecond > 0) {
            queue.setMaxDispatchesPerSecond(maxDispatchesPerSecond);
        }
        if (maxConcurrentDispatches > 0) {
            queue.setMaxConcurrentDispatches(maxConcurrentDispatches);
        }
        if (maxAttempts > 0) {
            queue.setMaxAttempts(maxAttempts);
        }
        queueStore.put(name, queue);
        return queue;
    }

    public void deleteQueue(String name) {
        LOG.infof("deleteQueue name=%s", name);
        if (queueStore.get(name).isEmpty()) {
            throw GcpException.notFound("Queue not found: " + name);
        }
        String taskPrefix = name + "/tasks/";
        taskStore.scan(k -> k.startsWith(taskPrefix)).forEach(t -> taskStore.delete(t.getName()));
        queueStore.delete(name);
    }

    public StoredQueue purgeQueue(String name) {
        LOG.infof("purgeQueue name=%s", name);
        StoredQueue queue = queueStore.get(name)
                .orElseThrow(() -> GcpException.notFound("Queue not found: " + name));
        String taskPrefix = name + "/tasks/";
        taskStore.scan(k -> k.startsWith(taskPrefix)).forEach(t -> taskStore.delete(t.getName()));
        queue.setPurgeTime(Instant.now().toString());
        queueStore.put(name, queue);
        return queue;
    }

    public StoredQueue pauseQueue(String name) {
        LOG.infof("pauseQueue name=%s", name);
        StoredQueue queue = queueStore.get(name)
                .orElseThrow(() -> GcpException.notFound("Queue not found: " + name));
        queue.setState("PAUSED");
        queueStore.put(name, queue);
        return queue;
    }

    public StoredQueue resumeQueue(String name) {
        LOG.infof("resumeQueue name=%s", name);
        StoredQueue queue = queueStore.get(name)
                .orElseThrow(() -> GcpException.notFound("Queue not found: " + name));
        queue.setState("RUNNING");
        queueStore.put(name, queue);
        return queue;
    }

    // ── Tasks ──────────────────────────────────────────────────────────────────

    public StoredTask createTask(String queueName, String taskId, String taskType,
            String httpMethod, String url, Map<String, String> headers, byte[] body,
            String appEngineHttpMethod, String relativeUri, String scheduleTime) {
        if (queueStore.get(queueName).isEmpty()) {
            throw GcpException.notFound("Queue not found: " + queueName);
        }
        String id = (taskId != null && !taskId.isBlank()) ? taskId : UUID.randomUUID().toString();
        String name = queueName + "/tasks/" + id;
        LOG.infof("createTask name=%s", name);
        if (taskStore.get(name).isPresent()) {
            throw GcpException.alreadyExists("Task already exists: " + name);
        }
        StoredTask task = new StoredTask();
        task.setName(name);
        task.setCreateTime(Instant.now().toString());
        task.setScheduleTime(scheduleTime != null ? scheduleTime : Instant.now().toString());
        task.setTaskType(taskType);
        task.setHttpMethod(httpMethod);
        task.setUrl(url);
        task.setHeaders(headers);
        task.setBody(body);
        task.setAppEngineHttpMethod(appEngineHttpMethod);
        task.setRelativeUri(relativeUri);
        taskStore.put(name, task);
        return task;
    }

    public StoredTask getTask(String name) {
        LOG.debugf("getTask name=%s", name);
        return taskStore.get(name)
                .orElseThrow(() -> GcpException.notFound("Task not found: " + name));
    }

    public List<StoredTask> listTasks(String queueName) {
        LOG.debugf("listTasks queue=%s", queueName);
        if (queueStore.get(queueName).isEmpty()) {
            throw GcpException.notFound("Queue not found: " + queueName);
        }
        String prefix = queueName + "/tasks/";
        return taskStore.scan(k -> k.startsWith(prefix));
    }

    public void deleteTask(String name) {
        LOG.infof("deleteTask name=%s", name);
        if (taskStore.get(name).isEmpty()) {
            throw GcpException.notFound("Task not found: " + name);
        }
        taskStore.delete(name);
    }

    public StoredTask runTask(String name) {
        LOG.infof("runTask name=%s", name);
        StoredTask task = taskStore.get(name)
                .orElseThrow(() -> GcpException.notFound("Task not found: " + name));
        task.setDispatchCount(task.getDispatchCount() + 1);
        taskStore.put(name, task);
        return task;
    }
}
