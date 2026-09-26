package io.floci.gcp.services.bigquery;

import io.floci.gcp.core.common.GcpException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
}
