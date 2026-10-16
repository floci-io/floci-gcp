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

        assertTrue(translated.contains("unnest([list_extract(list_transform([struct_pack("), translated);
        assertEquals(translated.indexOf("ARRAY_AGG"), translated.lastIndexOf("ARRAY_AGG"), translated);
        assertTrue(translated.contains("error('Array index out of bounds')"), translated);
    }

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
        assertEquals("SELECT * NOT SIMILAR TO '^(_PARTITION(DATE|TIME)|_TABLE_SUFFIX)$' FROM \"ds\".\"users\"", t.sql());
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
    void derivedPseudocolumnsRemainOrdinaryOutputColumns() {
        assertEquals("SELECT * FROM (SELECT _TABLE_SUFFIX FROM \"ds\".\"events_*\")"
                        + " AS \"_floci_derived_0\"",
                sql("SELECT * FROM (SELECT _TABLE_SUFFIX FROM `test-project.ds.events_*`)"));
        assertEquals("SELECT * REPLACE (UPPER(_TABLE_SUFFIX) AS _TABLE_SUFFIX)"
                        + " FROM (SELECT _TABLE_SUFFIX FROM \"ds\".\"events_*\") AS \"_floci_derived_0\"",
                sql("SELECT * REPLACE (UPPER(_TABLE_SUFFIX) AS _TABLE_SUFFIX)"
                        + " FROM (SELECT _TABLE_SUFFIX FROM `test-project.ds.events_*`)"));
    }

    @Test
    void nonTerminalWildcardIsRejected() {
        GcpException e = invalid("SELECT * FROM `test-project.ds.events_*_archive`");
        assertTrue(e.getMessage().contains("terminal '*'"));
    }

    @Test
    void scriptCreatesTemporaryMacroBeforeFinalSelect() {
        SqlDialectTranslator.ScriptTranslation script = SqlDialectTranslator.translateScript("""
                SELECT 1 AS ignored;
                CREATE TEMP FUNCTION add_one(value ANY TYPE) AS (value + 1);
                SELECT add_one(41) AS answer;
                """, "test-project", null, SqlDialectTranslator.QueryParameters.none());

        assertEquals("SELECT add_one(41) AS answer", script.sql());
        assertTrue(script.setupSql().contains("SELECT 1 AS ignored;"));
        assertTrue(script.setupSql().contains("CREATE TEMP MACRO \"add_one\"(\"_floci_udf_arg_0\")"
                + " AS (\"_floci_udf_arg_0\" + 1);"));
    }

    @Test
    void scriptCreatesTemporaryTablesBeforeTerminalInsert() {
        SqlDialectTranslator.ScriptTranslation script = SqlDialectTranslator.translateScript("""
                CREATE TEMP TABLE first_round AS (
                  SELECT id FROM source_rows
                );
                CREATE TEMP TABLE second_round AS (
                  SELECT id + 1 AS id FROM first_round
                );
                INSERT target_rows SELECT id FROM second_round;
                """, "test-project", "ds", SqlDialectTranslator.QueryParameters.none());

        assertEquals(SqlDialectTranslator.StatementKind.INSERT, script.finalStatement().kind());
        assertEquals("INSERT INTO \"ds\".\"target_rows\" SELECT id FROM \"second_round\"", script.sql());
        assertTrue(script.setupSql().contains("CREATE TEMP TABLE \"first_round\" AS (SELECT id FROM \"ds\".\"source_rows\");"),
                script.setupSql());
        assertTrue(script.setupSql().contains("CREATE TEMP TABLE \"second_round\" AS (SELECT id + 1 AS id FROM \"first_round\");"),
                script.setupSql());
        assertEquals(Set.of(new SqlDialectTranslator.TableRef("ds", "source_rows"),
                new SqlDialectTranslator.TableRef("ds", "target_rows")), script.tables());
    }

    @Test
    void proceduralIfKeepsGenericConditionsAndCopyStatementsAsStructure() {
        SqlDialectTranslator.ProceduralScript script = SqlDialectTranslator.proceduralScript("""
                IF EXISTS (SELECT * FROM `test-project.ds.new_rows`) THEN
                  CREATE OR REPLACE TABLE `test-project.ds.output_rows` COPY `test-project.ds.new_rows`;
                ELSE
                  CREATE OR REPLACE TABLE `test-project.ds.output_rows` COPY `test-project.ds.refreshed_rows`;
                END IF;
                """);

        assertEquals("EXISTS (SELECT * FROM `test-project.ds.new_rows`)",
                ((SqlDialectTranslator.IfBlock) script.items().getFirst()).condition());
        SqlDialectTranslator.IfBlock conditional = (SqlDialectTranslator.IfBlock) script.items().getFirst();
        assertEquals(1, conditional.whenTrue().size());
        assertEquals(1, conditional.whenFalse().size());

        SqlDialectTranslator.Statement copy = SqlDialectTranslator.parseStatement(
                ((SqlDialectTranslator.SqlItem) conditional.whenTrue().getFirst()).sql(), "test-project", null);
        assertEquals("CREATE_TABLE_COPY", copy.statementType());
        assertEquals(new SqlDialectTranslator.TableRef("ds", "output_rows"), copy.target());
        assertEquals(new SqlDialectTranslator.TableRef("ds", "new_rows"), copy.copySource());
    }

    @Test
    void proceduralElseIfIsRepresentedAsANestedConditional() {
        SqlDialectTranslator.ProceduralScript script = SqlDialectTranslator.proceduralScript("""
                IF first_condition THEN
                  SELECT 1;
                ELSEIF second_condition THEN
                  SELECT 2;
                ELSE
                  SELECT 3;
                END IF;
                """);

        SqlDialectTranslator.IfBlock first = (SqlDialectTranslator.IfBlock) script.items().getFirst();
        SqlDialectTranslator.IfBlock second = (SqlDialectTranslator.IfBlock) first.whenFalse().getFirst();
        assertEquals("first_condition", first.condition());
        assertEquals("second_condition", second.condition());
        assertEquals("SELECT 3", ((SqlDialectTranslator.SqlItem) second.whenFalse().getFirst()).sql());
    }

    @Test
    void proceduralIfKeepsCaseExpressionsInsideTheCondition() {
        SqlDialectTranslator.ProceduralScript script = SqlDialectTranslator.proceduralScript("""
                IF CASE WHEN TRUE THEN TRUE ELSE FALSE END THEN
                  SELECT 1;
                END IF;
                """);

        SqlDialectTranslator.IfBlock conditional = (SqlDialectTranslator.IfBlock) script.items().getFirst();
        assertEquals("CASE WHEN TRUE THEN TRUE ELSE FALSE END", conditional.condition());
    }

    @Test
    void scriptsKeepRequestLocalStatementsInSourceOrder() {
        SqlDialectTranslator.ScriptTranslation script = SqlDialectTranslator.translateScript("""
                CREATE TEMP TABLE first_round AS SELECT id FROM source_rows;
                SELECT COUNT(*) FROM first_round;
                CREATE OR REPLACE TEMP TABLE first_round AS SELECT id + 1 AS id FROM first_round;
                CREATE TEMP FUNCTION add_one(value ANY TYPE) AS (value + 1);
                CREATE TEMP TABLE second_round AS SELECT add_one(id) AS id FROM first_round;
                DROP TABLE second_round;
                SELECT id FROM first_round;
                """, "test-project", "ds", SqlDialectTranslator.QueryParameters.none());

        assertEquals("SELECT id FROM \"first_round\"", script.sql());
        assertTrue(script.setupSql().contains("CREATE TEMP TABLE \"first_round\" AS (SELECT id FROM \"ds\".\"source_rows\");"),
                script.setupSql());
        assertTrue(script.setupSql().contains("CREATE OR REPLACE TEMP TABLE \"first_round\" AS "
                + "(SELECT id + 1 AS id FROM \"first_round\");"), script.setupSql());
        assertTrue(script.setupSql().contains("CREATE TEMP MACRO \"add_one\"(\"_floci_udf_arg_0\")"
                        + " AS (\"_floci_udf_arg_0\" + 1);"),
                script.setupSql());
        assertTrue(script.setupSql().contains("CREATE TEMP TABLE \"second_round\" AS "
                + "(SELECT add_one(id) AS id FROM \"first_round\");\nDROP TABLE \"second_round\";"), script.setupSql());
        assertEquals(Set.of(new SqlDialectTranslator.TableRef("ds", "source_rows")), script.tables());
    }

    @Test
    void temporaryTablesRemainBoundWithoutADefaultDataset() {
        SqlDialectTranslator.ScriptTranslation script = SqlDialectTranslator.translateScript("""
                CREATE TEMP TABLE first_round AS (SELECT 1 AS id);
                CREATE TEMP TABLE second_round AS (SELECT id + 1 AS id FROM first_round);
                INSERT `ds.output_rows` SELECT id FROM second_round;
                """, "test-project", null, SqlDialectTranslator.QueryParameters.none());

        assertEquals("INSERT INTO \"ds\".\"output_rows\" SELECT id FROM \"second_round\"", script.sql());
        assertTrue(script.setupSql().contains("CREATE TEMP TABLE \"second_round\" AS "
                + "(SELECT id + 1 AS id FROM \"first_round\");"), script.setupSql());
        assertEquals(Set.of(new SqlDialectTranslator.TableRef("ds", "output_rows")), script.tables());
    }

    @Test
    void qualifiedPhysicalTablesAndStringLiteralsDoNotResolveToTemporaryTables() {
        SqlDialectTranslator.ScriptTranslation script = SqlDialectTranslator.translateScript("""
                CREATE TEMP TABLE t AS SELECT 1 AS id;
                SELECT '"ds"."t"' AS label, id FROM ds.t;
                """, "test-project", "ds", SqlDialectTranslator.QueryParameters.none());

        assertTrue(script.sql().contains("FROM \"ds\".\"t\""), script.sql());
        assertTrue(script.sql().contains("'\"ds\".\"t\"'"), script.sql());
        assertEquals(Set.of(new SqlDialectTranslator.TableRef("ds", "t")), script.tables());
    }

    @Test
    void truncateResolvesOnlyUnqualifiedOrSessionQualifiedTemporaryTables() {
        Set<String> temporaryTables = Set.of("t");

        assertEquals("t", SqlDialectTranslator.temporaryTruncateTarget("TRUNCATE TABLE t", temporaryTables));
        assertEquals("t", SqlDialectTranslator.temporaryTruncateTarget(
                "TRUNCATE TABLE _SESSION.t", temporaryTables));
        assertNull(SqlDialectTranslator.temporaryTruncateTarget(
                "TRUNCATE TABLE ds.t", temporaryTables));
    }

    @Test
    void preservesGeneralBigQueryByteOperatorsForSidecarOverloads() {
        SqlDialectTranslator.ScriptTranslation script = SqlDialectTranslator.translateScript("""
                CREATE TEMP FUNCTION CHECK_BIT_SET(bs BYTES, idx INT64) RETURNS BOOL AS (
                  BYTE_LENGTH(bs) > 0 AND
                  BIT_COUNT(bs & (CONCAT(REPEAT(b'\\x00', BYTE_LENGTH(bs) - 1), b'\\x01') << idx )) != 0
                );
                SELECT CHECK_BIT_SET(b'\\x10', 4) AS bit;
                """, "test-project", null, SqlDialectTranslator.QueryParameters.none());

        assertTrue(script.setupSql().contains("CAST(\"_floci_udf_arg_0\" AS BLOB) &"), script.setupSql());
        assertTrue(script.setupSql().contains("from_hex('01')"), script.setupSql());
        assertTrue(script.setupSql().contains("<< CAST(\"_floci_udf_arg_1\" AS BIGINT)"), script.setupSql());
        assertTrue(script.setupSql().contains(
                        "CASE (floci_byte_length(CAST(\"_floci_udf_arg_0\" AS BLOB)) > 0) WHEN FALSE THEN FALSE"),
                script.setupSql());
        assertFalse(script.setupSql().contains("CAST(CASE WHEN"), script.setupSql());
    }

    @Test
    void temporaryFunctionLogicalOperatorsPreservePrecedenceAndThreeValuedLogic() {
        SqlDialectTranslator.ScriptTranslation script = SqlDialectTranslator.translateScript("""
                CREATE TEMP FUNCTION predicate(a BOOL, b BOOL, c BOOL) AS (a OR b AND c);
                SELECT predicate(TRUE, FALSE, FALSE);
                """, "test-project", null, SqlDialectTranslator.QueryParameters.none());

        assertTrue(script.setupSql().contains(
                "CASE (CAST(\"_floci_udf_arg_0\" AS BOOLEAN)) WHEN TRUE THEN TRUE"), script.setupSql());
        assertTrue(script.setupSql().contains(
                "CASE (CAST(\"_floci_udf_arg_1\" AS BOOLEAN)) WHEN FALSE THEN FALSE"), script.setupSql());
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
        assertEquals("SELECT {'id': id, 'name': name} FROM \"ds\".\"users\"",
                sql("SELECT AS STRUCT id, name FROM ds.users"));
        assertEquals("SELECT {'id': id, 'display_name': name} FROM \"ds\".\"users\"",
                sql("SELECT AS STRUCT id, name AS display_name FROM ds.users"));
        assertEquals("SELECT struct_pack(COLUMNS(input.* NOT SIMILAR TO"
                        + " '(?i)^(_PARTITIONDATE|_PARTITIONTIME|_TABLE_SUFFIX|id)$'))"
                        + " FROM \"ds\".\"users\" AS \"input\"",
                sql("SELECT AS STRUCT input.* EXCEPT(id) FROM ds.users AS input"));
        assertEquals("SELECT {'_field_1': index * 25} AS value",
                sql("SELECT STRUCT(index * 25) AS value"));
    }

    @Test
    void translatesStructsInsideTemporaryFunctionBodies() {
        SqlDialectTranslator.ScriptTranslation translation = SqlDialectTranslator.translateScript("""
                CREATE TEMPORARY FUNCTION distinctRecords(
                  records ARRAY<STRUCT<category STRING, owner_id INT64>>
                ) AS (
                  ARRAY(
                    SELECT STRUCT(category AS category, owner_id AS owner_id)
                    FROM UNNEST(records)
                    GROUP BY category, owner_id
                  )
                );
                SELECT distinctRecords(records) FROM dataset.table
                """, "test-project", null, SqlDialectTranslator.QueryParameters.none());

        assertFalse(translation.setupSql().contains("SELECT STRUCT("), translation.setupSql());
        assertTrue(translation.setupSql().contains("{'category': category, 'owner_id': owner_id}"),
                translation.setupSql());
    }

    @Test
    void selectAsStructTreatsComparisonOperatorsAsExpressions() {
        assertEquals("SELECT {'small': id < 10} FROM \"ds\".\"events\"",
                sql("SELECT AS STRUCT id < 10 AS small FROM ds.events"));
        assertEquals("SELECT {'small': id < 10, 'large': id > 20} FROM \"ds\".\"events\"",
                sql("SELECT AS STRUCT id < 10 AS small, id > 20 AS large FROM ds.events"));
    }

    @Test
    void translatesSelectAsStructInsideTemporaryFunction() {
        SqlDialectTranslator.ScriptTranslation script = SqlDialectTranslator.translateScript("""
                CREATE TEMP FUNCTION pick(id STRING, name STRING) AS (
                  SELECT AS STRUCT id, name || '_display' display_name
                );
                SELECT pick('id1', 'name1') AS value;
                """, "test-project", null, SqlDialectTranslator.QueryParameters.none());

        assertTrue(script.setupSql().contains(
                        "SELECT {'id': CAST(\"_floci_udf_arg_0\" AS VARCHAR), "
                                + "'display_name': CAST(\"_floci_udf_arg_1\" AS VARCHAR) || '_display'}"),
                script.setupSql());
    }

    @Test
    void translatesTypedEmptyArrayInTemporaryFunction() {
        SqlDialectTranslator.ScriptTranslation script = SqlDialectTranslator.translateScript("""
                CREATE TEMP FUNCTION empty_metadata() AS (ARRAY<STRUCT<key STRING, value STRING>>[]);
                SELECT empty_metadata() AS metadata;
                """, "test-project", null, SqlDialectTranslator.QueryParameters.none());

        assertTrue(script.setupSql().contains(
                "CREATE TEMP MACRO \"empty_metadata\"() AS (CAST([] AS STRUCT(\"key\" VARCHAR, \"value\" VARCHAR)[]));"));
    }

    @Test
    void temporaryFunctionKeepsStructTypeCommasWithinOneParameter() {
        SqlDialectTranslator.ScriptTranslation script = SqlDialectTranslator.translateScript("""
                CREATE TEMP FUNCTION join_keys_to_str(join_keys ARRAY<STRUCT<id_type STRING, id_value STRING>>)
                RETURNS STRING AS ('');
                SELECT join_keys_to_str([]) AS keys;
                """, "test-project", null, SqlDialectTranslator.QueryParameters.none());

        assertTrue(script.setupSql().contains("CREATE TEMP MACRO \"join_keys_to_str\"(\"_floci_udf_arg_0\")"),
                script.setupSql());
    }

    @Test
    void temporaryFunctionSplitsParameterAfterNestedStructType() {
        SqlDialectTranslator.ScriptTranslation script = SqlDialectTranslator.translateScript("""
                CREATE TEMP FUNCTION extract_additional(addtl ARRAY<STRUCT<name STRING, value STRING>>, key STRING)
                RETURNS STRING AS (key);
                SELECT extract_additional([], 'email_source') AS value;
                """, "test-project", null, SqlDialectTranslator.QueryParameters.none());

        assertTrue(script.setupSql().contains(
                "CREATE TEMP MACRO \"extract_additional\"(\"_floci_udf_arg_0\", \"_floci_udf_arg_1\")"),
                script.setupSql());
    }

    @Test
    void temporaryFunctionAppliesDeclaredParameterAndReturnTypes() {
        SqlDialectTranslator.ScriptTranslation script = SqlDialectTranslator.translateScript("""
                CREATE TEMP FUNCTION as_float(value INT64) RETURNS FLOAT64 AS (value + 1);
                SELECT as_float(1) AS value;
                """, "test-project", null, SqlDialectTranslator.QueryParameters.none());

        assertTrue(script.setupSql().contains("CREATE TEMP MACRO \"as_float\"(\"_floci_udf_arg_0\")"),
                script.setupSql());
        assertTrue(script.setupSql().contains(
                "CAST((CAST(\"_floci_udf_arg_0\" AS BIGINT) + 1) AS DOUBLE)"), script.setupSql());
        assertFalse(script.setupSql().contains("FROM (SELECT"), script.setupSql());
    }

    @Test
    void temporaryFunctionCtesRemainInScope() {
        SqlDialectTranslator.ScriptTranslation script = SqlDialectTranslator.translateScript("""
                CREATE TEMP FUNCTION normalize(items ARRAY<STRING>) AS ((
                  WITH unnested AS (SELECT value FROM UNNEST(items) value)
                  SELECT ARRAY_AGG(value) FROM unnested
                ));
                SELECT normalize(['value']) AS result;
                """, "test-project", null, SqlDialectTranslator.QueryParameters.none());

        assertTrue(script.setupSql().contains("FROM \"unnested\""), script.setupSql());
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
        assertEquals("SELECT a.* NOT SIMILAR TO '^(_PARTITION(DATE|TIME)|_TABLE_SUFFIX)$'"
                        + " FROM \"ds\".\"filtered\" CROSS JOIN UNNEST(aliases) AS \"_unnest_a\"(\"a\")",
                sql("SELECT a.* FROM ds.filtered JOIN UNNEST(aliases) a"));
    }

    @Test
    void ctesOfParenthesizedQueriesAndSubqueriesAreNotTreatedAsTables() {
        SqlDialectTranslator.Translation t = translate("(WITH s AS (SELECT * FROM ds.t) SELECT x + 1 FROM s)");
        assertEquals(Set.of(new SqlDialectTranslator.TableRef("ds", "t")), t.tables());
        assertEquals("(WITH s AS (SELECT * NOT SIMILAR TO '^(_PARTITION(DATE|TIME)|_TABLE_SUFFIX)$'"
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
        assertEquals("SELECT * NOT SIMILAR TO '^(_PARTITION(DATE|TIME)|_TABLE_SUFFIX)$' FROM ("
                        + "SELECT * REPLACE (UPPER(name) AS name) FROM \"ds\".\"t\""
                        + ") AS \"_floci_star_replace\"",
                sql("SELECT * REPLACE (UPPER(name) AS name) FROM ds.t"));
    }

    @Test
    void parenthesizedPathKeepsItsImplicitColumnName() {
        assertEquals("WITH categories AS (SELECT DISTINCT(category) FROM \"ds\".\"t\")"
                        + " SELECT * NOT SIMILAR TO '^(_PARTITION(DATE|TIME)|_TABLE_SUFFIX)$'"
                        + " FROM \"ds\".\"t\" JOIN \"categories\" USING (category)",
                sql("WITH categories AS (SELECT DISTINCT(category) FROM ds.t)"
                        + " SELECT * FROM ds.t JOIN categories USING (category)"));
    }

    // ── Functions ────────────────────────────────────────────────────────────

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
        assertEquals("SELECT floci_length(value) AS n", sql("SELECT LENGTH(value) AS n"));
        assertEquals("SELECT floci_byte_length(value) AS n", sql("SELECT BYTE_LENGTH(value) AS n"));
        assertEquals("SELECT coalesce(floci_to_json_string(value, TRUE),"
                        + " CASE WHEN TRUE IS NULL THEN NULL ELSE 'null' END) AS json",
                sql("SELECT TO_JSON_STRING(value, TRUE) AS json"));
        assertEquals("SELECT (CASE WHEN x THEN 1 ELSE 2 END) AS c", sql("SELECT IF(x, 1, 2) AS c"));
        assertEquals("SELECT date_diff('day', b, a) AS d", sql("SELECT DATE_DIFF(a, b, DAY) AS d"));
        assertEquals("SELECT date_diff('week', (b - INTERVAL 1 DAY), (a - INTERVAL 1 DAY)) AS d",
                sql("SELECT DATE_DIFF(a, b, WEEK(TUESDAY)) AS d"));
        assertEquals("SELECT cast_to_type(date_trunc('month', d), d) AS m", sql("SELECT DATE_TRUNC(d, MONTH) AS m"));
        assertEquals("SELECT cast_to_type((date_trunc('week', d - INTERVAL 1 DAY) + INTERVAL 1 DAY), d) AS t",
                sql("SELECT DATE_TRUNC(d, WEEK(TUESDAY)) AS t"));
        assertEquals("SELECT cast_to_type(date_trunc('month', CAST('2024-05-17' AS DATE)), CAST('2024-05-17' AS DATE)) AS m",
                sql("SELECT DATE_TRUNC('2024-05-17', MONTH) AS m"));
        assertEquals("SELECT strftime(ts, '%Y') AS y", sql("SELECT FORMAT_TIMESTAMP('%Y', ts) AS y"));
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
    void aggregateArrayConcatAndHllSketchFunctionsTranslateToDuckDb() {
        assertEquals("SELECT flatten(list(tags)) AS tags FROM \"ds\".\"t\"",
                sql("SELECT ARRAY_CONCAT_AGG(tags) AS tags FROM ds.t"));
        assertEquals("SELECT floci_hll_init(list_filter(list(id), value -> value IS NOT NULL), CAST(20 AS INTEGER)) AS sketch"
                        + " FROM \"ds\".\"t\"",
                sql("SELECT HLL_COUNT.INIT(id, 20) AS sketch FROM ds.t"));
        assertEquals("SELECT floci_hll_merge(list_filter(list(CAST(sketch AS BLOB)), sketch -> sketch IS NOT NULL))"
                        + " AS cardinality FROM \"ds\".\"t\"",
                sql("SELECT HLL_COUNT.MERGE(sketch) AS cardinality FROM ds.t"));
        assertEquals("SELECT floci_hll_extract(CAST(sketch AS BLOB)) AS cardinality FROM \"ds\".\"t\"",
                sql("SELECT HLL_COUNT.EXTRACT(sketch) AS cardinality FROM ds.t"));
    }

    @Test
    void containsSubstrNormalizesAndComparesScalarStringsCaseInsensitively() {
        assertEquals("SELECT contains(lower(nfc_normalize(CAST(name AS VARCHAR))),"
                        + " lower(nfc_normalize(CAST(',' AS VARCHAR)))) AS has_comma FROM \"ds\".\"t\"",
                sql("SELECT CONTAINS_SUBSTR(name, ',') AS has_comma FROM ds.t"));
    }

    @Test
    void toJsonStringUsesTheTypeAwareSidecarSerializer() {
        assertEquals("SELECT coalesce(floci_to_json_string(\"row\"), 'null') AS json",
                sql("SELECT TO_JSON_STRING(row) AS json"));
    }

    @Test
    void farmFingerprintUsesTheCompatibleSidecarScalar() {
        assertEquals("SELECT farm_fingerprint(CAST('1footrue' AS BLOB)) AS fingerprint FROM \"ds\".\"t\"",
                sql("SELECT FARM_FINGERPRINT(CAST('1footrue' AS BYTES)) AS fingerprint FROM ds.t"));
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
        assertEquals(t.sql(), SqlDialectTranslator.translateScript(
                "SELECT metric, metric_value FROM (SELECT 1 AS present, CAST(NULL AS INT64) AS absent)\n"
                        + "    UNPIVOT (metric_value FOR metric IN (present, absent))",
                "test-project", null, SqlDialectTranslator.QueryParameters.none()).sql());
        assertEquals("SELECT id, metric, value FROM \"ds\".\"metrics\""
                        + " UNPIVOT INCLUDE NULLS (value FOR metric IN (a, b))",
                sql("SELECT id, metric, value FROM ds.metrics UNPIVOT INCLUDE NULLS (value FOR metric IN (a, b))"));
    }

    @Test
    void hllSketchFunctionsValidateTheirSignatures() {
        invalid("SELECT HLL_COUNT.MERGE()");
        assertTrue(invalid("SELECT HLL_COUNT.EXTRACT(a, b)").getMessage().contains("HLL_COUNT.EXTRACT"));
        assertTrue(invalid("SELECT HLL_COUNT.INIT(a, 10, 20)").getMessage().contains("HLL_COUNT.INIT"));
    }

    @Test
    void lengthAndJsonFunctionsValidateTheirSignatures() {
        assertTrue(invalid("SELECT LENGTH(a, b)").getMessage().contains("LENGTH"));
        assertTrue(invalid("SELECT BYTE_LENGTH(a, b)").getMessage().contains("BYTE_LENGTH"));
        assertTrue(invalid("SELECT TO_JSON_STRING()").getMessage().contains("TO_JSON_STRING"));
        assertTrue(invalid("SELECT TO_JSON_STRING(a, TRUE, FALSE)").getMessage().contains("TO_JSON_STRING"));
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
    void intervalDatePartsAreNotAliasesButAnAliasAfterThemIs() {
        assertEquals("SELECT d + INTERVAL 1 DAY AS \"name\" FROM \"ds\".\"t\"", sql("SELECT d + INTERVAL 1 DAY name FROM ds.t"));
        assertEquals("SELECT (d + INTERVAL 1 DAY) AS \"name\" FROM \"ds\".\"t\"", sql("SELECT (d + INTERVAL 1 DAY) name FROM ds.t"));
        assertEquals("SELECT d + INTERVAL 1 DAY AS f0_ FROM \"ds\".\"t\"", sql("SELECT d + INTERVAL 1 DAY FROM ds.t"));
        assertEquals("SELECT INTERVAL '1:2' HOUR TO MINUTE AS f0_ FROM \"ds\".\"t\"", sql("SELECT INTERVAL '1:2' HOUR TO MINUTE FROM ds.t"));
    }

    @Test
    void offsetAsANameIsQuoted() {
        assertEquals("SELECT 1 \"offset\"", sql("SELECT 1 offset"));
        assertEquals("SELECT 1 AS \"offset\"", sql("SELECT 1 AS offset"));
        assertEquals("SELECT \"offset\" + 1 AS o2 FROM \"ds\".\"t\" WHERE \"offset\" = 1 ORDER BY \"offset\"", sql("SELECT offset + 1 AS o2 FROM ds.t WHERE offset = 1 ORDER BY offset"));
    }

    @Test
    void cteNamedOffsetIsQuoted() {
        assertEquals("WITH \"offset\" AS (SELECT 1 AS x) SELECT x FROM \"offset\"", sql("WITH offset AS (SELECT 1 AS x) SELECT x FROM offset"));
        assertEquals("SELECT v, o FROM LATERAL (SELECT \"_value\" AS \"v\", \"_ordinality\" - 1 AS \"o\""
                        + " FROM UNNEST([1]) WITH ORDINALITY AS \"_floci_offset\"(\"_value\", \"_ordinality\"))"
                        + " AS \"_unnest_v\"",
                sql("SELECT v, o FROM UNNEST([1]) v WITH OFFSET AS o"));
    }

    @Test
    void offsetKeywordsStayKeywords() {
        assertEquals("SELECT x, 5 \"offset\" FROM \"ds\".\"t\" ORDER BY x LIMIT 1 OFFSET 1", sql("SELECT x, 5 offset FROM ds.t ORDER BY x LIMIT 1 OFFSET 1"));
        String ordinary = sql("SELECT a[OFFSET(1)] FROM ds.t");
        assertTrue(ordinary.contains("IS NULL THEN NULL"), ordinary);
        assertTrue(ordinary.contains("error('Array index out of bounds')"), ordinary);
        String safe = sql("SELECT a[SAFE_OFFSET(-2)] FROM ds.t");
        assertTrue(safe.contains(">= 0 THEN"), safe);
        assertTrue(safe.contains("ELSE NULL END"), safe);
        String literal = sql("SELECT ['zero', 'one'][OFFSET(0)]");
        assertEquals(literal.indexOf("['zero', 'one']"), literal.lastIndexOf("['zero', 'one']"), literal);
        assertTrue(literal.contains("error('Array index out of bounds')"), literal);
        assertEquals("invalidQuery", assertThrows(GcpException.class,
                () -> sql("SELECT x FROM UNNEST([1]) x WITH OFFSET")).getReason());
    }

    @Test
    void parenthesizedArrayOffsetRetainsOuterSelect() {
        String translated = sql("SELECT ([1, 2])[OFFSET(0)] AS value");

        assertTrue(translated.startsWith("SELECT "), translated);
        assertTrue(translated.endsWith(" AS value"), translated);
        assertFalse(translated.contains("[OFFSET("), translated);
    }

    @Test
    void unreservedFunctionNameCanBeIndexed() {
        SqlDialectTranslator.ScriptTranslation script = SqlDialectTranslator.translateScript("""
                CREATE TEMP FUNCTION last(x ARRAY<INT64>) AS (x);
                SELECT last([1, 2])[OFFSET(0)] AS value;
                """, "test-project", null, SqlDialectTranslator.QueryParameters.none());

        assertTrue(script.setupSql().startsWith("CREATE TEMP MACRO \"last\"("), script.setupSql());
        assertTrue(script.sql().contains(":= last([1, 2])"), script.sql());
        assertFalse(script.sql().contains("[OFFSET("), script.sql());
    }

    @Test
    void selectAsStructAppliesOffsetBoundsBeforeRenderingItsFields() {
        String safe = sql("SELECT AS STRUCT ['zero'][SAFE_OFFSET(-1)] AS value");
        assertTrue(safe.contains(">= 0 THEN"), safe);
        assertTrue(safe.contains("ELSE NULL END"), safe);

        String ordinary = sql("SELECT AS STRUCT ['zero'][OFFSET(1)] AS value");
        assertTrue(ordinary.contains("ELSE error('Array index out of bounds') END"), ordinary);
    }

    @Test
    void indexedNestedStructQueriesAreTranslatedBeforeOffsetRewriting() {
        String translated = sql("SELECT ARRAY(SELECT AS STRUCT 1 AS id)[OFFSET(0)].id");

        assertTrue(translated.contains("SELECT {'id': 1}"), translated);
        assertFalse(translated.contains("SELECT AS STRUCT"), translated);
        assertTrue(translated.contains("error('Array index out of bounds')"), translated);
    }

    @Test
    void temporaryFunctionParametersDoNotReplaceExtractDateParts() {
        SqlDialectTranslator.ScriptTranslation script = SqlDialectTranslator.translateScript("""
                CREATE TEMP FUNCTION f(day INT64, ts TIMESTAMP)
                AS (EXTRACT(DAY FROM ts) + day);
                SELECT f(1, TIMESTAMP '2026-10-01 00:00:00+00');
                """, "test-project", null, SqlDialectTranslator.QueryParameters.none());

        assertTrue(script.setupSql().contains(
                "EXTRACT(DAY FROM CAST(\"_floci_udf_arg_1\" AS TIMESTAMPTZ))"), script.setupSql());
        assertTrue(script.setupSql().contains("+ CAST(\"_floci_udf_arg_0\" AS BIGINT)"), script.setupSql());
    }

    @Test
    void temporaryFunctionSubqueryAliasesShadowParameters() {
        SqlDialectTranslator.ScriptTranslation script = SqlDialectTranslator.translateScript("""
                CREATE TEMP FUNCTION f(x INT64) AS ((SELECT x FROM UNNEST([2]) AS x));
                SELECT f(1) AS value;
                """, "test-project", null, SqlDialectTranslator.QueryParameters.none());

        assertTrue(script.setupSql().contains("SELECT x FROM UNNEST([2]) AS"), script.setupSql());
        assertFalse(script.setupSql().contains("SELECT CAST(\"_floci_udf_arg_0\" AS BIGINT) FROM"),
                script.setupSql());

        SqlDialectTranslator.ScriptTranslation implicit = SqlDialectTranslator.translateScript("""
                CREATE TEMP FUNCTION f(x INT64) AS ((SELECT x FROM UNNEST([2]) x));
                SELECT f(1) AS value;
                """, "test-project", null, SqlDialectTranslator.QueryParameters.none());
        assertTrue(implicit.setupSql().contains("SELECT x FROM UNNEST([2]) AS"), implicit.setupSql());
        assertFalse(implicit.setupSql().contains("SELECT CAST(\"_floci_udf_arg_0\" AS BIGINT) FROM"),
                implicit.setupSql());
    }

    @Test
    void temporaryFunctionParametersDoNotReplaceFunctionDateParts() {
        SqlDialectTranslator.ScriptTranslation script = SqlDialectTranslator.translateScript("""
                CREATE TEMP FUNCTION f(day INT64, ts TIMESTAMP)
                AS (EXTRACT(DAY FROM TIMESTAMP_TRUNC(ts, DAY)) + day);
                SELECT f(1, TIMESTAMP '2026-10-01 00:00:00+00');
                """, "test-project", null, SqlDialectTranslator.QueryParameters.none());

        assertTrue(script.setupSql().contains(
                "date_trunc('day', CAST(\"_floci_udf_arg_1\" AS TIMESTAMPTZ))"), script.setupSql());
        assertTrue(script.setupSql().contains("+ CAST(\"_floci_udf_arg_0\" AS BIGINT)"), script.setupSql());
    }

    @Test
    void temporaryFunctionParametersDoNotReplaceIntervalDateParts() {
        SqlDialectTranslator.ScriptTranslation script = SqlDialectTranslator.translateScript("""
                CREATE TEMP FUNCTION f(day INT64, d DATE)
                AS (UNIX_DATE(DATE_ADD(d, INTERVAL 1 DAY)) + day);
                SELECT f(1, DATE '2026-10-01');
                """, "test-project", null, SqlDialectTranslator.QueryParameters.none());

        assertTrue(script.setupSql().contains("INTERVAL 1 DAY"), script.setupSql());
        assertTrue(script.setupSql().contains("+ CAST(\"_floci_udf_arg_0\" AS BIGINT)"), script.setupSql());
    }

    @Test
    void chainedSafeOffsetsKeepTheIndexedStructAsTheFieldReceiver() {
        String translated = sql("SELECT groups[SAFE_OFFSET(0)].items[SAFE_OFFSET(0)].value FROM ds.t");

        assertTrue(translated.contains("struct_pack(\"_floci_offset_array_0\" := groups"), translated);
        assertTrue(translated.contains(")), 1).items, \"_floci_offset_index_1\" := 0"), translated);
        assertFalse(translated.contains(".list_extract(items"), translated);
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
    void netIpFunctionsUseSidecarByteScalars() {
        assertEquals("""
                SELECT safe_ip_from_string(ip) AS ipv4, ip_trunc(safe_ip_from_string(ipv6), 64) AS ipv6 FROM "ds"."t\"""",
                sql("SELECT NET.SAFE_IP_FROM_STRING(ip) AS ipv4, "
                        + "NET.IP_TRUNC(NET.SAFE_IP_FROM_STRING(ipv6), 64) AS ipv6 FROM ds.t"));
    }

    @Test
    void netIpToStringUsesTheSidecarScalar() {
        assertEquals("SELECT ip_to_string(ip) AS ip_string FROM \"ds\".\"t\"",
                sql("SELECT NET.IP_TO_STRING(ip) AS ip_string FROM ds.t"));
    }

    @Test
    void havingCanDereferenceAnAggregateStructAlias() {
        assertEquals("""
                WITH selected AS (
                  SELECT {'group_id': group_id, 'latest_at': MAX(_PARTITIONTIME)} AS latest_per_group
                  FROM "ds"."t" GROUP BY group_id HAVING (MAX(_PARTITIONTIME)) <= '2021-01-30'
                )
                SELECT * FROM "selected\"""",
                sql("""
                        WITH selected AS (
                          SELECT {'group_id': group_id, 'latest_at': MAX(_PARTITIONTIME)} AS latest_per_group
                          FROM ds.t GROUP BY group_id HAVING latest_per_group.latest_at <= '2021-01-30'
                        )
                        SELECT * FROM selected"""));
    }

    @Test
    void havingCanDereferenceGoogleSqlStructAliasFields() {
        assertEquals("""
                SELECT {'group_id': group_id, 'latest_at': MAX(_PARTITIONTIME)} AS latest_per_group
                FROM "ds"."t" GROUP BY group_id HAVING (MAX(_PARTITIONTIME)) <= '2021-01-30'""",
                sql("""
                        SELECT STRUCT(group_id, MAX(_PARTITIONTIME) AS latest_at) AS latest_per_group
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
        assertEquals("SELECT * NOT SIMILAR TO '^(_PARTITION(DATE|TIME)|_TABLE_SUFFIX)$' FROM \"ds\".\"t\""
                + " WHERE name = CAST('O''Brien' AS VARCHAR)"
                + " AND age >= CAST(21 AS BIGINT) AND ts > CAST('2024-01-02 03:04:05+00:00' AS TIMESTAMPTZ)", out);
    }

    @Test
    void positionalAndArrayParameters() {
        List<Map<String, Object>> params = List.of(Map.of(
                "parameterType", Map.of("type", "ARRAY", "arrayType", Map.of("type", "INT64")),
                "parameterValue", Map.of("arrayValues", List.of(Map.of("value", "1"), Map.of("value", "2")))));
        String out = SqlDialectTranslator.translate("SELECT * FROM ds.t WHERE id IN UNNEST(?)",
                "test-project", null, new SqlDialectTranslator.QueryParameters(params, "POSITIONAL")).sql();
        assertEquals("SELECT * NOT SIMILAR TO '^(_PARTITION(DATE|TIME)|_TABLE_SUFFIX)$' FROM \"ds\".\"t\""
                + " WHERE id IN (SELECT UNNEST([CAST(1 AS BIGINT), CAST(2 AS BIGINT)]))", out);
    }

    @Test
    void malformedBoolParameterIsRejectedRatherThanCoercedToFalse() {
        String out = SqlDialectTranslator.translate("SELECT * FROM ds.t WHERE active = @flag",
                "test-project", null, new SqlDialectTranslator.QueryParameters(
                        List.of(param("flag", "BOOL", "TRUE")), "NAMED")).sql();
        assertEquals("SELECT * NOT SIMILAR TO '^(_PARTITION(DATE|TIME)|_TABLE_SUFFIX)$'"
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
                        + " VALUES (\"s\".id + 1, UPPER(CAST(\"s\".v AS VARCHAR)), \"s\".d + INTERVAL 1 DAY)"
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
        assertTrue(moreDates.contains("date_trunc('day', \"S\".ts)") && moreDates.contains("YEAR TO MONTH")
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
        assertEquals(Set.of(new InformationSchema.Ref("SCHEMATA", null, "us-central1")),
                translate("SELECT * FROM region-us-central1.INFORMATION_SCHEMA.SCHEMATA").informationSchema());
        assertEquals(Set.of(new InformationSchema.Ref("SCHEMATA", null, "us")),
                translate("SELECT * FROM INFORMATION_SCHEMA.SCHEMATA").informationSchema());
        assertEquals(Set.of(new InformationSchema.Ref("SCHEMATA", null, "us")),
                translate("SELECT * FROM `test-project`.INFORMATION_SCHEMA.SCHEMATA").informationSchema());

        SqlDialectTranslator.Translation partitions = translate(
                "SELECT partition_id, total_rows FROM `test-project.ds.INFORMATION_SCHEMA.PARTITIONS`"
                        + " WHERE table_name = 'events' AND partition_id != '__NULL__' ORDER BY partition_id");
        assertEquals(Set.of(new InformationSchema.Ref("PARTITIONS", "ds", null)), partitions.informationSchema());
        assertTrue(partitions.sql().contains("WHERE table_name = 'events' AND partition_id != '__NULL__'"));
    }

    @Test
    void informationSchemaUsesTheDefaultDatasetAndValidatesTheView() {
        assertEquals(Set.of(new InformationSchema.Ref("VIEWS", "dflt", null)),
                SqlDialectTranslator.translate("SELECT * FROM INFORMATION_SCHEMA.VIEWS", "test-project", "dflt",
                        SqlDialectTranslator.QueryParameters.none()).informationSchema());
        assertTrue(invalid("SELECT * FROM INFORMATION_SCHEMA.TABLES").getMessage().contains("qualifier"));
        assertTrue(invalid("SELECT * FROM ds.INFORMATION_SCHEMA.tables").getMessage().contains("case-sensitive"));
        invalid("SELECT * FROM ds.INFORMATION_SCHEMA.JOBS");
        invalid("SELECT * FROM ds.INFORMATION_SCHEMA.SCHEMATA");
    }

    private static Map<String, Object> param(String name, String type, String value) {
        return Map.of("name", name, "parameterType", Map.of("type", type), "parameterValue", Map.of("value", value));
    }
}
