package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.GcpException;
import jakarta.ws.rs.core.Response;

import java.util.Arrays;

final class GcsMediaResponses {

    private GcsMediaResponses() {}

    static Response mediaResponse(byte[] data, String contentType, String rangeHeader) {
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return Response.ok(data).type(contentType).build();
        }

        Range range = parseRange(rangeHeader, data.length);
        byte[] body = Arrays.copyOfRange(data, range.start(), range.end() + 1);
        return Response.status(Response.Status.PARTIAL_CONTENT)
                .entity(body)
                .type(contentType)
                .header("Content-Range", "bytes " + range.start() + "-" + range.end() + "/" + data.length)
                .build();
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
