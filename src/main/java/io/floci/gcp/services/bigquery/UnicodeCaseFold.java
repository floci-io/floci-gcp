package io.floci.gcp.services.bigquery;

import com.ibm.icu.text.Normalizer2;

/** DuckDB setup for GoogleSQL's NFKC case folding. Built lazily for queries that need it. */
final class UnicodeCaseFold {

    private static final Normalizer2 NORMALIZER = Normalizer2.getNFKCCasefoldInstance();

    private UnicodeCaseFold() {}

    static String setupSql() {
        return Setup.SQL;
    }

    private static final class Setup {
        private static final String SQL = build();

        private static String build() {
            StringBuilder sql = new StringBuilder("CREATE TEMP TABLE _floci_unicode_casefold("
                    + "source VARCHAR PRIMARY KEY, replacement VARCHAR);\n"
                    + "INSERT INTO _floci_unicode_casefold VALUES\n");
            boolean first = true;
            for (int codePoint = Character.MIN_CODE_POINT; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
                if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                    continue;
                }
                String source = new String(Character.toChars(codePoint));
                String replacement = NORMALIZER.normalize(source);
                if (source.equals(replacement)) {
                    continue;
                }
                if (!first) {
                    sql.append(",\n");
                }
                sql.append('(').append(DuckTypes.quoteLiteral(source)).append(", ")
                        .append(DuckTypes.quoteLiteral(replacement)).append(')');
                first = false;
            }
            sql.append(";\nCREATE MACRO _floci_nfkc_casefold(value) AS (CASE WHEN value IS NULL THEN NULL ELSE "
                    + "coalesce((SELECT nfc_normalize(string_agg(coalesce(mapping.replacement, item.character), '' "
                    + "ORDER BY item.ordinality)) FROM unnest(string_split(CAST(value AS VARCHAR), '')) "
                    + "WITH ORDINALITY AS item(character, ordinality) LEFT JOIN _floci_unicode_casefold mapping "
                    + "ON item.character = mapping.source), '') END);\n");
            return sql.toString();
        }
    }
}
