package io.floci.gcp.services.cloudrun;

import com.google.cloud.run.v2.Condition;
import com.google.cloud.run.v2.Instance;
import com.google.protobuf.Timestamp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Condition shapes, lifecycle phases and server-generated IDs for Cloud Run v2 Instances, as observed on GCP.
 */
final class CloudRunInstanceStates {

    static final String RUNNING_TYPE = "Running";
    static final String STARTING_MESSAGE = "Waiting for instance to start.";
    static final String STOPPING_MESSAGE = "Waiting for instance to be stopped.";
    static final String STOPPED_MESSAGE = "Instance stopped.";
    static final String DELETED_MESSAGE = "Instance completed for deletion.";
    static final String RESOURCES_AVAILABLE_MESSAGE = "Provisioned imported containers.";

    private static final String ID_LETTERS = "abcdefghijklmnopqrstuvwxyz";
    private static final String ID_CHARACTERS = ID_LETTERS + "0123456789";
    static final int GENERATED_ID_LENGTH = 15;

    enum Phase {
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED
    }

    private CloudRunInstanceStates() {}

    static Phase phase(Instance instance) {
        Condition terminal = instance.getTerminalCondition();
        return switch (terminal.getState()) {
            case CONDITION_SUCCEEDED -> Phase.RUNNING;
            case CONDITION_RECONCILING, CONDITION_PENDING -> STOPPING_MESSAGE.equals(terminal.getMessage())
                    ? Phase.STOPPING
                    : Phase.STARTING;
            case CONDITION_FAILED -> STOPPED_MESSAGE.equals(terminal.getMessage()) ? Phase.STOPPED : Phase.FAILED;
            default -> Phase.FAILED;
        };
    }

    /**
     * True when the last requested lifecycle transition asks for the instance to run, whether or not the
     * container is up yet. Start is refused and stop is accepted in exactly these phases.
     */
    static boolean desiredRunning(Instance instance) {
        Phase phase = phase(instance);
        return phase == Phase.RUNNING || phase == Phase.STARTING;
    }

    static Condition starting(Timestamp now) {
        return terminal(Condition.State.CONDITION_RECONCILING, STARTING_MESSAGE, now);
    }

    static Condition running(Timestamp now, Duration startup) {
        return terminal(Condition.State.CONDITION_SUCCEEDED, "Started instance in " + seconds(startup) + ".", now);
    }

    static Condition stopping(Timestamp now) {
        return terminal(Condition.State.CONDITION_RECONCILING, STOPPING_MESSAGE, now);
    }

    static Condition stopped(Timestamp now) {
        return terminal(Condition.State.CONDITION_FAILED, STOPPED_MESSAGE, now);
    }

    static Condition deleted(Timestamp now) {
        return terminal(Condition.State.CONDITION_FAILED, DELETED_MESSAGE, now);
    }

    static Condition failed(Timestamp now, String message) {
        return terminal(Condition.State.CONDITION_FAILED, message, now);
    }

    static List<Condition> readyConditions(Timestamp now, Duration imageImport) {
        return List.of(
                Condition.newBuilder()
                        .setType("ContainerReady")
                        .setState(Condition.State.CONDITION_SUCCEEDED)
                        .setMessage("Imported container image in " + seconds(imageImport) + ".")
                        .setLastTransitionTime(now)
                        .build(),
                Condition.newBuilder()
                        .setType("ResourcesAvailable")
                        .setState(Condition.State.CONDITION_SUCCEEDED)
                        .setMessage(RESOURCES_AVAILABLE_MESSAGE)
                        .setLastTransitionTime(now)
                        .build());
    }

    /**
     * Formats a duration the way GCP condition messages do: seconds with at most two decimals and no
     * trailing zeros ({@code 14.39s}, {@code 1.1s}, {@code 0s}).
     */
    static String seconds(Duration duration) {
        BigDecimal value = BigDecimal.valueOf(Math.max(0, duration.toMillis()))
                .movePointLeft(3)
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return value.toPlainString() + "s";
    }

    /**
     * Server-generated instance ID: 15 lowercase alphanumerics starting with a letter (GCP sample:
     * {@code i7xwprgd6mtsf27}).
     */
    static String generateId(RandomGenerator random) {
        StringBuilder id = new StringBuilder(GENERATED_ID_LENGTH);
        id.append(ID_LETTERS.charAt(random.nextInt(ID_LETTERS.length())));
        for (int i = 1; i < GENERATED_ID_LENGTH; i++) {
            id.append(ID_CHARACTERS.charAt(random.nextInt(ID_CHARACTERS.length())));
        }
        return id.toString();
    }

    private static Condition terminal(Condition.State state, String message, Timestamp now) {
        return Condition.newBuilder()
                .setType(RUNNING_TYPE)
                .setState(state)
                .setMessage(message)
                .setLastTransitionTime(now)
                .build();
    }
}
