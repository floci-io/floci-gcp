package io.floci.gcp.core.common;

import java.util.Base64;

/** Shared decoding for proto3 JSON bytes fields. */
public final class ProtoJsonBytes {

    private ProtoJsonBytes() {}

    /**
     * Accepts standard or URL-safe base64, with or without padding, and reports
     * malformed input as an INVALID_ARGUMENT error at the supplied field path.
     */
    public static byte[] decode(String value, String field) {
        try {
            return Base64.getDecoder().decode(value.replace('-', '+').replace('_', '/'));
        } catch (IllegalArgumentException e) {
            throw GcpException.invalidArgument("Invalid value at '" + field + "' (TYPE_BYTES): " + e.getMessage());
        }
    }
}
