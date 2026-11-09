package io.floci.gcp.services.bigquery;

import org.junit.jupiter.api.Test;

class TempTranslateDumpTest {
    @Test
    void dump() {
        for (String q : new String[]{
                "SELECT t.s.* FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t GROUP BY ALL",
                "SELECT t.s.*, 5 AS b FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t GROUP BY ALL",
                "SELECT (ARRAY_AGG(t))[OFFSET(0)].* EXCEPT(id) FROM (SELECT 1 AS id, 'x' AS name UNION ALL SELECT 2, 'y') t"}) {
            System.out.println("### " + q);
            try {
                System.out.println("SQL: " + SqlDialectTranslator.translate(q, "test-project", null,
                        SqlDialectTranslator.QueryParameters.none()).sql());
            } catch (Exception e) {
                System.out.println("ERR: " + e.getMessage());
            }
        }
    }
}
