package io.floci.gcp.services.firestore;

import io.floci.gcp.services.firestore.model.StoredValue;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Firestore's total order over values, as defined by the backend and mirrored by the Java
 * client's {@code com.google.cloud.firestore.Order}: values compare by type first
 * (null &lt; boolean &lt; number &lt; timestamp &lt; string &lt; bytes &lt; reference &lt; geo point
 * &lt; array &lt; map), then within the type. Vector and BSON map representations are not
 * modelled and order as ordinary maps.
 */
final class FirestoreValueOrder {

    private static final int NULL = 0;
    private static final int BOOLEAN = 1;
    private static final int NUMBER = 2;
    private static final int TIMESTAMP = 3;
    private static final int STRING = 4;
    private static final int BYTES = 5;
    private static final int REFERENCE = 6;
    private static final int GEO_POINT = 7;
    private static final int ARRAY = 8;
    private static final int MAP = 9;

    private static final long MAX_INTEGER_TO_DOUBLE_PRECISION = 1L << 53;

    private FirestoreValueOrder() {}

    static int compare(StoredValue left, StoredValue right) {
        int leftRank = typeRank(left);
        int rightRank = typeRank(right);
        if (leftRank != rightRank) {
            return Integer.compare(leftRank, rightRank);
        }
        return switch (leftRank) {
            case BOOLEAN -> Boolean.compare(Boolean.TRUE.equals(left.getBooleanValue()),
                    Boolean.TRUE.equals(right.getBooleanValue()));
            case NUMBER -> compareNumbers(left, right);
            case TIMESTAMP -> compareTimestamps(left.getStringValue(), right.getStringValue());
            case STRING -> compareUtf8Strings(text(left), text(right));
            case BYTES -> compareBytes(left.getStringValue(), right.getStringValue());
            case REFERENCE -> compareResourceNames(text(left), text(right));
            case GEO_POINT -> compareGeoPoints(left.getStringValue(), right.getStringValue());
            case ARRAY -> compareArrays(left.getArrayValue(), right.getArrayValue());
            case MAP -> compareMaps(left.getMapValue(), right.getMapValue());
            default -> 0;
        };
    }

    /** Whether two values share a type rank; integers and doubles are both numbers. */
    static boolean sameType(StoredValue left, StoredValue right) {
        return typeRank(left) == typeRank(right);
    }

    static boolean isNull(StoredValue value) {
        return typeRank(value) == NULL;
    }

    static boolean isNaN(StoredValue value) {
        return "double".equals(value.getType()) && value.getDoubleValue() != null
                && value.getDoubleValue().isNaN();
    }

    /**
     * Compares strings in UTF-8 byte order. At the first differing UTF-16 unit, a surrogate
     * (part of a code point above U+FFFF, which takes four UTF-8 bytes) sorts after a
     * non-surrogate; otherwise the units compare directly.
     */
    static int compareUtf8Strings(String left, String right) {
        int length = Math.min(left.length(), right.length());
        for (int i = 0; i < length; i++) {
            char l = left.charAt(i);
            char r = right.charAt(i);
            if (l != r) {
                boolean leftSurrogate = Character.isSurrogate(l);
                if (leftSurrogate == Character.isSurrogate(r)) {
                    return Character.compare(l, r);
                }
                return leftSurrogate ? 1 : -1;
            }
        }
        return Integer.compare(left.length(), right.length());
    }

    /**
     * Compares document resource names segment by segment, then by segment count. Numeric ids
     * ({@code __id<n>__}) sort before string ids and compare numerically.
     */
    static int compareResourceNames(String left, String right) {
        String[] leftSegments = left.split("/");
        String[] rightSegments = right.split("/");
        int length = Math.min(leftSegments.length, rightSegments.length);
        for (int i = 0; i < length; i++) {
            int c = compareSegments(leftSegments[i], rightSegments[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(leftSegments.length, rightSegments.length);
    }

    private static int typeRank(StoredValue value) {
        if (value == null || value.getType() == null) {
            return NULL;
        }
        return switch (value.getType()) {
            case "boolean" -> BOOLEAN;
            case "integer", "double" -> NUMBER;
            case "timestamp" -> TIMESTAMP;
            case "string" -> STRING;
            case "bytes" -> BYTES;
            case "reference" -> REFERENCE;
            case "geo_point" -> GEO_POINT;
            case "array" -> ARRAY;
            case "map" -> MAP;
            default -> NULL;
        };
    }

    private static String text(StoredValue value) {
        return value.getStringValue() != null ? value.getStringValue() : "";
    }

    private static int compareNumbers(StoredValue left, StoredValue right) {
        if (isNaN(left)) {
            return isNaN(right) ? 0 : -1;
        }
        if (isNaN(right)) {
            return 1;
        }
        boolean leftDouble = "double".equals(left.getType());
        boolean rightDouble = "double".equals(right.getType());
        if (leftDouble && rightDouble) {
            return compareDoubles(doubleOf(left), doubleOf(right));
        }
        if (leftDouble) {
            return compareDoubleAndLong(doubleOf(left), longOf(right));
        }
        if (rightDouble) {
            return -compareDoubleAndLong(doubleOf(right), longOf(left));
        }
        return Long.compare(longOf(left), longOf(right));
    }

    private static double doubleOf(StoredValue value) {
        return value.getDoubleValue() != null ? value.getDoubleValue() : 0.0;
    }

    private static long longOf(StoredValue value) {
        return value.getIntegerValue() != null ? value.getIntegerValue() : 0L;
    }

    /** Firestore treats -0.0 and 0.0 as equal. */
    private static int compareDoubles(double left, double right) {
        return Double.compare(left == 0.0 ? 0.0 : left, right == 0.0 ? 0.0 : right);
    }

    private static int compareDoubleAndLong(double doubleValue, long longValue) {
        if (Math.abs(longValue) <= MAX_INTEGER_TO_DOUBLE_PRECISION) {
            return compareDoubles(doubleValue, (double) longValue);
        }
        if (doubleValue < (double) Long.MAX_VALUE && doubleValue >= (double) Long.MIN_VALUE) {
            return Long.compare((long) doubleValue, longValue);
        }
        return doubleValue < 0 ? -1 : 1;
    }

    private static int compareTimestamps(String left, String right) {
        Instant l = parseInstant(left);
        Instant r = parseInstant(right);
        if (l == null || r == null) {
            return Boolean.compare(l != null, r != null);
        }
        return l.compareTo(r);
    }

    private static Instant parseInstant(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            return StoredValue.toInstant(stored);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static int compareBytes(String left, String right) {
        byte[] l = decodeBytes(left);
        byte[] r = decodeBytes(right);
        int length = Math.min(l.length, r.length);
        for (int i = 0; i < length; i++) {
            int c = Integer.compare(l[i] & 0xff, r[i] & 0xff);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(l.length, r.length);
    }

    private static byte[] decodeBytes(String base64) {
        return base64 != null ? Base64.getDecoder().decode(base64) : new byte[0];
    }

    private static int compareSegments(String left, String right) {
        boolean leftNumeric = isNumericId(left);
        boolean rightNumeric = isNumericId(right);
        if (leftNumeric && rightNumeric) {
            return Long.compare(numericId(left), numericId(right));
        }
        if (leftNumeric != rightNumeric) {
            return leftNumeric ? -1 : 1;
        }
        return compareUtf8Strings(left, right);
    }

    private static boolean isNumericId(String segment) {
        if (segment.length() <= 6 || !segment.startsWith("__id") || !segment.endsWith("__")) {
            return false;
        }
        try {
            numericId(segment);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static long numericId(String segment) {
        return Long.parseLong(segment.substring(4, segment.length() - 2));
    }

    private static int compareGeoPoints(String left, String right) {
        double[] l = parseGeoPoint(left);
        double[] r = parseGeoPoint(right);
        int byLatitude = Double.compare(l[0], r[0]);
        return byLatitude != 0 ? byLatitude : Double.compare(l[1], r[1]);
    }

    private static double[] parseGeoPoint(String stored) {
        if (stored == null) {
            return new double[] {0.0, 0.0};
        }
        String[] latLng = stored.split(",", 2);
        return new double[] {Double.parseDouble(latLng[0]), Double.parseDouble(latLng[1])};
    }

    private static int compareArrays(List<StoredValue> left, List<StoredValue> right) {
        List<StoredValue> l = left != null ? left : List.of();
        List<StoredValue> r = right != null ? right : List.of();
        int length = Math.min(l.size(), r.size());
        for (int i = 0; i < length; i++) {
            int c = compare(l.get(i), r.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(l.size(), r.size());
    }

    private static int compareMaps(Map<String, StoredValue> left, Map<String, StoredValue> right) {
        Iterator<Map.Entry<String, StoredValue>> l = sorted(left).entrySet().iterator();
        Iterator<Map.Entry<String, StoredValue>> r = sorted(right).entrySet().iterator();
        while (l.hasNext() && r.hasNext()) {
            Map.Entry<String, StoredValue> leftEntry = l.next();
            Map.Entry<String, StoredValue> rightEntry = r.next();
            int byKey = compareUtf8Strings(leftEntry.getKey(), rightEntry.getKey());
            if (byKey != 0) {
                return byKey;
            }
            int byValue = compare(leftEntry.getValue(), rightEntry.getValue());
            if (byValue != 0) {
                return byValue;
            }
        }
        return Boolean.compare(l.hasNext(), r.hasNext());
    }

    private static TreeMap<String, StoredValue> sorted(Map<String, StoredValue> map) {
        TreeMap<String, StoredValue> result = new TreeMap<>(FirestoreValueOrder::compareUtf8Strings);
        if (map != null) {
            result.putAll(map);
        }
        return result;
    }
}
