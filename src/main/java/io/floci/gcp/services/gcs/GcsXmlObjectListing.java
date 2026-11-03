package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.XmlBuilder;
import io.floci.gcp.services.gcs.model.GcsBucket;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * XML API ListObjects: the {@code ListBucketResult} of list-type 1 and list-type 2, in the
 * shapes GCS serves (probed live on 2026-10-30 and 2026-11-01).
 */
final class GcsXmlObjectListing {

    private static final String NS = "http://doc.s3.amazonaws.com/2006-03-01";

    /**
     * The listing parameters as the client sent them. A missing parameter is null, one sent
     * without a value is an empty string. GCS echoes an empty start-after or continuation-token,
     * so the two cases must stay apart.
     */
    record Query(String prefix, String delimiter, String maxKeys, String marker, String listType,
            String startAfter, String continuationToken, String encodingType) {

        static Query of(MultivaluedMap<String, String> params) {
            return new Query(params.getFirst("prefix"), params.getFirst("delimiter"), params.getFirst("max-keys"),
                    params.getFirst("marker"), params.getFirst("list-type"), params.getFirst("start-after"),
                    params.getFirst("continuation-token"), params.getFirst("encoding-type"));
        }
    }

    private GcsXmlObjectListing() {
    }

    static Response respond(GcsBucket bucket, List<GcsObjectMeta> objects, Query query) {
        boolean v2 = "2".equals(query.listType());
        if (v2 && query.marker() != null && !query.marker().isEmpty()) {
            throw GcpException.unimplemented("GET ?marker is not implemented for listObjectsV2");
        }
        Integer maxKeys = maxKeys(query.maxKeys());
        boolean urlEncoded = urlEncoded(query.encodingType());
        String prefix = query.prefix() == null ? "" : query.prefix();
        String delimiter = query.delimiter() == null || query.delimiter().isEmpty() ? null : query.delimiter();
        String after = v2 ? afterOf(query) : query.marker();

        // A page is a single lexicographic run over both objects and rolled-up prefixes, so
        // max-keys bounds the combined result and the next marker names whichever entry ended
        // the page. Limiting only the objects would emit oversized pages and, worse, repeat
        // every prefix on the next page because the marker would skip past them.
        List<String> entryKeys = new ArrayList<>();
        Map<String, GcsObjectMeta> objectsByKey = new LinkedHashMap<>();
        Set<String> seenPrefixes = new LinkedHashSet<>();
        List<GcsObjectMeta> matching = objects.stream()
                .filter(o -> o.getName().startsWith(prefix))
                .sorted(Comparator.comparing(GcsObjectMeta::getName))
                .toList();
        for (GcsObjectMeta meta : matching) {
            String common = GcsXmlDelimiterRollup.commonPrefix(meta.getName(), prefix, delimiter);
            if (common != null) {
                if (seenPrefixes.add(common)) {
                    entryKeys.add(common);
                }
                continue;
            }
            objectsByKey.put(meta.getName(), meta);
            entryKeys.add(meta.getName());
        }
        // The marker is compared against the rolled-up entry key, not the raw object name. A
        // marker naming a common prefix would otherwise never advance: every object under
        // "d1/" sorts after "d1/", so they would survive the filter and roll up to "d1/" again,
        // handing the client the same page forever.
        if (after != null && !after.isEmpty()) {
            entryKeys.removeIf(key -> key.compareTo(after) <= 0);
        }
        entryKeys.sort(Comparator.naturalOrder());

        int limit = maxKeys != null ? maxKeys : entryKeys.size();
        List<String> page = entryKeys.subList(0, Math.min(limit, entryKeys.size()));
        // GCS reports max-keys=0 as not truncated.
        boolean truncated = limit > 0 && entryKeys.size() > limit;
        long keyCount = page.stream().filter(objectsByKey::containsKey).count();

        XmlBuilder xml = new XmlBuilder()
                .start("ListBucketResult", NS)
                .elem("Name", bucket.getName())
                .elem("Prefix", encode(prefix, urlEncoded));
        if (v2) {
            if (query.startAfter() != null) {
                xml.elem("StartAfter", encode(query.startAfter(), urlEncoded));
            }
            if (query.continuationToken() != null) {
                xml.elem("ContinuationToken", query.continuationToken());
            }
            if (truncated) {
                xml.elem("NextContinuationToken", token(page.getLast()));
            }
            xml.elem("KeyCount", keyCount);
        } else {
            xml.elem("Marker", encode(query.marker() == null ? "" : query.marker(), urlEncoded));
            if (truncated) {
                xml.elem("NextMarker", encode(page.getLast(), urlEncoded));
            }
        }
        if (maxKeys != null) {
            xml.elem("MaxKeys", maxKeys.intValue());
        }
        if (delimiter != null) {
            xml.elem("Delimiter", encode(delimiter, urlEncoded));
        }
        xml.elem("IsTruncated", truncated);
        if (urlEncoded) {
            xml.elem(v2 ? "EncodingType" : "Encoding-Type", "url");
        }
        for (String key : page) {
            GcsObjectMeta meta = objectsByKey.get(key);
            if (meta != null) {
                xml.start("Contents")
                        .elem("Key", encode(key, urlEncoded))
                        .elem("Generation", meta.getGeneration())
                        .elem("MetaGeneration", meta.getMetageneration())
                        .elem("LastModified", meta.getUpdated())
                        .elem("ETag", GcsMediaResponses.xmlEtag(meta))
                        .elem("Size", meta.getSize())
                        .end("Contents");
            }
        }
        for (String key : page) {
            if (!objectsByKey.containsKey(key)) {
                xml.start("CommonPrefixes").elem("Prefix", encode(key, urlEncoded)).end("CommonPrefixes");
            }
        }
        xml.end("ListBucketResult");
        return Response.ok(xml.build(), MediaType.APPLICATION_XML)
                .header("x-goog-metageneration", bucket.getMetageneration())
                .build();
    }

    private static String afterOf(Query query) {
        if (query.continuationToken() != null && !query.continuationToken().isEmpty()) {
            return keyOf(query.continuationToken());
        }
        return query.startAfter();
    }

    private static Integer maxKeys(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw GcpException.invalidArgument("Invalid max-keys: " + raw);
        }
        // GCS ignores a negative max-keys and leaves MaxKeys out of the result.
        return value < 0 ? null : value;
    }

    private static boolean urlEncoded(String encodingType) {
        if (encodingType == null || encodingType.isEmpty()) {
            return false;
        }
        if (encodingType.equalsIgnoreCase("url")) {
            return true;
        }
        throw GcpException.invalidArgument("Invalid encoding-type: " + encodingType);
    }

    private static String encode(String value, boolean urlEncoded) {
        return urlEncoded ? GcsXmlUrlEncoding.encode(value) : value;
    }

    // GCS hands out the last key of the page as a base64 protobuf message with the key in field 1.
    static String token(String key) {
        byte[] utf8 = key.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(utf8.length + 4);
        out.write(0x0a);
        int length = utf8.length;
        while (length >= 0x80) {
            out.write((length & 0x7f) | 0x80);
            length >>>= 7;
        }
        out.write(length);
        out.writeBytes(utf8);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    static String keyOf(String token) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(token);
        } catch (IllegalArgumentException e) {
            throw invalidToken();
        }
        if (bytes.length < 2 || bytes[0] != 0x0a) {
            throw invalidToken();
        }
        int length = 0;
        int shift = 0;
        int index = 1;
        while (true) {
            if (index >= bytes.length || shift > 21) {
                throw invalidToken();
            }
            int b = bytes[index++] & 0xff;
            length |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        if (bytes.length - index != length) {
            throw invalidToken();
        }
        return new String(bytes, index, length, StandardCharsets.UTF_8);
    }

    private static GcpException invalidToken() {
        return GcpException.invalidArgument("Invalid page token.");
    }
}
