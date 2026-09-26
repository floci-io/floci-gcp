package io.floci.gcp.services.bigquery;

import io.floci.gcp.core.common.GcpException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BigQueryStorageReadTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "int_field > 5",
            "date_field = CAST('2014-9-27' as DATE)",
            "nullable_field is not NULL",
            "numeric_field BETWEEN 1.0 AND 5.0",
            "EXTRACT(YEAR FROM ts) = 2024 AND name IN ('a', 'b')",
            "name = 'select; -- (not a keyword)'",
            "`order` = \"it\\\"s\"",
            "name = '''hello ' SELECT world'''",
            "name = \"\"\"a \" UNION b\"\"\"",
            "name = r'''raw''' AND age = 7"
    })
    void predicatesPass(String restriction) {
        assertDoesNotThrow(() -> BigQueryStorageRead.checkRestrictionIsPredicate(restriction));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "age = 7 UNION SELECT * FROM `other.t`",
            "age = 7) UNION ALL (SELECT 1",
            "age IN (SELECT age FROM `other.t`)",
            "age = 7; DROP TABLE t",
            "age = 7 -- trailing",
            "age = 7 /* note */",
            "(age = 7",
            "name = 'unterminated",
            "name = '''unterminated' SELECT 1"
    })
    void anythingBeyondOnePredicateIsRejected(String restriction) {
        assertThrows(GcpException.class, () -> BigQueryStorageRead.checkRestrictionIsPredicate(restriction));
    }

    @Test
    void wordsContainingKeywordsAreNotKeywords() {
        assertDoesNotThrow(() -> BigQueryStorageRead.checkRestrictionIsPredicate("selected = true AND withdrawn = 0"));
    }

    private static int clock;

    private static void create(Map<String, Instant> sessions, String project, int count, int perProject,
                               int total) {
        for (int i = 0; i < count; i++) {
            sessions.put("projects/" + project + "/locations/us/sessions/s" + clock,
                    Instant.ofEpochSecond(clock++));
            BigQueryStorageRead.evict(sessions, Function.identity(), project, perProject, total);
        }
    }

    private static long held(Map<String, Instant> sessions, String project) {
        return sessions.keySet().stream().filter(k -> k.startsWith("projects/" + project + "/")).count();
    }

    @Test
    void aProjectPastItsBoundDropsOnlyItsOwnOldest() {
        Map<String, Instant> sessions = new LinkedHashMap<>();
        create(sessions, "quiet", 2, 3, 100);
        create(sessions, "busy", 10, 3, 100);
        assertEquals(2, held(sessions, "quiet"));
        assertEquals(3, held(sessions, "busy"));
        assertEquals(Instant.ofEpochSecond(clock - 3),
                sessions.entrySet().stream().filter(e -> e.getKey().contains("/busy/"))
                        .map(Map.Entry::getValue).min(Instant::compareTo).orElseThrow());
    }

    @Test
    void theOverallBoundDropsFromTheProjectHoldingTheMost() {
        Map<String, Instant> sessions = new LinkedHashMap<>();
        create(sessions, "quiet", 2, 4, 8);
        for (int p = 0; p < 3; p++) {
            create(sessions, "spread-" + p, 4, 4, 8);
        }
        assertEquals(8, sessions.size());
        assertEquals(2, held(sessions, "quiet"), "the newer, larger projects give up sessions first");
        for (int p = 0; p < 3; p++) {
            assertEquals(2, held(sessions, "spread-" + p));
        }
    }

    @Test
    void aTieFallsOnTheNewerProjectNotTheLongLivedOne() {
        Map<String, Instant> sessions = new LinkedHashMap<>();
        create(sessions, "quiet", 2, 4, 4);
        create(sessions, "newer", 2, 4, 4);
        create(sessions, "newest", 1, 4, 4);
        assertEquals(2, held(sessions, "quiet"));
        assertEquals(1, held(sessions, "newer"));
        assertEquals(1, held(sessions, "newest"));
    }
}
