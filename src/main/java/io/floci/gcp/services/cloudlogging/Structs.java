package io.floci.gcp.services.cloudlogging;

import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dependency-free conversion between protobuf {@link Struct}/{@link Value}/{@link ListValue} and
 * plain Java objects (Map/List/String/Double/Boolean/null), used to store and round-trip a
 * {@code LogEntry.json_payload} without pulling in protobuf-java-util.
 */
final class Structs {

    private Structs() {}

    static Object toJava(Struct struct) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, Value> e : struct.getFieldsMap().entrySet()) {
            map.put(e.getKey(), toJava(e.getValue()));
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    static Struct toStruct(Object value) {
        Struct.Builder builder = Struct.newBuilder();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                builder.putFields(String.valueOf(e.getKey()), toValue(e.getValue()));
            }
        }
        return builder.build();
    }

    private static Object toJava(Value value) {
        return switch (value.getKindCase()) {
            case STRUCT_VALUE -> toJava(value.getStructValue());
            case LIST_VALUE -> {
                List<Object> list = new ArrayList<>();
                for (Value v : value.getListValue().getValuesList()) {
                    list.add(toJava(v));
                }
                yield list;
            }
            case STRING_VALUE -> value.getStringValue();
            case NUMBER_VALUE -> value.getNumberValue();
            case BOOL_VALUE -> value.getBoolValue();
            case NULL_VALUE, KIND_NOT_SET -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private static Value toValue(Object o) {
        if (o == null) {
            return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        }
        if (o instanceof String s) {
            return Value.newBuilder().setStringValue(s).build();
        }
        if (o instanceof Boolean b) {
            return Value.newBuilder().setBoolValue(b).build();
        }
        if (o instanceof Number n) {
            return Value.newBuilder().setNumberValue(n.doubleValue()).build();
        }
        if (o instanceof Map<?, ?> map) {
            return Value.newBuilder().setStructValue(toStruct(map)).build();
        }
        if (o instanceof List<?> list) {
            ListValue.Builder lv = ListValue.newBuilder();
            for (Object item : list) {
                lv.addValues(toValue(item));
            }
            return Value.newBuilder().setListValue(lv.build()).build();
        }
        return Value.newBuilder().setStringValue(String.valueOf(o)).build();
    }
}
