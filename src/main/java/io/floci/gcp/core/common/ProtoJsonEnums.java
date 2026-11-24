package io.floci.gcp.core.common;

import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;

import java.math.BigDecimal;

/** Shared decoding for proto3 JSON enum fields read from a request body parsed into plain maps. */
public final class ProtoJsonEnums {

    private ProtoJsonEnums() {}

    /**
     * Returns the name of the enum value a proto3 JSON field carries. ProtoJSON parsers accept an
     * enum as its name or its integer number, and REST clients such as the Go GAPIC ones send the
     * number. Null stays null. A name or number the enum does not define, or any other JSON type, is
     * an INVALID_ARGUMENT error at the supplied field path.
     */
    public static String name(Object value, EnumDescriptor type, String field) {
        if (value == null) {
            return null;
        }
        EnumValueDescriptor match = switch (value) {
            case String name -> type.findValueByName(name);
            case Number number -> byNumber(type, number);
            default -> null;
        };
        if (match == null) {
            String shown = value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
            throw GcpException.invalidArgument("Invalid value at '" + field + "' (type.googleapis.com/"
                    + type.getFullName() + "), " + shown);
        }
        return match.getName();
    }

    private static EnumValueDescriptor byNumber(EnumDescriptor type, Number number) {
        try {
            return type.findValueByNumber(new BigDecimal(number.toString()).intValueExact());
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }
}
