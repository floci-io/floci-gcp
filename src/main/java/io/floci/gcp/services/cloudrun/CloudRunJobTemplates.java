package io.floci.gcp.services.cloudrun;

import com.google.cloud.run.v2.Condition;
import com.google.cloud.run.v2.Container;
import com.google.cloud.run.v2.EnvVar;
import com.google.cloud.run.v2.ExecutionEnvironment;
import com.google.cloud.run.v2.ExecutionTemplate;
import com.google.cloud.run.v2.ResourceRequirements;
import com.google.cloud.run.v2.RunJobRequest;
import com.google.cloud.run.v2.TaskTemplate;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import io.floci.gcp.core.common.GcpException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

// java.time.Duration is written fully qualified: it collides with the imported com.google.protobuf.Duration.

/**
 * Pure Cloud Run Jobs rules: template defaults, execution and task naming, run overrides and the
 * GCP message strings shared by the control plane and the execution coordinator.
 */
final class CloudRunJobTemplates {

    static final int DEFAULT_TASK_COUNT = 1;
    static final int DEFAULT_MAX_RETRIES = 3;
    static final long DEFAULT_TIMEOUT_SECONDS = 600;
    static final String DEFAULT_CPU = "1000m";
    static final String DEFAULT_MEMORY = "512Mi";

    static final String EXIT_ERROR_MESSAGE = "The container exited with an error.";
    static final String TIMEOUT_MESSAGE = "The configured timeout was reached.";
    static final String CANCELLED_MESSAGE = "Cancelled by user.";
    static final String RESTARTED_TASK_MESSAGE = "The emulator restarted before the task completed.";
    static final String RESTARTED_EXECUTION_MESSAGE = "The emulator restarted before the execution completed.";
    static final String PROVISIONED_MESSAGE = "Provisioned imported containers.";
    static final String WAITING_TO_START_MESSAGE = "Waiting for execution to start.";
    static final String WAITING_FOR_CANCEL_MESSAGE = "Waiting for execution to be cancelled.";
    static final String CONTAINER_VANISHED_MESSAGE = "The task container stopped unexpectedly.";

    static final String KIND_JOB = "JOB";
    static final String KIND_EXECUTION = "EXECUTION";
    static final String KIND_TASK = "TASK";

    static final long EXPIRE_AFTER_DELETE_SECONDS = 30L * 24 * 60 * 60;

    private static final String RANDOM_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int EXECUTION_SUFFIX_LENGTH = 5;
    private static final int MAX_JOB_ID_AND_TOKEN_LENGTH = 63;
    private static final Pattern EXECUTION_ID = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");

    private CloudRunJobTemplates() {}

    /** The attempt message for a task whose writable GCS volumes could not be written back. */
    static String writeBackFailedMessage(String detail) {
        String trimmed = detail.endsWith(".") ? detail.substring(0, detail.length() - 1) : detail;
        return "The task's GCS volume could not be written back: " + trimmed + ".";
    }

    /**
     * The GCP NOT_FOUND message for a job, execution or task under
     * {@code projects/{project}/locations/{location}/...}.
     */
    static String notFoundMessage(String kind, String resourceName) {
        String[] parts = resourceName.split("/");
        String project = parts.length > 1 ? parts[1] : "";
        String location = parts.length > 3 ? parts[3] : "";
        return "Resource '" + parts[parts.length - 1] + "' of kind '" + kind + "' in region '" + location
                + "' in project '" + project + "' does not exist.";
    }

    static GcpException notFound(String kind, String resourceName) {
        return GcpException.notFound(notFoundMessage(kind, resourceName));
    }

    /**
     * Completes the defaults GCP fills in on a job template: task count, retries, timeout, container resource
     * limits and execution environment. Job-level {@code parallelism} is left as given; the execution reports
     * the effective value.
     */
    static ExecutionTemplate withDefaults(ExecutionTemplate template) {
        ExecutionTemplate.Builder builder = template.toBuilder();
        if (builder.getTaskCount() <= 0) {
            builder.setTaskCount(DEFAULT_TASK_COUNT);
        }
        builder.setTemplate(withDefaults(builder.getTemplate()));
        return builder.build();
    }

    static TaskTemplate withDefaults(TaskTemplate template) {
        TaskTemplate.Builder builder = template.toBuilder();
        if (!builder.hasMaxRetries()) {
            builder.setMaxRetries(DEFAULT_MAX_RETRIES);
        }
        if (!builder.hasTimeout() || isZero(builder.getTimeout())) {
            builder.setTimeout(Duration.newBuilder().setSeconds(DEFAULT_TIMEOUT_SECONDS).build());
        }
        if (builder.getExecutionEnvironment() == ExecutionEnvironment.EXECUTION_ENVIRONMENT_UNSPECIFIED) {
            builder.setExecutionEnvironment(ExecutionEnvironment.EXECUTION_ENVIRONMENT_GEN2);
        }
        List<Container> containers = new ArrayList<>();
        for (Container container : builder.getContainersList()) {
            containers.add(withDefaultResources(container));
        }
        builder.clearContainers().addAllContainers(containers);
        return builder.build();
    }

    private static Container withDefaultResources(Container container) {
        ResourceRequirements.Builder resources = container.getResources().toBuilder();
        if (!resources.containsLimits("cpu")) {
            resources.putLimits("cpu", DEFAULT_CPU);
        }
        if (!resources.containsLimits("memory")) {
            resources.putLimits("memory", DEFAULT_MEMORY);
        }
        return container.toBuilder().setResources(resources).build();
    }

    static int effectiveParallelism(ExecutionTemplate template) {
        return template.getParallelism() > 0 ? template.getParallelism() : template.getTaskCount();
    }

    static String randomExecutionId(String jobId, RandomGenerator random) {
        StringBuilder id = new StringBuilder(jobId).append('-');
        for (int i = 0; i < EXECUTION_SUFFIX_LENGTH; i++) {
            id.append(RANDOM_ALPHABET.charAt(random.nextInt(RANDOM_ALPHABET.length())));
        }
        return id.toString();
    }

    static String tokenExecutionId(String jobId, String token) {
        return jobId + "-" + token;
    }

    /**
     * Rejects a start or run execution token that cannot name an execution. The length rule is the one documented
     * on {@code Job.start_execution_token} in job.proto: the job name and the token together must be fewer than
     * {@value #MAX_JOB_ID_AND_TOKEN_LENGTH} characters. The resulting execution ID must also be a single lowercase
     * resource-name segment. An empty token is not set and is accepted.
     */
    static void validateExecutionToken(String field, String jobId, String token) {
        if (token.isEmpty()) {
            return;
        }
        if (jobId.length() + token.length() >= MAX_JOB_ID_AND_TOKEN_LENGTH) {
            throw GcpException.invalidArgument("Invalid " + field + " '" + token + "': the job name and the token"
                    + " must together be fewer than " + MAX_JOB_ID_AND_TOKEN_LENGTH + " characters.");
        }
        String executionId = tokenExecutionId(jobId, token);
        if (!EXECUTION_ID.matcher(executionId).matches()) {
            throw GcpException.invalidArgument("Invalid " + field + " '" + token + "': the execution ID '"
                    + executionId + "' must consist of lowercase letters, digits and hyphens, and must start and"
                    + " end with a letter or digit.");
        }
    }

    static String taskId(String executionId, int index) {
        return executionId + "-task" + index;
    }

    /**
     * Applies {@code jobs:run} overrides to the job template. {@code taskCount} and {@code timeout} replace the
     * template values. A container override is matched by name; when no container has that name and the template
     * has exactly one container, the override applies to it, otherwise the request is invalid. {@code args}
     * replaces the container args, {@code env} merges by name and {@code clearArgs} removes the args.
     */
    static ExecutionTemplate applyOverrides(ExecutionTemplate template, RunJobRequest.Overrides overrides) {
        ExecutionTemplate.Builder builder = template.toBuilder();
        if (overrides.getTaskCount() > 0) {
            builder.setTaskCount(overrides.getTaskCount());
        }
        TaskTemplate.Builder task = builder.getTemplate().toBuilder();
        if (overrides.hasTimeout() && !isZero(overrides.getTimeout())) {
            task.setTimeout(overrides.getTimeout());
        }
        List<Container> containers = new ArrayList<>(task.getContainersList());
        for (RunJobRequest.Overrides.ContainerOverride override : overrides.getContainerOverridesList()) {
            int target = overrideTarget(containers, override.getName());
            containers.set(target, applyOverride(containers.get(target), override));
        }
        task.clearContainers().addAllContainers(containers);
        builder.setTemplate(task);
        return builder.build();
    }

    private static int overrideTarget(List<Container> containers, String name) {
        for (int i = 0; i < containers.size(); i++) {
            if (!name.isEmpty() && containers.get(i).getName().equals(name)) {
                return i;
            }
        }
        if (containers.size() == 1) {
            return 0;
        }
        throw GcpException.invalidArgument("Container override name '" + name
                + "' does not match any container in the job template.");
    }

    private static Container applyOverride(Container container, RunJobRequest.Overrides.ContainerOverride override) {
        Container.Builder builder = container.toBuilder();
        if (override.getClearArgs()) {
            builder.clearArgs();
        } else if (override.getArgsCount() > 0) {
            builder.clearArgs().addAllArgs(override.getArgsList());
        }
        if (override.getEnvCount() > 0) {
            Map<String, EnvVar> env = new LinkedHashMap<>();
            for (EnvVar envVar : builder.getEnvList()) {
                env.put(envVar.getName(), envVar);
            }
            for (EnvVar envVar : override.getEnvList()) {
                env.put(envVar.getName(), envVar);
            }
            builder.clearEnv().addAllEnv(env.values());
        }
        return builder.build();
    }

    /**
     * GCP condition durations are truncated to hundredths of a second without trailing zeros, e.g. {@code 9.84s},
     * {@code 15.5s}.
     */
    static String formatSeconds(java.time.Duration duration) {
        java.time.Duration nonNegative = duration.isNegative() ? java.time.Duration.ZERO : duration;
        BigDecimal seconds = BigDecimal.valueOf(nonNegative.toMillis())
                .movePointLeft(3)
                .setScale(2, RoundingMode.DOWN)
                .stripTrailingZeros();
        if (seconds.signum() == 0) {
            return "0s";
        }
        return seconds.toPlainString() + "s";
    }

    static Condition condition(String type, Condition.State state, String message, Timestamp time) {
        Condition.Builder builder = Condition.newBuilder()
                .setType(type)
                .setState(state);
        if (message != null) {
            builder.setMessage(message);
        }
        if (time != null) {
            builder.setLastTransitionTime(time);
        }
        return builder.build();
    }

    static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    static Instant instant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    static java.time.Duration duration(Duration duration) {
        return java.time.Duration.ofSeconds(duration.getSeconds(), duration.getNanos());
    }

    private static boolean isZero(Duration duration) {
        return duration.getSeconds() == 0 && duration.getNanos() == 0;
    }
}
