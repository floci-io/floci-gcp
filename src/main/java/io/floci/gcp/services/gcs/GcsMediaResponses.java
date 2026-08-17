package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import jakarta.ws.rs.core.Response;

import java.util.Arrays;

final class GcsMediaResponses {

    static final String META_HEADER_PREFIX = "x-goog-meta-";

    private GcsMediaResponses() {}

    static Response mediaResponse(byte[] data, GcsObjectMeta meta, String rangeHeader) {
        Response.ResponseBuilder builder;
        if (rangeHeader == null || rangeHeader.isBlank()) {
            builder = Response.ok(data);
        } else {
            var range = parseRange(rangeHeader, data.length);
            builder = Response.status(Response.Status.PARTIAL_CONTENT)
                    .entity(Arrays.copyOfRange(data, range.start(), range.end() + 1))
                    .header("Content-Range", "bytes " + range.start() + "-" + range.end() + "/" + data.length);
        }
        return withMetadataHeaders(builder.type(meta.getContentType()), meta).build();
    }

    private static Response.ResponseBuilder withMetadataHeaders(Response.ResponseBuilder builder, GcsObjectMeta meta) {
        var metadata = meta.getMetadata();
        if (metadata != null) {
            for (var entry : metadata.entrySet()) {
                if (isValidHeaderName(entry.getKey()) && isValidHeaderValue(entry.getValue())) {
                    builder.header(META_HEADER_PREFIX + entry.getKey(), entry.getValue());
                }
            }
        }
        return builder;
    }

    // Metadata stored via the JSON API may contain characters that cannot appear in an
    // HTTP field. GCS documents XML API custom metadata as printable US-ASCII, and for
    // the field name the printable characters usable in a header are exactly the RFC 7230
    // token set. Entries outside those bounds stay in the object resource but are omitted
    // from media headers so response serialization cannot fail.
    private static boolean isValidHeaderName(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        for (var i = 0; i < key.length(); i++) {
            if (!isTokenChar(key.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTokenChar(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }

    private static boolean isValidHeaderValue(String value) {
        if (value == null) {
            return false;
        }
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            if ((c < 0x20 || c > 0x7E) && c != '\t') {
                return false;
            }
        }
        return true;
    }

    private static Range parseRange(String rangeHeader, int length) {
        if (!rangeHeader.startsWith("bytes=")) {
            throw GcpException.invalidArgument("invalid Range header: " + rangeHeader);
        }
        String spec = rangeHeader.substring("bytes=".length());
        int dash = spec.indexOf('-');
        if (dash < 0 || spec.indexOf(',', dash) >= 0) {
            throw GcpException.invalidArgument("invalid Range header: " + rangeHeader);
        }

        int start;
        int end;
        if (dash == 0) {
            int suffixLength = parseRangeInt(spec.substring(1), rangeHeader);
            if (suffixLength <= 0) {
                throw GcpException.invalidArgument("invalid Range header: " + rangeHeader);
            }
            start = Math.max(0, length - suffixLength);
            end = length - 1;
        } else {
            start = parseRangeInt(spec.substring(0, dash), rangeHeader);
            end = dash == spec.length() - 1 ? length - 1 : parseRangeInt(spec.substring(dash + 1), rangeHeader);
        }

        if (start >= length) {
            throw GcpException.outOfRange("Range not satisfiable: " + rangeHeader);
        }
        if (start < 0 || end < start) {
            throw GcpException.invalidArgument("invalid Range header: " + rangeHeader);
        }
        end = Math.min(end, length - 1);
        return new Range(start, end);
    }

    private static int parseRangeInt(String value, String rangeHeader) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw GcpException.invalidArgument("invalid Range header: " + rangeHeader);
        }
    }

    private record Range(int start, int end) {}
}
