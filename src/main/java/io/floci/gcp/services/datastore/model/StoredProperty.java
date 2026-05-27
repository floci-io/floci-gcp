package io.floci.gcp.services.datastore.model;


import com.google.datastore.v1.ArrayValue;
import com.google.datastore.v1.Key;
import com.google.datastore.v1.Value;
import com.google.protobuf.ByteString;
import com.google.protobuf.NullValue;
import com.google.protobuf.Timestamp;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection

public class StoredProperty {

    private String type;
    private Boolean booleanValue;
    private Long integerValue;
    private Double doubleValue;
    private String stringValue;
    private List<StoredProperty> arrayValues;
    private Map<String, StoredProperty> entityProperties;

    public StoredProperty() {}

    public static StoredProperty fromProto(Value v) {
        StoredProperty p = new StoredProperty();
        switch (v.getValueTypeCase()) {
            case NULL_VALUE -> p.type = "null";
            case BOOLEAN_VALUE -> {
                p.type = "boolean";
                p.booleanValue = v.getBooleanValue();
            }
            case INTEGER_VALUE -> {
                p.type = "integer";
                p.integerValue = v.getIntegerValue();
            }
            case DOUBLE_VALUE -> {
                p.type = "double";
                p.doubleValue = v.getDoubleValue();
            }
            case STRING_VALUE -> {
                p.type = "string";
                p.stringValue = v.getStringValue();
            }
            case TIMESTAMP_VALUE -> {
                p.type = "timestamp";
                Timestamp ts = v.getTimestampValue();
                p.stringValue = Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos()).toString();
            }
            case BLOB_VALUE -> {
                p.type = "bytes";
                p.stringValue = Base64.getEncoder().encodeToString(v.getBlobValue().toByteArray());
            }
            case KEY_VALUE -> {
                p.type = "key";
                p.stringValue = keyToString(v.getKeyValue());
            }
            case ARRAY_VALUE -> {
                p.type = "array";
                p.arrayValues = v.getArrayValue().getValuesList().stream()
                        .map(StoredProperty::fromProto)
                        .collect(Collectors.toList());
            }
            case ENTITY_VALUE -> {
                p.type = "entity";
                p.entityProperties = v.getEntityValue().getPropertiesMap().entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> fromProto(e.getValue())));
            }
            default -> p.type = "null";
        }
        return p;
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
            case "timestamp" -> {
                if (stringValue != null) {
                    Instant instant = Instant.parse(stringValue);
                    b.setTimestampValue(Timestamp.newBuilder()
                            .setSeconds(instant.getEpochSecond())
                            .setNanos(instant.getNano())
                            .build());
                }
            }
            case "bytes" -> {
                if (stringValue != null) {
                    b.setBlobValue(ByteString.copyFrom(Base64.getDecoder().decode(stringValue)));
                }
            }
            case "array" -> {
                ArrayValue.Builder av = ArrayValue.newBuilder();
                if (arrayValues != null) {
                    arrayValues.forEach(sp -> av.addValues(sp.toProto()));
                }
                b.setArrayValue(av.build());
            }
            default -> b.setNullValue(NullValue.NULL_VALUE);
        }
        return b.build();
    }

    public boolean matchesEqual(Value filterValue) {
        if (type == null) return false;
        return switch (type) {
            case "boolean" -> filterValue.hasBooleanValue()
                    && booleanValue != null && booleanValue.equals(filterValue.getBooleanValue());
            case "integer" -> filterValue.hasIntegerValue()
                    && integerValue != null && integerValue.equals(filterValue.getIntegerValue());
            case "double" -> filterValue.hasDoubleValue()
                    && doubleValue != null && doubleValue.equals(filterValue.getDoubleValue());
            case "string" -> filterValue.hasStringValue()
                    && stringValue != null && stringValue.equals(filterValue.getStringValue());
            case "null" -> filterValue.hasNullValue();
            default -> false;
        };
    }

    private static String keyToString(Key key) {
        StringBuilder sb = new StringBuilder();
        sb.append(key.getPartitionId().getProjectId()).append('/');
        sb.append(key.getPartitionId().getNamespaceId());
        for (Key.PathElement el : key.getPathList()) {
            sb.append('/').append(el.getKind()).append('/');
            if (el.hasName()) {
                sb.append(el.getName());
            } else {
                sb.append(el.getId());
            }
        }
        return sb.toString();
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
    public List<StoredProperty> getArrayValues() { return arrayValues; }
    public void setArrayValues(List<StoredProperty> arrayValues) { this.arrayValues = arrayValues; }
    public Map<String, StoredProperty> getEntityProperties() { return entityProperties; }
    public void setEntityProperties(Map<String, StoredProperty> entityProperties) { this.entityProperties = entityProperties; }
}
