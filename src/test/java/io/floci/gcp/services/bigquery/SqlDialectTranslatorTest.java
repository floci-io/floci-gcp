package io.floci.gcp.services.bigquery;

import io.floci.gcp.core.common.GcpException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlDialectTranslatorTest {

    private static SqlDialectTranslator.Translation translate(String sql) {
        return SqlDialectTranslator.translate(sql, "test-project", null,
                SqlDialectTranslator.QueryParameters.none());
    }

    private static String sql(String sql) {
        return translate(sql).sql();
    }

    private static GcpException invalid(String sql) {
        GcpException e = assertThrows(GcpException.class, () -> translate(sql));
        assertEquals("invalidQuery", e.getReason());
        return e;
    }

    // ── Table references ─────────────────────────────────────────────────────

    @Test
    void backtickedProjectPathBecomesSchemaQualifiedTable() {
        SqlDialectTranslator.Translation t = translate("SELECT name FROM `test-project.ds.users`");
        assertEquals("SELECT name FROM \"ds\".\"users\"", t.sql());
        assertEquals(Set.of(new SqlDialectTranslator.TableRef("ds", "users")), t.tables());
    }

    @Test
    void datasetDotTableAndJoinsAreStaged() {
        SqlDialectTranslator.Translation t = translate(
                "SELECT u.name, o.total FROM ds.users AS u JOIN ds.orders o ON u.id = o.user_id");
        assertEquals("SELECT u.name, o.total FROM \"ds\".\"users\" AS \"u\" JOIN \"ds\".\"orders\" AS \"o\""
                + " ON u.id = o.user_id", t.sql());
        assertEquals(2, t.tables().size());
    }

    @Test
    void bareTableUsesDefaultDataset() {
        SqlDialectTranslator.Translation t = SqlDialectTranslator.translate("SELECT * FROM users",
                "test-project", "ds", SqlDialectTranslator.QueryParameters.none());
        assertEquals("SELECT * FROM \"ds\".\"users\"", t.sql());
    }

    @Test
    void bareTableWithoutDefaultDatasetIsRejected() {
        GcpException e = invalid("SELECT * FROM users");
        assertTrue(e.getMessage().contains("missing dataset while no default dataset is set"));
    }

    @Test
    void cteNamesAreNotTreatedAsTables() {
        SqlDialectTranslator.Translation t = translate(
                "WITH recent AS (SELECT * FROM ds.orders) SELECT COUNT(*) AS n FROM recent");
        assertEquals(Set.of(new SqlDialectTranslator.TableRef("ds", "orders")), t.tables());
        assertTrue(t.sql().endsWith("FROM \"recent\""), t.sql());
    }

    @Test
    void crossProjectReferenceIsRejected() {
        GcpException e = invalid("SELECT * FROM `other-project.ds.t`");
        assertTrue(e.getMessage().contains("Cross-project"));
    }

    @Test
    void subqueryInFromIsNotATable() {
        SqlDialectTranslator.Translation t = translate("SELECT x FROM (SELECT id AS x FROM ds.t) AS sub");
        assertEquals("SELECT x FROM (SELECT id AS x FROM \"ds\".\"t\") AS sub", t.sql());
    }

    @Test
    void unnestAliasNamesTheElement() {
        assertEquals("SELECT tag FROM \"ds\".\"t\", UNNEST(tags) AS \"_unnest_tag\"(\"tag\")",
                sql("SELECT tag FROM ds.t, UNNEST(tags) AS tag"));
    }

    @Test
    void unaliasedUnnestExpandsStructFields() {
        assertEquals("SELECT * FROM (SELECT UNNEST([1, 2], max_depth := 2))", sql("SELECT * FROM UNNEST([1, 2])"));
    }

    @Test
    void implicitArrayPathBecomesUnnest() {
        assertEquals("SELECT tag FROM \"ds\".\"t\" AS \"x\", UNNEST(\"x\".\"tags\") AS \"_unnest_tag\"(\"tag\")",
                sql("SELECT tag FROM ds.t AS x, x.tags AS tag"));
    }

    // ── Literals and identifiers ─────────────────────────────────────────────

    @Test
    void doubleQuotedAndTripleQuotedStringsBecomeSqlStrings() {
        assertEquals("SELECT 'it''s' AS a, 'x' AS b", sql("SELECT \"it's\" AS a, '''x''' AS b"));
    }

    @Test
    void escapesAreDecodedButRawStringsKeepBackslashes() {
        assertEquals("SELECT 'a\nb' AS a, 'a\\d' AS b", sql("SELECT 'a\\nb' AS a, r'a\\d' AS b"));
    }

    @Test
    void keywordsInsideStringsAndCommentsAreUntouched() {
        assertEquals("SELECT 'FROM ds.t' AS s", sql("SELECT 'FROM ds.t' AS s -- FROM other.t"));
    }

    @Test
    void typedLiteralsAreCast() {
        assertEquals("SELECT CAST('2024-01-02 03:04:05' AS TIMESTAMPTZ) AS ts, CAST('2024-01-02' AS DATE) AS d",
                sql("SELECT TIMESTAMP '2024-01-02 03:04:05' AS ts, DATE '2024-01-02' AS d"));
    }

    @Test
    void castTypesAreMapped() {
        assertEquals("SELECT CAST(x AS BIGINT) AS a, TRY_CAST(y AS VARCHAR) AS b, CAST(z AS DOUBLE[]) AS c",
                sql("SELECT CAST(x AS INT64) AS a, SAFE_CAST(y AS STRING) AS b, CAST(z AS ARRAY<FLOAT64>) AS c"));
    }

    @Test
    void columnsNamedLikeDuckDbKeywordsAreQuoted() {
        assertEquals("SELECT \"table\", t.\"primary\", \"check\" AS c, LEFT(s, 1) AS l FROM \"ds\".\"t\" AS \"t\"",
                sql("SELECT table, t.primary, check AS c, LEFT(s, 1) AS l FROM ds.t AS t"));
    }

    @Test
    void nestedGenericCastTypes() {
        assertEquals("SELECT CAST(x AS STRUCT(\"a\" BIGINT[])[]) AS c",
                sql("SELECT CAST(x AS ARRAY<STRUCT<a ARRAY<INT64>>>) AS c"));
    }

    // ── Column naming ────────────────────────────────────────────────────────

    @Test
    void anonymousColumnsAreNamedLikeBigQuery() {
        assertEquals("SELECT COUNT(*) AS f0_, name, 1 + 1 AS two, MAX(age) AS f1_ FROM \"ds\".\"t\"",
                sql("SELECT COUNT(*), name, 1 + 1 AS two, MAX(age) FROM ds.t"));
    }

    @Test
    void starAndImplicitAliasesKeepTheirNames() {
        assertEquals("SELECT * EXCLUDE (secret), UPPER(name) upper_name FROM \"ds\".\"t\"",
                sql("SELECT * EXCEPT (secret), UPPER(name) upper_name FROM ds.t"));
    }

    // ── Functions ────────────────────────────────────────────────────────────

    @Test
    void functionShimsRewriteToDuckDb() {
        assertEquals("SELECT (CASE WHEN (b) = 0 THEN NULL ELSE (a) / (b) END) AS r", sql("SELECT SAFE_DIVIDE(a, b) AS r"));
        assertEquals("SELECT count_if(x > 1) AS c", sql("SELECT COUNTIF(x > 1) AS c"));
        assertEquals("SELECT (CASE WHEN x THEN 1 ELSE 2 END) AS c", sql("SELECT IF(x, 1, 2) AS c"));
        assertEquals("SELECT date_diff('day', b, a) AS d", sql("SELECT DATE_DIFF(a, b, DAY) AS d"));
        assertEquals("SELECT CAST(date_trunc('month', d) AS DATE) AS m", sql("SELECT DATE_TRUNC(d, MONTH) AS m"));
        assertEquals("SELECT strftime(ts, '%Y') AS y", sql("SELECT FORMAT_TIMESTAMP('%Y', ts) AS y"));
        assertEquals("SELECT (dayofweek(d) + 1) AS w", sql("SELECT EXTRACT(DAYOFWEEK FROM d) AS w"));
        assertEquals("SELECT regexp_extract(s, 'a(b)', 1) AS x", sql("SELECT REGEXP_EXTRACT(s, r'a(b)') AS x"));
        assertEquals("SELECT regexp_replace(s, 'a', 'b', 'g') AS x", sql("SELECT REGEXP_REPLACE(s, 'a', 'b') AS x"));
        assertEquals("SELECT {'a': 1, 'b': 'x'} AS s", sql("SELECT STRUCT(1 AS a, 'x' AS b) AS s"));
        assertEquals("SELECT len(arr) AS n", sql("SELECT ARRAY_LENGTH(arr) AS n"));
    }

    @Test
    void unshimmedFunctionsPassThrough() {
        assertEquals("SELECT LOWER(name) AS l, COALESCE(a, b) AS c FROM \"ds\".\"t\"",
                sql("SELECT LOWER(name) AS l, COALESCE(a, b) AS c FROM ds.t"));
    }

    @Test
    void setOperatorDistinctIsDropped() {
        assertEquals("SELECT 1 AS a UNION SELECT 2", sql("SELECT 1 AS a UNION DISTINCT SELECT 2"));
    }

    // ── Parameters ───────────────────────────────────────────────────────────

    @Test
    void namedParametersAreInlinedAsTypedLiterals() {
        List<Map<String, Object>> params = List.of(
                param("name", "STRING", "O'Brien"),
                param("min_age", "INT64", "21"),
                param("since", "TIMESTAMP", "2024-01-02 03:04:05+00:00"));
        String out = SqlDialectTranslator.translate(
                "SELECT * FROM ds.t WHERE name = @name AND age >= @min_age AND ts > @since",
                "test-project", null, new SqlDialectTranslator.QueryParameters(params, "NAMED")).sql();
        assertEquals("SELECT * FROM \"ds\".\"t\" WHERE name = CAST('O''Brien' AS VARCHAR)"
                + " AND age >= CAST(21 AS BIGINT) AND ts > CAST('2024-01-02 03:04:05+00:00' AS TIMESTAMPTZ)", out);
    }

    @Test
    void positionalAndArrayParameters() {
        List<Map<String, Object>> params = List.of(Map.of(
                "parameterType", Map.of("type", "ARRAY", "arrayType", Map.of("type", "INT64")),
                "parameterValue", Map.of("arrayValues", List.of(Map.of("value", "1"), Map.of("value", "2")))));
        String out = SqlDialectTranslator.translate("SELECT * FROM ds.t WHERE id IN UNNEST(?)",
                "test-project", null, new SqlDialectTranslator.QueryParameters(params, "POSITIONAL")).sql();
        assertEquals("SELECT * FROM \"ds\".\"t\" WHERE id IN (SELECT UNNEST([CAST(1 AS BIGINT), CAST(2 AS BIGINT)]))", out);
    }

    @Test
    void malformedBoolParameterIsRejectedRatherThanCoercedToFalse() {
        String out = SqlDialectTranslator.translate("SELECT * FROM ds.t WHERE active = @flag",
                "test-project", null, new SqlDialectTranslator.QueryParameters(
                        List.of(param("flag", "BOOL", "TRUE")), "NAMED")).sql();
        assertEquals("SELECT * FROM \"ds\".\"t\" WHERE active = TRUE", out);

        // "yes" is not a BOOL literal. Coercing it to FALSE would silently invert the predicate,
        // so it fails the query the way a malformed INT64 already does.
        GcpException e = assertThrows(GcpException.class, () -> SqlDialectTranslator.translate(
                "SELECT * FROM ds.t WHERE active = @flag", "test-project", null,
                new SqlDialectTranslator.QueryParameters(List.of(param("flag", "BOOL", "yes")), "NAMED")));
        assertEquals("invalidQuery", e.getReason());
    }

    @Test
    void dropWithTrailingGarbageIsASyntaxErrorRatherThanADrop() {
        assertEquals("invalidQuery", assertThrows(GcpException.class, () -> SqlDialectTranslator.parseStatement(
                "DROP TABLE ds.t GARBAGE", "test-project", null)).getReason());
        assertEquals("invalidQuery", assertThrows(GcpException.class, () -> SqlDialectTranslator.parseStatement(
                "DROP VIEW ds.v EXTRA TOKENS", "test-project", null)).getReason());

        // The shapes that are real syntax still parse.
        assertEquals(SqlDialectTranslator.StatementKind.DROP_TABLE,
                SqlDialectTranslator.parseStatement("DROP TABLE ds.t", "test-project", null).kind());
        assertEquals(SqlDialectTranslator.StatementKind.DROP_TABLE,
                SqlDialectTranslator.parseStatement("DROP TABLE IF EXISTS ds.t", "test-project", null).kind());
        assertTrue(SqlDialectTranslator.parseStatement("DROP SCHEMA ds CASCADE", "test-project", null).cascade());
    }

    @Test
    void missingOrMalformedParametersAreRejected() {
        assertThrows(GcpException.class, () -> translate("SELECT @missing AS x"));
        GcpException e = assertThrows(GcpException.class, () -> SqlDialectTranslator.translate("SELECT @n AS x",
                "test-project", null, new SqlDialectTranslator.QueryParameters(
                        List.of(param("n", "INT64", "1; DROP TABLE x")), "NAMED")));
        assertEquals("invalidQuery", e.getReason());
    }

    // ── Unsupported statements ───────────────────────────────────────────────

    @Test
    void selectTranslationRejectsDmlAndScripts() {
        assertTrue(invalid("INSERT INTO ds.t (a) VALUES (1)").getMessage().contains("INSERT"));
        assertTrue(invalid("SELECT 1; SELECT 2").getMessage().contains("scripts"));
    }

    // ── DML / DDL statements ─────────────────────────────────────────────────

    private static SqlDialectTranslator.Statement statement(String sql) {
        return SqlDialectTranslator.parseStatement(sql, "test-project", "ds");
    }

    private static String dml(String sql) {
        return SqlDialectTranslator.translateDml(sql, "test-project", "ds",
                SqlDialectTranslator.QueryParameters.none()).sql();
    }

    @Test
    void classifiesDmlWithOptionalIntoAndFrom() {
        SqlDialectTranslator.Statement insert = statement("INSERT ds.t (a) VALUES (1)");
        assertEquals(SqlDialectTranslator.StatementKind.INSERT, insert.kind());
        assertEquals(new SqlDialectTranslator.TableRef("ds", "t"), insert.target());
        assertEquals("DELETE", statement("DELETE `test-project.ds.t` WHERE a = 1").statementType());
        assertEquals("MERGE", statement("MERGE t USING s ON t.id = s.id WHEN MATCHED THEN DELETE").statementType());
        assertEquals("TRUNCATE_TABLE", statement("TRUNCATE TABLE ds.t").statementType());
    }

    @Test
    void updateAndDeleteNeedAWhereClause() {
        GcpException e = assertThrows(GcpException.class, () -> statement("DELETE FROM ds.t"));
        assertEquals("invalidQuery", e.getReason());
        assertThrows(GcpException.class, () -> statement("UPDATE ds.t SET a = 1"));
    }

    @Test
    void dmlIsTranslatedWithExplicitIntoFromAndAliases() {
        assertEquals("INSERT INTO \"ds\".\"t\" (a, b) VALUES (1, 'x')", dml("INSERT ds.t (a, b) VALUES (1, \"x\")"));
        assertEquals("DELETE FROM \"ds\".\"t\" WHERE a = 1", dml("DELETE ds.t WHERE a = 1"));
        assertEquals("UPDATE \"ds\".\"t\" AS \"x\" SET a = 2 WHERE x.a = 1", dml("UPDATE ds.t x SET a = 2 WHERE x.a = 1"));
        assertEquals("MERGE INTO \"ds\".\"t\" AS \"t\" USING \"ds\".\"s\" AS \"s\" ON t.id = s.id"
                        + " WHEN MATCHED THEN DELETE RETURNING merge_action",
                dml("MERGE ds.t t USING ds.s s ON t.id = s.id WHEN MATCHED THEN DELETE"));
    }

    @Test
    void insertSelectStagesTheSourceTable() {
        SqlDialectTranslator.Translation t = SqlDialectTranslator.translateDml(
                "INSERT INTO ds.t SELECT * FROM ds.src WHERE x > 1", "test-project", "ds",
                SqlDialectTranslator.QueryParameters.none());
        assertEquals(Set.of(new SqlDialectTranslator.TableRef("ds", "t"), new SqlDialectTranslator.TableRef("ds", "src")),
                t.tables());
    }

    @Test
    void createTableColumnsBecomeABigQuerySchema() {
        SqlDialectTranslator.Statement create = statement("CREATE TABLE IF NOT EXISTS ds.t ("
                + "id INT64 NOT NULL OPTIONS(description = 'key'), amount BIGNUMERIC(40, 2), tags ARRAY<STRING>, "
                + "place STRUCT<city STRING, geo STRUCT<lat FLOAT64, lng FLOAT64>>, PRIMARY KEY (id) NOT ENFORCED)"
                + " PARTITION BY DATE(_PARTITIONTIME) OPTIONS(description = 'orders')");
        assertEquals("CREATE_TABLE", create.statementType());
        assertTrue(create.ifNotExists());
        List<io.floci.gcp.services.bigquery.model.TableFieldSchema> cols = create.columns();
        assertEquals(List.of("id", "amount", "tags", "place"), cols.stream().map(c -> c.getName()).toList());
        assertEquals("INTEGER", cols.get(0).getType());
        assertEquals("REQUIRED", cols.get(0).getMode());
        assertEquals("key", cols.get(0).getDescription());
        assertEquals("BIGNUMERIC", cols.get(1).getType());
        assertEquals("REPEATED", cols.get(2).getMode());
        assertEquals("RECORD", cols.get(3).getType());
        assertEquals("RECORD", cols.get(3).getFields().get(1).getType());
        assertEquals("FLOAT", cols.get(3).getFields().get(1).getFields().get(0).getType());
    }

    @Test
    void createTableAsSelectAndViewsKeepTheirQuery() {
        SqlDialectTranslator.Statement ctas = statement("CREATE OR REPLACE TABLE ds.t AS SELECT a FROM ds.src");
        assertEquals("CREATE_TABLE_AS_SELECT", ctas.statementType());
        assertTrue(ctas.orReplace());
        assertEquals("SELECT a FROM ds.src", ctas.querySql().trim());

        SqlDialectTranslator.Statement view = statement("CREATE VIEW ds.v OPTIONS(description='x') AS SELECT 1 AS a");
        assertEquals("CREATE_VIEW", view.statementType());
        assertEquals("SELECT 1 AS a", view.querySql());
        assertEquals("CREATE_MATERIALIZED_VIEW",
                statement("CREATE MATERIALIZED VIEW ds.mv AS SELECT 1 AS a").statementType());
    }

    @Test
    void dropAndSchemaStatements() {
        SqlDialectTranslator.Statement drop = statement("DROP TABLE IF EXISTS ds.t");
        assertEquals(SqlDialectTranslator.StatementKind.DROP_TABLE, drop.kind());
        assertTrue(drop.ifExists());
        assertEquals("DROP_VIEW", statement("DROP VIEW ds.v").statementType());
        SqlDialectTranslator.Statement schema = statement("DROP SCHEMA `test-project.old` CASCADE");
        assertEquals("old", schema.datasetTarget());
        assertTrue(schema.cascade());
        assertEquals("CREATE_SCHEMA", statement("CREATE SCHEMA IF NOT EXISTS fresh").statementType());
        assertThrows(GcpException.class, () -> statement("ALTER TABLE ds.t ADD COLUMN x INT64"));
    }

    @Test
    void trailingSemicolonIsAllowed() {
        assertEquals("SELECT 1 AS a", sql("SELECT 1 AS a;"));
    }

    @Test
    void statementTypeComesFromTheFirstKeyword() {
        assertEquals("SELECT", SqlDialectTranslator.statementType("  WITH x AS (SELECT 1) SELECT * FROM x"));
        assertEquals("SELECT", SqlDialectTranslator.statementType("(SELECT 1)"));
        assertEquals("DELETE", SqlDialectTranslator.statementType("DELETE FROM ds.t WHERE true"));
    }

    private static Map<String, Object> param(String name, String type, String value) {
        return Map.of("name", name, "parameterType", Map.of("type", type), "parameterValue", Map.of("value", value));
    }
}
