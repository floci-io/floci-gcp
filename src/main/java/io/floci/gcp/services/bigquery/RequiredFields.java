package io.floci.gcp.services.bigquery;

import io.floci.gcp.services.bigquery.model.TableFieldSchema;

import java.util.List;
import java.util.Map;

/**
 * "A REQUIRED field cannot store a NULL value": BigQuery fails a write that would, naming the field
 * and then each field that encloses it, with a dot between the names.
 */
final class RequiredFields {

    private RequiredFields() {}

    static void check(List<TableFieldSchema> fields, List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            check(fields, row, "");
        }
    }

    private static void check(List<TableFieldSchema> fields, Map<?, ?> row, String prefix) {
        for (TableFieldSchema field : fields) {
            String path = prefix + field.getName();
            Object value = row.get(field.getName());
            if (value == null && "REQUIRED".equals(field.getMode())) {
                throw SqlDialectTranslator.invalidQuery("NULL value for required field " + path);
            }
            if (value == null || field.getFields() == null) {
                continue;
            }
            if ("REPEATED".equals(field.getMode()) && value instanceof List<?> values) {
                for (Object element : values) {
                    if (element instanceof Map<?, ?> nested) {
                        check(field.getFields(), nested, path + ".");
                    }
                }
            } else if (value instanceof Map<?, ?> nested) {
                check(field.getFields(), nested, path + ".");
            }
        }
    }
}
