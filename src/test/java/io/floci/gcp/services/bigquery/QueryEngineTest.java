package io.floci.gcp.services.bigquery;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;
import io.floci.gcp.services.bigquery.model.TableSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QueryEngineTest {

    private static final TableSchema SCHEMA = new TableSchema(List.of(
            field("name", "STRING", "NULLABLE"),
            field("age", "INTEGER", "NULLABLE"),
            field("score", "FLOAT", "NULLABLE"),
            field("active", "BOOLEAN", "NULLABLE"),
            field("tags", "STRING", "REPEATED")));

    private static final List<Map<String, Object>> ROWS = List.of(
            row("alice", 30L, 9.5, true, List.of("a")),
            row("bob", 25L, 7.0, false, List.of()),
            row("carol", 30L, 8.25, true, List.of("b", "c")));

    // ── Parsing: supported grammar ──

    @Test
    void parsesSelectStarWithTableRefVariants() {
        assertEquals(new QueryEngine.TableRef(null, null, "t"),
                QueryEngine.parse("SELECT * FROM t").table());
        assertEquals(new QueryEngine.TableRef(null, "d", "t"),
                QueryEngine.parse("select * from d.t;").table());
        assertEquals(new QueryEngine.TableRef("p", "d", "t"),
                QueryEngine.parse("SELECT * FROM p.d.t").table());
        assertEquals(new QueryEngine.TableRef("p", "d", "t"),
                QueryEngine.parse("SELECT * FROM `p.d.t`").table());
        assertEquals(new QueryEngine.TableRef("p", "d", "t"),
                QueryEngine.parse("SELECT * FROM `p`.`d`.`t`").table());
    }

    @Test
    void parsesColumnsCountWhereAndLimit() {
        QueryEngine.ParsedQuery columns = QueryEngine.parse("SELECT name, age FROM d.t");
        assertEquals(QueryEngine.Kind.COLUMNS, columns.kind());
        assertEquals(List.of("name", "age"), columns.columns());

        QueryEngine.ParsedQuery count = QueryEngine.parse("SELECT COUNT(*) FROM d.t");
        assertEquals(QueryEngine.Kind.COUNT_STAR, count.kind());

        QueryEngine.ParsedQuery filtered = QueryEngine.parse(
                "SELECT * FROM d.t WHERE name = 'alice' AND age = 30 AND active = TRUE LIMIT 5");
        assertEquals(3, filtered.conditions().size());
        assertEquals("alice", filtered.conditions().get(0).literal());
        assertEquals(30L, filtered.conditions().get(1).literal());
        assertEquals(Boolean.TRUE, filtered.conditions().get(2).literal());
        assertEquals(5, filtered.limit());
    }

    @Test
    void parsesLiteralShapes() {
        assertEquals(-3L, QueryEngine.parse("SELECT * FROM t WHERE a = -3").conditions().get(0).literal());
        assertEquals(2.5, QueryEngine.parse("SELECT * FROM t WHERE a = 2.5").conditions().get(0).literal());
        assertEquals("it's", QueryEngine.parse("SELECT * FROM t WHERE a = 'it\\'s'").conditions().get(0).literal());
        assertEquals("x", QueryEngine.parse("SELECT * FROM t WHERE a = \"x\"").conditions().get(0).literal());
    }

    // ── Parsing: rejections with invalidQuery ──

    @Test
    void rejectsUnsupportedConstructs() {
        for (String sql : List.of(
                "SELECT * FROM a JOIN b ON a.id = b.id",
                "SELECT * FROM t GROUP BY name",
                "SELECT * FROM t ORDER BY name",
                "SELECT * FROM t WHERE a = 1 OR b = 2",
                "SELECT * FROM t WHERE a != 1",
                "SELECT * FROM t WHERE a < 1",
                "SELECT MAX(age) FROM t",
                "SELECT name AS n FROM t",
                "SELECT * FROM (SELECT * FROM t)",
                "SELECT * FROM t WHERE a = NULL",
                "INSERT INTO t VALUES (1)",
                "DELETE FROM t",
                "")) {
            GcpException ex = assertThrows(GcpException.class, () -> QueryEngine.parse(sql), sql);
            assertEquals("INVALID_ARGUMENT", ex.getGcpStatus(), sql);
            if (!sql.isEmpty()) {
                assertEquals("invalidQuery", ex.getReason(), sql);
            }
        }
    }

    // ── Evaluation ──

    @Test
    void evaluatesTypedEqualityAcrossTypes() {
        assertEquals(2, evaluate("SELECT * FROM t WHERE age = 30").rows().size());
        assertEquals(1, evaluate("SELECT * FROM t WHERE score = 7.0").rows().size());
        assertEquals(2, evaluate("SELECT * FROM t WHERE active = TRUE").rows().size());
        assertEquals(1, evaluate("SELECT * FROM t WHERE name = 'bob'").rows().size());
        assertEquals(1, evaluate("SELECT * FROM t WHERE age = 30 AND name = 'carol'").rows().size());
        assertEquals(0, evaluate("SELECT * FROM t WHERE age = 99").rows().size());
    }

    @Test
    void integerColumnAcceptsIntegerLiteralOnly() {
        GcpException ex = assertThrows(GcpException.class,
                () -> evaluate("SELECT * FROM t WHERE age = 'thirty'"));
        assertEquals("invalidQuery", ex.getReason());
        assertTrue(ex.getMessage().contains("No matching signature"));
    }

    @Test
    void unknownColumnRejected() {
        GcpException ex = assertThrows(GcpException.class,
                () -> evaluate("SELECT bogus FROM t"));
        assertTrue(ex.getMessage().contains("Unrecognized name: bogus"));
    }

    @Test
    void filteringOnRepeatedColumnRejected() {
        GcpException ex = assertThrows(GcpException.class,
                () -> evaluate("SELECT * FROM t WHERE tags = 'a'"));
        assertEquals("invalidQuery", ex.getReason());
    }

    @Test
    void projectionIsCaseInsensitiveAndOrdered() {
        QueryEngine.Result result = evaluate("SELECT AGE, Name FROM t LIMIT 2");
        assertEquals(List.of("age", "name"),
                result.schema().getFields().stream().map(TableFieldSchema::getName).toList());
        assertEquals(2, result.rows().size());
        assertEquals(30L, result.rows().get(0).get("age"));
    }

    @Test
    void countStarCountsFilteredRows() {
        QueryEngine.Result result = evaluate("SELECT COUNT(*) FROM t WHERE active = TRUE");
        assertEquals("f0_", result.schema().getFields().get(0).getName());
        assertEquals(2L, result.rows().get(0).get("f0_"));
    }

    @Test
    void countStarRespectsLimitZero() {
        QueryEngine.Result result = evaluate("SELECT COUNT(*) FROM t LIMIT 0");
        assertEquals(0, result.rows().size());
    }

    @Test
    void nullStoredValuesNeverMatch() {
        List<Map<String, Object>> rows = List.of(row(null, null, null, null, null));
        QueryEngine.ParsedQuery query = QueryEngine.parse("SELECT * FROM t WHERE age = 1");
        assertEquals(0, QueryEngine.evaluate(query, SCHEMA, rows).rows().size());
    }

    // ── Helpers ──

    private static QueryEngine.Result evaluate(String sql) {
        return QueryEngine.evaluate(QueryEngine.parse(sql), SCHEMA, ROWS);
    }

    private static TableFieldSchema field(String name, String type, String mode) {
        TableFieldSchema f = new TableFieldSchema();
        f.setName(name);
        f.setType(type);
        f.setMode(mode);
        return f;
    }

    private static Map<String, Object> row(String name, Long age, Double score, Boolean active,
                                           List<String> tags) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("age", age);
        row.put("score", score);
        row.put("active", active);
        row.put("tags", tags);
        return row;
    }
}
