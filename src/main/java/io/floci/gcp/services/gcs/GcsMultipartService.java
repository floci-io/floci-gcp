package io.floci.gcp.services.gcs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.gcs.model.GcsMultipartUpload;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class GcsMultipartService {
    private final GcsService gcs;
    private final StorageBackend<String, GcsMultipartUpload> uploads;
    @Inject
    public GcsMultipartService(GcsService gcs, StorageFactory factory) {
        this.gcs = gcs;
        uploads = factory.createGlobal("gcs-multipart", "gcs-multipart.json", new TypeReference<Map<String, GcsMultipartUpload>>() {});
    }
    public synchronized GcsMultipartUpload initiate(String bucket, String object, String type, Map<String,String> metadata) {
        gcs.getBucket(bucket);
        GcsMultipartUpload upload = new GcsMultipartUpload();
        upload.id = UUID.randomUUID().toString(); upload.bucket = bucket; upload.object = object;
        upload.contentType = type; upload.metadata = metadata; upload.initiated = Instant.now().toString();
        uploads.put(upload.id, upload); uploads.checkpoint(); return upload;
    }
    public synchronized GcsMultipartUpload get(String bucket, String object, String id) {
        gcs.getBucket(bucket);
        GcsMultipartUpload upload = uploads.get(id).orElseThrow(() -> GcpException.notFound("The specified upload does not exist").withReason("NoSuchUpload"));
        if (!upload.bucket.equals(bucket) || !upload.object.equals(object)) { throw GcpException.notFound("The specified upload does not exist").withReason("NoSuchUpload"); }
        return upload;
    }
    public synchronized GcsMultipartUpload.Part putPart(String bucket, String object, String id, int number, byte[] bytes, String contentMd5) {
        if (number < 1 || number > 10000) { throw GcpException.invalidArgument("Part number must be between 1 and 10000"); }
        GcsMultipartUpload upload = get(bucket, object, id);
        byte[] digest = md5(bytes);
        if (contentMd5 != null && !contentMd5.equals(Base64.getEncoder().encodeToString(digest))) {
            throw GcpException.invalidArgument("Content-MD5 does not match").withReason("BadDigest");
        }
        var part = new GcsMultipartUpload.Part(number, "\"" + HexFormat.of().formatHex(digest) + "\"", Instant.now().toString(), bytes.clone());
        upload.parts.put(number, part); uploads.put(id, upload); uploads.checkpoint(); return part;
    }
    public synchronized GcsObjectMeta complete(String bucket, String object, String id, List<Map<String,String>> requested, String baseUrl) {
        GcsMultipartUpload upload = get(bucket, object, id);
        if (requested.isEmpty() || requested.size() > 10000) { throw GcpException.invalidArgument("A part list is required").withReason("MalformedXML"); }
        int previous = 0;
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < requested.size(); i++) {
            Map<String,String> item = requested.get(i);
            int number;
            try { number = Integer.parseInt(item.getOrDefault("PartNumber", "")); }
            catch (NumberFormatException e) { throw GcpException.invalidArgument("Invalid PartNumber").withReason("InvalidPart"); }
            if (number <= previous) { throw GcpException.invalidArgument("Parts must be in increasing order").withReason("InvalidPartOrder"); }
            previous = number;
            var part = upload.parts.get(number);
            if (part == null || !part.etag().equals(item.get("ETag"))) { throw GcpException.invalidArgument("Missing part or mismatched ETag").withReason("InvalidPart"); }
            if (i < requested.size() - 1 && part.data().length < 5 * 1024 * 1024) { throw GcpException.invalidArgument("Non-final parts must contain at least 5 MiB").withReason("EntityTooSmall"); }
            data.writeBytes(part.data());
        }
        GcsObjectMeta meta = gcs.putXmlMultipartObject(bucket, object, upload.contentType, data.toByteArray(), upload.metadata, baseUrl);
        uploads.delete(id); uploads.checkpoint(); return meta;
    }
    public synchronized void abort(String bucket, String object, String id) {
        get(bucket, object, id); uploads.delete(id); uploads.checkpoint();
    }
    public synchronized List<GcsMultipartUpload> list(String bucket, String prefix) {
        gcs.getBucket(bucket);
        return uploads.scan(k -> true).stream().filter(u -> u.bucket.equals(bucket) && u.object.startsWith(prefix == null ? "" : prefix))
                .sorted(Comparator.comparing((GcsMultipartUpload u) -> u.object).thenComparing(u -> u.id)).toList();
    }
    private static byte[] md5(byte[] data) {
        try { return MessageDigest.getInstance("MD5").digest(data); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
