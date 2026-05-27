package io.floci.gcp.services.gcs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.gcs.model.GcsBucket;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import io.floci.gcp.services.gcs.model.ResumableUpload;
import io.floci.gcp.services.gcs.model.StoredAcl;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLEncoder;
import java.util.Optional;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32C;

@ApplicationScoped
public class GcsService {

    private static final Logger LOG = Logger.getLogger(GcsService.class);

    private final StorageBackend<String, GcsBucket> bucketStore;
    private final StorageBackend<String, GcsObjectMeta> objectMetaStore;
    private final StorageBackend<String, StoredAcl> aclStore;
    private final ConcurrentHashMap<String, byte[]> objectData = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ResumableUpload> resumableUploads = new ConcurrentHashMap<>();

    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final String defaultProjectId;

    @Inject
    public GcsService(ServiceRegistry serviceRegistry, EmulatorConfig config, StorageFactory storageFactory) {
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.defaultProjectId = config.defaultProjectId();
        this.bucketStore = storageFactory.createGlobal("gcs-buckets", "gcs-buckets.json",
                new TypeReference<Map<String, GcsBucket>>() {});
        this.objectMetaStore = storageFactory.createGlobal("gcs-objects", "gcs-objects.json",
                new TypeReference<Map<String, GcsObjectMeta>>() {});
        this.aclStore = storageFactory.createGlobal("gcs-acls", "gcs-acls.json",
                new TypeReference<Map<String, StoredAcl>>() {});
    }

    GcsService(StorageBackend<String, GcsBucket> bucketStore,
            StorageBackend<String, GcsObjectMeta> objectMetaStore,
            StorageBackend<String, StoredAcl> aclStore,
            String defaultProjectId) {
        this.bucketStore = bucketStore;
        this.objectMetaStore = objectMetaStore;
        this.aclStore = aclStore;
        this.defaultProjectId = defaultProjectId;
        this.serviceRegistry = null;
        this.config = null;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("gcs")
                .enabled(config.services().gcs().enabled())
                .storageKey("gcs")
                .protocol(ServiceProtocol.REST)
                .resourceClasses(GcsBucketController.class, GcsObjectController.class,
                        GcsUploadController.class, GcsDownloadController.class,
                        GcsXmlDownloadController.class)
                .build());
    }

    @SuppressWarnings("unchecked")
    public GcsBucket createBucket(String name, String projectId, String baseUrl,
            Map<String, Object> body) {
        LOG.infof("createBucket name=%s project=%s", name, projectId);
        if (bucketStore.get(name).isPresent()) {
            LOG.warnf("createBucket failed: bucket already exists name=%s", name);
            throw GcpException.alreadyExists("Bucket already exists: " + name);
        }
        String now = Instant.now().toString();
        GcsBucket bucket = new GcsBucket();
        bucket.setId(name);
        bucket.setName(name);
        bucket.setProjectId(projectId != null ? projectId : defaultProjectId);
        bucket.setProjectNumber("1");
        String location = body != null && body.containsKey("location")
                ? (String) body.get("location") : "US";
        bucket.setLocation(location.toUpperCase());
        String storageClass = body != null && body.containsKey("storageClass")
                ? (String) body.get("storageClass") : "STANDARD";
        bucket.setStorageClass(storageClass);
        bucket.setTimeCreated(now);
        bucket.setUpdated(now);
        bucket.setSelfLink(baseUrl + "/storage/v1/b/" + name);
        bucket.setEtag("CAE=");
        if (body != null) {
            if (body.containsKey("labels")) {
                bucket.setLabels((Map<String, String>) body.get("labels"));
            }
            if (body.containsKey("versioning")) {
                bucket.setVersioning((Map<String, Object>) body.get("versioning"));
            }
            if (body.containsKey("lifecycle")) {
                bucket.setLifecycle((Map<String, Object>) body.get("lifecycle"));
            }
            if (body.containsKey("cors")) {
                bucket.setCors((List<Map<String, Object>>) body.get("cors"));
            }
            if (body.containsKey("retentionPolicy")) {
                bucket.setRetentionPolicy((Map<String, Object>) body.get("retentionPolicy"));
            }
        }
        bucketStore.put(name, bucket);
        return bucket;
    }

    public GcsBucket getBucket(String name) {
        LOG.debugf("getBucket name=%s", name);
        return bucketStore.get(name)
                .orElseThrow(() -> GcpException.notFound("Bucket not found: " + name));
    }

    @SuppressWarnings("unchecked")
    public GcsBucket updateBucket(String name, Map<String, Object> patch) {
        LOG.infof("updateBucket name=%s", name);
        GcsBucket bucket = getBucket(name);
        if (patch.containsKey("labels")) {
            bucket.setLabels((Map<String, String>) patch.get("labels"));
        }
        if (patch.containsKey("versioning")) {
            bucket.setVersioning((Map<String, Object>) patch.get("versioning"));
        }
        if (patch.containsKey("lifecycle")) {
            bucket.setLifecycle((Map<String, Object>) patch.get("lifecycle"));
        }
        if (patch.containsKey("cors")) {
            bucket.setCors((List<Map<String, Object>>) patch.get("cors"));
        }
        if (patch.containsKey("retentionPolicy")) {
            bucket.setRetentionPolicy((Map<String, Object>) patch.get("retentionPolicy"));
        }
        if (patch.containsKey("storageClass")) {
            bucket.setStorageClass((String) patch.get("storageClass"));
        }
        bucket.setUpdated(Instant.now().toString());
        bucketStore.put(name, bucket);
        return bucket;
    }

    public void deleteBucket(String name) {
        LOG.infof("deleteBucket name=%s", name);
        if (bucketStore.get(name).isEmpty()) {
            LOG.warnf("deleteBucket failed: bucket not found name=%s", name);
            throw GcpException.notFound("Bucket not found: " + name);
        }
        bucketStore.delete(name);
    }

    public List<GcsBucket> listBuckets(String projectId) {
        LOG.debugf("listBuckets project=%s", projectId);
        List<GcsBucket> buckets = bucketStore.scan(k -> true).stream()
                .filter(b -> projectId == null || projectId.equals(b.getProjectId()))
                .toList();
        LOG.debugf("listBuckets project=%s count=%d", projectId, buckets.size());
        return buckets;
    }

    public GcsObjectMeta putObject(String bucket, String objectName, String contentType, byte[] data, String baseUrl) {
        LOG.infof("putObject bucket=%s name=%s contentType=%s size=%d", bucket, objectName, contentType, data.length);
        if (bucketStore.get(bucket).isEmpty()) {
            LOG.warnf("putObject failed: bucket not found bucket=%s", bucket);
            throw GcpException.notFound("Bucket not found: " + bucket);
        }
        String key = objectKey(bucket, objectName);
        long generation = System.currentTimeMillis();
        String now = Instant.now().toString();
        String encodedName = urlEncode(objectName);

        GcsObjectMeta meta = new GcsObjectMeta();
        meta.setId(bucket + "/" + objectName + "/" + generation);
        meta.setName(objectName);
        meta.setBucket(bucket);
        meta.setGeneration(String.valueOf(generation));
        meta.setSize(String.valueOf(data.length));
        meta.setContentType(contentType != null ? contentType : "application/octet-stream");
        meta.setStorageClass("STANDARD");
        meta.setTimeCreated(now);
        meta.setUpdated(now);
        meta.setSelfLink(baseUrl + "/storage/v1/b/" + bucket + "/o/" + encodedName);
        meta.setMediaLink(baseUrl + "/storage/v1/b/" + bucket + "/o/" + encodedName
                + "?alt=media&generation=" + generation);
        String crc32c = computeCrc32c(data);
        meta.setCrc32c(crc32c);
        String md5 = computeMd5(data);
        meta.setMd5Hash(md5);
        meta.setEtag(md5);

        objectMetaStore.put(key, meta);
        objectData.put(key, data);
        return meta;
    }

    public GcsObjectMeta getObjectMeta(String bucket, String objectName) {
        LOG.debugf("getObjectMeta bucket=%s name=%s", bucket, objectName);
        return objectMetaStore.get(objectKey(bucket, objectName))
                .orElseThrow(() -> GcpException.notFound("Object not found: " + objectName));
    }

    public byte[] getObjectData(String bucket, String objectName) {
        LOG.debugf("getObjectData bucket=%s name=%s", bucket, objectName);
        byte[] data = objectData.get(objectKey(bucket, objectName));
        if (data == null) {
            LOG.warnf("getObjectData failed: object not found bucket=%s name=%s", bucket, objectName);
            throw GcpException.notFound("Object not found: " + objectName);
        }
        return data;
    }

    public boolean deleteObject(String bucket, String objectName) {
        LOG.infof("deleteObject bucket=%s name=%s", bucket, objectName);
        String key = objectKey(bucket, objectName);
        objectData.remove(key);
        if (objectMetaStore.get(key).isEmpty()) {
            LOG.debugf("deleteObject: object metadata not found bucket=%s name=%s", bucket, objectName);
            return false;
        }
        objectMetaStore.delete(key);
        return true;
    }

    public GcsObjectMeta patchObject(String bucket, String objectName, Map<String, Object> patch) {
        LOG.infof("patchObject bucket=%s name=%s", bucket, objectName);
        String key = objectKey(bucket, objectName);
        GcsObjectMeta meta = objectMetaStore.get(key)
                .orElseThrow(() -> GcpException.notFound("Object not found: " + objectName));

        if (patch.containsKey("contentType")) {
            meta.setContentType((String) patch.get("contentType"));
        }
        if (patch.containsKey("contentDisposition")) {
            meta.setContentDisposition((String) patch.get("contentDisposition"));
        }
        if (patch.containsKey("contentEncoding")) {
            meta.setContentEncoding((String) patch.get("contentEncoding"));
        }
        if (patch.containsKey("contentLanguage")) {
            meta.setContentLanguage((String) patch.get("contentLanguage"));
        }
        if (patch.containsKey("metadata")) {
            @SuppressWarnings("unchecked")
            Map<String, String> userMeta = (Map<String, String>) patch.get("metadata");
            meta.setMetadata(userMeta);
        }
        meta.setUpdated(Instant.now().toString());
        long mg = Long.parseLong(meta.getMetageneration() != null ? meta.getMetageneration() : "1");
        meta.setMetageneration(String.valueOf(mg + 1));
        objectMetaStore.put(key, meta);
        return meta;
    }

    public GcsObjectMeta composeObject(String bucket, String destObject,
            List<String> sourceNames, String contentType, String baseUrl) {
        LOG.infof("composeObject bucket=%s dest=%s sources=%d", bucket, destObject, sourceNames.size());
        if (bucketStore.get(bucket).isEmpty()) {
            throw GcpException.notFound("Bucket not found: " + bucket);
        }
        byte[] composed = new byte[0];
        for (String src : sourceNames) {
            byte[] data = getObjectData(bucket, src);
            byte[] merged = new byte[composed.length + data.length];
            System.arraycopy(composed, 0, merged, 0, composed.length);
            System.arraycopy(data, 0, merged, composed.length, data.length);
            composed = merged;
        }
        String resolvedType = contentType;
        if (resolvedType == null && !sourceNames.isEmpty()) {
            resolvedType = objectMetaStore.get(objectKey(bucket, sourceNames.get(0)))
                    .map(GcsObjectMeta::getContentType).orElse(null);
        }
        return putObject(bucket, destObject, resolvedType != null ? resolvedType : "application/octet-stream",
                composed, baseUrl);
    }

    public void checkPreconditions(String bucket, String objectName,
            Long ifGenerationMatch, Long ifGenerationNotMatch,
            Long ifMetagenerationMatch, Long ifMetagenerationNotMatch) {
        if (ifGenerationMatch == null && ifGenerationNotMatch == null
                && ifMetagenerationMatch == null && ifMetagenerationNotMatch == null) {
            return;
        }
        Optional<GcsObjectMeta> metaOpt = objectMetaStore.get(objectKey(bucket, objectName));
        if (metaOpt.isEmpty()) {
            if (ifGenerationMatch != null && ifGenerationMatch != 0) {
                throw GcpException.conditionNotMet("ifGenerationMatch: object does not exist");
            }
            return;
        }
        GcsObjectMeta meta = metaOpt.get();
        long gen = meta.getGeneration() != null ? Long.parseLong(meta.getGeneration()) : 0;
        long mg = meta.getMetageneration() != null ? Long.parseLong(meta.getMetageneration()) : 1;
        if (ifGenerationMatch != null && gen != ifGenerationMatch) {
            throw GcpException.conditionNotMet("ifGenerationMatch: " + gen + " != " + ifGenerationMatch);
        }
        if (ifGenerationNotMatch != null && gen == ifGenerationNotMatch) {
            throw GcpException.conditionNotMet("ifGenerationNotMatch: " + gen + " == " + ifGenerationNotMatch);
        }
        if (ifMetagenerationMatch != null && mg != ifMetagenerationMatch) {
            throw GcpException.conditionNotMet("ifMetagenerationMatch: " + mg + " != " + ifMetagenerationMatch);
        }
        if (ifMetagenerationNotMatch != null && mg == ifMetagenerationNotMatch) {
            throw GcpException.conditionNotMet("ifMetagenerationNotMatch: " + mg + " == " + ifMetagenerationNotMatch);
        }
    }

    public GcsObjectMeta copyObject(String srcBucket, String srcObject, String dstBucket, String dstObject, String baseUrl) {
        LOG.infof("copyObject src=%s/%s dst=%s/%s", srcBucket, srcObject, dstBucket, dstObject);
        GcsObjectMeta srcMeta = getObjectMeta(srcBucket, srcObject);
        byte[] data = getObjectData(srcBucket, srcObject);
        return putObject(dstBucket, dstObject, srcMeta.getContentType(), data, baseUrl);
    }

    public List<GcsObjectMeta> listObjects(String bucket) {
        LOG.debugf("listObjects bucket=%s", bucket);
        if (bucketStore.get(bucket).isEmpty()) {
            LOG.warnf("listObjects failed: bucket not found bucket=%s", bucket);
            throw GcpException.notFound("Bucket not found: " + bucket);
        }
        List<GcsObjectMeta> objects = objectMetaStore.scan(k -> k.startsWith(bucket + "\0"));
        LOG.debugf("listObjects bucket=%s count=%d", bucket, objects.size());
        return objects;
    }

    // ── ACLs ───────────────────────────────────────────────────────────────────

    public List<StoredAcl> listObjectAcls(String bucket, String objectName) {
        getObjectMeta(bucket, objectName);
        String prefix = "oacl:" + bucket + "\0" + objectName + ":";
        return aclStore.scan(k -> k.startsWith(prefix));
    }

    public StoredAcl upsertObjectAcl(String bucket, String objectName, String entity, String role) {
        getObjectMeta(bucket, objectName);
        StoredAcl acl = buildAcl("storage#objectAccessControl", bucket, objectName, entity, role);
        aclStore.put("oacl:" + bucket + "\0" + objectName + ":" + entity, acl);
        return acl;
    }

    public StoredAcl getObjectAcl(String bucket, String objectName, String entity) {
        return aclStore.get("oacl:" + bucket + "\0" + objectName + ":" + entity)
                .orElseThrow(() -> GcpException.notFound("ACL not found: " + entity));
    }

    public void deleteObjectAcl(String bucket, String objectName, String entity) {
        aclStore.delete("oacl:" + bucket + "\0" + objectName + ":" + entity);
    }

    public List<StoredAcl> listBucketAcls(String bucket) {
        getBucket(bucket);
        String prefix = "bacl:" + bucket + ":";
        return aclStore.scan(k -> k.startsWith(prefix));
    }

    public StoredAcl upsertBucketAcl(String bucket, String entity, String role) {
        getBucket(bucket);
        StoredAcl acl = buildAcl("storage#bucketAccessControl", bucket, null, entity, role);
        aclStore.put("bacl:" + bucket + ":" + entity, acl);
        return acl;
    }

    public StoredAcl getBucketAcl(String bucket, String entity) {
        return aclStore.get("bacl:" + bucket + ":" + entity)
                .orElseThrow(() -> GcpException.notFound("ACL not found: " + entity));
    }

    public void deleteBucketAcl(String bucket, String entity) {
        aclStore.delete("bacl:" + bucket + ":" + entity);
    }

    public List<StoredAcl> listDefaultAcls(String bucket) {
        getBucket(bucket);
        String prefix = "dacl:" + bucket + ":";
        return aclStore.scan(k -> k.startsWith(prefix));
    }

    public StoredAcl upsertDefaultAcl(String bucket, String entity, String role) {
        getBucket(bucket);
        StoredAcl acl = buildAcl("storage#objectAccessControl", bucket, null, entity, role);
        aclStore.put("dacl:" + bucket + ":" + entity, acl);
        return acl;
    }

    public StoredAcl getDefaultAcl(String bucket, String entity) {
        return aclStore.get("dacl:" + bucket + ":" + entity)
                .orElseThrow(() -> GcpException.notFound("Default ACL not found: " + entity));
    }

    public void deleteDefaultAcl(String bucket, String entity) {
        aclStore.delete("dacl:" + bucket + ":" + entity);
    }

    private static StoredAcl buildAcl(String kind, String bucket, String objectName,
            String entity, String role) {
        StoredAcl acl = new StoredAcl();
        acl.setKind(kind);
        acl.setBucket(bucket);
        acl.setObject(objectName);
        acl.setEntity(entity);
        acl.setRole(role != null ? role : "READER");
        acl.setEtag("CAE=");
        if (entity != null && entity.startsWith("user:")) {
            acl.setEmail(entity.substring("user:".length()));
        }
        acl.setId(bucket + (objectName != null ? "/" + objectName : "") + "/" + entity);
        return acl;
    }

    public String startResumableUpload(String bucket, String objectName, String contentType) {
        LOG.infof("startResumableUpload bucket=%s name=%s contentType=%s", bucket, objectName, contentType);
        if (bucketStore.get(bucket).isEmpty()) {
            LOG.warnf("startResumableUpload failed: bucket not found bucket=%s", bucket);
            throw GcpException.notFound("Bucket not found: " + bucket);
        }
        String uploadId = UUID.randomUUID().toString();
        resumableUploads.put(uploadId, new ResumableUpload(bucket, objectName, contentType));
        LOG.debugf("startResumableUpload uploadId=%s", uploadId);
        return uploadId;
    }

    public GcsObjectMeta completeResumableUpload(String uploadId, byte[] data, String baseUrl) {
        LOG.infof("completeResumableUpload uploadId=%s size=%d", uploadId, data.length);
        ResumableUpload upload = resumableUploads.remove(uploadId);
        if (upload == null) {
            LOG.warnf("completeResumableUpload failed: upload not found uploadId=%s", uploadId);
            throw GcpException.notFound("Resumable upload not found: " + uploadId);
        }
        return putObject(upload.bucket(), upload.objectName(), upload.contentType(), data, baseUrl);
    }

    private static String objectKey(String bucket, String objectName) {
        return bucket + "\0" + objectName;
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String computeCrc32c(byte[] data) {
        CRC32C crc = new CRC32C();
        crc.update(data);
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putInt((int) crc.getValue());
        return Base64.getEncoder().encodeToString(buf.array());
    }

    private static String computeMd5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return Base64.getEncoder().encodeToString(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }
}
