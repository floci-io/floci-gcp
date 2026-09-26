package io.floci.gcp.services.bigquery;

import io.floci.gcp.services.bigquery.model.ErrorProto;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;
import io.floci.gcp.services.bigquery.model.TableSchema;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RowCodecTest {

    private static List<ErrorProto> normalize(Object value, Map<String, Object> out) {
        TableFieldSchema field = new TableFieldSchema();
        field.setName("n");
        field.setType("INTEGER");
        return RowCodec.normalizeRow(new TableSchema(List.of(field)), Map.of("n", value), false, out);
    }

    @Test
    void integersInRangeAreKept() {
        for (Object value : List.of(Long.MAX_VALUE, Long.MIN_VALUE, 7, new BigDecimal("42"), new BigInteger("-3"), 5.0)) {
            Map<String, Object> out = new LinkedHashMap<>();
            assertTrue(normalize(value, out).isEmpty(), String.valueOf(value));
            assertEquals(new BigDecimal(value.toString()).longValueExact(), out.get("n"), String.valueOf(value));
        }
    }

    @Test
    void integersOutsideInt64AreRejectedNotWrapped() {
        for (Object value : List.of(new BigDecimal("18446744073709551615"), new BigInteger("9223372036854775808"),
                1e19, Double.POSITIVE_INFINITY)) {
            List<ErrorProto> errors = normalize(value, new LinkedHashMap<>());
            assertEquals(1, errors.size(), String.valueOf(value));
            assertTrue(errors.get(0).getMessage().contains("Cannot convert value to integer"),
                    errors.get(0).getMessage());
        }
    }
}
