package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import jakarta.ws.rs.core.Response;

import java.util.Arrays;
import java.util.StringJoiner;

final class GcsMediaResponses {

    static final String META_HEADER_PREFIX = "x-goog-meta-";

    private GcsMediaResponses() {}

    static Response mediaResponse(byte[] data, GcsObjectMeta meta, String rangeHeader) {
        return mediaResponse(data, meta, rangeHeader, null);
    }

    /**
     * Builds a media response, applying decompressive transcoding when it applies.
     *
     * <p>An object stored with {@code contentEncoding: gzip} is served decompressed to a client
     * that did not ask for gzip, and as stored to one that did. GCS also ignores {@code Range} on a
     * transcoded read: the stored byte offsets do not correspond to the bytes the caller receives
     * and answers with the whole object, which is what we do here.
     */
    static Response mediaResponse(byte[] data, GcsObjectMeta meta, String rangeHeader,
            String acceptEncoding) {
        if (shouldTranscode(meta, acceptEncoding)) {
            byte[] decompressed = gunzip(data);
            if (decompressed != null) {
                return withMetadataHeaders(Response.ok(decompressed).type(meta.getContentType()), meta)
                        .header("Content-Length", decompressed.length)
                        .build();
            }
            // Not actually gzip despite the declared encoding: serve the stored bytes rather
            // than failing a read.
        }

        Response.ResponseBuilder builder;
        if (rangeHeader == null || rangeHeader.isBlank()) {
            builder = Response.ok(data);
        } else {
            var range = parseRange(rangeHeader, data.length);
            builder = Response.status(Response.Status.PARTIAL_CONTENT)
                    .entity(Arrays.copyOfRange(data, range.start(), range.end() + 1))
                    .header("Content-Range", "bytes " + range.start() + "-" + range.end() + "/" + data.length);
        }
        if (isGzipStored(meta)) {
            // Served as stored, so the client must be told to inflate it.
            builder.header("Content-Encoding", "gzip");
        }
        return withMetadataHeaders(builder.type(meta.getContentType()), meta).build();
    }

    private static boolean isGzipStored(GcsObjectMeta meta) {
        return "gzip".equalsIgnoreCase(meta.getContentEncoding());
    }

    private static boolean shouldTranscode(GcsObjectMeta meta, String acceptEncoding) {
        return isGzipStored(meta) && !acceptsGzip(acceptEncoding);
    }

    // Absent Accept-Encoding means the client made no claim, so it gets the decompressed
    // bytes; "identity" is an explicit refusal of gzip and behaves the same way.
    private static boolean acceptsGzip(String acceptEncoding) {
        if (acceptEncoding == null || acceptEncoding.isBlank()) {
            return false;
        }
        Double gzipQ = null;
        Double wildcardQ = null;
        for (String part : acceptEncoding.split(",")) {
            String token = part.trim().toLowerCase(java.util.Locale.ROOT);
            int semi = token.indexOf(';');
            String coding = semi >= 0 ? token.substring(0, semi).trim() : token;
            String params = semi >= 0 ? token.substring(semi + 1).replace(" ", "") : "";
            if (coding.equals("gzip")) {
                gzipQ = qValue(params);
            } else if (coding.equals("*")) {
                wildcardQ = qValue(params);
            }
        }
        // A codings entry names gzip specifically, so it decides regardless of the wildcard:
        // "gzip;q=0, *" is a refusal, not an acceptance (RFC 7231 section 5.3.4).
        if (gzipQ != null) {
            return gzipQ > 0;
        }
        return wildcardQ != null && wildcardQ > 0;
    }

    /**
     * The qvalue of an Accept-Encoding entry, defaulting to 1 when absent. Parsed as a number
     * rather than matched as text: RFC 7231 writes a qvalue as {@code "0" [ "." 0*3DIGIT ]}, so
     * {@code q=0.0} and {@code q=0.000} are the same explicit refusal as {@code q=0}, and a
     * string comparison against "q=0" alone would hand gzip to a client that refused it.
     */
    private static double qValue(String params) {
        for (String param : params.split(";")) {
            if (param.startsWith("q=")) {
                try {
                    return Double.parseDouble(param.substring(2));
                } catch (NumberFormatException e) {
                    return 1;
                }
            }
        }
        return 1;
    }

    private static byte[] gunzip(byte[] data) {
        if (data == null || data.length < 2 || (data[0] & 0xff) != 0x1f || (data[1] & 0xff) != 0x8b) {
            return null;
        }
        try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(data))) {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static Response.ResponseBuilder withMetadataHeaders(Response.ResponseBuilder builder, GcsObjectMeta meta) {
        addHeaderIfPresent(builder, "ETag", meta.getEtag());
        addHeaderIfPresent(builder, "x-goog-generation", meta.getGeneration());
        addHeaderIfPresent(builder, "x-goog-metageneration", meta.getMetageneration());
        addHeaderIfPresent(builder, "x-goog-stored-content-length", meta.getSize());
        builder.header("x-goog-stored-content-encoding",
                meta.getContentEncoding() != null ? meta.getContentEncoding() : "identity");
        addHeaderIfPresent(builder, "x-goog-storage-class", meta.getStorageClass());
        addHeaderIfPresent(builder, "x-goog-hash", hashHeader(meta));
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

    private static void addHeaderIfPresent(Response.ResponseBuilder builder, String name, String value) {
        if (value != null && !value.isEmpty()) {
            builder.header(name, value);
        }
    }

    // Real GCS lists crc32c before md5 and omits md5 for composite objects.
    private static String hashHeader(GcsObjectMeta meta) {
        var parts = new StringJoiner(",");
        if (meta.getCrc32c() != null) {
            parts.add("crc32c=" + meta.getCrc32c());
        }
        if (meta.getMd5Hash() != null) {
            parts.add("md5=" + meta.getMd5Hash());
        }
        return parts.length() == 0 ? null : parts.toString();
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
