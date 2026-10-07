package io.floci.gcp.services.firestore.model;


import com.google.firestore.v1.ArrayValue;
import com.google.firestore.v1.MapValue;
import com.google.firestore.v1.Value;
import com.google.protobuf.ByteString;
import com.google.protobuf.NullValue;
import com.google.protobuf.Timestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection

public class StoredValue {

    private String type;
    private Boolean booleanValue;
    private Long integerValue;
    private Double doubleValue;
    private String stringValue;
    private List<StoredValue> arrayValue;
    private Map<String, StoredValue> mapValue;

    public StoredValue() {}

    public static StoredValue fromProto(Value v) {
        StoredValue sv = new StoredValue();
        switch (v.getValueTypeCase()) {
            case NULL_VALUE -> sv.type = "null";
            case BOOLEAN_VALUE -> {
                sv.type = "boolean";
                sv.booleanValue = v.getBooleanValue();
            }
            case INTEGER_VALUE -> {
                sv.type = "integer";
                sv.integerValue = v.getIntegerValue();
            }
            case DOUBLE_VALUE -> {
                sv.type = "double";
                sv.doubleValue = v.getDoubleValue();
            }
            case STRING_VALUE -> {
                sv.type = "string";
                sv.stringValue = v.getStringValue();
            }
            case TIMESTAMP_VALUE -> {
                sv.type = "timestamp";
                Timestamp ts = v.getTimestampValue();
                sv.stringValue = Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos()).toString();
            }
            case BYTES_VALUE -> {
                sv.type = "bytes";
                sv.stringValue = Base64.getEncoder().encodeToString(v.getBytesValue().toByteArray());
            }
            case REFERENCE_VALUE -> {
                sv.type = "reference";
                sv.stringValue = v.getReferenceValue();
            }
            case ARRAY_VALUE -> {
                sv.type = "array";
                sv.arrayValue = v.getArrayValue().getValuesList().stream()
                        .map(StoredValue::fromProto).collect(Collectors.toList());
            }
            case MAP_VALUE -> {
                sv.type = "map";
                sv.mapValue = new LinkedHashMap<>();
                v.getMapValue().getFieldsMap().forEach((k, val) -> sv.mapValue.put(k, fromProto(val)));
            }
            default -> sv.type = "null";
        }
        return sv;
    }

    public Value toProto() {
        Value.Builder b = Value.newBuilder();
        if (type == null) return b.setNullValue(NullValue.NULL_VALUE).build();
        switch (type) {
            case "null" -> b.setNullValue(NullValue.NULL_VALUE);
            case "boolean" -> b.setBooleanValue(booleanValue != null && booleanValue);
            case "integer" -> b.setIntegerValue(integerValue != null ? integerValue : 0L);
            case "double" -> b.setDoubleValue(doubleValue != null ? doubleValue : 0.0);
            case "string" -> b.setStringValue(stringValue != null ? stringValue : "");
            case "reference" -> b.setReferenceValue(stringValue != null ? stringValue : "");
            case "timestamp" -> {
                if (stringValue != null) {
                    Instant i = Instant.parse(stringValue);
                    b.setTimestampValue(Timestamp.newBuilder()
                            .setSeconds(i.getEpochSecond()).setNanos(i.getNano()).build());
                }
            }
            case "bytes" -> {
                if (stringValue != null) {
                    b.setBytesValue(ByteString.copyFrom(Base64.getDecoder().decode(stringValue)));
                }
            }
            case "array" -> {
                ArrayValue.Builder av = ArrayValue.newBuilder();
                if (arrayValue != null) {
                    arrayValue.forEach(sv -> av.addValues(sv.toProto()));
                }
                b.setArrayValue(av.build());
            }
            case "map" -> {
                MapValue.Builder mv = MapValue.newBuilder();
                if (mapValue != null) {
                    mapValue.forEach((k, sv) -> mv.putFields(k, sv.toProto()));
                }
                b.setMapValue(mv.build());
            }
            default -> b.setNullValue(NullValue.NULL_VALUE);
        }
        return b.build();
    }

    public boolean matchesEqual(Value proto) {
        switch (proto.getValueTypeCase()) {
            case BOOLEAN_VALUE -> { return "boolean".equals(type) && proto.getBooleanValue() == Boolean.TRUE.equals(booleanValue); }
            case INTEGER_VALUE -> { return matchesNumber(BigDecimal.valueOf(proto.getIntegerValue())); }
            case DOUBLE_VALUE -> {
                double d = proto.getDoubleValue();
                if (Double.isNaN(d)) { return "double".equals(type) && doubleValue != null && doubleValue.isNaN(); }
                if (Double.isInfinite(d)) { return "double".equals(type) && doubleValue != null && doubleValue == d; }
                return matchesNumber(new BigDecimal(d));
            }
            case STRING_VALUE -> { return "string".equals(type) && proto.getStringValue().equals(stringValue); }
            case NULL_VALUE -> { return "null".equals(type); }
            case REFERENCE_VALUE -> { return "reference".equals(type) && proto.getReferenceValue().equals(stringValue); }
            case TIMESTAMP_VALUE -> {
                if (!"timestamp".equals(type) || stringValue == null) { return false; }
                try {
                    Instant a = Instant.parse(stringValue);
                    Timestamp ts = proto.getTimestampValue();
                    Instant b = Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
                    return a.equals(b);
                } catch (Exception e) {
                    return false;
                }
            }
            case BYTES_VALUE -> { return "bytes".equals(type) && stringValue != null
                    && stringValue.equals(Base64.getEncoder().encodeToString(proto.getBytesValue().toByteArray())); }
            case ARRAY_VALUE -> {
                List<Value> values = proto.getArrayValue().getValuesList();
                List<StoredValue> stored = arrayValue != null ? arrayValue : List.of();
                if (!"array".equals(type) || stored.size() != values.size()) { return false; }
                for (int i = 0; i < values.size(); i++) {
                    if (!stored.get(i).matchesEqual(values.get(i))) { return false; }
                }
                return true;
            }
            case MAP_VALUE -> {
                Map<String, Value> fields = proto.getMapValue().getFieldsMap();
                Map<String, StoredValue> stored = mapValue != null ? mapValue : Map.of();
                if (!"map".equals(type) || !stored.keySet().equals(fields.keySet())) { return false; }
                return fields.entrySet().stream().allMatch(e -> stored.get(e.getKey()).matchesEqual(e.getValue()));
            }
            default -> { return false; }
        }
    }

    private boolean matchesNumber(BigDecimal number) {
        if ("integer".equals(type) && integerValue != null) {
            return BigDecimal.valueOf(integerValue).compareTo(number) == 0;
        }
        if ("double".equals(type) && doubleValue != null && Double.isFinite(doubleValue)) {
            return new BigDecimal(doubleValue).compareTo(number) == 0;
        }
        return false;
    }

    public boolean matchesEqual(StoredValue other) {
        return other != null && matchesEqual(other.toProto());
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Boolean getBooleanValue() { return booleanValue; }
    public void setBooleanValue(Boolean booleanValue) { this.booleanValue = booleanValue; }

    public Long getIntegerValue() { return integerValue; }
    public void setIntegerValue(Long integerValue) { this.integerValue = integerValue; }

    public Double getDoubleValue() { return doubleValue; }
    public void setDoubleValue(Double doubleValue) { this.doubleValue = doubleValue; }

    public String getStringValue() { return stringValue; }
    public void setStringValue(String stringValue) { this.stringValue = stringValue; }

    public List<StoredValue> getArrayValue() { return arrayValue; }
    public void setArrayValue(List<StoredValue> arrayValue) { this.arrayValue = arrayValue; }

    public Map<String, StoredValue> getMapValue() { return mapValue; }
    public void setMapValue(Map<String, StoredValue> mapValue) { this.mapValue = mapValue; }
}
