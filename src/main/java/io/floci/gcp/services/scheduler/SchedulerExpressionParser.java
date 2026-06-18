package io.floci.gcp.services.scheduler;

import com.cronutils.model.CronType;
import com.cronutils.model.Cron;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Parses Cloud Scheduler {@code schedule} expressions and computes next fire times.
 *
 * Cloud Scheduler uses standard five-field unix-cron ({@code minute hour day-of-month
 * month day-of-week}), interpreted in the job's {@code time_zone} (default UTC, with the
 * proto-documented {@code "utc"} alias). The English-like schedule syntax (e.g.
 * "every 2 hours") is not yet supported.
 */
public final class SchedulerExpressionParser {

    private static final CronParser UNIX_PARSER =
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    private SchedulerExpressionParser() {}

    /**
     * Returns the next fire instant strictly after {@code from} for a unix-cron expression
     * evaluated in {@code timeZone} (default UTC).
     */
    public static Instant nextCronFire(String schedule, Instant from, String timeZone) {
        if (schedule == null || schedule.isBlank()) {
            throw new IllegalArgumentException("Schedule expression is empty");
        }
        Cron cron = UNIX_PARSER.parse(schedule.trim());
        cron.validate();
        ExecutionTime exec = ExecutionTime.forCron(cron);

        ZonedDateTime zdt = from.atZone(resolveZone(timeZone));
        return exec.nextExecution(zdt)
                .map(ZonedDateTime::toInstant)
                .orElseThrow(() -> new IllegalStateException(
                        "No next fire time for schedule: " + schedule));
    }

    /** Validates a unix-cron expression, throwing IllegalArgumentException if malformed. */
    public static void validate(String schedule) {
        if (schedule == null || schedule.isBlank()) {
            throw new IllegalArgumentException("Schedule expression is empty");
        }
        UNIX_PARSER.parse(schedule.trim()).validate();
    }

    private static ZoneId resolveZone(String timeZone) {
        if (timeZone == null || timeZone.isBlank() || timeZone.equalsIgnoreCase("utc")) {
            return ZoneOffset.UTC;
        }
        return ZoneId.of(timeZone);
    }
}
