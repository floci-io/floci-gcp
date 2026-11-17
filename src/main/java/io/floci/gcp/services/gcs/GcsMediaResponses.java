package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.zip.GZIPInputStream;

final class GcsMediaResponses {

    private static final Logger LOG = Logger.getLogger(GcsMediaResponses.class);

    static final String META_HEADER_PREFIX = "x-goog-meta-";

    // IMF-fixdate, the only format RFC 7231 allows a recipient to be required to parse. Not
    // DateTimeFormatter.RFC_1123_DATE_TIME, which writes a single-digit day unpadded
    // ("Fri, 6 Feb 2026 ...") and is therefore not the format GCS sends.
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
            .ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    private GcsMediaResponses() {}

    /**
     * Builds a media response, applying decompressive transcoding when it applies.
     *
     * <p>An object stored with {@code contentEncoding: gzip} is served decompressed to a client
     * that did not ask for gzip, and as stored to one that did. GCS also ignores {@code Range} on a
     * transcoded read: the stored byte offsets do not correspond to the bytes the caller receives
     * and answers with the whole object, which is what we do here, without advertising ranges on
     * that response.
     */
    static Response mediaResponse(byte[] data, GcsObjectMeta meta, String rangeHeader,
            String acceptEncoding) {
        return mediaResponse(data, meta, rangeHeader, acceptEncoding, meta.getEtag(), false);
    }

    static Response xmlMediaResponse(byte[] data, GcsObjectMeta meta, String rangeHeader,
            String acceptEncoding) {
        return mediaResponse(data, meta, rangeHeader, acceptEncoding, xmlEtag(meta), true);
    }

    private static Response mediaResponse(byte[] data, GcsObjectMeta meta, String rangeHeader,
            String acceptEncoding, String etag, boolean xmlApi) {
        if (shouldTranscode(meta, acceptEncoding)) {
            byte[] decompressed = gunzip(data);
            if (decompressed != null) {
                return withMetadataHeaders(Response.ok(decompressed).type(meta.getContentType()), meta, etag,
                        xmlApi, false, true)
                        .header(HttpHeaders.CONTENT_LENGTH, decompressed.length)
                        .build();
            }
            // Not actually gzip despite the declared encoding: serve the stored bytes rather
            // than failing a read.
        }

        Response.ResponseBuilder builder;
        int contentLength;
        Range range = parseRange(rangeHeader, data.length);
        if (range == null) {
            builder = Response.ok(data);
            contentLength = data.length;
        } else {
            byte[] partial = Arrays.copyOfRange(data, range.start(), range.end() + 1);
            builder = Response.status(Response.Status.PARTIAL_CONTENT)
                    .entity(partial)
                    .header("Content-Range", "bytes " + range.start() + "-" + range.end() + "/" + data.length);
            contentLength = partial.length;
        }
        if (isGzipStored(meta)) {
            // Served as stored, so the client must be told to inflate it.
            builder.header("Content-Encoding", "gzip");
        }
        // Set rather than left to the HTTP layer: for an implicit HEAD it strips the body and with
        // it the Content-Length it would have generated, and a HEAD is how a client reads an
        // object's size without transferring it.
        return withMetadataHeaders(builder.type(meta.getContentType()), meta, etag, xmlApi, range != null, false)
                .header(HttpHeaders.CONTENT_LENGTH, contentLength)
                .build();
    }

    /**
     * Applies the {@code response-content-disposition} and {@code response-content-type} query
     * parameters of an XML API GET. Cloud Storage lets them override the response headers, which is
     * how a signed URL sets the download's file name or serves it inline.
     */
    static Response withResponseOverrides(Response response, MultivaluedMap<String, String> query) {
        // A value the HTTP layer would refuse is ignored, as before overrides were supported,
        // so it cannot turn a working read into a 500 or a dropped connection.
        String disposition = query.getFirst("response-content-disposition");
        if (!isValidHeaderValue(disposition)) {
            disposition = null;
        }
        MediaType contentType = parseMediaType(query.getFirst("response-content-type"));
        if (disposition == null && contentType == null) {
            return response;
        }
        Response.ResponseBuilder builder = Response.fromResponse(response);
        if (disposition != null) {
            builder.header("Content-Disposition", null).header("Content-Disposition", disposition);
        }
        if (contentType != null) {
            builder.type(contentType);
        }
        return builder.build();
    }

    private static MediaType parseMediaType(String value) {
        if (!isValidHeaderValue(value) || value.isBlank()) {
            return null;
        }
        try {
            return MediaType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
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
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The XML API PUT Object answer as GCS sends it: no body, which its upload server still
     * labels {@code text/html}, the entity tag in quotes, and the generation and hash headers an
     * S3-compatible client reads back to confirm the upload.
     */
    static Response uploadResponse(GcsObjectMeta meta) {
        Response.ResponseBuilder builder = Response.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/html; charset=UTF-8");
        addHeaderIfPresent(builder, "ETag", xmlEtag(meta));
        addHeaderIfPresent(builder, "x-goog-generation", meta.getGeneration());
        addHeaderIfPresent(builder, "x-goog-metageneration", meta.getMetageneration());
        // Left as one field: the XML API docs do not publish this response's header list, and the
        // clients that read it here take the single comma-separated value. Media reads, whose
        // layout is documented, follow it instead.
        addHeaderIfPresent(builder, "x-goog-hash", hashHeader(meta));
        addHeaderIfPresent(builder, "x-goog-stored-content-length", meta.getSize());
        builder.header("x-goog-stored-content-encoding",
                meta.getContentEncoding() != null ? meta.getContentEncoding() : "identity");
        return builder.build();
    }

    /**
     * The entity tag as the XML API writes it, in quotes (probed live on 2026-10-29): the MD5 in
     * hex for an object that has one, the ETag a multipart completion computed from its parts,
     * and a dash before the resource etag for a composite object, which has no MD5.
     */
    static String xmlEtag(GcsObjectMeta meta) {
        if (meta.getMd5Hash() != null) {
            return "\"" + HexFormat.of().formatHex(Base64.getDecoder().decode(meta.getMd5Hash())) + "\"";
        }
        if (meta.getEtag() == null) {
            return null;
        }
        if (meta.getComponentCount() != null) {
            return "\"-" + meta.getEtag() + "\"";
        }
        // A multipart object stored by an older emulator carries a generation-based etag here.
        return "\"" + meta.getEtag() + "\"";
    }

    private static Response.ResponseBuilder withMetadataHeaders(Response.ResponseBuilder builder,
            GcsObjectMeta meta, String etag, boolean xmlApi, boolean partial, boolean transcoded) {
        addHeaderIfPresent(builder, "ETag", etag);
        addHeaderIfPresent(builder, HttpHeaders.LAST_MODIFIED, lastModified(meta));
        // The XML API advertises byte ranges and the JSON API does not, which is what GCS does. A
        // transcoded read ignores Range, because the stored offsets do not map to the bytes it
        // returns, and GCS sends no Accept-Ranges on that response.
        if (xmlApi && !transcoded) {
            builder.header("Accept-Ranges", "bytes");
        }
        addHeaderIfPresent(builder, "x-goog-generation", meta.getGeneration());
        addHeaderIfPresent(builder, "x-goog-metageneration", meta.getMetageneration());
        addHeaderIfPresent(builder, "x-goog-stored-content-length", meta.getSize());
        builder.header("x-goog-stored-content-encoding",
                meta.getContentEncoding() != null ? meta.getContentEncoding() : "identity");
        addHeaderIfPresent(builder, "x-goog-storage-class", meta.getStorageClass());
        addHashHeaders(builder, meta, xmlApi, partial);
        Map<String, String> metadata = meta.getMetadata();
        if (metadata != null) {
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                if (isValidHeaderName(entry.getKey()) && isValidHeaderValue(entry.getValue())) {
                    builder.header(META_HEADER_PREFIX + entry.getKey(), entry.getValue());
                }
            }
        }
        return builder;
    }

    /**
     * The object's update time as an HTTP-date. GCS sends this on every media read, and a client
     * that takes the update time from the response header rather than the resource body requires
     * it.
     */
    private static String lastModified(GcsObjectMeta meta) {
        String updated = meta.getUpdated() != null ? meta.getUpdated() : meta.getTimeCreated();
        if (updated == null) {
            return null;
        }
        try {
            return HTTP_DATE.format(Instant.parse(updated));
        } catch (DateTimeParseException e) {
            LOG.debugf("omitting Last-Modified, unparseable timestamp %s on %s", updated, meta.getName());
            return null;
        }
    }

    private static void addHeaderIfPresent(Response.ResponseBuilder builder, String name, String value) {
        if (value != null && !value.isEmpty()) {
            builder.header(name, value);
        }
    }

    /**
     * GCS writes the XML API's checksums as one field per algorithm and the JSON API's as a single
     * comma-separated field, and leaves them out of a JSON media read that is partial. Only the
     * XML API's layout is documented, so only that one is copied here.
     */
    private static void addHashHeaders(Response.ResponseBuilder builder, GcsObjectMeta meta,
            boolean xmlApi, boolean partial) {
        if (xmlApi) {
            addHeaderIfPresent(builder, "x-goog-hash", crc32cHash(meta));
            addHeaderIfPresent(builder, "x-goog-hash", md5Hash(meta));
        } else if (!partial) {
            addHeaderIfPresent(builder, "x-goog-hash", hashHeader(meta));
        }
    }

    private static String crc32cHash(GcsObjectMeta meta) {
        return meta.getCrc32c() == null ? null : "crc32c=" + meta.getCrc32c();
    }

    private static String md5Hash(GcsObjectMeta meta) {
        return meta.getMd5Hash() == null ? null : "md5=" + meta.getMd5Hash();
    }

    // Real GCS lists crc32c before md5 and omits md5 for composite objects.
    private static String hashHeader(GcsObjectMeta meta) {
        StringJoiner parts = new StringJoiner(",");
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
        for (int i = 0; i < key.length(); i++) {
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
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 || c > 0x7E) && c != '\t') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads the {@code Range} header the way GCS reads it, which is mostly the RFC 7233 rule that
     * a range a server cannot make sense of is ignored rather than rejected.
     *
     * <p>An element GCS cannot read makes the whole header ignorable, wherever in the set it sits:
     * an unknown unit, an empty set, a range whose last byte precedes its first, or a position that
     * does not fit a signed 64-bit integer. The same goes for a set of several ranges that does not
     * walk forward. What stays an error is a single range that cannot be satisfied, a first byte at
     * or past the end or a suffix of zero, which is a 416, and a set whose every range begins after
     * the previous one ends, which is a 400.
     *
     * @return the range to serve, or null when the header is to be ignored
     */
    private static Range parseRange(String rangeHeader, int length) {
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return null;
        }
        int separator = rangeHeader.indexOf('=');
        if (separator < 0 || !rangeHeader.substring(0, separator).trim().equalsIgnoreCase("bytes")) {
            return null;
        }
        List<RangeSpec> specs = new ArrayList<>();
        for (String element : rangeHeader.substring(separator + 1).split(",", -1)) {
            RangeSpec spec = RangeSpec.parse(element.trim());
            if (spec == null) {
                return null;
            }
            specs.add(spec);
        }
        if (specs.size() == 1) {
            RangeSpec spec = specs.get(0);
            if (!spec.isSatisfiable(length)) {
                throw GcpException.outOfRange("Range not satisfiable: " + rangeHeader);
            }
            return spec.resolve(length);
        }
        for (int i = 1; i < specs.size(); i++) {
            if (specs.get(i).start(length) <= specs.get(i - 1).end(length)) {
                return null;
            }
        }
        throw GcpException.invalidArgument("multiple ranges are not supported: " + rangeHeader);
    }

    // Digit-only, so a value the ABNF does not allow is malformed rather than silently accepted the
    // way a number parser would take a leading plus sign. -1 means "not a position", which covers
    // text GCS cannot read and a value past the signed 64-bit range it can.
    private static long parsePosition(String value) {
        if (value.isEmpty()) {
            return -1;
        }
        long position = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            int digit = c - '0';
            if (position > (Long.MAX_VALUE - digit) / 10) {
                return -1;
            }
            position = position * 10 + digit;
        }
        return position;
    }

    /**
     * One element of the range set: a suffix length when {@code suffix} is set, and otherwise a
     * first byte with an optional last. The constructor's fields stay unresolved because whether a
     * position is satisfiable depends on the object's length.
     */
    private record RangeSpec(long first, long last, boolean suffix) {

        /** The element, or null when GCS would not read it. */
        static RangeSpec parse(String element) {
            int dash = element.indexOf('-');
            if (dash < 0 || element.indexOf('-', dash + 1) >= 0) {
                return null;
            }
            String from = element.substring(0, dash).trim();
            String to = element.substring(dash + 1).trim();
            if (from.isEmpty()) {
                long suffixLength = parsePosition(to);
                return suffixLength < 0 ? null : new RangeSpec(-1, suffixLength, true);
            }
            long first = parsePosition(from);
            if (first < 0) {
                return null;
            }
            if (to.isEmpty()) {
                return new RangeSpec(first, -1, false);
            }
            long last = parsePosition(to);
            if (last < 0 || last < first) {
                return null;
            }
            return new RangeSpec(first, last, false);
        }

        long start(int length) {
            return suffix ? Math.max(0, length - last) : first;
        }

        long end(int length) {
            if (suffix) {
                return length - 1L;
            }
            return last < 0 ? length - 1L : Math.min(last, length - 1L);
        }

        boolean isSatisfiable(int length) {
            return length > 0 && start(length) <= end(length);
        }

        Range resolve(int length) {
            return new Range((int) start(length), (int) end(length));
        }
    }

    private record Range(int start, int end) {}
}
