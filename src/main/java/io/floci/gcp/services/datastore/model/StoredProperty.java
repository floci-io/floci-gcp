package io.floci.gcp.services.datastore.model;


import com.google.datastore.v1.ArrayValue;
import com.google.datastore.v1.Entity;
import com.google.datastore.v1.Key;
import com.google.datastore.v1.Value;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.NullValue;
import com.google.protobuf.Timestamp;
import com.google.type.LatLng;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.jboss.logging.Logger;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RegisterForReflection

public class StoredProperty {

    private static final Logger LOG = Logger.getLogger(StoredProperty.class);

    private String type;
    private Boolean booleanValue;
    private Long integerValue;
    private Double doubleValue;
    private String stringValue;
    private List<StoredProperty> arrayValues;
    private Map<String, StoredProperty> entityProperties;
    /**
     * A key value, or an embedded entity's own key, as the base64 of the serialized {@code Key}.
     * Unlike the flattened path in {@code stringValue}, this keeps ancestors and tells an id from a
     * name with the same digits, so the key can be rebuilt on read.
     */
    private String keyValue;
    /** {@code Value.meaning}, or null when unset (0). */
    private Integer meaning;
    /** {@code Value.exclude_from_indexes}, or null when false. */
    private Boolean excludeFromIndexes;

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
            case GEO_POINT_VALUE -> {
                p.type = "geo_point";
                LatLng geo = v.getGeoPointValue();
                p.stringValue = geo.getLatitude() + "," + geo.getLongitude();
            }
            case KEY_VALUE -> {
                p.type = "key";
                p.keyValue = encodeKey(v.getKeyValue());
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
                if (v.getEntityValue().hasKey()) {
                    p.keyValue = encodeKey(v.getEntityValue().getKey());
                }
            }
            default -> p.type = "null";
        }
        if (v.getMeaning() != 0) {
            p.meaning = v.getMeaning();
        }
        if (v.getExcludeFromIndexes()) {
            p.excludeFromIndexes = true;
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
            case "geo_point" -> {
                if (stringValue != null) {
                    String[] latLng = stringValue.split(",", 2);
                    b.setGeoPointValue(LatLng.newBuilder()
                            .setLatitude(Double.parseDouble(latLng[0]))
                            .setLongitude(Double.parseDouble(latLng[1]))
                            .build());
                }
            }
            case "array" -> {
                ArrayValue.Builder av = ArrayValue.newBuilder();
                if (arrayValues != null) {
                    arrayValues.forEach(sp -> av.addValues(sp.toProto()));
                }
                b.setArrayValue(av.build());
            }
            case "key" -> {
                // A key stored before keyValue existed holds only the flattened path, which cannot be
                // rebuilt into a Key, so it still reads back as null.
                Key key = decodeKey(keyValue);
                if (key != null) {
                    b.setKeyValue(key);
                } else {
                    b.setNullValue(NullValue.NULL_VALUE);
                }
            }
            case "entity" -> {
                Entity.Builder eb = Entity.newBuilder();
                Key key = decodeKey(keyValue);
                if (key != null) {
                    eb.setKey(key);
                }
                if (entityProperties != null) {
                    entityProperties.forEach((name, sp) -> eb.putProperties(name, sp.toProto()));
                }
                b.setEntityValue(eb.build());
            }
            default -> b.setNullValue(NullValue.NULL_VALUE);
        }
        if (meaning != null) {
            b.setMeaning(meaning);
        }
        if (Boolean.TRUE.equals(excludeFromIndexes)) {
            b.setExcludeFromIndexes(true);
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
            case "timestamp" -> matchesTimestamp(filterValue);
            case "bytes" -> filterValue.hasBlobValue()
                    && stringValue != null
                    && stringValue.equals(Base64.getEncoder().encodeToString(
                            filterValue.getBlobValue().toByteArray()));
            case "key" -> matchesKey(filterValue);
            case "array" -> matchesArray(filterValue);
            case "entity" -> matchesEntity(filterValue);
            default -> false;
        };
    }

    private boolean matchesTimestamp(Value filterValue) {
        if (stringValue == null || !filterValue.hasTimestampValue()) {
            return false;
        }
        try {
            Instant stored = Instant.parse(stringValue);
            Timestamp timestamp = filterValue.getTimestampValue();
            Instant filter = Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
            return stored.equals(filter);
        } catch (DateTimeException expected) {
            // An unparseable or out-of-range timestamp is not equal, and the query must not fail.
            return false;
        }
    }

    private boolean matchesKey(Value filterValue) {
        if (!filterValue.hasKeyValue()) {
            return false;
        }
        Key key = decodeKey(keyValue);
        if (key != null) {
            return key.equals(filterValue.getKeyValue());
        }
        return stringValue != null && stringValue.equals(keyToString(filterValue.getKeyValue()));
    }

    private boolean matchesArray(Value filterValue) {
        if (arrayValues == null) {
            return false;
        }
        for (StoredProperty element : arrayValues) {
            if (element != null && element.matchesEqual(filterValue)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesEntity(Value filterValue) {
        if (entityProperties == null || !filterValue.hasEntityValue()) {
            return false;
        }
        Map<String, Value> filterProperties = filterValue.getEntityValue().getPropertiesMap();
        if (!entityProperties.keySet().equals(filterProperties.keySet())) {
            return false;
        }
        for (Map.Entry<String, StoredProperty> entry : entityProperties.entrySet()) {
            StoredProperty property = entry.getValue();
            if (property == null || !property.matchesEqual(filterProperties.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static String encodeKey(Key key) {
        return Base64.getEncoder().encodeToString(key.toByteArray());
    }

    private static Key decodeKey(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            return Key.parseFrom(Base64.getDecoder().decode(encoded));
        } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
            // Only encodeKey writes this field, so a value that does not decode means the stored data
            // was damaged or edited by hand. The property reads back as null rather than failing the read.
            LOG.warnf("Stored Datastore key value is not a valid Key and reads back as null: %s", e.getMessage());
            return null;
        }
    }

    /** The flattened path that key values were stored as before {@code keyValue}; kept for matching them. */
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
    public String getKeyValue() { return keyValue; }
    public void setKeyValue(String keyValue) { this.keyValue = keyValue; }
    public Integer getMeaning() { return meaning; }
    public void setMeaning(Integer meaning) { this.meaning = meaning; }
    public Boolean getExcludeFromIndexes() { return excludeFromIndexes; }
    public void setExcludeFromIndexes(Boolean excludeFromIndexes) { this.excludeFromIndexes = excludeFromIndexes; }
}
