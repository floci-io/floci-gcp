package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.XmlBuilder;
import io.floci.gcp.services.gcs.model.GcsMultipartUpload;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * XML API ListMultipartUploads: the {@code ListMultipartUploadsResult} in the shape GCS serves
 * (probed live on 2026-11-01).
 */
final class GcsXmlUploadListing {

    private static final String NS = "http://s3.amazonaws.com/doc/2006-03-01/";
    // GCS answers the listing as text/html, like a multipart completion.
    private static final String CONTENT_TYPE = "text/html; charset=UTF-8";
    private static final int MAX_UPLOADS = 1000;

    /**
     * The listing parameters as the client sent them. A missing parameter is null, one sent
     * without a value is an empty string.
     */
    record Query(String prefix, String delimiter, String maxUploads, String keyMarker, String uploadIdMarker,
            String encodingType) {

        static Query of(MultivaluedMap<String, String> params) {
            return new Query(params.getFirst("prefix"), params.getFirst("delimiter"), params.getFirst("max-uploads"),
                    params.getFirst("key-marker"), params.getFirst("upload-id-marker"), params.getFirst("encoding-type"));
        }
    }

    /** One line of the page: an upload, or a common prefix when {@code upload} is null. */
    private record Entry(String key, GcsMultipartUpload upload) {
    }

    private GcsXmlUploadListing() {
    }

    static Response respond(String bucket, List<GcsMultipartUpload> uploads, Query query) {
        boolean urlEncoded = urlEncoded(query.encodingType());
        int max = maxUploads(query.maxUploads());
        // GCS answers an empty key-marker with an internal error. The emulator treats it as not sent.
        String keyMarker = query.keyMarker() == null || query.keyMarker().isEmpty() ? null : query.keyMarker();
        String uploadIdMarker = uploadIdMarker(query.uploadIdMarker(), keyMarker);
        String prefix = query.prefix() == null ? "" : query.prefix();
        String delimiter = query.delimiter() == null || query.delimiter().isEmpty() ? null : query.delimiter();

        List<Entry> entries = new ArrayList<>();
        Set<String> seenPrefixes = new LinkedHashSet<>();
        List<GcsMultipartUpload> matching = uploads.stream()
                .filter(u -> u.object.startsWith(prefix))
                .sorted(Comparator.comparing((GcsMultipartUpload u) -> u.object).thenComparing(u -> u.id))
                .toList();
        for (GcsMultipartUpload upload : matching) {
            if (delimiter != null) {
                String rest = upload.object.substring(prefix.length());
                int idx = rest.indexOf(delimiter);
                if (idx >= 0) {
                    String common = prefix + rest.substring(0, idx + delimiter.length());
                    if (seenPrefixes.add(common)) {
                        entries.add(new Entry(common, null));
                    }
                    continue;
                }
            }
            entries.add(new Entry(upload.object, upload));
        }
        if (keyMarker != null) {
            entries.removeIf(entry -> !after(entry, keyMarker, uploadIdMarker));
        }
        // A stable sort keeps the uploads of one key in id order.
        entries.sort(Comparator.comparing(Entry::key));

        List<Entry> page = entries.subList(0, Math.min(max, entries.size()));
        boolean truncated = max > 0 && entries.size() > max;
        if (truncated && page.getLast().upload() == null) {
            // GCS cannot hand out a common prefix as the next marker and fails without Details.
            throw GcpException.invalidArgument("Invalid argument.").withReason("InvalidArgument");
        }

        XmlBuilder xml = new XmlBuilder()
                .start("ListMultipartUploadsResult", NS)
                .elem("Bucket", bucket)
                .elem("KeyMarker", encode(keyMarker == null ? "" : keyMarker, urlEncoded))
                .elem("UploadIdMarker", uploadIdMarker == null ? "" : uploadIdMarker)
                .elem("NextKeyMarker", truncated ? encode(page.getLast().key(), urlEncoded) : "");
        if (query.prefix() != null) {
            xml.elem("Prefix", encode(query.prefix(), urlEncoded));
        }
        if (query.delimiter() != null) {
            xml.elem("Delimiter", encode(query.delimiter(), urlEncoded));
        }
        xml.elem("NextUploadIdMarker", truncated ? page.getLast().upload().id : "")
                .elem("MaxUploads", max)
                .elem("IsTruncated", truncated);
        for (Entry entry : page) {
            if (entry.upload() != null) {
                xml.start("Upload")
                        .elem("Key", encode(entry.key(), urlEncoded))
                        .elem("UploadId", entry.upload().id)
                        .elem("StorageClass", "STANDARD")
                        .elem("Initiated", entry.upload().initiated)
                        .end("Upload");
            }
        }
        for (Entry entry : page) {
            if (entry.upload() == null) {
                xml.start("CommonPrefixes").elem("Prefix", encode(entry.key(), urlEncoded)).end("CommonPrefixes");
            }
        }
        if (urlEncoded) {
            xml.elem("EncodingType", "url");
        }
        xml.end("ListMultipartUploadsResult");
        return Response.ok(xml.build(), CONTENT_TYPE).build();
    }

    private static boolean after(Entry entry, String keyMarker, String uploadIdMarker) {
        int order = entry.key().compareTo(keyMarker);
        if (order != 0) {
            return order > 0;
        }
        return entry.upload() != null && uploadIdMarker != null && entry.upload().id.compareTo(uploadIdMarker) > 0;
    }

    private static String uploadIdMarker(String raw, String keyMarker) {
        if (raw == null) {
            return null;
        }
        if (raw.isEmpty()) {
            throw GcpException.invalidArgument("Invalid upload ID marker: " + raw);
        }
        // Without a key-marker GCS ignores the upload-id-marker and echoes it empty.
        return keyMarker == null ? null : raw;
    }

    private static int maxUploads(String raw) {
        if (raw == null) {
            return MAX_UPLOADS;
        }
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw GcpException.invalidArgument("Parameter max-uploads must be an integer; received " + raw + ".");
        }
        if (value < 0) {
            throw GcpException.invalidArgument("Invalid max uploads: " + raw);
        }
        return Math.min(value, MAX_UPLOADS);
    }

    private static boolean urlEncoded(String encodingType) {
        if (encodingType == null) {
            return false;
        }
        if (encodingType.equals("url")) {
            return true;
        }
        throw GcpException.invalidArgument("Invalid encoding type: " + encodingType);
    }

    private static String encode(String value, boolean urlEncoded) {
        return urlEncoded ? GcsXmlUrlEncoding.encode(value) : value;
    }
}
