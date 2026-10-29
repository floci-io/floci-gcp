package io.floci.gcp.services.bigquery;

import io.floci.gcp.core.common.GcpException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlDialectTranslatorTest {

    @Test
    void indexedStructWildcardExpandsAsColumns() {
        String translated = sql("SELECT ARRAY_AGG(STRUCT(1 AS a, 2 AS b) LIMIT 1)[OFFSET(0)].*");

        assertTrue(translated.contains("unnest([(list_transform([struct_pack(a := "), translated);
        assertTrue(translated.contains("max_depth := 2"), translated);
        assertEquals(translated.indexOf("ARRAY_AGG"), translated.lastIndexOf("ARRAY_AGG"), translated);
        assertTrue(translated.contains("is out of bounds"), translated);
    }

    @Test
    void indexedStructWildcardPreservesNestedFields() {
        String translated = sql("SELECT [STRUCT(STRUCT(1 AS x) AS child, 2 AS y)][OFFSET(0)].*");

        assertTrue(translated.contains("max_depth := 2"), translated);
        assertFalse(translated.contains("recursive := true"), translated);
    }

    @Test
    void selectAsStructRewritesIndexedStructWildcardsBeforePackingTheRecord() {
        String translated = sql("SELECT (SELECT AS STRUCT [STRUCT(1 AS x, 2 AS y)][OFFSET(0)].*) AS r");

        assertTrue(translated.contains("struct_pack(*COLUMNS(*)) FROM (SELECT unnest(["), translated);
        assertTrue(translated.contains("max_depth := 2"), translated);
        assertFalse(translated.contains(").*"), translated);
    }

    private static SqlDialectTranslator.Translation translate(String sql) {
        return SqlDialectTranslator.translate(sql, "test-project", null,
                SqlDialectTranslator.QueryParameters.none());
    }

    private static String sql(String sql) {
        return translate(sql).sql();
    }

    private static String sql(String sql, Map<SqlDialectTranslator.TableRef, Set<String>> columns) {
        return SqlDialectTranslator.translate(sql, "test-project", null,
                SqlDialectTranslator.QueryParameters.none(), columns::get).sql();
    }

    private static GcpException invalid(String sql) {
        GcpException e = assertThrows(GcpException.class, () -> translate(sql));
        assertEquals("invalidQuery", e.getReason());
        return e;
    }

    @Test
    void createTableCopyCapturesItsSource() {
        SqlDialectTranslator.Statement statement = SqlDialectTranslator.parseStatement(
                "CREATE OR REPLACE TABLE ds.destination COPY ds.source", "test-project", null);

        assertEquals("CREATE_TABLE_COPY", statement.statementType());
        assertEquals(new SqlDialectTranslator.TableRef("ds", "destination"), statement.target());
        assertEquals(new SqlDialectTranslator.TableRef("ds", "source"), statement.copySource());
    }

    @Test
    void createTableCopyParsesTrailingOptions() {
        SqlDialectTranslator.Statement statement = SqlDialectTranslator.parseStatement(
                "CREATE TABLE ds.destination COPY `test-project.ds.source` OPTIONS("
                        + "description='copy, not source', labels=[('team', 'analytics')],"
                        + " expiration_timestamp=TIMESTAMP '2099-01-01 00:00:00 UTC');",
                "test-project", null);

        assertEquals(new SqlDialectTranslator.TableRef("ds", "source"), statement.copySource());
        assertEquals("copy, not source", statement.options().get("description"));
        assertEquals(Map.of("team", "analytics"), statement.options().get("labels"));
        assertTrue(statement.options().get("expiration_timestamp") instanceof Long);
    }

    @Test
    void createTableCopyRejectsMalformedOptionsAndTrailingTokens() {
        for (String suffix : List.of("OPTIONS", "OPTIONS description='x'", "OPTIONS(description=)",
                "OPTIONS(description='x') GARBAGE", "OPTIONS(description=1)", "GARBAGE")) {
            GcpException error = assertThrows(GcpException.class, () -> SqlDialectTranslator.parseStatement(
                    "CREATE TABLE ds.destination COPY ds.source " + suffix, "test-project", null));
            assertEquals("invalidQuery", error.getReason());
        }
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
        assertEquals("SELECT * NOT SIMILAR TO '^(_PARTITIONDATE|_PARTITIONTIME|_TABLE_SUFFIX)$' FROM \"ds\".\"users\"",
                t.sql());
    }

    @Test
    void bareTableWithoutDefaultDatasetIsRejected() {
        GcpException e = invalid("SELECT * FROM users");
        assertTrue(e.getMessage().contains("missing dataset while no default dataset is set"));
    }

    @Test
    void terminalWildcardTableIsPreservedForStaging() {
        SqlDialectTranslator.Translation t = translate(
                "SELECT _TABLE_SUFFIX FROM `test-project.ds.events_*` WHERE _TABLE_SUFFIX LIKE '2026%'");
        assertEquals("SELECT _TABLE_SUFFIX FROM \"ds\".\"events_*\""
                        + " WHERE _TABLE_SUFFIX LIKE '2026%'", t.sql());
        assertEquals(Set.of(new SqlDialectTranslator.TableRef("ds", "events_*")), t.tables());
    }

    @Test
    void nonTerminalWildcardIsRejected() {
        GcpException e = invalid("SELECT * FROM `test-project.ds.events_*_archive`");
        assertTrue(e.getMessage().contains("terminal '*'"));
    }

    @Test
    void translatesTypedEmptyArrayLiteralAndGenerateUuid() {
        assertEquals("SELECT CAST([] AS STRUCT(\"key\" VARCHAR, \"value\" VARCHAR)[]) AS metadata, uuid() AS id",
                sql("SELECT ARRAY<STRUCT<key STRING, value STRING>>[] AS metadata, GENERATE_UUID() AS id"));
    }

    @Test
    void translatesNonEmptyTypedStructArrayLiteral() {
        String translated = sql("""
                SELECT ARRAY<STRUCT<name STRING, value STRING>>[
                  STRUCT('source' AS name, 'origin' AS value)
                ] AS metadata""");

        assertFalse(translated.contains("ARRAY<"), translated);
        assertTrue(translated.contains("'source'"), translated);
    }

    @Test
    void translatesIndexedTypedArrayLiteralAsOneCastExpression() {
        String translated = sql("SELECT ARRAY<INT64>[1, 2][OFFSET(0)] AS value");

        assertFalse(translated.contains("ARRAY<"), translated);
        assertFalse(translated.contains("OFFSET("), translated);
        assertTrue(translated.contains("CAST([1, 2] AS BIGINT[])"), translated);
    }

    @Test
    void arrayStructElementsInheritFieldNamesFromFirstElement() {
        assertEquals("SELECT [{'metric_name': 'first', 'metric_value': 1}, "
                        + "{'metric_name': 'second', 'metric_value': 2}] AS metrics",
                sql("SELECT [STRUCT('first' AS metric_name, 1 AS metric_value), "
                        + "STRUCT('second', 2)] AS metrics"));
    }

    @Test
    void nestedArrayStructsKeepTheirOwnFieldNames() {
        assertEquals("SELECT [{'child': {'x': 1}}, {'child': {'x': 2}}] AS records",
                sql("SELECT [STRUCT(STRUCT(1 AS x) AS child), "
                        + "STRUCT(STRUCT(2 AS x) AS child)] AS records"));
    }

    @Test
    void retainsDeclaredNamesForPositionalTypedStructArrayElements() {
        assertEquals("SELECT CAST([(item_category, owner_id)] AS STRUCT(\"category\" VARCHAR, \"owner_id\" BIGINT)[]) AS records",
                sql("SELECT ARRAY<STRUCT<category STRING, owner_id INT64>>[(item_category, owner_id)] AS records"));
    }

    @Test
    void translatesSelectAsStruct() {
        assertEquals("SELECT (SELECT {'id': id, 'name': name} FROM \"ds\".\"users\") AS s",
                sql("SELECT (SELECT AS STRUCT id, name FROM ds.users) AS s"));
        assertEquals("SELECT (SELECT {'id': id, 'display_name': name} FROM \"ds\".\"users\") AS s",
                sql("SELECT (SELECT AS STRUCT id, name AS display_name FROM ds.users) AS s"));
        assertEquals("SELECT (SELECT struct_pack(*COLUMNS(input.* EXCLUDE (\"id\")))"
                        + " FROM \"ds\".\"users\" AS \"input\") AS s",
                sql("SELECT (SELECT AS STRUCT input.* EXCEPT(id) FROM ds.users AS input) AS s"));
        assertEquals("SELECT {'_field_1': index * 25} AS value",
                sql("SELECT STRUCT(index * 25) AS value"));
    }

    @Test
    void mixedStructStarsContributeSiblingFields() {
        String translated = sql("SELECT (SELECT AS STRUCT t.*, 2 AS y FROM (SELECT 1 AS x) t) AS r");
        assertTrue(translated.contains("FROM (SELECT  t.*, 2 AS y"), translated);
        assertFalse(translated.contains("_field_1"), translated);
        String twoStars = sql("SELECT (SELECT AS STRUCT t.*, u.* FROM (SELECT 1 AS x) t"
                + " CROSS JOIN (SELECT 2 AS y) u) AS r");
        assertTrue(twoStars.contains("FROM (SELECT  t.*, u.*"), twoStars);
    }

    @Test
    void selectAsStructKeepsAliasesVisibleToLaterClauses() {
        String translated = sql("SELECT ARRAY(SELECT AS STRUCT COUNT(*) AS n FROM UNNEST([1, 2]) x"
                + " HAVING n > 1 ORDER BY n) AS a");

        assertTrue(translated.contains("FROM (SELECT  COUNT(*) AS n FROM"), translated);
        assertTrue(translated.contains("HAVING n > 1 ORDER BY n) AS _floci_struct_row"), translated);
        assertTrue(translated.contains("SELECT struct_pack(*COLUMNS(_floci_struct_row.*))"), translated);

        String anonymous = sql("SELECT ARRAY(SELECT AS STRUCT x + 1 FROM UNNEST([1]) x ORDER BY 1) AS a");
        assertTrue(anonymous.contains("(SELECT  x + 1 AS \"_field_1\" FROM"), anonymous);
        assertTrue(anonymous.contains("ORDER BY 1) AS _floci_struct_row"), anonymous);
    }

    @Test
    void mixedStructStarsKeepNestedQueriesVisibleToLaterRewrites() {
        String aliases = sql("SELECT AS STRUCT t.*, 2 AS y FROM (SELECT 1 sample) t");
        assertTrue(aliases.contains("SELECT 1 AS \"sample\""), aliases);

        String pivot = sql("SELECT AS STRUCT t.*, 2 AS y FROM (SELECT * FROM"
                + " (SELECT 'sample' AS metric, 1 AS amount)"
                + " PIVOT(ANY_VALUE(amount) FOR metric IN ('sample'))) t");
        assertFalse(pivot.contains("PIVOT("), pivot);
        assertTrue(pivot.contains("PIVOT "), pivot);

        String having = sql("SELECT AS STRUCT t.*, 2 AS y FROM"
                + " (SELECT STRUCT(MAX(x) AS n) AS sample FROM UNNEST([1]) x HAVING sample.n = 1) t");
        assertTrue(having.contains("HAVING (MAX(x)) = 1"), having);
    }

    @Test
    void quotedDerivedTableAliasDoesNotReceiveAnotherAlias() {
        assertEquals("SELECT s.x FROM (SELECT 1 AS x) \"s\"",
                sql("SELECT s.x FROM (SELECT 1 AS x) `s`"));
        assertEquals("SELECT \"select\".x FROM (SELECT 1 AS x) \"select\"",
                sql("SELECT `select`.x FROM (SELECT 1 AS x) `select`"));
    }

    @Test
    void outermostSelectAsStructReturnsItsFieldsAsColumns() {
        assertEquals("SELECT 1 AS a, 2 AS b", sql("SELECT AS STRUCT 1 AS a, 2 AS b"));
        assertEquals("SELECT 1 AS \"_field_1\"", sql("SELECT AS STRUCT 1"));
        assertEquals(sql("SELECT t.* FROM (SELECT 1 AS a) t"), sql("SELECT AS STRUCT t.* FROM (SELECT 1 AS a) t"));
        assertEquals(sql("SELECT [STRUCT(1 AS a, 2 AS b)][OFFSET(0)].*"),
                sql("SELECT AS STRUCT [STRUCT(1 AS a, 2 AS b)][OFFSET(0)].*"));
        assertEquals("SELECT 1 AS a UNION ALL SELECT 2 AS a",
                sql("SELECT AS STRUCT 1 AS a UNION ALL SELECT AS STRUCT 2 AS a"));
        assertEquals("(SELECT 1 AS a)", sql("(SELECT AS STRUCT 1 AS a)"));
        assertEquals("SELECT 1 AS a, 2 AS b UNION ALL (SELECT 3 AS a, 4 AS b)",
                sql("SELECT AS STRUCT 1 AS a, 2 AS b UNION ALL (SELECT AS STRUCT 3 AS a, 4 AS b)"));
        assertEquals("(SELECT 1 AS a) UNION ((SELECT 2 AS a))",
                sql("(SELECT AS STRUCT 1 AS a) UNION DISTINCT ((SELECT AS STRUCT 2 AS a))"));
        assertTrue(sql("SELECT DISTINCT (SELECT AS STRUCT 1 AS a) AS s").contains("{'a': 1}"));
        assertTrue(sql("SELECT x FROM UNNEST([1]) x WHERE x IN ((SELECT AS STRUCT 1 AS a).a)").contains("{'a': 1}"));

        String nested = sql("SELECT ARRAY(SELECT AS STRUCT x AS a FROM UNNEST([1]) AS x) AS arr");
        assertTrue(nested.contains("SELECT {'a': x} FROM"), nested);
        assertFalse(nested.contains("f0_"), nested);
        assertEquals("SELECT (SELECT {'x': 1}) AS s", sql("SELECT (SELECT AS STRUCT 1 AS x) AS s"));
    }

    @Test
    void selectDistinctAsStructKeepsItsRowModifier() {
        assertTrue(sql("SELECT ARRAY(SELECT DISTINCT AS STRUCT x FROM UNNEST([1, 1]) x) AS a")
                .contains("SELECT DISTINCT {'x': x} FROM UNNEST([1, 1])"));
        assertTrue(sql("SELECT ARRAY(SELECT ALL AS STRUCT x FROM UNNEST([1]) x) AS a")
                .contains("SELECT ALL {'x': x} FROM"));
        assertEquals("SELECT DISTINCT x FROM UNNEST([1, 1]) AS \"_unnest_x\"(\"x\")",
                sql("SELECT DISTINCT AS STRUCT x FROM UNNEST([1, 1]) x"));
    }

    @Test
    void selectAsStructTreatsComparisonOperatorsAsExpressions() {
        assertEquals("SELECT (SELECT {'small': id < 10} FROM \"ds\".\"events\") AS s",
                sql("SELECT (SELECT AS STRUCT id < 10 AS small FROM ds.events) AS s"));
        assertEquals("SELECT (SELECT {'small': id < 10, 'large': id > 20} FROM \"ds\".\"events\") AS s",
                sql("SELECT (SELECT AS STRUCT id < 10 AS small, id > 20 AS large FROM ds.events) AS s"));
    }

    @Test
    void cteNamesAreNotTreatedAsTables() {
        SqlDialectTranslator.Translation t = translate(
                "WITH recent AS (SELECT * FROM ds.orders) SELECT COUNT(*) AS n FROM recent");
        assertEquals(Set.of(new SqlDialectTranslator.TableRef("ds", "orders")), t.tables());
        assertTrue(t.sql().endsWith("FROM \"recent\""), t.sql());
    }

    @Test
    void unpivotSyntaxIsPreserved() {
        assertEquals("SELECT id, metric, value FROM \"ds\".\"metrics\""
                        + " UNPIVOT INCLUDE NULLS (value FOR metric IN (a, b))",
                sql("SELECT id, metric, value FROM ds.metrics UNPIVOT INCLUDE NULLS (value FOR metric IN (a, b))"));
    }

    @Test
    void nestedCtesInResultWrapperAreNotTreatedAsTables() {
        SqlDialectTranslator.Translation t = translate("""
                WITH INPUT AS (
                  WITH new_aggregated AS (SELECT 1 AS id),
                       nested_results AS (SELECT * FROM new_aggregated)
                  SELECT * FROM nested_results
                )
                SELECT TO_JSON_STRING(row) FROM INPUT AS row
                """);

        assertEquals(Set.of(), t.tables());
        assertTrue(t.sql().contains("FROM \"new_aggregated\""), t.sql());
        assertTrue(t.sql().contains("FROM \"nested_results\""), t.sql());
        assertTrue(t.sql().contains("FROM \"INPUT\" AS \"row\""), t.sql());
    }

    @Test
    void nestedCteAfterOuterCteCommaIsVisibleWithinItsContainingCte() {
        SqlDialectTranslator.Translation t = translate("""
                WITH INPUT AS (
                  WITH prev AS (SELECT 1 AS id),
                       `new` AS (
                         WITH new_aggregated AS (
                           SELECT (SELECT id FROM UNNEST(aliases) WHERE type IN ('idfa') LIMIT 1) id_value
                           FROM `test-project.ds.batch_id_events`
                         )
                         SELECT id_value FROM new_aggregated
                       ),
                       unified AS (SELECT * FROM prev UNION ALL SELECT * FROM `new`)
                  SELECT * FROM unified
                )
                SELECT TO_JSON_STRING(row) FROM INPUT AS row
                """);

        assertEquals(Set.of(new SqlDialectTranslator.TableRef("ds", "batch_id_events")), t.tables());
        assertTrue(t.sql().contains("FROM \"new_aggregated\""), t.sql());
    }

    @Test
    void nestedCtesDoNotHidePhysicalTablesOutsideTheirScope() {
        SqlDialectTranslator.Translation t = SqlDialectTranslator.translate("""
                WITH INPUT AS (
                  WITH users AS (SELECT 1 AS id)
                  SELECT * FROM users
                )
                SELECT * FROM users
                """, "test-project", "ds", SqlDialectTranslator.QueryParameters.none());

        assertEquals(Set.of(new SqlDialectTranslator.TableRef("ds", "users")), t.tables());
        assertTrue(t.sql().endsWith("FROM \"ds\".\"users\""), t.sql());
    }

    @Test
    void crossProjectReferenceIsRejected() {
        GcpException e = invalid("SELECT * FROM `other-project.ds.t`");
        assertTrue(e.getMessage().contains("Cross-project"));
    }

    private static SqlDialectTranslator.Translation translateInJob(String sql) {
        return SqlDialectTranslator.translate(sql, "data-project", "job-project", "ds",
                SqlDialectTranslator.QueryParameters.none(), ref -> null);
    }

    @Test
    void unqualifiedTableResolvesAgainstTheDefaultDatasetProject() {
        SqlDialectTranslator.Translation t = translateInJob("SELECT * FROM t");
        assertEquals(Set.of(new SqlDialectTranslator.TableRef("ds", "t")), t.tables());

        t = translateInJob("SELECT * FROM `data-project.ds.t`");
        assertEquals(Set.of(new SqlDialectTranslator.TableRef("ds", "t")), t.tables());
    }

    @Test
    void datasetQualifiedTableStaysInTheJobProject() {
        GcpException e = assertThrows(GcpException.class, () -> translateInJob("SELECT * FROM ds.t"));
        assertEquals("invalidQuery", e.getReason());
        assertTrue(e.getMessage().contains("Cross-project") && e.getMessage().contains("job-project.ds.t"),
                e.getMessage());
        e = assertThrows(GcpException.class, () -> translateInJob("SELECT * FROM `job-project.ds.t`"));
        assertTrue(e.getMessage().contains("Cross-project"), e.getMessage());
        e = assertThrows(GcpException.class, () -> translateInJob("SELECT * FROM ds.INFORMATION_SCHEMA.TABLES"));
        assertTrue(e.getMessage().contains("job-project.ds.INFORMATION_SCHEMA.TABLES"), e.getMessage());
        assertEquals("ds", translateInJob("SELECT * FROM INFORMATION_SCHEMA.TABLES").informationSchema()
                .iterator().next().dataset());
        // A dataset spelled like the default dataset's project is still a dataset in the job's project.
        e = assertThrows(GcpException.class,
                () -> translateInJob("SELECT * FROM `data-project`.INFORMATION_SCHEMA.TABLES"));
        assertTrue(e.getMessage().contains("job-project.data-project.INFORMATION_SCHEMA.TABLES"), e.getMessage());
        assertEquals(1, translateInJob("SELECT * FROM `data-project`.INFORMATION_SCHEMA.SCHEMATA")
                .informationSchema().size());
    }

    @Test
    void defaultDatasetProjectAppliesToStatementTargets() {
        assertEquals(new SqlDialectTranslator.TableRef("ds", "t"), SqlDialectTranslator.parseStatement(
                "INSERT INTO t (id) VALUES (1)", "data-project", "job-project", "ds").target());
        assertEquals(new SqlDialectTranslator.TableRef("ds", "t"), SqlDialectTranslator.parseStatement(
                "CREATE TABLE t (id INT64)", "data-project", "job-project", "ds").target());
        assertEquals("other", SqlDialectTranslator.parseStatement(
                "CREATE SCHEMA other", "data-project", "job-project", "ds").datasetTarget());
        assertEquals("other", SqlDialectTranslator.parseStatement(
                "CREATE SCHEMA `job-project.other`", "data-project", "job-project", "ds").datasetTarget());
        assertThrows(GcpException.class, () -> SqlDialectTranslator.parseStatement(
                "CREATE SCHEMA `data-project.other`", "data-project", "job-project", "ds"));
    }

    @Test
    void subqueryInFromIsNotATable() {
        SqlDialectTranslator.Translation t = translate("SELECT x FROM (SELECT id AS x FROM ds.t) AS sub");
        assertEquals("SELECT x FROM (SELECT id AS x FROM \"ds\".\"t\") AS sub", t.sql());
    }

    @Test
    void parenthesizedQueriesNameTheirOwnSelectList() {
        assertEquals("(SELECT 1 + 1 AS f0_)", sql("(SELECT 1 + 1)"));
        assertEquals("((SELECT 1 AS \"x\", 2 + 2 AS f0_))", sql("((SELECT 1 x, 2 + 2))"));
        assertEquals("(SELECT 1 AS \"x\") UNION ALL (SELECT 2 AS \"x\") ORDER BY x",
                sql("(SELECT 1 x) UNION ALL (SELECT 2 x) ORDER BY x"));
    }

    @Test
    void unaliasedParenthesizedQueryInFromGetsADuckDbAlias() {
        assertEquals("SELECT *"
                        + " FROM ((SELECT 1 AS \"value\") UNION ALL (SELECT 2 AS \"value\")) AS \"_floci_derived_0\"",
                sql("SELECT * FROM ((SELECT 1 value) UNION ALL (SELECT 2 value))"));
    }

    @Test
    void parenthesizedJoinGroupsDoNotHideTheirRangeVariables() {
        String translated = sql("""
                WITH previous_batch AS (SELECT 1 AS group_id, [1] AS items),
                     current_batch AS (SELECT 1 AS group_id, [1] AS items)
                SELECT previous_rows.group_id
                FROM ((previous_batch previous_rows CROSS JOIN UNNEST(previous_rows.items) AS previous_item)
                      FULL JOIN (current_batch current_rows CROSS JOIN UNNEST(current_rows.items) AS current_item)
                      ON previous_rows.group_id = current_rows.group_id)
                """);

        assertTrue(translated.contains("ON previous_rows.group_id = current_rows.group_id"), translated);
        assertFalse(translated.contains("_floci_derived_"), translated);
    }

    @Test
    void bareJoinUnnestBecomesCrossJoin() {
        assertEquals("SELECT a.*"
                        + " FROM \"ds\".\"filtered\" CROSS JOIN UNNEST(aliases) AS \"_unnest_a\"(\"a\")",
                sql("SELECT a.* FROM ds.filtered JOIN UNNEST(aliases) a"));
    }

    @Test
    void qualifiedStarsHidePseudocolumnsOnlyForTheirOwnPhysicalSource() {
        String derivedFirst = sql("SELECT d.*, w.* FROM (SELECT 1 AS id, 'd' AS _TABLE_SUFFIX) d"
                + " JOIN `ds.events_*` w ON d.id = w.id");
        assertTrue(derivedFirst.startsWith("SELECT d.*, w.* NOT SIMILAR TO '^(_PARTITIONDATE|_PARTITIONTIME|_TABLE_SUFFIX)$'"), derivedFirst);

        String physicalFirst = sql("SELECT w.*, d.* FROM `ds.events_*` w"
                + " JOIN (SELECT 1 AS id, 'd' AS _TABLE_SUFFIX) d ON d.id = w.id");
        assertTrue(physicalFirst.startsWith("SELECT w.* NOT SIMILAR TO '^(_PARTITIONDATE|_PARTITIONTIME|_TABLE_SUFFIX)$', d.*"), physicalFirst);
        assertEquals(1, physicalFirst.split("NOT SIMILAR", -1).length - 1, physicalFirst);
    }

    @Test
    void combinedStarExceptAndReplaceUsesDuckDbCompatibleModifiers() {
        assertEquals("SELECT * NOT SIMILAR TO '^(_PARTITIONDATE|_PARTITIONTIME|_TABLE_SUFFIX)$' FROM ("
                        + "SELECT * EXCLUDE (\"secret\") REPLACE(UPPER(name) AS name) FROM \"ds\".\"t\""
                        + ") AS \"_floci_star_replace\"",
                sql("SELECT * EXCEPT(secret) REPLACE(UPPER(name) AS name) FROM ds.t"));
    }

    @Test
    void conditionedJoinUnnestRemainsAnInnerJoin() {
        assertEquals("SELECT x FROM UNNEST([1]) AS \"_unnest_x\"(\"x\")"
                        + " JOIN UNNEST([1]) AS \"_unnest_y\"(\"y\") ON x = y",
                sql("SELECT x FROM UNNEST([1]) x JOIN UNNEST([1]) y ON x = y"));
    }

    @Test
    void ctesOfParenthesizedQueriesAndSubqueriesAreNotTreatedAsTables() {
        SqlDialectTranslator.Translation t = translate("(WITH s AS (SELECT * FROM ds.t) SELECT x + 1 FROM s)");
        assertEquals(Set.of(new SqlDialectTranslator.TableRef("ds", "t")), t.tables());
        assertEquals("(WITH s AS (SELECT * NOT SIMILAR TO '^(_PARTITIONDATE|_PARTITIONTIME|_TABLE_SUFFIX)$'"
                + " FROM \"ds\".\"t\") SELECT x + 1 AS f0_ FROM \"s\")", t.sql());
        assertEquals("SELECT *"
                        + " FROM (WITH s AS (SELECT 1 AS x) SELECT x FROM \"s\") AS \"_floci_derived_0\"",
                sql("SELECT * FROM (WITH s AS (SELECT 1 AS x) SELECT x FROM s)"));
        assertEquals("SELECT (WITH s AS (SELECT 1 AS x) SELECT x FROM \"s\") AS \"y\"",
                sql("SELECT (WITH s AS (SELECT 1 AS x) SELECT x FROM s) y"));
    }

    @Test
    void unnestAliasNamesTheElement() {
        assertEquals("SELECT tag FROM \"ds\".\"t\", UNNEST(tags) AS \"_unnest_tag\"(\"tag\")",
                sql("SELECT tag FROM ds.t, UNNEST(tags) AS tag"));
    }

    @Test
    void correlatedLeftJoinUnnestBecomesLateralJoinWithNullPreservingCondition() {
        assertEquals("""
                SELECT g.id, member FROM "ds"."groups" AS "g" LEFT JOIN LATERAL UNNEST(g.members) AS "_unnest_member"("member") ON TRUE WHERE g.id = 1""",
                sql("SELECT g.id, member FROM ds.groups g LEFT JOIN UNNEST(g.members) member WHERE g.id = 1"));
    }

    @Test
    void usingColumnNamesAreNotRewrittenAsOffsetRangeValues() {
        for (String key : List.of("id", "`id`")) {
            String translated = sql("SELECT t.id FROM (SELECT 1 AS id) t LEFT JOIN"
                    + " UNNEST([1]) id WITH OFFSET AS pos USING(" + key + ")");
            assertFalse(translated.contains("USING(\"_floci_offset_value_"), translated);
            assertTrue(translated.endsWith("USING(" + (key.equals("id") ? "id" : "\"id\"") + ")"), translated);
        }
    }

    @Test
    void mergeUsingSubqueriesStillRewriteOffsetRangeReferences() {
        String translated = dml("MERGE ds.t T USING (SELECT item.x AS id FROM"
                + " UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos) S ON T.id = S.id"
                + " WHEN MATCHED THEN DELETE");
        assertTrue(translated.contains("SELECT \"_floci_offset_value_0\".x AS id"), translated);
    }

    @Test
    void successivePivotsAreTranslatedAfterTheirSourcesShrink() {
        String translated = sql("SELECT a.id, a.x, b.y FROM"
                + " (SELECT 1 AS id, 2 AS v, 'x' AS k) PIVOT(SUM(v) FOR k IN ('x')) a"
                + " CROSS JOIN ds.metrics PIVOT(SUM(v) FOR k IN ('y')) b");
        assertEquals(2, translated.split("PIVOT ", -1).length - 1, translated);
        assertFalse(translated.contains("PIVOT("), translated);
        assertTrue(translated.contains("PIVOT \"ds\".\"metrics\" ON k IN ('y') USING SUM(v)"), translated);
    }

    @Test
    void leftJoinUnnestPreservesExplicitJoinConditions() {
        for (String source : List.of("UNNEST([STRUCT(1 AS id)]) item",
                "UNNEST([STRUCT(1 AS id)])", "UNNEST([STRUCT(1 AS id)]) item WITH OFFSET AS pos",
                "UNNEST([STRUCT(1 AS id)]) WITH OFFSET", "UNNEST([1]) id")) {
            String translated = sql("SELECT id FROM (SELECT 1 AS id) t LEFT JOIN " + source + " USING(id)");
            assertTrue(translated.contains("USING(id)"), translated);
            assertFalse(translated.contains("ON TRUE"), translated);
        }
        String on = sql("SELECT t.id FROM (SELECT 1 AS id) t"
                + " LEFT JOIN UNNEST([STRUCT(1 AS id)]) item ON t.id = item.id");
        assertFalse(on.contains("ON TRUE"), on);
        assertTrue(on.contains("ON t.id = \"_floci_offset_value_0\".id"), on);
    }

    @Test
    void unaliasedUnnestExpandsStructFields() {
        assertEquals("SELECT * FROM (SELECT UNNEST([1, 2], max_depth := 2)) AS \"_floci_unnest_0\"",
                sql("SELECT * FROM UNNEST([1, 2])"));
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
    void byteLiteralsDecodeHexAndOctalEscapesWithoutUtf8Reencoding() {
        assertEquals("SELECT from_hex('0010ff41') AS value",
                sql("SELECT b'\\x00\\020\\xffA' AS value"));
        assertEquals("SELECT 'A😀' AS value", sql("SELECT '\\u0041\\U0001F600' AS value"));
        assertThrows(GcpException.class, () -> sql("SELECT b'\\u0041'"));
        assertThrows(GcpException.class, () -> sql("SELECT b'\\x0'"));
        assertThrows(GcpException.class, () -> sql("SELECT b'\\400'"));
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
    void stringCastOfNumericLiteralTrimsItsScale() {
        String translated = sql("SELECT CAST(NUMERIC '12.30' AS STRING) AS a");
        assertTrue(translated.contains("LIKE 'DECIMAL%'"), translated);
        assertTrue(translated.contains("rtrim(rtrim(CAST(\"_floci_cast_input\" AS VARCHAR), '0'), '.')"),
                translated);
    }

    @Test
    void stringCastOfFloatLiteralsCoercesToDouble() {
        String translated = sql("SELECT CAST(0.0 AS STRING) AS a, CAST(-0.0 AS STRING) AS b, "
                + "CAST(0.5 AS STRING) AS c");
        assertTrue(translated.contains("[CAST(0.0 AS DOUBLE)]"), translated);
        assertTrue(translated.contains("[CAST(-0.0 AS DOUBLE)]"), translated);
        assertTrue(translated.contains("[CAST(0.5 AS DOUBLE)]"), translated);
    }

    @Test
    void stringCastKeepsAggregateAndWindowOperandsInTheirQuery() {
        assertEquals("SELECT " + numericStringCast("CAST", "COUNT(*)") + " AS c FROM \"ds\".\"t\"",
                sql("SELECT CAST(COUNT(*) AS STRING) AS c FROM ds.t"));
        assertEquals("SELECT " + numericStringCast("CAST", "ROW_NUMBER() OVER ()")
                        + " AS c FROM \"ds\".\"t\"",
                sql("SELECT CAST(ROW_NUMBER() OVER () AS STRING) AS c FROM ds.t"));
    }

    @Test
    void stringCastGroupsByTheSameTextItSelects() {
        String wrapper = numericStringCast("CAST", "i");
        assertEquals("SELECT " + wrapper + " AS s, COUNT(*) AS f0_ FROM \"ds\".\"t\" GROUP BY " + wrapper,
                sql("SELECT CAST(i AS STRING) AS s, COUNT(*) FROM ds.t GROUP BY CAST(i AS STRING)"));
        assertEquals("SELECT " + numericStringCast("CAST", "total") + " AS s, COUNT(*) AS f0_ "
                        + "FROM \"ds\".\"t\" GROUP BY " + numericStringCast("CAST", "total"),
                sql("SELECT CAST(total AS STRING) AS s, COUNT(*) FROM ds.t GROUP BY CAST(total AS STRING)"));
    }

    @Test
    void stringCastWrapsAnyModArgumentThatMayBeDecimal() {
        assertEquals("SELECT " + numericStringCast("CAST", "MOD(5, CAST('2' AS DECIMAL(38,9)))") + " AS c",
                sql("SELECT CAST(MOD(5, NUMERIC '2') AS STRING) AS c"));
        assertEquals("SELECT CAST(MOD(5, 2) AS VARCHAR) AS c", sql("SELECT CAST(MOD(5, 2) AS STRING) AS c"));
    }

    /** The zero-aware, decimal-trimming wrapper the translator emits for a numeric STRING cast. */
    private static String numericStringCast(String function, String expression) {
        String value = "\"_floci_cast_input\"";
        return "list_extract(list_transform([" + expression + "], " + value + " -> CASE"
                + " WHEN typeof(" + value + ") IN ('DOUBLE', 'FLOAT') AND " + value + " = 0 THEN '0'"
                + " WHEN typeof(" + value + ") LIKE 'DECIMAL%' AND " + function + "(" + value
                + " AS VARCHAR) LIKE '%.%'"
                + " THEN rtrim(rtrim(" + function + "(" + value + " AS VARCHAR), '0'), '.')"
                + " ELSE " + function + "(" + value + " AS VARCHAR) END), 1)";
    }

    @Test
    void castTypesAreMapped() {
        assertEquals("SELECT CAST(x AS BIGINT) AS a, " + numericStringCast("TRY_CAST", "y") + " AS b, "
                        + "CAST(z AS DOUBLE[]) AS c",
                sql("SELECT CAST(x AS INT64) AS a, SAFE_CAST(y AS STRING) AS b, "
                        + "CAST(z AS ARRAY<FLOAT64>) AS c"));
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
    void anonymousColumnsBelowTheResultKeepTheirPosition() {
        assertEquals("SELECT *"
                        + " FROM (SELECT 1 + 1 AS \"$col1\", 2 AS x, 3 + 3 AS \"$col3\") AS \"_floci_derived_0\"",
                sql("SELECT * FROM (SELECT 1 + 1, 2 AS x, 3 + 3)"));
        assertEquals("WITH s AS (SELECT 1 + 1 AS \"$col1\") SELECT * FROM \"s\"",
                sql("WITH s AS (SELECT 1 + 1) SELECT * FROM s"));
        assertEquals("SELECT 1 + 1 AS \"$col1\", *"
                        + " FROM (SELECT 2 + 2 AS \"$col1\") AS \"_floci_derived_0\"",
                sql("SELECT 1 + 1, * FROM (SELECT 2 + 2)"));
        assertEquals("SELECT (SELECT 1 + 1 AS \"$col1\") AS v, 1 + 1 AS f0_", sql("SELECT (SELECT 1 + 1) AS v, 1 + 1"));
        assertEquals("SELECT 1 + 1 AS f0_ UNION ALL SELECT 2 + 2", sql("SELECT 1 + 1 UNION ALL SELECT 2 + 2"));
        assertEquals("SELECT 1 + 1 AS f1_, 9 AS f0_", sql("SELECT 1 + 1, 9 AS f0_"));
        assertEquals("SELECT *"
                        + " FROM (SELECT * NOT SIMILAR TO '(?i)^(x)$', 9 + 9 AS \"$col2\""
                        + " FROM (SELECT 2 + 2 AS \"$col1\", 3 AS x) AS \"_floci_derived_0\")"
                        + " AS \"_floci_derived_1\"",
                sql("SELECT * FROM (SELECT * EXCEPT (x), 9 + 9 FROM (SELECT 2 + 2, 3 AS x))"));
    }

    @Test
    void starAndImplicitAliasesKeepTheirNames() {
        assertEquals("SELECT * NOT SIMILAR TO '(?i)^(_PARTITIONDATE|_PARTITIONTIME|_TABLE_SUFFIX|secret)$',"
                        + " UPPER(name) AS \"upper_name\" FROM \"ds\".\"t\"",
                sql("SELECT * EXCEPT (secret), UPPER(name) upper_name FROM ds.t"));
        assertEquals("SELECT * NOT SIMILAR TO '^(_PARTITIONDATE|_PARTITIONTIME|_TABLE_SUFFIX)$' FROM ("
                        + "SELECT * REPLACE (UPPER(name) AS name) FROM \"ds\".\"t\""
                        + ") AS \"_floci_star_replace\"",
                sql("SELECT * REPLACE (UPPER(name) AS name) FROM ds.t"));
        assertEquals("SELECT * REPLACE (UPPER(name) AS name), id AS original_id FROM \"ds\".\"t\"",
                sql("SELECT * REPLACE (UPPER(name) AS name), id AS original_id FROM ds.t"));
    }

    @Test
    void partitionPseudocolumnsStayScopedToPhysicalStarExpansions() {
        SqlDialectTranslator.TableRef events = new SqlDialectTranslator.TableRef("ds", "events");
        Map<SqlDialectTranslator.TableRef, Set<String>> partitionColumns = Map.of(events,
                Set.of("id", "name", "secret", "_partitiondate", "_partitiontime"));

        assertEquals("SELECT * EXCLUDE (\"_PARTITIONDATE\", \"_PARTITIONTIME\", \"secret\")"
                        + " REPLACE(id + 1 AS id) FROM \"ds\".\"events\"",
                sql("SELECT * EXCEPT(secret) REPLACE(id + 1 AS id) FROM ds.events", partitionColumns));
        assertEquals("SELECT \"d\".*, \"e\".* NOT SIMILAR TO"
                        + " '(?i)^(_PARTITIONDATE|_PARTITIONTIME)$' FROM"
                        + " (SELECT 1 AS marker) d CROSS JOIN \"ds\".\"events\" AS \"e\"",
                sql("SELECT * FROM (SELECT 1 AS marker) d CROSS JOIN ds.events e", partitionColumns));
        assertEquals("SELECT COALESCE(\"d\".\"id\", \"e\".\"id\") AS \"id\","
                        + " \"d\".* NOT SIMILAR TO '(?i)^(id)$',"
                        + " \"e\".* NOT SIMILAR TO '(?i)^(id|_PARTITIONDATE|_PARTITIONTIME)$' FROM"
                        + " (SELECT 1 AS ID, 'left' AS label) d LEFT JOIN \"ds\".\"events\" AS \"e\" USING(id)",
                sql("SELECT * FROM (SELECT 1 AS ID, 'left' AS label) d"
                        + " LEFT JOIN ds.events e USING(id)", partitionColumns));

        SqlDialectTranslator.TableRef a = new SqlDialectTranslator.TableRef("ds", "a");
        SqlDialectTranslator.TableRef b = new SqlDialectTranslator.TableRef("ds", "b");
        Map<SqlDialectTranslator.TableRef, Set<String>> joinedColumns = Map.of(
                a, Set.of("id", "a_value", "_partitiondate", "_partitiontime"),
                b, Set.of("id", "b_value"));
        assertEquals("SELECT * NOT SIMILAR TO '^(_PARTITIONDATE|_PARTITIONTIME)$'"
                        + " FROM \"ds\".\"b\" AS \"b\" CROSS JOIN \"ds\".\"a\" AS \"a\"",
                sql("SELECT * FROM ds.b b CROSS JOIN ds.a a", joinedColumns));
        assertEquals("SELECT COALESCE(\"d\".\"id\", \"a\".\"id\") AS \"id\","
                        + " \"a\".* NOT SIMILAR TO '(?i)^(id|_PARTITIONDATE|_PARTITIONTIME)$',"
                        + " \"b\".* FROM"
                        + " (SELECT 1 AS id) d JOIN \"ds\".\"a\" AS \"a\" USING(id)"
                        + " JOIN \"ds\".\"b\" AS \"b\" ON b.id = a.id",
                sql("SELECT * FROM (SELECT 1 AS id) d JOIN ds.a a USING(id)"
                        + " JOIN ds.b b ON b.id = a.id", joinedColumns));

        Map<SqlDialectTranslator.TableRef, Set<String>> differentlyJoinedColumns = Map.of(
                a, Set.of("x", "a_value", "_partitiondate", "_partitiontime"),
                b, Set.of("y", "b_value", "_partitiondate", "_partitiontime"));
        assertEquals("SELECT COALESCE(\"d\".\"y\", \"b\".\"y\") AS \"y\","
                        + " COALESCE(\"d\".\"x\", \"a\".\"x\") AS \"x\","
                        + " \"d\".* NOT SIMILAR TO '(?i)^(y|x)$',"
                        + " \"a\".* NOT SIMILAR TO '(?i)^(x|_PARTITIONDATE|_PARTITIONTIME)$',"
                        + " \"b\".* NOT SIMILAR TO '(?i)^(y|_PARTITIONDATE|_PARTITIONTIME)$' FROM"
                        + " (SELECT 1 AS x, 2 AS y, 'derived' AS label) d"
                        + " JOIN \"ds\".\"a\" AS \"a\" USING(x) JOIN \"ds\".\"b\" AS \"b\" USING(y)",
                sql("SELECT * FROM (SELECT 1 AS x, 2 AS y, 'derived' AS label) d"
                        + " JOIN ds.a a USING(x) JOIN ds.b b USING(y)", differentlyJoinedColumns));

        assertEquals("SELECT * FROM"
                        + " (SELECT * NOT SIMILAR TO '^(_PARTITIONDATE|_PARTITIONTIME)$'"
                        + " FROM \"ds\".\"a\") d LEFT JOIN \"ds\".\"b\" AS \"b\" USING(id)",
                sql("SELECT * FROM (SELECT * FROM ds.a) d LEFT JOIN ds.b b USING(id)", joinedColumns));

        Map<SqlDialectTranslator.TableRef, Set<String>> ordinaryColumns = Map.of(events, Set.of("id"));
        assertEquals("SELECT * FROM \"ds\".\"events\" AS \"e\" CROSS JOIN"
                        + " (SELECT CAST('2026-10-01' AS DATE) AS _PARTITIONDATE) d",
                sql("SELECT * FROM ds.events e CROSS JOIN"
                        + " (SELECT DATE '2026-10-01' AS _PARTITIONDATE) d", ordinaryColumns));
        assertEquals("SELECT * FROM (SELECT 1 AS ID, 'left' AS label) d"
                        + " JOIN \"ds\".\"events\" AS \"a\" USING(id)",
                sql("SELECT * FROM (SELECT 1 AS ID, 'left' AS label) d"
                        + " JOIN ds.events a USING(id)", ordinaryColumns));
        assertEquals("SELECT e.* REPLACE(id + 1 AS id), CAST('2026-10-01' AS DATE) AS _PARTITIONDATE"
                        + " FROM \"ds\".\"events\" AS \"e\"",
                sql("SELECT e.* REPLACE(id + 1 AS id), DATE '2026-10-01' AS _PARTITIONDATE"
                        + " FROM ds.events e", ordinaryColumns));
    }

    @Test
    void parenthesizedPathKeepsItsImplicitColumnName() {
        assertEquals("WITH categories AS (SELECT DISTINCT(category) FROM \"ds\".\"t\")"
                        + " SELECT * NOT SIMILAR TO '^(_PARTITIONDATE|_PARTITIONTIME|_TABLE_SUFFIX)$'"
                        + " FROM \"ds\".\"t\" JOIN \"categories\" USING (category)",
                sql("WITH categories AS (SELECT DISTINCT(category) FROM ds.t)"
                        + " SELECT * FROM ds.t JOIN categories USING (category)"));
    }

    // ── Functions ────────────────────────────────────────────────────────────

    @Test
    void currentRowInAWindowFrameStaysAKeyword() {
        assertEquals("SELECT SUM(x) OVER (ORDER BY x ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS s,"
                        + " COUNT(*) OVER (ORDER BY x ROWS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING) AS c FROM \"ds\".\"t\"",
                sql("SELECT SUM(x) OVER (ORDER BY x ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS s,"
                        + " COUNT(*) OVER (ORDER BY x ROWS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING) AS c FROM ds.t"));
        assertEquals("SELECT \"row\" FROM \"ds\".\"t\"", sql("SELECT row FROM ds.t"), "a column named row is still quoted");
    }

    @Test
    void safeArraySubscriptsBecomeOneBasedIndexes() {
        assertEquals("SELECT (list_transform([struct_pack(a := a, i := i)], lambda _s: CASE WHEN _s.i >= 0 AND _s.i < len(_s.a)"
                + " THEN _s.a[_s.i + 1] END)[1]) AS f0_, (list_transform([struct_pack(a := a, i := 2)], lambda _s: CASE"
                + " WHEN _s.i >= 1 AND _s.i < len(_s.a) + 1 THEN _s.a[_s.i] END)[1]) AS f1_ FROM \"ds\".\"t\"",
                sql("SELECT a[SAFE_OFFSET(i)], a[SAFE_ORDINAL(2)] FROM ds.t"));
        assertTrue(sql("SELECT ARRAY_AGG(x ORDER BY x)[SAFE_OFFSET(0)] AS top FROM ds.t")
                .startsWith("SELECT (list_transform([struct_pack(a := ARRAY_AGG(x ORDER BY x), i := 0)]"));
        assertTrue(sql("SELECT t.a[SAFE_OFFSET(0)] AS f FROM ds.t t")
                .startsWith("SELECT (list_transform([struct_pack(a := t.a, i := 0)]"));
    }

    @Test
    void subscriptsWriteTheArrayAndTheIndexOnce() {
        List<Map<String, Object>> one = List.of(Map.of("parameterType", Map.of("type", "INT64"),
                "parameterValue", Map.of("value", "1")));
        String safe = SqlDialectTranslator.translate("SELECT [10, 20][SAFE_OFFSET(?)] AS v", "test-project", null,
                new SqlDialectTranslator.QueryParameters(one, "POSITIONAL")).sql();
        assertTrue(safe.contains("struct_pack(a := [10, 20], i := CAST(1 AS BIGINT))"), safe);
        List<Map<String, Object>> two = List.of(
                Map.of("parameterType", Map.of("type", "INT64"), "parameterValue", Map.of("value", "5")),
                Map.of("parameterType", Map.of("type", "INT64"), "parameterValue", Map.of("value", "1")));
        String ordered = SqlDialectTranslator.translate("SELECT [?, 20][OFFSET(?)] AS v", "test-project", null,
                new SqlDialectTranslator.QueryParameters(two, "POSITIONAL")).sql();
        assertTrue(ordered.contains("struct_pack(a := [CAST(5 AS BIGINT), 20], i := CAST(1 AS BIGINT))"), ordered);

        String chained = sql("SELECT [1]" + "[OFFSET(0)]".repeat(20) + " AS v");
        assertTrue(chained.length() < 20_000, "a chain of subscripts grows linearly: " + chained.length());
    }

    @Test
    void aSubscriptAppliesToTheWholeExpressionBeforeIt() {
        String field = sql("SELECT [STRUCT([10, 20] AS a)][SAFE_OFFSET(0)].a[SAFE_OFFSET(1)] AS v");
        assertTrue(field.startsWith("SELECT (list_transform([struct_pack(a := (list_transform([struct_pack(a := "), field);
        assertTrue(field.contains(")[1]).a, i := 1)]"), field);
        String window = sql("SELECT ARRAY_AGG(x) OVER ()[SAFE_OFFSET(0)] AS v FROM ds.t");
        assertTrue(window.contains("struct_pack(a := ARRAY_AGG(x) OVER (), i := 0)"), window);
    }

    @Test
    void createTableKeepsItsQuerySubscriptsAsGoogleSql() {
        // The stored query is translated again when it runs, so it must not be rewritten here.
        assertEquals("SELECT a[SAFE_OFFSET(0)] AS x FROM ds.s",
                statement("CREATE TABLE ds.t AS SELECT a[SAFE_OFFSET(0)] AS x FROM ds.s").querySql().trim());
    }

    @Test
    void plainArraySubscriptsCheckTheirBounds() {
        String offset = sql("SELECT a[OFFSET(i)] AS v FROM ds.t");
        assertTrue(offset.contains("WHEN _s.i < 0 THEN error('Array index ' || CAST(_s.i AS VARCHAR) || ' is out of bounds (underflow)')"), offset);
        assertTrue(offset.contains("WHEN _s.i >= len(_s.a) THEN error('Array index ' || CAST(_s.i AS VARCHAR) || ' is out of bounds (overflow)')"), offset);
        assertTrue(offset.contains("ELSE _s.a[_s.i + 1] END)[1]) AS v"), offset);
        assertEquals("SELECT a[i] AS v, j['a'] AS f FROM \"ds\".\"t\"", sql("SELECT a[i] AS v, j['a'] AS f FROM ds.t"),
                "a bare subscript is left as it is: it is also the JSON subscript operator");
        String ordinal = sql("SELECT a[ORDINAL(i)] AS v FROM ds.t");
        assertTrue(ordinal.contains("WHEN _s.i < 1 THEN") && ordinal.contains("WHEN _s.i >= len(_s.a) + 1 THEN")
                && ordinal.contains("ELSE _s.a[_s.i] END)[1]) AS v"), ordinal);
        assertEquals("SELECT [1, 2] AS a", sql("SELECT [1, 2] AS a"), "an array literal is not a subscript");
    }

    @Test
    void generateDateArrayBecomesADateSeries() {
        assertEquals("SELECT (CASE WHEN (INTERVAL 1 DAY) = INTERVAL 0 DAY THEN error('GENERATE_ARRAY step cannot be 0.')"
                + " ELSE CAST(generate_series(CAST(CAST(a AS DATE) AS TIMESTAMP), CAST(CAST(b AS DATE) AS TIMESTAMP),"
                + " INTERVAL 1 DAY) AS DATE[]) END) AS d FROM \"ds\".\"t\"",
                sql("SELECT GENERATE_DATE_ARRAY(a, b) AS d FROM ds.t"));
        assertTrue(sql("SELECT GENERATE_DATE_ARRAY(a, b, INTERVAL 1 MONTH) AS d FROM ds.t")
                .contains("CAST(CAST(b AS DATE) AS TIMESTAMP), INTERVAL 1 MONTH) AS DATE[])"));
        assertEquals("invalidQuery", assertThrows(GcpException.class,
                () -> sql("SELECT GENERATE_DATE_ARRAY(a) FROM ds.t")).getReason());
    }

    @Test
    void structDotStarBecomesUnnest() {
        assertEquals("SELECT unnest(t.s), t.n FROM \"ds\".\"t\" AS \"t\"", sql("SELECT t.s.*, t.n FROM ds.t t"));
        assertEquals("SELECT unnest(t.s.inr) FROM \"ds\".\"t\" AS \"t\"", sql("SELECT t.s.inr.* FROM ds.t t"));
        String call = sql("SELECT g, ANY_VALUE(s).*, MAX(x) AS m FROM ds.t GROUP BY g");
        assertTrue(call.startsWith("SELECT g, unnest(ANY_VALUE(s)), MAX(x) AS m"), call);
        assertEquals("SELECT s.* FROM \"ds\".\"t\"", sql("SELECT s.* FROM ds.t"), "a bare name keeps its .*");
        assertEquals("SELECT t.* NOT SIMILAR TO '^(_PARTITIONDATE|_PARTITIONTIME|_TABLE_SUFFIX)$'"
                        + " FROM \"ds\".\"t\" AS \"t\"",
                sql("SELECT t.* FROM ds.t t"));
        assertTrue(assertThrows(GcpException.class, () -> sql("SELECT t.s.* EXCEPT (a) FROM ds.t t"))
                .getMessage().contains("not supported by the floci BigQuery emulator yet"));
        assertEquals("SELECT unnest(f(x).s) FROM \"ds\".\"t\"", sql("SELECT f(x).s.* FROM ds.t"));
        String subscript = sql("SELECT a[OFFSET(0)].s.* FROM ds.t");
        assertTrue(subscript.startsWith("SELECT unnest(") && subscript.endsWith(".s) FROM \"ds\".\"t\"")
                && !subscript.contains(".*"), subscript);
        String difference = sql("SELECT STRUCT(1 AS a).* EXCEPT DISTINCT SELECT 1 AS a");
        assertTrue(difference.startsWith("SELECT unnest(") && difference.contains(" EXCEPT "), difference);
    }

    @Test
    void initcapWalksTheCharactersAgainstTheDelimiters() {
        String rendered = sql("SELECT INITCAP(name) AS n FROM ds.t");
        assertTrue(rendered.startsWith("SELECT list_extract(list_transform([struct_pack(s := (name), d := (' ' || chr(9)"),
                rendered);
        assertTrue(rendered.contains("'[](){}/|\\<>!?@\"^#$&~_,.:;*%+-'"), rendered);
        assertTrue(rendered.contains("range(1, length(_floci_initcap_0.s) + 1), _floci_initcap_0_i ->"), rendered);
        String custom = sql("SELECT INITCAP(f(name), g(d)) AS n FROM ds.t");
        assertEquals(1, custom.split("f\\(name\\)", -1).length - 1, custom);
        assertEquals(1, custom.split("g\\(d\\)", -1).length - 1, custom);
        assertFalse(custom.contains("__i"), custom);
        assertEquals("invalidQuery", assertThrows(GcpException.class,
                () -> sql("SELECT INITCAP() FROM ds.t")).getReason());
        assertEquals("invalidQuery", assertThrows(GcpException.class,
                () -> sql("SELECT INITCAP(a, b, c) FROM ds.t")).getReason());
    }

    @Test
    void anyValueHavingBecomesArgMaxOrArgMin() {
        String max = sql("SELECT g, ANY_VALUE(x HAVING MAX y) AS v FROM ds.t GROUP BY g");
        assertTrue(max.startsWith("SELECT g, list_extract(list_transform([max(list_extract(list_transform([struct_pack(k :="
                + " (y), v := (x))], _floci_any_value_0 -> CASE WHEN _floci_any_value_0.k IS NULL THEN NULL ELSE"
                + " struct_pack(z := (typeof(_floci_any_value_0.k) IN ('DOUBLE', 'FLOAT') AND isnan("), max);
        assertTrue(max.contains(", n := _floci_any_value_0.v IS NOT NULL, v := _floci_any_value_0.v) END), 1))],"
                + " _floci_any_value_0_m -> CASE WHEN _floci_any_value_0_m.z THEN NULL ELSE _floci_any_value_0_m.v END), 1)"
                + " AS v FROM \"ds\".\"t\" GROUP BY g"), max);
        String min = sql("SELECT ANY_VALUE(g(x) HAVING MIN f(y, 1)) AS v FROM ds.t");
        assertTrue(min.contains("[min(") && min.contains("struct_pack(z := NOT (typeof(") && min.contains(".v IS NULL, v := ")
                && min.contains("CASE WHEN NOT _floci_any_value_0_m.z THEN NULL"), min);
        assertEquals(1, min.split("g\\(x\\)", -1).length - 1, min);
        assertEquals(1, min.split("f\\(y, 1\\)", -1).length - 1, min);
        assertEquals("SELECT ANY_VALUE(x) AS v FROM \"ds\".\"t\"", sql("SELECT ANY_VALUE(x) AS v FROM ds.t"));
        assertEquals("invalidQuery", assertThrows(GcpException.class,
                () -> sql("SELECT ANY_VALUE(x HAVING y) FROM ds.t")).getReason());
    }

    @Test
    void arrayAggNullModifiers() {
        assertEquals("SELECT ARRAY_AGG(x) FILTER (WHERE (x) IS NOT NULL) AS xs FROM \"ds\".\"t\"", sql("SELECT ARRAY_AGG(x IGNORE NULLS) AS xs FROM ds.t"));
        assertEquals("SELECT ARRAY_AGG(DISTINCT x ORDER BY x DESC) FILTER (WHERE (x) IS NOT NULL) AS xs FROM \"ds\".\"t\"", sql("SELECT ARRAY_AGG(DISTINCT x IGNORE NULLS ORDER BY x DESC) AS xs FROM ds.t"));
        assertEquals("SELECT ARRAY_AGG((CASE WHEN x = 1 THEN 'keep ignore nulls' ELSE NULL END))"
                + " FILTER (WHERE ((CASE WHEN x = 1 THEN 'keep ignore nulls' ELSE NULL END)) IS NOT NULL) AS xs FROM \"ds\".\"t\"", sql("SELECT ARRAY_AGG(IF(x = 1, 'keep ignore nulls', NULL) IGNORE NULLS) AS xs FROM ds.t"));
        assertEquals("SELECT ARRAY_AGG(x) AS xs FROM \"ds\".\"t\"", sql("SELECT ARRAY_AGG(x RESPECT NULLS) AS xs FROM ds.t"));
        assertEquals("SELECT ARRAY_AGG(x) AS xs FROM \"ds\".\"t\"", sql("SELECT ARRAY_AGG(x) AS xs FROM ds.t"));
    }

    @Test
    void arrayAggLimitKeepsTheFirstElements() {
        assertEquals("SELECT list_slice(ARRAY_AGG(x ORDER BY x DESC), 1, 2) AS top2 FROM \"ds\".\"t\"",
                sql("SELECT ARRAY_AGG(x ORDER BY x DESC LIMIT 2) AS top2 FROM ds.t"));
        assertEquals("SELECT list_slice(ARRAY_AGG(DISTINCT x ORDER BY x) FILTER (WHERE (x) IS NOT NULL), 1, 1) AS l"
                + " FROM \"ds\".\"t\"", sql("SELECT ARRAY_AGG(DISTINCT x IGNORE NULLS ORDER BY x LIMIT 1) AS l FROM ds.t"));
        GcpException e = assertThrows(GcpException.class,
                () -> sql("SELECT ARRAY_AGG(x LIMIT 1) OVER () AS a FROM ds.t"));
        assertEquals("invalidQuery", e.getReason());
        assertTrue(e.getMessage().contains("LIMIT in arguments is not supported on analytic functions"), e.getMessage());
        assertEquals("LIMIT expects a non-negative integer literal or parameter",
                invalid("SELECT ARRAY_AGG(x ORDER BY x LIMIT -1) AS a FROM ds.t").getMessage());
        assertEquals("LIMIT expects an integer literal or parameter",
                invalid("SELECT ARRAY_AGG(x ORDER BY x LIMIT 1 + 1) AS a FROM ds.t").getMessage());
        assertEquals("LIMIT expects an integer literal or parameter",
                invalid("SELECT ARRAY_AGG(x ORDER BY x LIMIT 1.5) AS a FROM ds.t").getMessage());
        assertEquals("SELECT ARRAY_AGG(t.limit) AS a, list_slice(ARRAY_AGG(t.limit), 1, 1) AS b, ARRAY_AGG(t.ignore) AS c"
                + " FROM \"ds\".\"t\"",
                sql("SELECT ARRAY_AGG(t.limit) AS a, ARRAY_AGG(t.limit LIMIT 1) AS b, ARRAY_AGG(t.ignore) AS c FROM ds.t"));
    }

    @Test
    void arrayAggNullModifierIsRejectedOnTheAnalyticForm() {
        GcpException e = assertThrows(GcpException.class,
                () -> sql("SELECT ARRAY_AGG(y IGNORE NULLS) OVER (ORDER BY x) AS a FROM ds.t"));
        assertEquals("invalidQuery", e.getReason());
        assertTrue(e.getMessage().contains("does not support IGNORE NULLS or RESPECT NULLS"), e.getMessage());
    }

    @Test
    void distinctCountIfAppliesDistinctToTheConditionalResult() {
        assertEquals("SELECT count(DISTINCT CASE WHEN x > 0 THEN 1 END) AS c",
                sql("SELECT COUNTIF(DISTINCT x > 0) AS c"));
        assertEquals("SELECT count(DISTINCT CASE WHEN x > 0 THEN 1 END) OVER () AS c",
                sql("SELECT COUNTIF(distinct x > 0) OVER () AS c"));
        assertEquals("SELECT count(DISTINCT CASE WHEN (x > 0) THEN 1 END) AS c",
                sql("SELECT COUNTIF(DISTINCT(x > 0)) AS c"));
    }

    @Test
    void pivotSourcesKeepQuotedImplicitAliasesAndHavingRewrites() {
        String translated = sql("SELECT * FROM (SELECT 1 sample, 'one' AS metric)"
                + " PIVOT(ANY_VALUE(sample) FOR metric IN ('one'))");
        assertTrue(translated.contains("SELECT 1 AS \"sample\""), translated);
        assertFalse(translated.contains("1 sample"), translated);
        String having = sql("SELECT * FROM (SELECT STRUCT(MAX(x) AS n) AS s, 'one' AS metric"
                + " FROM UNNEST([1,2]) x HAVING s.n > 0)"
                + " PIVOT(ANY_VALUE(s) FOR metric IN ('one'))");
        assertFalse(having.contains("HAVING s.n"), having);
    }

    @Test
    void pivotSourcesKeepNestedSelectAsStructVisibleForLaterRewrites() {
        String translated = sql("SELECT one.x FROM (SELECT (SELECT AS STRUCT 1 AS x) AS s, 'one' AS k)"
                + " PIVOT(ANY_VALUE(s) FOR k IN ('one'))");
        assertFalse(translated.contains("SELECT AS STRUCT"), translated);
        assertTrue(translated.contains("{'x': 1}"), translated);
    }

    @Test
    void functionShimsRewriteToDuckDb() {
        assertEquals("SELECT (CASE WHEN (b) = 0 THEN NULL ELSE (a) / (b) END) AS r", sql("SELECT SAFE_DIVIDE(a, b) AS r"));
        assertEquals("SELECT count(CASE WHEN x > 1 THEN 1 END) AS c", sql("SELECT COUNTIF(x > 1) AS c"));
        assertEquals("SELECT count(CASE WHEN x > 1 THEN 1 END) OVER (PARTITION BY bucket) AS c",
                sql("SELECT COUNTIF(x > 1) OVER (PARTITION BY bucket) AS c"));
        assertEquals("SELECT list_extract(list_transform([struct_pack(\"_floci_concat_0_0\" := a,"
                        + " \"_floci_concat_0_1\" := b)], _floci_concat_value_0 -> CASE WHEN"
                        + " _floci_concat_value_0.\"_floci_concat_0_0\" IS NULL OR"
                        + " _floci_concat_value_0.\"_floci_concat_0_1\" IS NULL THEN NULL ELSE concat("
                        + "_floci_concat_value_0.\"_floci_concat_0_0\","
                        + " _floci_concat_value_0.\"_floci_concat_0_1\") END), 1) AS c",
                sql("SELECT CONCAT(a, b) AS c"));
        String singleConcat = sql("SELECT CONCAT(7) AS c");
        assertTrue(singleConcat.contains("concat(_floci_concat_value_0.\"_floci_concat_0_0\")"),
                singleConcat);
        assertTrue(singleConcat.contains("IS NULL THEN NULL"), singleConcat);
        assertTrue(sql("SELECT CONCAT(7, '_', label) AS c").contains(
                "struct_pack(\"_floci_concat_0_0\" := 7, \"_floci_concat_0_1\" := '_',"
                        + " \"_floci_concat_0_2\" := label)"));
        String volatileConcat = sql("SELECT CONCAT(IF(RAND() < 0.5, NULL, 'x'), 'y') AS c");
        assertEquals(1, volatileConcat.split("random\\(\\)", -1).length - 1, volatileConcat);
        String aggregateConcat = sql("SELECT CONCAT(COUNT(*), ' rows') FROM ds.t");
        assertEquals(1, aggregateConcat.split("COUNT\\(\\*\\)", -1).length - 1, aggregateConcat);
        assertFalse(aggregateConcat.contains("FROM (SELECT COUNT"), aggregateConcat);
        assertEquals("SELECT LENGTH(value) AS n", sql("SELECT LENGTH(value) AS n"));
        assertEquals("SELECT CAST(to_json(value) AS VARCHAR) AS json",
                sql("SELECT TO_JSON_STRING(value) AS json"));
        assertEquals("SELECT (CASE WHEN x THEN 1 ELSE 2 END) AS c", sql("SELECT IF(x, 1, 2) AS c"));
        assertEquals("SELECT date_diff('day', b, a) AS d", sql("SELECT DATE_DIFF(a, b, DAY) AS d"));
        assertEquals("SELECT date_diff('week', (b - INTERVAL 1 DAY), (a - INTERVAL 1 DAY)) AS d",
                sql("SELECT DATE_DIFF(a, b, WEEK(TUESDAY)) AS d"));
        assertEquals("SELECT cast_to_type(date_trunc('month', d), d) AS m", sql("SELECT DATE_TRUNC(d, MONTH) AS m"));
        assertEquals("SELECT cast_to_type((date_trunc('week', d - INTERVAL 1 DAY) + INTERVAL 1 DAY), d) AS t",
                sql("SELECT DATE_TRUNC(d, WEEK(TUESDAY)) AS t"));
        assertEquals("SELECT cast_to_type(date_trunc('month', CAST('2024-05-17' AS DATE)), CAST('2024-05-17' AS DATE)) AS m",
                sql("SELECT DATE_TRUNC('2024-05-17', MONTH) AS m"));
        assertEquals("SELECT CAST(CAST('2026-03-01' AS DATE) + INTERVAL 2 DAY AS DATE) AS m",
                sql("SELECT DATE_ADD('2026-03-01', INTERVAL 2 DAY) AS m"));
        assertEquals("SELECT CAST(s || 'x' + INTERVAL 1 DAY AS DATE) AS n", sql("SELECT DATE_ADD(s || 'x', INTERVAL 1 DAY) AS n"));
        assertEquals("SELECT CAST(CAST('2026-03-01' AS DATE) + INTERVAL 1 DAY AS DATE) AS m",
                sql("SELECT DATE_ADD(('2026-03-01'), INTERVAL 1 DAY) AS m"));
        assertEquals("SELECT CAST(('a') || ('b') + INTERVAL 1 DAY AS DATE) AS m",
                sql("SELECT DATE_ADD(('a') || ('b'), INTERVAL 1 DAY) AS m"));
        assertEquals("SELECT strftime(ts, '%Y') AS y", sql("SELECT FORMAT_TIMESTAMP('%Y', ts) AS y"));
        assertEquals("SELECT strftime(d, '%Y-%m-%d %%F %m/%d/%y %%R') AS y",
                sql("SELECT FORMAT_DATE('%F %%F %D %R', d) AS y"));
        assertEquals("SELECT CAST(strptime(s, '%Y-%m-%d') AS DATE) AS y", sql("SELECT PARSE_DATE('%F', s) AS y"));
        assertEquals("SELECT list_extract(list_transform([(d)], _floci_format_value -> (lpad(CAST(day(_floci_format_value)"
                + " AS VARCHAR), 2, ' ') || strftime(_floci_format_value, '/%m ') || CAST(quarter(_floci_format_value)"
                + " AS VARCHAR))), 1) AS y", sql("SELECT FORMAT_DATE('%e/%m %Q', d) AS y"));
        String emulated = sql("SELECT FORMAT_DATE('%e/%m %Q', g(d)) AS y");
        assertEquals(1, emulated.split("g\\(d\\)", -1).length - 1, emulated);
        assertEquals("SELECT strftime(d, '%%K a%%') AS y", sql("SELECT FORMAT_DATE('%K a%', d) AS y"));
        assertTrue(sql("SELECT FORMAT_TIMESTAMP(f, d) AS y").startsWith("SELECT list_extract(list_transform([struct_pack(v := (d),"
                + " f := (f))], _floci_format_row -> CASE WHEN _floci_format_row.f IS NULL OR _floci_format_row.v IS NULL"
                + " THEN NULL ELSE array_to_string(list_transform(regexp_extract_all(_floci_format_row.f,"
                + " '%E\\*S|%E[0-9]+S|%E4Y|%Ez|%.?|[^%]+'), _floci_format_element -> CASE WHEN regexp_full_match("
                + "_floci_format_element, '%E[0-9]+S') THEN list_extract(list_transform([CASE WHEN length(ltrim("
                + "regexp_extract(_floci_format_element, '[0-9]+'), '0')) > 2 THEN 18 ELSE least(CAST('0' || ltrim("
                + "regexp_extract(_floci_format_element, '[0-9]+'), '0') AS INTEGER), 18) END], _floci_format_digits ->"));
        assertTrue(sql("SELECT FORMAT_TIMESTAMP(f, d) AS y").contains(" ELSE CASE _floci_format_element WHEN '%%' THEN"
                + " strftime(_floci_format_row.v, '%%')"));
        assertEquals("SELECT strftime(d, '%%H:%m %%s') AS y", sql("SELECT FORMAT_DATE('%H:%m %s', d) AS y"));
        assertEquals("SELECT strftime((DATE '1970-01-01' + t), '%%Y %H %%z') AS y", sql("SELECT FORMAT_TIME('%Y %H %z', t) AS y"));
        assertEquals("SELECT strftime(dt, '%H %%z') AS y", sql("SELECT FORMAT_DATETIME('%H %z', dt) AS y"));
        assertEquals("SELECT list_extract(list_transform([(d)], _floci_format_value -> lpad(CAST((weekofyear(_floci_format_value)"
                + " - 1) * 7 + isodow(_floci_format_value) AS VARCHAR), 3, '0')), 1) AS y", sql("SELECT FORMAT_DATE('%J', d) AS y"));
        assertEquals("SELECT strftime((DATE '1970-01-01' + t), '%%J') AS y", sql("SELECT FORMAT_TIME('%J', t) AS y"));
        String dateRow = sql("SELECT FORMAT_DATE(f, d) AS y");
        assertTrue(dateRow.contains("WHEN '%Y' THEN") && !dateRow.contains("WHEN '%H' THEN")
                && !dateRow.contains("regexp_full_match"), dateRow);
        assertEquals("SELECT list_extract(list_transform([(ts)], _floci_format_value -> CAST((epoch_us(_floci_format_value)"
                + " - (epoch_us(_floci_format_value) % 1000000 + 1000000) % 1000000) // 1000000 AS VARCHAR)), 1) AS y",
                sql("SELECT FORMAT_TIMESTAMP('%s', ts) AS y"));
        assertEquals("SELECT list_extract(list_transform([(ts)], _floci_format_value -> (strftime(_floci_format_value, '%S.')"
                + " || left(rpad(strftime(_floci_format_value, '%f'), 18, '0'), 18))), 1) AS y",
                sql("SELECT FORMAT_TIMESTAMP('%E100S', ts) AS y"));
        String perRow = sql("SELECT FORMAT_DATE(h(f), g(d)) AS y");
        assertEquals(1, perRow.split("g\\(d\\)", -1).length - 1, perRow);
        assertEquals(1, perRow.split("h\\(f\\)", -1).length - 1, perRow);
        assertEquals("SELECT CAST(try_strptime(s, '%Y-%m-%d') AS DATE) AS y", sql("SELECT SAFE.PARSE_DATE('%F', s) AS y"));
        assertTrue(sql("SELECT SAFE.PARSE_TIMESTAMP('%F %R', s) AS y").contains("try_strptime(s, '%Y-%m-%d %H:%M')"));
        assertEquals("SELECT (dayofweek(d) + 1) AS w", sql("SELECT EXTRACT(DAYOFWEEK FROM d) AS w"));
        assertEquals("SELECT regexp_extract(s, 'a(b)', 1) AS x", sql("SELECT REGEXP_EXTRACT(s, r'a(b)') AS x"));
        assertEquals("SELECT regexp_replace(s, 'a', 'b', 'g') AS x", sql("SELECT REGEXP_REPLACE(s, 'a', 'b') AS x"));
        assertEquals("SELECT {'a': 1, 'b': 'x'} AS s", sql("SELECT STRUCT(1 AS a, 'x' AS b) AS s"));
        assertEquals("SELECT len(arr) AS n", sql("SELECT ARRAY_LENGTH(arr) AS n"));
        assertEquals("SELECT ARRAY_AGG(value ORDER BY ordinal) FILTER (WHERE (value) IS NOT NULL) AS \"values\"",
                sql("SELECT ARRAY_AGG(value IGNORE NULLS ORDER BY ordinal) AS values"));
        assertEquals("SELECT quantile_disc(counts, list_transform(generate_series(0, CAST(4 AS BIGINT)), x -> CAST(x AS DOUBLE) / CAST(4 AS DOUBLE))) AS \"values\"",
                sql("SELECT APPROX_QUANTILES(counts, 4) AS values"));
        assertEquals("SELECT quantile_disc(counts, list_transform(generate_series(0, CAST(4 AS BIGINT)), x -> CAST(x AS DOUBLE) / CAST(4 AS DOUBLE))) AS \"values\"",
                sql("SELECT APPROX_QUANTILES(counts, 4 IGNORE NULLS) AS values"));
        assertEquals("SELECT COUNT(DISTINCT value) AS count", sql("SELECT APPROX_COUNT_DISTINCT(value) AS count"));
        assertEquals("SELECT CAST(try_strptime(value, '%Y%m%d') AS DATE) AS parsed",
                sql("SELECT SAFE.PARSE_DATE('%Y%m%d', value) AS parsed"));
        assertEquals("SELECT lower(hex(value)) AS encoded", sql("SELECT TO_HEX(value) AS encoded"));
        assertEquals("SELECT from_hex(md5(value)) AS md5, from_hex(sha1(value)) AS sha1,"
                        + " from_hex(sha256(value)) AS sha256",
                sql("SELECT MD5(value) AS md5, SHA1(value) AS sha1, SHA256(value) AS sha256"));
        assertEquals("SELECT lower(hex(from_hex(md5(UPPER(id))))) AS md5,"
                        + " lower(hex(from_hex(sha1(UPPER(id))))) AS sha1",
                sql("SELECT TO_HEX(MD5(UPPER(id))) AS md5, TO_HEX(SHA1(UPPER(id))) AS sha1"));
    }

    @Test
    void aggregateFunctionModifiersKeepTheirGoogleSqlMeaning() {
        assertEquals("SELECT list_extract(list_transform([list_filter(list(items ORDER BY id),"
                        + " _floci_concat_array -> _floci_concat_array IS NOT NULL)],"
                        + " _floci_concat_arrays -> CASE WHEN coalesce(len(_floci_concat_arrays), 0) = 0"
                        + " THEN NULL ELSE flatten(list_slice(_floci_concat_arrays, 1, 2)) END), 1) AS f0_"
                        + " FROM \"ds\".\"t\"",
                sql("SELECT ARRAY_CONCAT_AGG(items ORDER BY id LIMIT 2) FROM ds.t"));

        String quantiles = sql("SELECT APPROX_QUANTILES(x, 2 RESPECT NULLS) FROM ds.t");
        assertTrue(quantiles.contains("ORDER BY x ASC NULLS FIRST"), quantiles);
        assertTrue(quantiles.contains("list_filter(_floci_quantile_values"), quantiles);
        assertFalse(quantiles.contains("2 RESPECT NULLS"), quantiles);
    }

    @Test
    void timestampFunctionsPreserveTimeAndHonorZones() {
        assertEquals("SELECT timezone('UTC', date_trunc('hour', timezone('UTC',"
                        + " CAST('2024-05-17 12:34:56+00' AS TIMESTAMPTZ)))) AS f0_",
                sql("SELECT TIMESTAMP_TRUNC('2024-05-17 12:34:56+00', HOUR)"));
        assertEquals("SELECT date_trunc('hour', CAST('2024-05-17 12:34:56' AS TIMESTAMP)) AS f0_",
                sql("SELECT DATETIME_TRUNC('2024-05-17 12:34:56', HOUR)"));
        assertEquals("SELECT timezone('America/Los_Angeles', date_trunc('day', timezone('America/Los_Angeles',"
                        + " CAST('2024-01-01 07:34:56+00' AS TIMESTAMPTZ)))) AS f0_",
                sql("SELECT DATE_TRUNC('2024-01-01 07:34:56+00', DAY, 'America/Los_Angeles')"));
        String safe = sql("SELECT SAFE.PARSE_TIMESTAMP('%Y-%m-%d %H:%M:%S', '2024-01-01 00:00:00',"
                + " 'America/Los_Angeles')");
        assertTrue(safe.contains("try_strptime('2024-01-01 00:00:00', '%Y-%m-%d %H:%M:%S')"), safe);
        assertTrue(safe.contains("timezone('America/Los_Angeles', CAST(_floci_parsed_timestamp AS TIMESTAMP))"), safe);
        String parsed = sql("SELECT PARSE_TIMESTAMP('%Y-%m-%d', value) AS parsed");
        assertTrue(parsed.contains("strptime(value, '%Y-%m-%d')"), parsed);
        assertTrue(parsed.contains("timezone('UTC', CAST(_floci_parsed_timestamp AS TIMESTAMP))"), parsed);
    }

    @Test
    void indexedArraySubqueriesCanReferenceVisibleCtes() {
        SqlDialectTranslator.Translation translated = translate(
                "WITH x AS (SELECT 1 AS id) SELECT ARRAY(SELECT id FROM x)[OFFSET(0)] AS value");

        assertTrue(translated.tables().isEmpty(), translated.tables().toString());
        assertTrue(translated.sql().contains("SELECT id FROM \"x\""), translated.sql());
    }

    @Test
    void weekWeekdayWorksInEverySupportedDatePartPosition() {
        String extracted = sql("SELECT EXTRACT(WEEK(TUESDAY) FROM d) AS week FROM ds.t");
        assertTrue(extracted.contains("CASE WHEN CAST(d AS DATE) <"), extracted);
        assertTrue(extracted.contains("date_diff('day'"), extracted);
        assertTrue(extracted.contains("// 7) + 1 END"), extracted);
    }

    @Test
    void translatesBigQueryPivotSyntaxToDuckDb() {
        assertEquals("SELECT *"
                        + " FROM (PIVOT \"ds\".\"metrics\" ON metric IN ('one', 'two') USING ANY_VALUE(value))",
                sql("SELECT * FROM ds.metrics PIVOT (ANY_VALUE(value) FOR metric IN ('one', 'two'))"));
    }

    @Test
    void pivotAfterJoinDecoratesOnlyItsOwnInput() {
        String translated = sql("SELECT a.id, p.one FROM ds.accounts a JOIN ds.metrics"
                + " PIVOT (ANY_VALUE(value) FOR metric IN ('one')) p ON a.id = p.id");

        assertTrue(translated.contains("JOIN (PIVOT \"ds\".\"metrics\" ON metric"), translated);
        assertFalse(translated.contains("PIVOT \"ds\".\"accounts\""), translated);
    }

    @Test
    void containsSubstrNormalizesAndComparesScalarStringsCaseInsensitively() {
        SqlDialectTranslator.Translation translated = translate(
                "SELECT CONTAINS_SUBSTR(name, ',') AS has_comma FROM ds.t");

        assertEquals("SELECT contains(_floci_nfkc_casefold(name), _floci_nfkc_casefold(','))"
                + " AS has_comma FROM \"ds\".\"t\"", translated.sql());
        assertTrue(translated.needsUnicodeCaseFold());
    }

    @Test
    void standardSingleValueUnpivotPreservesItsDefaultNullExclusion() {
        SqlDialectTranslator.Translation t = translate(
                "SELECT metric, metric_value FROM (SELECT 1 AS present, CAST(NULL AS INT64) AS absent)\n"
                        + "    UNPIVOT (metric_value FOR metric IN (present, absent))");

        assertEquals(Set.of(), t.tables());
        assertEquals("SELECT metric, metric_value FROM (SELECT 1 AS present, CAST(NULL AS BIGINT) AS absent) AS \"_floci_derived_0\""
                + "\n    UNPIVOT (metric_value FOR metric IN (present, absent))", t.sql());
        assertEquals(SqlDialectTranslator.StatementKind.QUERY, statement(
                "SELECT metric, metric_value FROM (SELECT 1 AS present, CAST(NULL AS INT64) AS absent)\n"
                        + "    UNPIVOT (metric_value FOR metric IN (present, absent))").kind());
        assertEquals("SELECT id, metric, value FROM \"ds\".\"metrics\""
                        + " UNPIVOT INCLUDE NULLS (value FOR metric IN (a, b))",
                sql("SELECT id, metric, value FROM ds.metrics UNPIVOT INCLUDE NULLS (value FOR metric IN (a, b))"));
    }

    @Test
    void jsonTypeMapsDuckDbTypesToBigQueryJsonTypeNames() {
        assertEquals("SELECT (CASE json_type(j)"
                        + " WHEN 'OBJECT' THEN 'object' WHEN 'ARRAY' THEN 'array' WHEN 'VARCHAR' THEN 'string'"
                        + " WHEN 'BOOLEAN' THEN 'boolean' WHEN 'NULL' THEN 'null'"
                        + " WHEN 'BIGINT' THEN 'number' WHEN 'UBIGINT' THEN 'number' WHEN 'HUGEINT' THEN 'number'"
                        + " WHEN 'DOUBLE' THEN 'number' END) AS t",
                sql("SELECT JSON_TYPE(j) AS t"));
    }

    @Test
    void implicitSelectAliasesBecomeQuotedAsAliases() {
        assertEquals("SELECT 1 AS \"sample\", x AS \"name\", COUNT(*) AS \"value\" FROM \"ds\".\"t\"",
                sql("SELECT 1 sample, x name, COUNT(*) value FROM ds.t"));
        assertEquals("SELECT name FROM (SELECT 'a' AS \"name\", (CASE WHEN x THEN 1 ELSE 2 END) AS \"type\" FROM \"ds\".\"t\") AS \"_floci_derived_0\"",
                sql("SELECT name FROM (SELECT 'a' name, IF(x, 1, 2) type FROM ds.t)"));
        assertEquals("SELECT CASE WHEN x THEN 1 END AS \"year\" FROM \"ds\".\"t\"",
                sql("SELECT CASE WHEN x THEN 1 END year FROM ds.t"));
    }

    @Test
    void implicitAliasAfterAParameterIsKept() {
        List<Map<String, Object>> named = List.of(param("p", "STRING", "abc"), param("n", "INT64", "5"));
        assertEquals("SELECT CAST('abc' AS VARCHAR) AS \"x\", CAST(5 AS BIGINT) AS \"n2\"",
                SqlDialectTranslator.translate("SELECT @p x, @n n2", "test-project", null,
                        new SqlDialectTranslator.QueryParameters(named, "NAMED")).sql());
        List<Map<String, Object>> positional = List.of(Map.of(
                "parameterType", Map.of("type", "INT64"), "parameterValue", Map.of("value", "7")));
        assertEquals("SELECT CAST(7 AS BIGINT) AS \"y\"",
                SqlDialectTranslator.translate("SELECT ? y", "test-project", null,
                        new SqlDialectTranslator.QueryParameters(positional, "POSITIONAL")).sql());
    }

    @Test
    void intervalDatePartsAreNotAliasesButAnAliasAfterThemIs() {
        assertEquals("SELECT d + INTERVAL 1 DAY AS \"name\" FROM \"ds\".\"t\"", sql("SELECT d + INTERVAL 1 DAY name FROM ds.t"));
        assertEquals("SELECT (d + INTERVAL 1 DAY) AS \"name\" FROM \"ds\".\"t\"", sql("SELECT (d + INTERVAL 1 DAY) name FROM ds.t"));
        assertEquals("SELECT d + INTERVAL 1 DAY AS f0_ FROM \"ds\".\"t\"", sql("SELECT d + INTERVAL 1 DAY FROM ds.t"));
        assertEquals("SELECT INTERVAL '1:2' HOUR TO MINUTE AS f0_ FROM \"ds\".\"t\"", sql("SELECT INTERVAL '1:2' HOUR TO MINUTE FROM ds.t"));
    }

    @Test
    void anAliasAfterNullTrueOrFalseIsAnAlias() {
        assertEquals("SELECT x IS NOT NULL AS \"c\", x IS NULL AS \"d\", NULL AS \"n\", TRUE AS \"t\", x IS NOT FALSE AS \"nf\""
                        + " FROM \"ds\".\"t\"",
                sql("SELECT x IS NOT NULL c, x IS NULL d, NULL n, TRUE t, x IS NOT FALSE nf FROM ds.t"));
        assertEquals("SELECT x IS NOT NULL AS f0_, NULL AS f1_, x IS TRUE AS f2_ FROM \"ds\".\"t\"",
                sql("SELECT x IS NOT NULL, NULL, x IS TRUE FROM ds.t"));
    }

    @Test
    void isDistinctFromIsAnOperatorNotAFromClause() {
        assertEquals("SELECT a IS DISTINCT FROM b AS \"d\", a IS NOT DISTINCT FROM NULL AS c FROM \"ds\".\"t\""
                        + " WHERE a IS DISTINCT FROM b",
                sql("SELECT a IS DISTINCT FROM b d, a IS NOT DISTINCT FROM NULL AS c FROM ds.t WHERE a IS DISTINCT FROM b"));
        assertEquals("SELECT a IS DISTINCT FROM b AS f0_ FROM \"ds\".\"t\"", sql("SELECT a IS DISTINCT FROM b FROM ds.t"));
        assertEquals("SELECT CASE WHEN a IS DISTINCT FROM b THEN 1 END AS k FROM \"ds\".\"t\" AS \"t1\""
                        + " JOIN \"ds\".\"u\" AS \"t2\" ON t1.a IS NOT DISTINCT FROM t2.a",
                sql("SELECT CASE WHEN a IS DISTINCT FROM b THEN 1 END AS k FROM ds.t t1 JOIN ds.u t2"
                        + " ON t1.a IS NOT DISTINCT FROM t2.a"));
        assertEquals("SELECT ARRAY(SELECT {'d': x IS DISTINCT FROM 2, 'x': x} FROM UNNEST([1, 2]) AS \"_unnest_x\"(\"x\")) AS \"a\"",
                sql("SELECT ARRAY(SELECT AS STRUCT x IS DISTINCT FROM 2 AS d, x FROM UNNEST([1, 2]) x) a"));
        String star = sql("SELECT a IS DISTINCT FROM b AS d, * FROM ds.t");
        assertTrue(star.startsWith("SELECT a IS DISTINCT FROM b AS d, * NOT SIMILAR TO '^(") && star.contains("_TABLE_SUFFIX"), star);
    }

    @Test
    void offsetAsANameIsQuoted() {
        assertEquals("SELECT 1 \"offset\"", sql("SELECT 1 offset"));
        assertEquals("SELECT 1 AS \"offset\"", sql("SELECT 1 AS offset"));
        assertEquals("SELECT \"offset\" + 1 AS o2 FROM \"ds\".\"t\" WHERE \"offset\" = 1 ORDER BY \"offset\"", sql("SELECT offset + 1 AS o2 FROM ds.t WHERE offset = 1 ORDER BY offset"));
    }

    @Test
    void offsetStructAliasesKeepTheirValueSeparateFromExpandedFields() {
        String translated = sql("SELECT item, item.x, x, pos FROM"
                + " UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos");
        assertTrue(translated.contains("\"_floci_offset_value_0\" AS \"item\""), translated);
        assertTrue(translated.contains("\"_floci_offset_value_0\".x"), translated);
        assertTrue(translated.contains("UNNEST([\"_value\"], max_depth := 2) AS \"item\""), translated);
        String stars = sql("SELECT * FROM UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos");
        assertTrue(stars.contains("NOT SIMILAR TO '^(_floci_offset_value_0)$'"), stars);
        String qualified = sql("SELECT item.* FROM UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos");
        assertTrue(qualified.startsWith("SELECT \"_floci_offset_value_0\".*"), qualified);
    }

    @Test
    void offsetRangeAliasesDoNotCaptureDatePartsOrIntervalUnits() {
        String month = sql("SELECT DATE_TRUNC(DATE '2024-05-17', MONTH)"
                + " FROM UNNEST([1]) month WITH OFFSET AS pos");
        assertTrue(month.contains("date_trunc('month', CAST('2024-05-17' AS DATE))"), month);
        assertFalse(month.contains("date_trunc('\"_floci_offset_value_0\"'"), month);

        String day = sql("SELECT DATE '2024-05-17' + INTERVAL 1 DAY"
                + " FROM UNNEST([1]) day WITH OFFSET AS pos");
        assertTrue(day.contains("INTERVAL 1 DAY"), day);
        assertFalse(day.contains("INTERVAL 1 \"_floci_offset_value_0\""), day);
    }

    @Test
    void selectAsStructKeepsOffsetAliasesVisibleToLaterClauses() {
        String translated = sql("SELECT AS STRUCT x FROM UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos"
                + " WHERE item.x = 1");
        assertTrue(translated.contains("WHERE \"_floci_offset_value_0\".x = 1"), translated);
    }

    @Test
    void arrayConcatAggReturnsNullWhenNoNonNullArraysRemain() {
        String translated = sql("SELECT ARRAY_CONCAT_AGG(items) FROM ds.t");
        assertTrue(translated.contains("coalesce(len(_floci_concat_arrays), 0) = 0 THEN NULL ELSE flatten("),
                translated);
    }

    @Test
    void selectAsStructFieldsKeepNestedQueriesForLaterRewrites() {
        String translated = sql("SELECT AS STRUCT (SELECT 1 sample) AS child");
        assertTrue(translated.contains("\"sample\""), translated);
    }

    @Test
    void localSourceColumnsShadowEnclosingOffsetAliases() {
        String cte = sql("WITH local AS (SELECT STRUCT(2 AS x) AS item) SELECT (SELECT item.x FROM local) AS x"
                + " FROM UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos");
        assertTrue(cte.contains("SELECT item.x FROM"), cte);
        String table = sql("SELECT (SELECT item.x FROM ds.local) AS x"
                + " FROM UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos");
        assertTrue(table.contains("SELECT \"_floci_offset_value_0\".x FROM"), table);

        String knownTable = SqlDialectTranslator.translate(
                "SELECT (SELECT item.x FROM ds.local) AS x"
                        + " FROM UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos",
                "test-project", null, SqlDialectTranslator.QueryParameters.none(),
                ref -> Set.of("item")).sql();
        assertTrue(knownTable.contains("SELECT item.x FROM"), knownTable);
    }

    @Test
    void concatOfBytesKeepsTheBytesType() {
        assertEquals("SELECT ((from_hex('4142')) || (from_hex('4344'))) AS payload",
                sql("SELECT CONCAT(b'AB', b'CD') AS payload"));
        String casted = sql("SELECT CONCAT(CAST(x AS BYTES), FROM_HEX('00')) AS payload FROM ds.t");
        assertTrue(casted.contains(" || ") && !casted.contains("concat("), casted);
        assertTrue(sql("SELECT CONCAT('a', 1)").contains("concat("));
    }

    @Test
    void pivotAggregatePrefixesComeBeforeThePivotValue() {
        String translated = sql("SELECT total_x FROM (SELECT 'x' AS k, 1 AS v) PIVOT(SUM(v) AS total FOR k IN ('x'))");
        assertTrue(translated.contains("SELECT * RENAME (\"x_total\" AS \"total_x\") FROM"), translated);
        String aliased = sql("SELECT * FROM (SELECT 'x' AS k, 1 AS v) PIVOT(SUM(v) total, MAX(v) AS m FOR k IN ('x' AS a, 1))");
        assertTrue(aliased.contains("\"a_total\" AS \"total_a\", \"1_total\" AS \"total_1\","
                + " \"a_m\" AS \"m_a\", \"1_m\" AS \"m_1\""), aliased);
        assertFalse(sql("SELECT * FROM (SELECT 'x' AS k, 1 AS v) PIVOT(SUM(v) FOR k IN ('x'))").contains("RENAME"));
    }

    @Test
    void parseTimestampPreservesTheTypeReturnedByTheParser() {
        String parsed = sql("SELECT PARSE_TIMESTAMP('%Y-%m-%d %H:%M:%S%z',"
                + " '2024-01-01 00:00:00+0000', 'America/Los_Angeles')");
        assertTrue(parsed.contains("typeof(_floci_parsed_timestamp) = 'TIMESTAMP WITH TIME ZONE'"), parsed);
        assertTrue(parsed.contains("THEN CAST(_floci_parsed_timestamp AS TIMESTAMPTZ)"), parsed);
        assertEquals(1, parsed.split("strptime", -1).length - 1, parsed);
    }

    @Test
    void correlatedOffsetAliasesResolveThroughEnclosingQueries() {
        String translated = sql("SELECT (SELECT (SELECT item.x)) AS x FROM"
                + " UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos");
        assertTrue(translated.contains("SELECT (SELECT (SELECT \"_floci_offset_value_0\".x"), translated);
        String shadowed = sql("SELECT (SELECT item.x FROM (SELECT 2 AS x) item) AS x FROM"
                + " UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos");
        assertTrue(shadowed.contains("SELECT item.x FROM"), shadowed);
        String column = sql("SELECT (SELECT item.x FROM (SELECT STRUCT(2 AS x) AS item) local) AS x FROM"
                + " UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos");
        assertTrue(column.contains("SELECT item.x FROM"), column);
    }

    @Test
    void replacementStarsExcludeOffsetHelpersInTheirOwnScope() {
        for (String projection : List.of("* REPLACE(x + 1 AS x)", "* REPLACE(x + 1 AS x), pos AS original")) {
            String translated = sql("SELECT * FROM (SELECT " + projection
                    + " FROM UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos) q");
            assertTrue(translated.contains("* EXCLUDE (\"_floci_offset_value_0\") REPLACE"), translated);
        }
    }

    @Test
    void cteNamedOffsetIsQuoted() {
        assertEquals("WITH \"offset\" AS (SELECT 1 AS x) SELECT x FROM \"offset\"", sql("WITH offset AS (SELECT 1 AS x) SELECT x FROM offset"));
        String named = sql("SELECT v, o FROM UNNEST([1]) v WITH OFFSET AS o");
        assertTrue(named.contains("UNNEST([\"_value\"], max_depth := 2) AS \"v\""), named);
        assertTrue(named.contains("\"_ordinality\" - 1 AS \"o\""), named);
        String defaultOffset = sql("SELECT v, offset FROM UNNEST([1]) v WITH OFFSET");
        assertTrue(defaultOffset.contains("\"_ordinality\" - 1 AS \"offset\""), defaultOffset);
        String aliasFree = sql("SELECT * FROM UNNEST([1]) WITH OFFSET");
        assertTrue(aliasFree.contains("SELECT UNNEST([\"_value\"], max_depth := 2),"
                + " \"_ordinality\" - 1 AS \"offset\""),
                aliasFree);
        assertTrue(aliasFree.contains("UNNEST([1]) WITH ORDINALITY"), aliasFree);
    }

    @Test
    void offsetKeywordsStayKeywords() {
        assertEquals("SELECT x, 5 \"offset\" FROM \"ds\".\"t\" ORDER BY x LIMIT 1 OFFSET 1", sql("SELECT x, 5 offset FROM ds.t ORDER BY x LIMIT 1 OFFSET 1"));
        assertTrue(sql("SELECT a[OFFSET(1)] FROM ds.t").startsWith("SELECT (list_transform([struct_pack(a := a, i := 1)]"));
    }

    @Test
    void parenthesizedArrayOffsetRetainsOuterSelect() {
        String translated = sql("SELECT ([1, 2])[OFFSET(0)] AS value");

        assertTrue(translated.startsWith("SELECT "), translated);
        assertTrue(translated.endsWith(" AS value"), translated);
        assertFalse(translated.contains("[OFFSET("), translated);
    }

    @Test
    void selectAsStructAppliesOffsetBoundsBeforeRenderingItsFields() {
        String safe = sql("SELECT AS STRUCT ['zero'][SAFE_OFFSET(-1)] AS value");
        assertTrue(safe.contains("CASE WHEN _s.i >= 0"), safe);
        assertFalse(safe.contains("[SAFE_OFFSET("), safe);

        String ordinary = sql("SELECT AS STRUCT ['zero'][OFFSET(1)] AS value");
        assertTrue(ordinary.contains("is out of bounds (overflow)"), ordinary);
        assertFalse(ordinary.contains("[OFFSET("), ordinary);
    }

    @Test
    void indexedNestedStructQueriesAreTranslatedBeforeOffsetRewriting() {
        String translated = sql("SELECT ARRAY(SELECT AS STRUCT 1 AS id)[OFFSET(0)].id");

        assertTrue(translated.contains("SELECT {'id': 1}"), translated);
        assertFalse(translated.contains("SELECT AS STRUCT"), translated);
        assertTrue(translated.contains("is out of bounds"), translated);
    }

    @Test
    void selectAsStructExceptExpandsAnAliasedStructUnnest() {
        String translated = sql("SELECT ARRAY(SELECT AS STRUCT * EXCEPT(id)"
                + " FROM UNNEST([STRUCT('1' AS id, 'example' AS type)]) al)");

        assertTrue(translated.contains("struct_pack(*COLUMNS(* EXCLUDE"
                + " (\"_floci_offset_value_0\", \"id\")))"), translated);
        assertTrue(translated.contains("max_depth := 2) AS \"al\""), translated);
        assertFalse(translated.contains("NOT SIMILAR"), translated);
    }

    @Test
    void structUnnestAliasDenotesTheWholeRecordDespiteAFieldNameCollision() {
        String translated = sql("SELECT al, al.al, al.x, al.*"
                + " FROM UNNEST([STRUCT(1 AS al, 2 AS x)]) al");
        assertTrue(translated.startsWith("SELECT \"_floci_offset_value_0\" AS \"al\","
                + " \"_floci_offset_value_0\".al, \"_floci_offset_value_0\".x,"
                + " \"_floci_offset_value_0\".*"), translated);
        assertTrue(sql("SELECT al AS result FROM UNNEST([STRUCT(1 AS al, 2 AS x)]) al")
                .startsWith("SELECT \"_floci_offset_value_0\" AS result"));
        assertTrue(sql("SELECT x al FROM UNNEST([STRUCT(1 AS al, 2 AS x)]) al")
                .startsWith("SELECT x AS \"al\" FROM"));
        String nested = sql("SELECT al, (SELECT al FROM (SELECT 3 AS al)) AS nested"
                + " FROM UNNEST([STRUCT(1 AS al, 2 AS x)]) al");
        assertTrue(nested.contains("(SELECT al FROM (SELECT 3 AS al)"), nested);
    }

    @Test
    void chainedSafeOffsetsKeepTheIndexedStructAsTheFieldReceiver() {
        String translated = sql("SELECT records[SAFE_OFFSET(0)].elements[SAFE_OFFSET(0)].value FROM ds.t");

        assertTrue(translated.contains("struct_pack(a := records, i := 0)"), translated);
        assertTrue(translated.contains(")[1]).elements"), translated);
        assertFalse(translated.contains("[SAFE_OFFSET("), translated);
    }

    @Test
    void intervalExpressionStepSizesAreParenthesized() {
        assertEquals("SELECT d + INTERVAL (-5) DAY AS f0_ FROM \"ds\".\"t\"", sql("SELECT d + INTERVAL -5 DAY FROM ds.t"));
        assertEquals("SELECT d + INTERVAL (n * 2) HOUR AS \"d2\" FROM \"ds\".\"t\"", sql("SELECT d + INTERVAL n * 2 HOUR d2 FROM ds.t"));
        assertEquals("SELECT CAST(d + INTERVAL (-x) DAY AS DATE) AS f0_ FROM \"ds\".\"t\"",
                sql("SELECT DATE_ADD(d, INTERVAL -x DAY) FROM ds.t"));
        assertEquals("SELECT CAST(d + INTERVAL (n + day) DAY AS DATE) AS f0_ FROM \"ds\".\"t\"",
                sql("SELECT DATE_ADD(d, INTERVAL n + day DAY) FROM ds.t"));
        assertEquals("SELECT d + INTERVAL (x) DAY AS \"day\" FROM \"ds\".\"t\"", sql("SELECT d + INTERVAL x DAY day FROM ds.t"));
    }

    @Test
    void intervalParameterAndNestedStepSizesAreParenthesized() {
        assertEquals("SELECT CAST(d + INTERVAL (CAST(5 AS BIGINT)) DAY AS DATE) AS \"a\","
                + " CAST(d + INTERVAL (-CAST(5 AS BIGINT)) DAY AS DATE) AS \"b\" FROM \"ds\".\"t\"", SqlDialectTranslator.translate(
                "SELECT DATE_ADD(d, INTERVAL @n DAY) a, DATE_ADD(d, INTERVAL -@n DAY) b FROM ds.t", "test-project", null,
                new SqlDialectTranslator.QueryParameters(List.of(param("n", "INT64", "5")), "NAMED")).sql());
        assertEquals("SELECT CAST(d + INTERVAL (-EXTRACT(DAY FROM CAST(d + INTERVAL (-1) DAY AS DATE))) DAY AS DATE)"
                + " AS f0_ FROM \"ds\".\"t\"", sql("SELECT DATE_ADD(d, INTERVAL -EXTRACT(DAY FROM DATE_ADD(d, INTERVAL -1 DAY)) DAY) FROM ds.t"));
    }

    @Test
    void intervalLiteralStepSizesStayAsTheyAre() {
        assertEquals("SELECT d + INTERVAL 5 DAY AS f0_, d + INTERVAL '-5' DAY AS f1_, d + INTERVAL (-5) DAY AS f2_"
                + " FROM \"ds\".\"t\"", sql("SELECT d + INTERVAL 5 DAY, d + INTERVAL '-5' DAY, d + INTERVAL (-5) DAY FROM ds.t"));
        assertEquals("SELECT INTERVAL '1:2' HOUR TO MINUTE AS f0_ FROM \"ds\".\"t\"", sql("SELECT INTERVAL '1:2' HOUR TO MINUTE FROM ds.t"));
    }

    @Test
    void havingCanDereferenceAnAggregateStructAlias() {
        assertEquals("""
                WITH selected AS (
                  SELECT {'group_id': group_id, 'latest_at': MAX(event_time)} AS latest_per_group
                  FROM "ds"."t" GROUP BY group_id HAVING (MAX(event_time)) <= '2021-01-30'
                )
                SELECT * FROM "selected\"""",
                sql("""
                        WITH selected AS (
                          SELECT {'group_id': group_id, 'latest_at': MAX(event_time)} AS latest_per_group
                          FROM ds.t GROUP BY group_id HAVING latest_per_group.latest_at <= '2021-01-30'
                        )
                        SELECT * FROM selected"""));
    }

    @Test
    void havingCanDereferenceGoogleSqlStructAliasFields() {
        assertEquals("""
                SELECT {'group_id': group_id, 'latest_at': MAX(event_time)} AS latest_per_group
                FROM "ds"."t" GROUP BY group_id HAVING (MAX(event_time)) <= '2021-01-30'""",
                sql("""
                        SELECT STRUCT(group_id, MAX(event_time) AS latest_at) AS latest_per_group
                        FROM ds.t GROUP BY group_id HAVING latest_per_group.latest_at <= '2021-01-30'"""));
    }

    @Test
    void havingStructAliasWorksWithAnInlineSourceQuery() {
        String translated = sql("""
                WITH grouped AS (
                  SELECT {'group_id': group_id, 'latest_at': MAX(event_time)} AS latest_per_group
                  FROM (
                    SELECT 'first' AS group_id, TIMESTAMP '2021-01-29 00:00:00+00' AS event_time
                    UNION ALL SELECT 'first', TIMESTAMP '2021-01-30 00:00:00+00'
                  )
                  GROUP BY group_id
                  HAVING latest_per_group.latest_at <= TIMESTAMP '2021-01-30 00:00:00+00'
                )
                SELECT latest_per_group.group_id FROM grouped""");

        assertTrue(translated.contains(
                "HAVING (MAX(event_time)) <= CAST('2021-01-30 00:00:00+00' AS TIMESTAMPTZ)"), translated);
    }

    @Test
    void havingStructAliasDoesNotCaptureAnAliasInsideANestedQuery() {
        String translated = sql("SELECT STRUCT(MAX(x) AS n) AS s FROM UNNEST([1,2]) x"
                + " HAVING (SELECT s.n FROM (SELECT STRUCT(0 AS n) AS s)) = 0");

        assertTrue(translated.contains("HAVING (SELECT s.n FROM (SELECT {'n': 0} AS s)"), translated);
        assertFalse(translated.contains("HAVING (SELECT (MAX(x))"), translated);
    }

    @Test
    void timestampSubCoercesStringLiterals() {
        assertEquals("SELECT (CAST('2021-01-30' AS TIMESTAMPTZ) - INTERVAL 3 DAY) AS f0_",
                sql("SELECT TIMESTAMP_SUB('2021-01-30', INTERVAL 3 DAY)"));
    }

    @Test
    void dateTruncPreservesItsOverloadedInputType() {
        String translated = sql("""
                SELECT DATE_TRUNC(DATE '2021-09-21', WEEK(TUESDAY)) AS date_value,
                       DATE_TRUNC(DATETIME '2021-09-21 12:34:56', WEEK(TUESDAY)) AS datetime_value,
                       DATE_TRUNC(TIMESTAMP '2021-09-21 12:34:56+00', WEEK(TUESDAY)) AS timestamp_value
                """);

        assertTrue(translated.contains("cast_to_type((date_trunc('week', CAST('2021-09-21' AS DATE) - INTERVAL 1 DAY) + INTERVAL 1 DAY), CAST('2021-09-21' AS DATE))"), translated);
        assertTrue(translated.contains("cast_to_type((date_trunc('week', CAST('2021-09-21 12:34:56' AS TIMESTAMP) - INTERVAL 1 DAY) + INTERVAL 1 DAY), CAST('2021-09-21 12:34:56' AS TIMESTAMP))"), translated);
        assertTrue(translated.contains("cast_to_type((date_trunc('week', CAST('2021-09-21 12:34:56+00' AS TIMESTAMPTZ) - INTERVAL 1 DAY) + INTERVAL 1 DAY), CAST('2021-09-21 12:34:56+00' AS TIMESTAMPTZ))"), translated);

        String timestampArithmetic = sql("""
                SELECT TIMESTAMP_ADD(
                  DATE_TRUNC(TIMESTAMP '2021-09-21 12:34:56+00', WEEK(TUESDAY)),
                  INTERVAL 7 DAY)
                """);
        assertTrue(timestampArithmetic.contains("cast_to_type((date_trunc('week', "
                + "CAST('2021-09-21 12:34:56+00' AS TIMESTAMPTZ) - INTERVAL 1 DAY) + INTERVAL 1 DAY), "
                + "CAST('2021-09-21 12:34:56+00' AS TIMESTAMPTZ)) + INTERVAL 7 DAY"), timestampArithmetic);
    }

    @Test
    void reservedWordsAreNotRewrittenIntoAliases() {
        assertEquals("SELECT 1 struct", sql("SELECT 1 struct"));
        assertEquals("SELECT 1 default", sql("SELECT 1 default"));
    }

    @Test
    void implicitAliasRewriteLeavesExpressionsAlone() {
        assertEquals("SELECT a AS name FROM \"ds\".\"t\"", sql("SELECT a AS name FROM ds.t"));
        assertEquals("SELECT a LIKE b AS f0_ FROM \"ds\".\"t\"", sql("SELECT a LIKE b FROM ds.t"));
        assertEquals("SELECT SUM(x) OVER w AS f0_ FROM \"ds\".\"t\" WINDOW w AS (ORDER BY y)",
                sql("SELECT SUM(x) OVER w FROM ds.t WINDOW w AS (ORDER BY y)"));
    }

    @Test
    void safeFromHexValidatesHexWithoutCatchingArgumentErrors() {
        String translated = sql("SELECT SAFE.FROM_HEX(CAST(ERROR('argument failed') AS STRING)) AS decoded");
        assertTrue(translated.contains("regexp_full_match(_floci_hex_value, '[0-9A-Fa-f]*')"), translated);
        assertTrue(translated.contains("THEN from_hex(_floci_hex_value)"), translated);
        assertFalse(translated.contains("TRY("), translated);
        assertEquals(1, translated.split("argument failed", -1).length - 1, translated);
        assertEquals("invalidQuery", invalid("SELECT SAFE.FROM_HEX()").getReason());
        assertEquals("invalidQuery", invalid("SELECT SAFE.FROM_HEX('41', '42')").getReason());
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
        assertEquals("SELECT * NOT SIMILAR TO '^(_PARTITIONDATE|_PARTITIONTIME|_TABLE_SUFFIX)$' FROM \"ds\".\"t\""
                + " WHERE name = CAST('O''Brien' AS VARCHAR)"
                + " AND age >= CAST(21 AS BIGINT) AND ts > CAST('2024-01-02 03:04:05+00:00' AS TIMESTAMPTZ)", out);
    }

    @Test
    void temporalStringParametersStayUntypedLiterals() {
        List<Map<String, Object>> params = List.of(
                param("from", "STRING", "2026-03-01"),
                param("ts", "STRING", "2026-03-05T09:00:00.5Z"),
                param("code", "STRING", "2026-03"));
        String out = SqlDialectTranslator.translate("SELECT d FROM ds.t WHERE d >= @from AND ts > @ts AND c = @code",
                "test-project", null, new SqlDialectTranslator.QueryParameters(params, "NAMED")).sql();
        assertEquals("SELECT d FROM \"ds\".\"t\" WHERE d >= '2026-03-01' AND ts > '2026-03-05T09:00:00.5Z'"
                + " AND c = CAST('2026-03' AS VARCHAR)", out);
    }

    @Test
    void positionalAndArrayParameters() {
        List<Map<String, Object>> params = List.of(Map.of(
                "parameterType", Map.of("type", "ARRAY", "arrayType", Map.of("type", "INT64")),
                "parameterValue", Map.of("arrayValues", List.of(Map.of("value", "1"), Map.of("value", "2")))));
        String out = SqlDialectTranslator.translate("SELECT * FROM ds.t WHERE id IN UNNEST(?)",
                "test-project", null, new SqlDialectTranslator.QueryParameters(params, "POSITIONAL")).sql();
        assertEquals("SELECT * NOT SIMILAR TO '^(_PARTITIONDATE|_PARTITIONTIME|_TABLE_SUFFIX)$' FROM \"ds\".\"t\""
                + " WHERE id IN (SELECT UNNEST([CAST(1 AS BIGINT), CAST(2 AS BIGINT)]))", out);
    }

    @Test
    void positionalParametersKeepTheirSourceOrderThroughRewrites() {
        String offset = positionalSql("SELECT ? AS marker, [10, 20][OFFSET(?)] AS value", "111", "0");
        assertTrue(offset.startsWith("SELECT CAST(111 AS BIGINT) AS marker"), offset);
        assertTrue(offset.contains("struct_pack(a := [10, 20], i := CAST(0 AS BIGINT))"), offset);

        assertEquals("SELECT CAST(111 AS BIGINT) AS marker, (SELECT {'x': CAST(222 AS BIGINT)}) AS s",
                positionalSql("SELECT ? AS marker, (SELECT AS STRUCT ? AS x) AS s", "111", "222"));

        String pivot = positionalSql("SELECT ? AS marker FROM (SELECT 'one' AS metric, ? AS value)"
                + " PIVOT (ANY_VALUE(value) FOR metric IN ('one'))", "111", "222");
        assertTrue(pivot.startsWith("SELECT CAST(111 AS BIGINT) AS marker"), pivot);
        assertTrue(pivot.contains("CAST(222 AS BIGINT) AS value"), pivot);

        String having = positionalSql(
                "SELECT STRUCT(MAX(?) AS x) AS s FROM UNNEST([1]) HAVING s.x > 0", "7");
        assertEquals(2, having.split("CAST\\(7 AS BIGINT\\)", -1).length - 1, having);
    }

    @Test
    void malformedBoolParameterIsRejectedRatherThanCoercedToFalse() {
        String out = SqlDialectTranslator.translate("SELECT * FROM ds.t WHERE active = @flag",
                "test-project", null, new SqlDialectTranslator.QueryParameters(
                        List.of(param("flag", "BOOL", "TRUE")), "NAMED")).sql();
        assertEquals("SELECT * NOT SIMILAR TO '^(_PARTITIONDATE|_PARTITIONTIME|_TABLE_SUFFIX)$'"
                + " FROM \"ds\".\"t\" WHERE active = TRUE", out);

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
        assertEquals(SqlDialectTranslator.StatementKind.DROP_SCHEMA,
                SqlDialectTranslator.parseStatement("DROP SCHEMA ds RESTRICT", "test-project", null).kind());
    }

    @Test
    void cascadeAndRestrictAreOnlyValidOnDropSchema() {
        for (String sql : List.of("DROP TABLE ds.t CASCADE", "DROP TABLE ds.t RESTRICT", "DROP VIEW ds.v CASCADE",
                "DROP MATERIALIZED VIEW ds.v RESTRICT")) {
            GcpException e = assertThrows(GcpException.class,
                    () -> SqlDialectTranslator.parseStatement(sql, "test-project", null), sql);
            assertEquals("invalidQuery", e.getReason(), sql);
        }
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
    void typedArraysAndStructSubqueriesAreTranslatedInDml() {
        String typedArray = dml("INSERT ds.t (items) VALUES (ARRAY<INT64>[1, 2])");
        assertTrue(typedArray.contains("CAST([1, 2] AS BIGINT[])"), typedArray);
        assertFalse(typedArray.contains("ARRAY<"), typedArray);

        String struct = dml("INSERT ds.t (item) SELECT AS STRUCT 1 AS id, 'one' AS name");
        assertFalse(struct.contains("SELECT AS STRUCT"), struct);
        assertTrue(struct.contains("{'id': 1, 'name': 'one'}"), struct);
    }

    @Test
    void notMatchedClausesReadBareColumnsFromTheSource() {
        assertEquals("MERGE INTO \"ds\".\"t\" AS \"T\" USING \"ds\".\"s\" AS \"S\" ON S.id = T.id"
                        + " WHEN MATCHED THEN UPDATE SET \"v\" = S.\"v\""
                        + " WHEN NOT MATCHED THEN INSERT (\"id\", \"v\") VALUES (\"S\".\"id\", \"S\".\"v\")"
                        + " RETURNING merge_action",
                dml("MERGE ds.t AS T USING ds.s AS S ON S.id = T.id WHEN MATCHED THEN UPDATE SET `v` = S.`v`"
                        + " WHEN NOT MATCHED THEN INSERT (`id`, `v`) VALUES (`id`, `v`)"));
        assertEquals("MERGE INTO \"ds\".\"t\" USING \"ds\".\"s\" ON t.id = s.id"
                        + " WHEN NOT MATCHED AND \"s\".v > 1 THEN INSERT (id, v, d)"
                        + " VALUES (\"s\".id + 1, UPPER(" + numericStringCast("CAST", "\"s\".v")
                        + "), \"s\".d + INTERVAL 1 DAY)"
                        + " WHEN NOT MATCHED BY SOURCE AND v = 0 THEN DELETE RETURNING merge_action",
                dml("MERGE ds.t USING ds.s ON t.id = s.id WHEN NOT MATCHED AND v > 1 THEN INSERT (id, v, d)"
                        + " VALUES (id + 1, UPPER(CAST(v AS STRING)), d + INTERVAL 1 DAY)"
                        + " WHEN NOT MATCHED BY SOURCE AND v = 0 THEN DELETE"));
    }

    @Test
    void notMatchedClausesLeaveDatePartsSubqueriesTypesAndCaseAlone() {
        String dates = dml("MERGE ds.t T USING ds.s S ON T.id = S.id WHEN NOT MATCHED AND EXTRACT(DAY FROM d) = 5"
                + " AND d > DATE '2026-01-01' THEN INSERT (id, d) VALUES (id, DATE_TRUNC(d, MONTH))");
        assertTrue(dates.contains("\"S\".d") && dates.contains("\"S\".id"), dates);
        assertFalse(dates.contains("\"S\".DAY") || dates.contains("\"S\".DATE") || dates.contains("\"S\".MONTH"), dates);
        String moreDates = dml("MERGE ds.t T USING ds.s S ON T.id = S.id WHEN NOT MATCHED"
                + " AND TIMESTAMP_TRUNC(ts, DAY, 'UTC') IS NOT NULL AND d + INTERVAL '0-1' YEAR TO MONTH > d"
                + " AND DATE_DIFF(d, d2, ISOWEEK) = 0 THEN INSERT (id) VALUES (id)");
        assertTrue(moreDates.contains("timezone('UTC', date_trunc('day', timezone('UTC', \"S\".ts)))")
                && moreDates.contains("YEAR TO MONTH")
                && moreDates.contains("\"S\".d2"), moreDates);
        assertFalse(moreDates.contains("\"S\".MONTH") || moreDates.contains("\"S\".ISOWEEK"), moreDates);
        String dateNamedColumns = dml("MERGE ds.t T USING ds.s S ON T.id = S.id WHEN NOT MATCHED"
                + " AND DATE_DIFF(d, day, DAY) > 0 AND DATE_TRUNC(date, MONTH) = d THEN INSERT (id) VALUES (id)");
        assertTrue(dateNamedColumns.contains("\"S\".day") && dateNamedColumns.contains("\"S\".date"), dateNamedColumns);
        assertFalse(dateNamedColumns.contains("\"S\".DAY") || dateNamedColumns.contains("\"S\".MONTH"), dateNamedColumns);
        String arrayInDatePart = dml("MERGE ds.t T USING ds.s S ON T.id = S.id WHEN NOT MATCHED"
                + " AND DATE_TRUNC(CASE WHEN [d, d2] IS NOT NULL THEN d ELSE d2 END, MONTH) = d THEN INSERT (id) VALUES (id)");
        assertTrue(arrayInDatePart.contains("[\"S\".d, \"S\".d2]") && arrayInDatePart.contains("'month'"), arrayInDatePart);
        assertFalse(arrayInDatePart.contains("\"S\".MONTH") || arrayInDatePart.contains("\"s\".month"), arrayInDatePart);

        String subquery = dml("MERGE ds.t T USING ds.s S ON T.id = S.id WHEN NOT MATCHED THEN INSERT (id, v)"
                + " VALUES (id, (SELECT v FROM ds.lookup LIMIT 1))");
        assertTrue(subquery.contains("\"S\".id") && subquery.contains("(SELECT v FROM"), subquery);
        assertFalse(subquery.contains("\"S\".v"), subquery);

        String compare = dml("MERGE ds.t T USING ds.s S ON T.id = S.id WHEN NOT MATCHED AND a < b"
                + " AND CAST(v AS ARRAY<STRUCT<x INT64, y STRING>>) IS NOT NULL THEN INSERT (id) VALUES (id)");
        assertTrue(compare.contains("\"S\".a < \"S\".b") && compare.contains("\"S\".v"), compare);
        assertFalse(compare.contains("\"S\".x") || compare.contains("\"S\".y"), compare);

        String caseWhen = dml("MERGE ds.t T USING ds.s S ON T.id = S.id WHEN NOT MATCHED AND CASE WHEN flag THEN TRUE"
                + " ELSE FALSE END THEN INSERT (id, flag) VALUES (id, flag)");
        assertTrue(caseWhen.contains("CASE WHEN \"S\".flag THEN") && caseWhen.contains("VALUES (\"S\".id, \"S\".flag)"),
                caseWhen);

        String parenless = dml("MERGE ds.t T USING ds.s S ON T.id = S.id WHEN NOT MATCHED AND d <= CURRENT_DATE"
                + " THEN INSERT (id, ts, dt, tm, d) VALUES (id, CURRENT_TIMESTAMP, CURRENT_DATETIME, CURRENT_TIME,"
                + " CURRENT_DATE)");
        assertTrue(parenless.contains("\"S\".d <=") && parenless.contains("VALUES (\"S\".id"), parenless);
        assertFalse(parenless.toLowerCase().contains("\"s\".current"), parenless);

        String atTimeZone = dml("MERGE ds.t T USING ds.s S ON T.id = S.id WHEN NOT MATCHED THEN INSERT (id, d)"
                + " VALUES (id, EXTRACT(DATE FROM TIMESTAMP(ts) AT TIME ZONE 'UTC'))");
        assertTrue(atTimeZone.contains("\"S\".ts") && atTimeZone.contains("AT TIME ZONE 'UTC'"), atTimeZone);
        assertFalse(atTimeZone.contains("\"S\".TIME") || atTimeZone.contains("\"S\".ZONE"), atTimeZone);

        String namedArgument = dml("MERGE ds.t T USING ds.s S ON T.id = S.id WHEN NOT MATCHED THEN INSERT (id, g)"
                + " VALUES (id, ST_GEOGFROMTEXT(w, make_valid => TRUE))");
        assertTrue(namedArgument.contains("\"S\".w") && namedArgument.contains("make_valid => TRUE"), namedArgument);
        assertFalse(namedArgument.contains("\"S\".make_valid"), namedArgument);

        String unnest = dml("MERGE ds.t USING UNNEST([STRUCT(1 AS src_id)]) ON FALSE WHEN NOT MATCHED"
                + " THEN INSERT (id) VALUES (src_id)");
        assertTrue(unnest.contains("max_depth := 2)) AS \"_merge_source\"")
                && unnest.contains("VALUES (\"_merge_source\".src_id)"), unnest);
        assertFalse(unnest.toUpperCase().contains("\"UNNEST\"."), unnest);
        String aliasedUnnest = dml("MERGE ds.t USING UNNEST([STRUCT(1 AS src_id)]) AS u ON FALSE WHEN NOT MATCHED"
                + " THEN INSERT (id) VALUES (src_id)");
        assertTrue(aliasedUnnest.contains("VALUES (src_id)"), aliasedUnnest);
        String takenName = dml("MERGE ds.t AS _merge_source USING UNNEST([STRUCT(9 AS id)]) ON FALSE WHEN NOT MATCHED"
                + " THEN INSERT (id) VALUES (id)");
        assertTrue(takenName.contains("max_depth := 2)) AS \"_merge_source_1\"")
                && takenName.contains("VALUES (\"_merge_source_1\".id)"), takenName);
    }

    @Test
    void fullyQualifiedProjectTableWithHyphenIsResolvedForDml() {
        SqlDialectTranslator.Statement statement = SqlDialectTranslator.parseStatement(
                "INSERT INTO `floci-local.ds.t` (a) VALUES (1)", "floci-local", null);
        assertEquals(new SqlDialectTranslator.TableRef("ds", "t"), statement.target());
        assertEquals("INSERT INTO \"ds\".\"t\" (a) VALUES (1)",
                SqlDialectTranslator.translateDml("INSERT INTO `floci-local.ds.t` (a) VALUES (1)",
                        "floci-local", null, SqlDialectTranslator.QueryParameters.none()).sql());
    }

    @Test
    void dmlCtesRemainInScope() {
        assertEquals("""
                INSERT INTO "ds"."target"
                WITH grouped_rows AS (SELECT group_id FROM "ds"."details")
                SELECT group_id FROM "grouped_rows\"""",
                dml("""
                        INSERT ds.target
                        WITH grouped_rows AS (SELECT group_id FROM ds.details)
                        SELECT group_id FROM grouped_rows"""));
    }

    @Test
    void notMatchedClausesQualifyOnlyTargetColumnsWhenTheSchemasAreKnown() {
        String merge = dmlWithSchemas("MERGE ds.t T USING ds.s S ON T.id = S.id WHEN NOT MATCHED AND v > 0"
                + " AND only_in_source > 0 THEN INSERT (id, v) VALUES (id, some_function_word)");
        assertTrue(merge.contains("\"S\".v > 0") && merge.contains("VALUES (\"S\".id"), merge);
        assertFalse(merge.contains("\"S\".only_in_source") || merge.contains("\"S\".some_function_word"), merge);
    }

    @Test
    void aCorrelatedSubqueryInANotMatchedClauseIsRejectedLikeBigQuery() {
        String merge = "MERGE ds.t T USING ds.s S ON T.id = S.id WHEN NOT MATCHED";
        assertEquals("Correlated Subquery is unsupported in INSERT clause.", assertThrows(GcpException.class,
                () -> dmlWithSchemas(merge + " THEN INSERT (id, v) VALUES (id, (SELECT name FROM ds.lookup WHERE lk = v))"))
                .getMessage());
        assertEquals("Correlated Subquery is unsupported in WHEN clause.", assertThrows(GcpException.class,
                () -> dmlWithSchemas(merge + " AND EXISTS (SELECT 1 FROM ds.lookup l WHERE l.lk = S.v)"
                        + " THEN INSERT (id) VALUES (id)")).getMessage());
        assertEquals("Correlated Subquery is unsupported in INSERT clause.", assertThrows(GcpException.class,
                () -> dml(merge + " THEN INSERT (id, v) VALUES (id, (SELECT name FROM ds.lookup l WHERE l.lk = T.v))"))
                .getMessage());

        String inner = dmlWithSchemas(merge + " THEN INSERT (id, v) VALUES (id, (SELECT MAX(name) FROM ds.lookup"
                + " WHERE lk = 'a'))");
        assertTrue(inner.contains("(SELECT MAX(name) FROM \"ds\".\"lookup\" WHERE lk = 'a')"), inner);
    }

    @Test
    void notMatchedSubqueriesThatReadOnlyTheirOwnTablesAreAccepted() {
        String merge = "MERGE ds.t T USING ds.s S ON T.id = S.id WHEN NOT MATCHED";
        assertTrue(dmlWithSchemas(merge + " THEN INSERT (id, v) VALUES (id, (SELECT MAX(v) FROM ds.a, ds.b))")
                .contains("(SELECT MAX(v) FROM \"ds\".\"a\", \"ds\".\"b\")"));
        dmlWithSchemas(merge + " THEN INSERT (id, v) VALUES (id, (SELECT MAX(b.v) FROM ds.a a, ds.b b WHERE a.a_col = 'x'))");
        dmlWithSchemas(merge + " THEN INSERT (id, v) VALUES (id, (SELECT MAX(name) AS v FROM ds.lookup ORDER BY v LIMIT 1))");
        dmlWithSchemas(merge + " AND EXISTS (SELECT 1 FROM ds.lookup l WHERE EXISTS (SELECT 1 FROM ds.lookup WHERE name = 'x'))"
                + " THEN INSERT (id) VALUES (id)");
        dmlWithSchemas(merge + " THEN INSERT (id, v) VALUES (id, (SELECT MAX(name) FROM ds.lookup WHERE lk IN ('a')))");
    }

    @Test
    void nestedInAndUnnestSourceCorrelationsAreRejectedLikeBigQuery() {
        String merge = "MERGE ds.t T USING ds.s S ON T.id = S.id WHEN NOT MATCHED";
        String when = "Correlated Subquery is unsupported in WHEN clause.";
        String insert = "Correlated Subquery is unsupported in INSERT clause.";
        assertEquals(when, assertThrows(GcpException.class, () -> dmlWithSchemas(merge + " AND EXISTS (SELECT 1 FROM ds.lookup l"
                + " WHERE l.lk = S.v AND EXISTS (SELECT 1 FROM ds.lookup S)) THEN INSERT (id) VALUES (id)")).getMessage());
        assertEquals(when, assertThrows(GcpException.class, () -> dmlWithSchemas(merge + " AND EXISTS (SELECT 1 FROM ds.lookup l"
                + " WHERE EXISTS (SELECT 1 FROM ds.lookup m WHERE m.lk = v)) THEN INSERT (id) VALUES (id)")).getMessage());
        assertEquals(insert, assertThrows(GcpException.class, () -> dmlWithSchemas(merge + " THEN INSERT (id, v) VALUES (id,"
                + " (SELECT MAX(l.name) FROM ds.lookup l WHERE EXISTS (SELECT 1 FROM ds.b m WHERE m.v = l.lk)))")).getMessage());
        assertEquals(insert, assertThrows(GcpException.class, () -> dmlWithSchemas(merge + " THEN INSERT (id, v) VALUES (id,"
                + " (SELECT MAX(l.name) FROM ds.lookup l WHERE l.lk IN (SELECT v FROM ds.b)))")).getMessage());
        assertEquals(when, assertThrows(GcpException.class, () -> dmlWithSchemas(merge
                + " AND v IN (SELECT lk FROM ds.lookup) THEN INSERT (id) VALUES (id)")).getMessage());
        assertEquals(insert, assertThrows(GcpException.class, () -> dmlWithSchemas(merge
                + " THEN INSERT (id, v) VALUES (id, CASE WHEN v IN (SELECT lk FROM ds.lookup) THEN 'y' END)")).getMessage());

        String subquerySource = "MERGE ds.t T USING (SELECT 1 AS id, 'a' AS k) S ON T.id = S.id WHEN NOT MATCHED";
        assertEquals(when, assertThrows(GcpException.class, () -> dmlWithSchemas(subquerySource
                + " AND k IN (SELECT 'a') THEN INSERT (id) VALUES (id)")).getMessage());
        dmlWithSchemas(subquerySource + " AND CAST('a' AS STRING) IN (SELECT 'a') THEN INSERT (id) VALUES (id)");
        dmlWithSchemas(subquerySource + " AND EXTRACT(DAY FROM DATE '2026-01-01') IN (SELECT 1) THEN INSERT (id) VALUES (id)");
        assertEquals(when, assertThrows(GcpException.class, () -> dmlWithSchemas("MERGE ds.t T USING (SELECT 1 AS id, 'a' AS source) S"
                + " ON T.id = S.id WHEN NOT MATCHED AND source IN (SELECT 'a') THEN INSERT (id) VALUES (id)")).getMessage());
        assertEquals(insert, assertThrows(GcpException.class, () -> dmlWithSchemas("MERGE ds.t T USING (SELECT 1 AS id, 'a' AS v) S"
                + " ON T.id = S.id WHEN NOT MATCHED THEN INSERT (id, v) VALUES (id, (SELECT MAX(name) FROM ds.lookup WHERE lk = v))"))
                .getMessage());

        String unnest = "MERGE ds.t T USING UNNEST([STRUCT(1 AS id, 'a' AS v)]) AS u ON T.id = u.id WHEN NOT MATCHED";
        assertEquals(insert, assertThrows(GcpException.class,
                () -> dmlWithSchemas(unnest + " THEN INSERT (id, v) VALUES (id, (SELECT u.v))")).getMessage());
        assertEquals(insert, assertThrows(GcpException.class, () -> dmlWithSchemas(unnest
                + " THEN INSERT (id, v) VALUES (id, (SELECT MAX(name) FROM ds.lookup WHERE lk = u.v))")).getMessage());
        assertTrue(dmlWithSchemas(unnest + " THEN INSERT (id, v) VALUES (id, (SELECT MAX(name) FROM ds.lookup))")
                .contains("VALUES (id, (SELECT MAX(name) FROM \"ds\".\"lookup\"))"));
    }

    @Test
    void constantInOperandsAndNamesInsideParenthesizedJoinsAreNotCorrelations() {
        String merge = "MERGE ds.t T USING ds.s S ON T.id = S.id WHEN NOT MATCHED";
        dmlWithSchemas(merge + " AND ('a') IN (SELECT lk FROM ds.lookup) THEN INSERT (id) VALUES (id)");
        dmlWithSchemas(merge + " AND UPPER('a') IN (SELECT UPPER(lk) FROM ds.lookup) THEN INSERT (id) VALUES (id)");
        dmlWithSchemas(merge + " AND DATE '2026-01-01' IN (SELECT DATE '2026-01-01') THEN INSERT (id) VALUES (id)");
        dmlWithSchemas(merge + " AND TIMESTAMP '2026-01-01 00:00:00' IN (SELECT TIMESTAMP '2026-01-01 00:00:00')"
                + " THEN INSERT (id) VALUES (id)");
        dmlWithSchemas(merge + " AND CAST('a' AS STRING) IN (SELECT lk FROM ds.lookup) THEN INSERT (id) VALUES (id)");
        dmlWithSchemas(merge + " AND SAFE_CAST('1' AS INT64) IN (SELECT 1) THEN INSERT (id) VALUES (id)");
        dmlWithSchemas(merge + " AND DATE_SUB(DATE '2026-01-02', INTERVAL 1 DAY) IN (SELECT lk FROM ds.lookup)"
                + " THEN INSERT (id) VALUES (id)");
        assertEquals("Correlated Subquery is unsupported in WHEN clause.", assertThrows(GcpException.class,
                () -> dmlWithSchemas(merge + " AND CAST(v AS STRING) IN (SELECT lk FROM ds.lookup) THEN INSERT (id) VALUES (id)"))
                .getMessage());
        dmlWithSchemas(merge + " THEN INSERT (id, v) VALUES (id, (SELECT MAX(S.name) FROM (ds.s x JOIN ds.lookup S ON TRUE)))");
        assertEquals("Correlated Subquery is unsupported in WHEN clause.", assertThrows(GcpException.class,
                () -> dmlWithSchemas(merge + " AND UPPER(v) IN (SELECT lk FROM ds.lookup) THEN INSERT (id) VALUES (id)"))
                .getMessage());
    }

    @Test
    void aSelectListAliasHidesAnOuterColumnOnlyFromGroupByOn() {
        String merge = "MERGE ds.t T USING ds.s S ON T.id = S.id WHEN NOT MATCHED THEN INSERT (id, v) VALUES (id, ";
        assertEquals("Correlated Subquery is unsupported in INSERT clause.", assertThrows(GcpException.class,
                () -> dmlWithSchemas(merge + "(SELECT MAX(name) AS v FROM ds.lookup WHERE lk = v))")).getMessage());
        dmlWithSchemas(merge + "(SELECT lk AS v FROM ds.lookup GROUP BY v ORDER BY v LIMIT 1))");
        dmlWithSchemas(merge + "(SELECT MAX(name) AS v FROM ds.lookup HAVING v IS NOT NULL))");
        dmlWithSchemas(merge + "(SELECT lk AS v FROM ds.lookup QUALIFY ROW_NUMBER() OVER (ORDER BY v) = 1))");
        dmlWithSchemas(merge + "(SELECT MAX(name) v FROM ds.lookup ORDER BY v LIMIT 1))");
        assertEquals("Correlated Subquery is unsupported in INSERT clause.", assertThrows(GcpException.class,
                () -> dmlWithSchemas(merge + "(SELECT MAX(name) v FROM ds.lookup WHERE lk = v))")).getMessage());
    }

    /**
     * {@code ds.t} and {@code ds.s} share {@code id} and {@code v}; {@code ds.lookup} has {@code lk, name},
     * {@code ds.a} has {@code a_col} and {@code ds.b} has {@code v}.
     */
    private static String dmlWithSchemas(String sql) {
        Map<String, Set<String>> columns = Map.of("t", Set.of("id", "v"), "s", Set.of("id", "v", "only_in_source"),
                "lookup", Set.of("lk", "name"), "a", Set.of("a_col"), "b", Set.of("v"));
        return SqlDialectTranslator.translateDml(sql, "test-project", "ds", SqlDialectTranslator.QueryParameters.none(),
                ref -> columns.get(ref.tableId())).sql();
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
        assertEquals(Map.of("type", "DAY"), create.timePartitioning());

        SqlDialectTranslator.Statement fieldPartitioned = statement(
                "CREATE TABLE ds.events (event_time TIMESTAMP) PARTITION BY TIMESTAMP_TRUNC(event_time, HOUR)");
        assertEquals(Map.of("type", "HOUR", "field", "event_time"), fieldPartitioned.timePartitioning());

        SqlDialectTranslator.Statement rangePartitioned = statement(
                "CREATE TABLE ds.ranged (id INT64) PARTITION BY RANGE_BUCKET(id, GENERATE_ARRAY(-10, 100, 5))");
        assertEquals(Map.of("field", "id", "range",
                Map.of("start", "-10", "end", "100", "interval", "5")), rangePartitioned.rangePartitioning());

        SqlDialectTranslator.Statement quotedPartition = statement(
                "CREATE TABLE ds.quoted (`event date` DATE, eventdate DATE) PARTITION BY `event date`");
        assertEquals(Map.of("type", "DAY", "field", "event date"), quotedPartition.timePartitioning());

        for (String type : List.of("HOUR", "DAY", "MONTH", "YEAR")) {
            SqlDialectTranslator.Statement ingestionTime = statement(
                    "CREATE TABLE ds.ingestion (id INT64) PARTITION BY TIMESTAMP_TRUNC(_PARTITIONTIME, "
                            + type + ")");
            assertEquals(Map.of("type", type), ingestionTime.timePartitioning());
        }
    }

    @Test
    void createTableAsSelectAndViewsKeepTheirQuery() {
        SqlDialectTranslator.Statement ctas = statement("CREATE OR REPLACE TABLE ds.t AS SELECT a FROM ds.src");
        assertEquals("CREATE_TABLE_AS_SELECT", ctas.statementType());
        assertTrue(ctas.orReplace());
        assertEquals("SELECT a FROM ds.src", ctas.querySql().trim());

        SqlDialectTranslator.Statement partitionedCtas = statement(
                "CREATE TABLE ds.daily PARTITION BY DATE(event_time) AS SELECT event_time FROM ds.src");
        assertEquals(Map.of("type", "DAY", "field", "event_time"), partitionedCtas.timePartitioning());
        assertEquals("SELECT event_time FROM ds.src", partitionedCtas.querySql().trim());

        SqlDialectTranslator.Statement rangeCtas = statement(
                "CREATE TABLE ds.ranged PARTITION BY RANGE_BUCKET(id, GENERATE_ARRAY(0, 100)) "
                        + "AS SELECT id FROM ds.src");
        assertEquals(Map.of("field", "id", "range",
                Map.of("start", "0", "end", "100", "interval", "1")), rangeCtas.rangePartitioning());

        GcpException ingestionCtas = assertThrows(GcpException.class, () -> statement(
                "CREATE TABLE ds.daily PARTITION BY DATE(_PARTITIONTIME) AS SELECT event_time FROM ds.src"));
        assertEquals("invalidQuery", ingestionCtas.getReason());
        assertTrue(ingestionCtas.getMessage().contains("ingestion-time"));

        SqlDialectTranslator.Statement view = statement("CREATE VIEW ds.v OPTIONS(description='x') AS SELECT 1 AS a");
        assertEquals("CREATE_VIEW", view.statementType());
        assertEquals("SELECT 1 AS a", view.querySql());
        assertEquals("CREATE_MATERIALIZED_VIEW",
                statement("CREATE MATERIALIZED VIEW ds.mv AS SELECT 1 AS a").statementType());
    }

    @Test
    void alterSetOptionsIsClassifiedWithItsOptions() {
        SqlDialectTranslator.Statement empty = statement("ALTER TABLE ds.t SET OPTIONS()");
        assertEquals(SqlDialectTranslator.StatementKind.ALTER_TABLE, empty.kind());
        assertEquals("ALTER_TABLE", empty.statementType());
        assertEquals(new SqlDialectTranslator.TableRef("ds", "t"), empty.target());
        assertTrue(empty.options().isEmpty());

        SqlDialectTranslator.Statement all = statement("ALTER TABLE IF EXISTS ds.t SET OPTIONS(description='d',"
                + " friendly_name=NULL, labels=[('a', 'b'), STRUCT('c' AS key, 'd' AS value)],"
                + " expiration_timestamp=TIMESTAMP '2026-11-20 00:00:00 UTC')");
        assertTrue(all.ifExists());
        assertEquals("d", all.options().get("description"));
        assertTrue(all.options().containsKey("friendly_name") && all.options().get("friendly_name") == null);
        assertEquals(Map.of("a", "b", "c", "d"), all.options().get("labels"));
        assertEquals(1795132800000L, all.options().get("expiration_timestamp"));
        assertEquals("ALTER_VIEW", statement("ALTER VIEW ds.v SET OPTIONS(description='v')").statementType());
    }

    @Test
    void alterRejectsWhatBigQueryRejectsAndWhatFlociDoesNotSupport() {
        assertEquals("Unknown option: foo", assertThrows(GcpException.class,
                () -> statement("ALTER TABLE ds.t SET OPTIONS(foo='bar')")).getMessage());
        assertEquals("Option description value has type INT64 which cannot be coerced to expected type STRING",
                assertThrows(GcpException.class,
                        () -> statement("ALTER TABLE ds.t SET OPTIONS(description=5)")).getMessage());
        assertTrue(assertThrows(GcpException.class, () -> statement("ALTER TABLE ds.t ADD COLUMN y INT64"))
                .getMessage().contains("only supported with SET OPTIONS"));
    }

    @Test
    void alterRejectsLabelTuplesWithoutSeparators() {
        for (String labels : List.of("[('a','b')('c','d')]", "[('a','b'),]", "[,('a','b')]",
                "[STRUCT('a' AS key, 'b' AS value) STRUCT('c' AS key, 'd' AS value)]")) {
            assertEquals("invalidQuery", assertThrows(GcpException.class,
                    () -> statement("ALTER TABLE ds.t SET OPTIONS(labels=" + labels + ")")).getReason(), labels);
        }
    }

    @Test
    void alterRejectsInvalidExpirationTimestampsLikeBigQuery() {
        assertEquals("Invalid TIMESTAMP literal; failed to set 'expiration_timestamp' in OPTIONS()",
                assertThrows(GcpException.class, () -> statement(
                        "ALTER TABLE ds.t SET OPTIONS(expiration_timestamp=TIMESTAMP '2099-13-01 00:00:00 UTC')"))
                        .getMessage());
        assertEquals("Could not cast literal \"2099-02-30 00:00:00 UTC\" to type TIMESTAMP;"
                        + " failed to set 'expiration_timestamp' in OPTIONS()",
                assertThrows(GcpException.class, () -> statement(
                        "ALTER TABLE ds.t SET OPTIONS(expiration_timestamp='2099-02-30 00:00:00 UTC')")).getMessage());
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

    // ── INFORMATION_SCHEMA ───────────────────────────────────────────────────

    @Test
    void informationSchemaReferencesResolveToAScope() {
        SqlDialectTranslator.Translation dataset = translate("SELECT table_name FROM ds.INFORMATION_SCHEMA.TABLES");
        assertEquals(Set.of(new InformationSchema.Ref("TABLES", "ds", null)), dataset.informationSchema());
        assertTrue(dataset.tables().isEmpty());
        assertEquals("SELECT table_name FROM \"_floci_information_schema\".\"tables__ds_ds\" AS \"TABLES\"",
                dataset.sql());

        assertEquals(Set.of(new InformationSchema.Ref("COLUMNS", "ds", null)),
                translate("SELECT * FROM `test-project.ds.INFORMATION_SCHEMA.COLUMNS` c").informationSchema());
        assertEquals(Set.of(new InformationSchema.Ref("TABLES", null, "us")),
                translate("SELECT * FROM `region-us`.INFORMATION_SCHEMA.TABLES").informationSchema());
        assertEquals(Set.of(new InformationSchema.Ref("PARTITIONS", "ds", null)),
                translate("SELECT * FROM ds.INFORMATION_SCHEMA.PARTITIONS").informationSchema());
        assertEquals(Set.of(new InformationSchema.Ref("SCHEMATA", null, "us-central1")),
                translate("SELECT * FROM region-us-central1.INFORMATION_SCHEMA.SCHEMATA").informationSchema());
        assertEquals(Set.of(new InformationSchema.Ref("SCHEMATA", null, "us")),
                translate("SELECT * FROM INFORMATION_SCHEMA.SCHEMATA").informationSchema());
        assertEquals(Set.of(new InformationSchema.Ref("SCHEMATA", null, "us")),
                translate("SELECT * FROM `test-project`.INFORMATION_SCHEMA.SCHEMATA").informationSchema());

    }

    @Test
    void informationSchemaUsesTheDefaultDatasetAndValidatesTheView() {
        assertEquals(Set.of(new InformationSchema.Ref("VIEWS", "dflt", null)),
                SqlDialectTranslator.translate("SELECT * FROM INFORMATION_SCHEMA.VIEWS", "test-project", "dflt",
                        SqlDialectTranslator.QueryParameters.none()).informationSchema());
        assertTrue(invalid("SELECT * FROM INFORMATION_SCHEMA.TABLES").getMessage().contains("qualifier"));
        assertTrue(invalid("SELECT * FROM ds.INFORMATION_SCHEMA.tables").getMessage().contains("case-sensitive"));
        assertTrue(invalid("SELECT * FROM `region-us`.INFORMATION_SCHEMA.PARTITIONS")
                .getMessage().contains("dataset qualifier"));
        invalid("SELECT * FROM ds.INFORMATION_SCHEMA.JOBS");
        invalid("SELECT * FROM ds.INFORMATION_SCHEMA.SCHEMATA");
    }

    private static Map<String, Object> param(String name, String type, String value) {
        return Map.of("name", name, "parameterType", Map.of("type", type), "parameterValue", Map.of("value", value));
    }

    private static String positionalSql(String sql, String... values) {
        List<Map<String, Object>> parameters = List.of(values).stream()
                .map(value -> Map.<String, Object>of("parameterType", Map.of("type", "INT64"),
                        "parameterValue", Map.of("value", value)))
                .toList();
        return SqlDialectTranslator.translate(sql, "test-project", null,
                new SqlDialectTranslator.QueryParameters(parameters, "POSITIONAL")).sql();
    }
}
