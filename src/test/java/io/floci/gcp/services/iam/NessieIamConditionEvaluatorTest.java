package io.floci.gcp.services.iam;

import io.floci.gcp.core.common.GcpException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NessieIamConditionEvaluatorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-31T23:59:59Z"), ZoneOffset.UTC);
    private static final IamResource REPORT = IamResource.gcsObject("example", "reports/july.csv");

    @Test
    void evaluatesAllowedProfileExpression() {
        NessieIamConditionEvaluator evaluator = new NessieIamConditionEvaluator(CLOCK, 4);
        IamCondition condition = condition("resource.service == 'storage.googleapis.com'"
                + " && resource.type == 'storage.googleapis.com/Object'"
                + " && resource.name.startsWith('projects/_/buckets/example/objects/reports/')"
                + " && request.time < timestamp('2026-08-01T00:00:00Z')");

        assertTrue(evaluator.matches(condition, REPORT));
        assertFalse(evaluator.matches(condition, IamResource.gcsObject("example", "private/july.csv")));
        assertFalse(evaluator.matches(condition, IamResource.gcsBucket("example")));
    }

    @Test
    void supportsBooleanEqualityInequalityAndEndsWith() {
        NessieIamConditionEvaluator evaluator = new NessieIamConditionEvaluator(CLOCK, 4);
        IamCondition condition = condition("resource.name.endsWith('.csv')"
                + " && resource.service == 'storage.googleapis.com'"
                + " && resource.type != 'storage.googleapis.com/Bucket'"
                + " && !(resource.name.endsWith('.tmp')"
                + " || resource.name == 'projects/_/buckets/example/objects/blocked.csv')");

        assertTrue(evaluator.matches(condition, REPORT));
        assertFalse(evaluator.matches(condition, IamResource.gcsObject("example", "reports/july.tmp")));
        assertFalse(evaluator.matches(condition, IamResource.gcsObject("example", "blocked.csv")));
    }

    @Test
    void rejectsUnsupportedProfileFeatures() {
        NessieIamConditionEvaluator evaluator = new NessieIamConditionEvaluator(CLOCK, 4);

        for (String expression : List.of(
                "resource.name.matches('.*')",
                "resource.name.extract('projects/{project}/') == 'test-project'",
                "[1, 2].exists(x, x == 1)",
                "request.path == '/admin'",
                "unknownIdentifier == 'value'")) {
            GcpException exception = assertThrows(GcpException.class, () -> evaluator.validate(condition(expression)));
            assertEquals("INVALID_ARGUMENT", exception.getGcpStatus());
        }
    }

    @Test
    void failsClosedWhenTimestampConversionCannotProduceATimestamp() {
        NessieIamConditionEvaluator evaluator = new NessieIamConditionEvaluator(CLOCK, 4);

        assertFalse(evaluator.matches(condition("request.time < timestamp('not-a-timestamp')"), REPORT));
    }

    @Test
    void cachesOnlySuccessfulProgramsAndEvictsLeastRecentlyUsedEntry() {
        NessieIamConditionEvaluator evaluator = new NessieIamConditionEvaluator(CLOCK, 2);
        IamCondition first = condition("resource.name.endsWith('.csv')");
        IamCondition second = condition("resource.name.startsWith('projects/_/buckets/example/')");
        IamCondition third = condition("request.time < timestamp('2027-01-01T00:00:00Z')");

        evaluator.validate(first);
        evaluator.validate(second);
        evaluator.validate(first);
        evaluator.validate(third);

        assertEquals(2, evaluator.cachedProgramCount());
        assertFalse(evaluator.matches(condition("resource.name.matches('.*')"), REPORT));
        assertEquals(2, evaluator.cachedProgramCount());
    }

    private static IamCondition condition(String expression) {
        return new IamCondition("test", expression, null);
    }
}
