package io.floci.gcp.services.scheduler;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerExpressionParserTest {

    @Test
    void nextCronFireIsStrictlyAfterFrom() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant next = SchedulerExpressionParser.nextCronFire("*/5 * * * *", from, "utc");
        assertEquals(Instant.parse("2026-01-01T00:05:00Z"), next);
    }

    @Test
    void nextCronFireHonoursTimeZone() {
        // "0 9 * * *" = 09:00 local; in America/New_York that is 14:00Z (standard time, Jan).
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant next = SchedulerExpressionParser.nextCronFire("0 9 * * *", from, "America/New_York");
        assertEquals(Instant.parse("2026-01-01T14:00:00Z"), next);
    }

    @Test
    void validateRejectsGarbage() {
        assertThrows(RuntimeException.class, () -> SchedulerExpressionParser.validate("not a cron"));
    }

    @Test
    void validateAcceptsStandardUnixCron() {
        assertDoesNotThrow(() -> SchedulerExpressionParser.validate("0 */2 * * *"));
    }
}
