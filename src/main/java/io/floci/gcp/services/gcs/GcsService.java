package io.floci.gcp.services.gcs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
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
import io.floci.gcp.services.gcs.model.StoredNotification;
import io.floci.gcp.services.pubsub.PubSubService;
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
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
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
    private final StorageBackend<String, StoredNotification> notificationStore;
    private final ConcurrentHashMap<String, byte[]> objectData = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ResumableUpload> resumableUploads = new ConcurrentHashMap<>();

    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final String defaultProjectId;
    private final PubSubService pubSubService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    public GcsService(ServiceRegistry serviceRegistry, EmulatorConfig config,
            StorageFactory storageFactory, PubSubService pubSubService) {
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.defaultProjectId = config.defaultProjectId();
        this.pubSubService = pubSubService;
        this.bucketStore = storageFactory.createGlobal("gcs-buckets", "gcs-buckets.json",
                new TypeReference<Map<String, GcsBucket>>() {});
        this.objectMetaStore = storageFactory.createGlobal("gcs-objects", "gcs-objects.json",
                new TypeReference<Map<String, GcsObjectMeta>>() {});
        this.aclStore = storageFactory.createGlobal("gcs-acls", "gcs-acls.json",
                new TypeReference<Map<String, StoredAcl>>() {});
        this.notificationStore = storageFactory.createGlobal("gcs-notifications", "gcs-notifications.json",
                new TypeReference<Map<String, StoredNotification>>() {});
    }

    GcsService(StorageBackend<String, GcsBucket> bucketStore,
            StorageBackend<String, GcsObjectMeta> objectMetaStore,
            StorageBackend<String, StoredAcl> aclStore,
            String defaultProjectId) {
        this.bucketStore = bucketStore;
        this.objectMetaStore = objectMetaStore;
        this.aclStore = aclStore;
        this.defaultProjectId = defaultProjectId;
        this.notificationStore = new io.floci.gcp.core.storage.InMemoryStorage<>();
        this.serviceRegistry = null;
        this.config = null;
        this.pubSubService = null;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("gcs")
                .enabled(config.services().gcs().enabled())
                .storageKey("gcs")
                .protocol(ServiceProtocol.REST)
                .resourceClasses(GcsBucketController.class, GcsObjectController.class,
                        GcsUploadController.class, GcsDownloadController.class,
                        GcsXmlDownloadController.class, GcsNotificationController.class,
                        GcsBatchController.class)
                .build());
    }

    @SuppressWarnings("unchecked")
    public GcsBucket createBucket(String name, String projectId, String baseUrl,
            Map<String, Object> body) {
        LOG.debugf("createBucket name=%s project=%s", name, projectId);
        if (bucketStore.get(name).isPresent()) {
            LOG.warnf("createBucket failed: bucket already exists name=%s", name);
            throw GcpException.alreadyExists("Bucket already exists: " + name);
        }
        String now = nowTimestamp();
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
            if (body.containsKey("defaultEventBasedHold")) {
                bucket.setDefaultEventBasedHold((Boolean) body.get("defaultEventBasedHold"));
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
        LOG.debugf("updateBucket name=%s", name);
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
        if (patch.containsKey("defaultEventBasedHold")) {
            bucket.setDefaultEventBasedHold((Boolean) patch.get("defaultEventBasedHold"));
        }
        bucket.setUpdated(nowTimestamp());
        bucketStore.put(name, bucket);
        return bucket;
    }

    public void deleteBucket(String name) {
        LOG.debugf("deleteBucket name=%s", name);
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

    public GcsObjectMeta putObject(String bucket, String objectName, String contentType, byte[] data,
            GcsCustomerEncryption customerEncryption, String baseUrl) {
        LOG.debugf("putObject bucket=%s name=%s contentType=%s size=%d", bucket, objectName, contentType, data.length);
        GcsBucket b = bucketStore.get(bucket).orElse(null);
        if (b == null) {
            LOG.warnf("putObject failed: bucket not found bucket=%s", bucket);
            throw GcpException.notFound("Bucket not found: " + bucket);
        }
        String key = objectKey(bucket, objectName);
        long generation = System.currentTimeMillis();
        String now = nowTimestamp();
        String encodedName = urlEncode(objectName);

        GcsObjectMeta existing = objectMetaStore.get(key).orElse(null);
        if (existing != null && existing.getTimeDeleted() == null) {
            checkObjectMutable(existing);
        }

        if (isVersioningEnabled(bucket)) {
            if (existing != null) {
                String archiveKey = key + "\0" + existing.getGeneration();
                GcsObjectMeta archived = cloneMeta(existing);
                archived.setIsLatest(false);
                objectMetaStore.put(archiveKey, archived);
                byte[] oldData = objectData.get(key);
                if (oldData != null) {
                    objectData.put(archiveKey, oldData);
                }
            }
        }

        GcsObjectMeta meta = new GcsObjectMeta();
        meta.setId(bucket + "/" + objectName + "/" + generation);
        meta.setName(objectName);
        meta.setBucket(bucket);
        meta.setGeneration(String.valueOf(generation));
        meta.setSize(String.valueOf(data.length));
        meta.setContentType(contentType != null ? contentType : "application/octet-stream");
        meta.setCustomerEncryption(customerEncryption.metadata());
        meta.setStorageClass("STANDARD");
        meta.setTimeCreated(now);
        meta.setUpdated(now);
        meta.setSelfLink(baseUrl + "/storage/v1/b/" + bucket + "/o/" + encodedName);
        meta.setMediaLink(baseUrl + "/storage/v1/b/" + bucket + "/o/" + encodedName
                + "?alt=media&generation=" + generation);
        meta.setIsLatest(true);
        String crc32c = computeCrc32c(data);
        meta.setCrc32c(crc32c);
        String md5 = computeMd5(data);
        meta.setMd5Hash(md5);
        meta.setEtag(md5);

        String retentionExpiry = computeRetentionExpiry(bucket, now);
        if (retentionExpiry != null) {
            meta.setRetentionExpirationTime(retentionExpiry);
        }
        if (Boolean.TRUE.equals(b.getDefaultEventBasedHold())) {
            meta.setEventBasedHold(true);
        }

        objectMetaStore.put(key, meta);
        objectData.put(key, data);
        publishNotificationEvent(bucket, objectName, meta, "OBJECT_FINALIZE");
        return meta;
    }

    public GcsObjectMeta getObjectMeta(String bucket, String objectName) {
        LOG.debugf("getObjectMeta bucket=%s name=%s", bucket, objectName);
        GcsObjectMeta meta = objectMetaStore.get(objectKey(bucket, objectName))
                .orElseThrow(() -> GcpException.notFound("Object not found: " + objectName));
        if (meta.getTimeDeleted() != null) {
            throw GcpException.notFound("Object not found: " + objectName);
        }
        return meta;
    }

    public GcsObjectMeta getObjectMeta(String bucket, String objectName, String generation) {
        LOG.debugf("getObjectMeta bucket=%s name=%s generation=%s", bucket, objectName, generation);
        String liveKey = objectKey(bucket, objectName);
        GcsObjectMeta live = objectMetaStore.get(liveKey).orElse(null);
        if (live != null && generation.equals(live.getGeneration())) {
            return live;
        }
        String archiveKey = liveKey + "\0" + generation;
        return objectMetaStore.get(archiveKey)
                .orElseThrow(() -> GcpException.notFound(
                        "Object version not found: " + objectName + "@" + generation));
    }

    public byte[] getObjectData(String bucket, String objectName, GcsCustomerEncryption customerEncryption) {
        LOG.debugf("getObjectData bucket=%s name=%s", bucket, objectName);
        String key = objectKey(bucket, objectName);
        GcsObjectMeta meta = objectMetaStore.get(key).orElse(null);
        if (meta != null && meta.getTimeDeleted() != null) {
            throw GcpException.notFound("Object not found: " + objectName);
        }
        checkCustomerEncryption(meta, customerEncryption);
        byte[] data = objectData.get(key);
        if (data == null) {
            LOG.warnf("getObjectData failed: object not found bucket=%s name=%s", bucket, objectName);
            throw GcpException.notFound("Object not found: " + objectName);
        }
        return data;
    }

    public byte[] getObjectData(String bucket, String objectName, String generation,
            GcsCustomerEncryption customerEncryption) {
        LOG.debugf("getObjectData bucket=%s name=%s generation=%s", bucket, objectName, generation);
        String liveKey = objectKey(bucket, objectName);
        GcsObjectMeta live = objectMetaStore.get(liveKey).orElse(null);
        if (live != null && generation.equals(live.getGeneration())) {
            checkCustomerEncryption(live, customerEncryption);
            byte[] data = objectData.get(liveKey);
            if (data != null) {
                return data;
            }
        }
        String archiveKey = liveKey + "\0" + generation;
        checkCustomerEncryption(objectMetaStore.get(archiveKey).orElse(null), customerEncryption);
        byte[] data = objectData.get(archiveKey);
        if (data == null) {
            throw GcpException.notFound("Object version not found: " + objectName + "@" + generation);
        }
        return data;
    }

    private static void checkCustomerEncryption(GcsObjectMeta meta, GcsCustomerEncryption customerEncryption) {
        if (meta == null || meta.getCustomerEncryption() == null) {
            return;
        }
        String expected = meta.getCustomerEncryption().get("keySha256");
        if (!expected.equals(customerEncryption.keySha256())) {
            throw GcpException.permissionDenied("Missing or invalid customer-supplied encryption key");
        }
    }

    public boolean deleteObject(String bucket, String objectName) {
        LOG.debugf("deleteObject bucket=%s name=%s", bucket, objectName);
        String key = objectKey(bucket, objectName);
        GcsObjectMeta live = objectMetaStore.get(key).orElse(null);
        if (live == null) {
            LOG.debugf("deleteObject: object metadata not found bucket=%s name=%s", bucket, objectName);
            return false;
        }
        if (live.getTimeDeleted() == null) {
            checkObjectMutable(live);
        }
        if (isVersioningEnabled(bucket)) {
            String archiveKey = key + "\0" + live.getGeneration();
            GcsObjectMeta archived = cloneMeta(live);
            archived.setIsLatest(false);
            objectMetaStore.put(archiveKey, archived);
            byte[] oldData = objectData.get(key);
            if (oldData != null) {
                objectData.put(archiveKey, oldData);
            }
            long markerGen = System.currentTimeMillis() + 1;
            GcsObjectMeta marker = new GcsObjectMeta();
            marker.setName(objectName);
            marker.setBucket(bucket);
            marker.setGeneration(String.valueOf(markerGen));
            marker.setIsLatest(true);
            String now = nowTimestamp();
            marker.setTimeDeleted(now);
            marker.setTimeCreated(now);
            marker.setUpdated(now);
            objectMetaStore.put(key + "\0" + markerGen, marker);
        }
        GcsObjectMeta deletedMeta = live;
        objectMetaStore.delete(key);
        objectData.remove(key);
        if (deletedMeta != null) {
            publishNotificationEvent(bucket, objectName, deletedMeta, "OBJECT_DELETE");
        }
        return true;
    }

    public void deleteObjectVersion(String bucket, String objectName, String generation) {
        LOG.debugf("deleteObjectVersion bucket=%s name=%s generation=%s", bucket, objectName, generation);
        String liveKey = objectKey(bucket, objectName);
        GcsObjectMeta live = objectMetaStore.get(liveKey).orElse(null);
        if (live != null && generation.equals(live.getGeneration())) {
            objectMetaStore.delete(liveKey);
            objectData.remove(liveKey);
            return;
        }
        String archiveKey = liveKey + "\0" + generation;
        if (objectMetaStore.get(archiveKey).isEmpty()) {
            throw GcpException.notFound("Object version not found: " + objectName + "@" + generation);
        }
        objectMetaStore.delete(archiveKey);
        objectData.remove(archiveKey);
    }

    public GcsObjectMeta patchObject(String bucket, String objectName, Map<String, Object> patch) {
        LOG.debugf("patchObject bucket=%s name=%s", bucket, objectName);
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
        if (patch.containsKey("temporaryHold")) {
            meta.setTemporaryHold((Boolean) patch.get("temporaryHold"));
        }
        if (patch.containsKey("eventBasedHold")) {
            meta.setEventBasedHold((Boolean) patch.get("eventBasedHold"));
        }
        meta.setUpdated(nowTimestamp());
        long mg = Long.parseLong(meta.getMetageneration() != null ? meta.getMetageneration() : "1");
        meta.setMetageneration(String.valueOf(mg + 1));
        objectMetaStore.put(key, meta);
        return meta;
    }

    public GcsObjectMeta composeObject(String bucket, String destObject,
            List<String> sourceNames, String contentType, String baseUrl) {
        LOG.debugf("composeObject bucket=%s dest=%s sources=%d", bucket, destObject, sourceNames.size());
        if (bucketStore.get(bucket).isEmpty()) {
            throw GcpException.notFound("Bucket not found: " + bucket);
        }
        byte[] composed = new byte[0];
        for (String src : sourceNames) {
            byte[] data = getObjectData(bucket, src, GcsCustomerEncryption.none());
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
                composed, GcsCustomerEncryption.none(), baseUrl);
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
        LOG.debugf("copyObject src=%s/%s dst=%s/%s", srcBucket, srcObject, dstBucket, dstObject);
        GcsObjectMeta srcMeta = getObjectMeta(srcBucket, srcObject);
        byte[] data = getObjectData(srcBucket, srcObject, GcsCustomerEncryption.none());
        GcsObjectMeta dstMeta = putObject(dstBucket, dstObject, srcMeta.getContentType(), data,
                GcsCustomerEncryption.none(), baseUrl);
        if (srcMeta.getMetadata() != null) {
            dstMeta.setMetadata(new LinkedHashMap<>(srcMeta.getMetadata()));
        }
        dstMeta.setContentDisposition(srcMeta.getContentDisposition());
        dstMeta.setContentEncoding(srcMeta.getContentEncoding());
        dstMeta.setContentLanguage(srcMeta.getContentLanguage());
        objectMetaStore.put(objectKey(dstBucket, dstObject), dstMeta);
        return dstMeta;
    }

    public List<GcsObjectMeta> listObjects(String bucket) {
        LOG.debugf("listObjects bucket=%s", bucket);
        if (bucketStore.get(bucket).isEmpty()) {
            LOG.warnf("listObjects failed: bucket not found bucket=%s", bucket);
            throw GcpException.notFound("Bucket not found: " + bucket);
        }
        String prefix = bucket + "\0";
        int prefixLen = prefix.length();
        List<GcsObjectMeta> objects = objectMetaStore.scan(k ->
                k.startsWith(prefix) && k.indexOf('\0', prefixLen) == -1
                        && (objectMetaStore.get(k).map(m -> m.getTimeDeleted() == null).orElse(true)));
        LOG.debugf("listObjects bucket=%s count=%d", bucket, objects.size());
        return objects;
    }

    public List<GcsObjectMeta> listObjectVersions(String bucket, String prefix) {
        LOG.debugf("listObjectVersions bucket=%s prefix=%s", bucket, prefix);
        if (bucketStore.get(bucket).isEmpty()) {
            throw GcpException.notFound("Bucket not found: " + bucket);
        }
        String bucketPrefix = bucket + "\0";
        List<GcsObjectMeta> all = objectMetaStore.scan(k -> k.startsWith(bucketPrefix));
        List<GcsObjectMeta> result = all.stream()
                .filter(m -> prefix == null || prefix.isBlank() || m.getName() != null && m.getName().startsWith(prefix))
                .toList();
        LOG.debugf("listObjectVersions bucket=%s count=%d", bucket, result.size());
        return result;
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

    public String startResumableUpload(String bucket, String objectName, String contentType,
            GcsCustomerEncryption customerEncryption) {
        LOG.debugf("startResumableUpload bucket=%s name=%s contentType=%s", bucket, objectName, contentType);
        if (bucketStore.get(bucket).isEmpty()) {
            LOG.warnf("startResumableUpload failed: bucket not found bucket=%s", bucket);
            throw GcpException.notFound("Bucket not found: " + bucket);
        }
        String uploadId = UUID.randomUUID().toString();
        resumableUploads.put(uploadId, new ResumableUpload(bucket, objectName, contentType,
                customerEncryption.metadata()));
        LOG.debugf("startResumableUpload uploadId=%s", uploadId);
        return uploadId;
    }

    public GcsObjectMeta completeResumableUpload(String uploadId, byte[] data, String baseUrl) {
        LOG.debugf("completeResumableUpload uploadId=%s size=%d", uploadId, data.length);
        ResumableUpload upload = resumableUploads.remove(uploadId);
        if (upload == null) {
            LOG.warnf("completeResumableUpload failed: upload not found uploadId=%s", uploadId);
            throw GcpException.notFound("Resumable upload not found: " + uploadId);
        }
        return putObject(upload.bucket(), upload.objectName(), upload.contentType(), data,
                GcsCustomerEncryption.fromMetadata(upload.customerEncryption()), baseUrl);
    }

    // ── Notifications ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public StoredNotification createNotification(String bucket, Map<String, Object> body) {
        LOG.infof("createNotification bucket=%s", bucket);
        getBucket(bucket);
        String prefix = bucket + ":";
        int nextId = (int) notificationStore.scan(k -> k.startsWith(prefix)).size() + 1;
        String id = String.valueOf(nextId);

        StoredNotification notif = new StoredNotification();
        notif.setId(id);
        notif.setTopic((String) body.get("topic"));
        String fmt = (String) body.get("payloadFormat");
        if (fmt != null) notif.setPayloadFormat(fmt);
        if (body.containsKey("eventTypes")) {
            notif.setEventTypes((List<String>) body.get("eventTypes"));
        }
        if (body.containsKey("customAttributes")) {
            notif.setCustomAttributes((Map<String, String>) body.get("customAttributes"));
        }
        if (body.containsKey("objectNamePrefix")) {
            notif.setObjectNamePrefix((String) body.get("objectNamePrefix"));
        }
        notif.setSelfLink(config != null
                ? config.baseUrl() + "/storage/v1/b/" + bucket + "/notificationConfigs/" + id
                : "/storage/v1/b/" + bucket + "/notificationConfigs/" + id);
        notificationStore.put(prefix + id, notif);
        return notif;
    }

    public StoredNotification getNotification(String bucket, String notificationId) {
        LOG.debugf("getNotification bucket=%s id=%s", bucket, notificationId);
        return notificationStore.get(bucket + ":" + notificationId)
                .orElseThrow(() -> GcpException.notFound(
                        "Notification not found: " + notificationId));
    }

    public List<StoredNotification> listNotifications(String bucket) {
        LOG.debugf("listNotifications bucket=%s", bucket);
        String prefix = bucket + ":";
        return notificationStore.scan(k -> k.startsWith(prefix));
    }

    public void deleteNotification(String bucket, String notificationId) {
        LOG.infof("deleteNotification bucket=%s id=%s", bucket, notificationId);
        String key = bucket + ":" + notificationId;
        if (notificationStore.get(key).isEmpty()) {
            throw GcpException.notFound("Notification not found: " + notificationId);
        }
        notificationStore.delete(key);
    }

    private void publishNotificationEvent(String bucket, String objectName,
            GcsObjectMeta meta, String eventType) {
        if (pubSubService == null) return;
        String prefix = bucket + ":";
        List<StoredNotification> notifications = notificationStore.scan(k -> k.startsWith(prefix));
        if (notifications.isEmpty()) return;

        for (StoredNotification notif : notifications) {
            if (notif.getEventTypes() != null && !notif.getEventTypes().contains(eventType)) {
                continue;
            }
            String namePrefix = notif.getObjectNamePrefix();
            if (namePrefix != null && !namePrefix.isBlank() && !objectName.startsWith(namePrefix)) {
                continue;
            }
            try {
                PubsubMessage.Builder msg = PubsubMessage.newBuilder()
                        .putAttributes("eventType", eventType)
                        .putAttributes("payloadFormat", notif.getPayloadFormat() != null
                                ? notif.getPayloadFormat() : "JSON_API_V1")
                        .putAttributes("bucketId", bucket)
                        .putAttributes("objectId", objectName)
                        .putAttributes("objectGeneration",
                                meta.getGeneration() != null ? meta.getGeneration() : "0")
                        .putAttributes("notificationConfig", notif.getSelfLink() != null
                                ? notif.getSelfLink() : bucket + "/notificationConfigs/" + notif.getId());

                if ("JSON_API_V1".equals(notif.getPayloadFormat()) || notif.getPayloadFormat() == null) {
                    byte[] payload = MAPPER.writeValueAsBytes(meta);
                    msg.setData(ByteString.copyFrom(payload));
                }

                pubSubService.publish(notif.getTopic(), List.of(msg.build()));
            } catch (Exception e) {
                LOG.warnf("Failed to publish GCS notification event bucket=%s object=%s topic=%s: %s",
                        bucket, objectName, notif.getTopic(), e.getMessage());
            }
        }
    }

    public Optional<GcsBucket> findBucket(String name) {
        return bucketStore.get(name);
    }

    public GcsBucket lockRetentionPolicy(String bucket, Long ifMetagenerationMatch) {
        LOG.infof("lockRetentionPolicy bucket=%s", bucket);
        GcsBucket b = getBucket(bucket);
        long current = b.getMetageneration() != null ? Long.parseLong(b.getMetageneration()) : 1;
        if (ifMetagenerationMatch != null && current != ifMetagenerationMatch) {
            throw GcpException.conditionNotMet(
                    "ifMetagenerationMatch: " + current + " != " + ifMetagenerationMatch);
        }
        Map<String, Object> rp = b.getRetentionPolicy();
        if (rp == null) {
            rp = new java.util.LinkedHashMap<>();
        }
        rp.put("isLocked", true);
        if (!rp.containsKey("effectiveTime")) {
            rp.put("effectiveTime", nowTimestamp());
        }
        b.setRetentionPolicy(rp);
        b.setMetageneration(String.valueOf(current + 1));
        b.setUpdated(nowTimestamp());
        bucketStore.put(bucket, b);
        return b;
    }

    private void checkObjectMutable(GcsObjectMeta meta) {
        if (Boolean.TRUE.equals(meta.getTemporaryHold())) {
            throw GcpException.permissionDenied(
                    "Object '" + meta.getName() + "' is under a temporary hold.");
        }
        if (Boolean.TRUE.equals(meta.getEventBasedHold())) {
            throw GcpException.permissionDenied(
                    "Object '" + meta.getName() + "' is under an event-based hold.");
        }
        if (meta.getRetentionExpirationTime() != null) {
            Instant expiry = Instant.parse(meta.getRetentionExpirationTime());
            if (Instant.now().isBefore(expiry)) {
                throw GcpException.permissionDenied(
                        "Object '" + meta.getName() + "' is subject to the bucket's retention policy "
                        + "and cannot be deleted or overwritten until "
                        + meta.getRetentionExpirationTime());
            }
        }
    }

    private String computeRetentionExpiry(String bucket, String timeCreated) {
        return bucketStore.get(bucket).map(b -> {
            if (b.getRetentionPolicy() == null) {
                return null;
            }
            Object period = b.getRetentionPolicy().get("retentionPeriod");
            if (period == null) {
                return null;
            }
            long seconds = period instanceof Number n ? n.longValue() : Long.parseLong(period.toString());
            return Instant.parse(timeCreated).plusSeconds(seconds)
                    .truncatedTo(ChronoUnit.MICROS).toString();
        }).orElse(null);
    }

    /**
     * Current time as an RFC 3339 string with at most microsecond precision.
     * GCS timestamps are microsecond-resolution; emitting nanoseconds makes
     * clients (e.g. the gcloud CLI) warn and truncate.
     */
    private static String nowTimestamp() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS).toString();
    }

    private boolean isVersioningEnabled(String bucketName) {
        return bucketStore.get(bucketName)
                .map(b -> {
                    if (b.getVersioning() == null) {
                        return false;
                    }
                    Object enabled = b.getVersioning().get("enabled");
                    return Boolean.TRUE.equals(enabled);
                })
                .orElse(false);
    }

    private static GcsObjectMeta cloneMeta(GcsObjectMeta src) {
        GcsObjectMeta copy = new GcsObjectMeta();
        copy.setKind(src.getKind());
        copy.setId(src.getId());
        copy.setName(src.getName());
        copy.setBucket(src.getBucket());
        copy.setGeneration(src.getGeneration());
        copy.setMetageneration(src.getMetageneration());
        copy.setContentType(src.getContentType());
        copy.setStorageClass(src.getStorageClass());
        copy.setSize(src.getSize());
        copy.setTimeCreated(src.getTimeCreated());
        copy.setUpdated(src.getUpdated());
        copy.setCrc32c(src.getCrc32c());
        copy.setMd5Hash(src.getMd5Hash());
        copy.setMediaLink(src.getMediaLink());
        copy.setSelfLink(src.getSelfLink());
        copy.setEtag(src.getEtag());
        copy.setMetadata(src.getMetadata());
        copy.setCustomerEncryption(src.getCustomerEncryption());
        copy.setTimeDeleted(src.getTimeDeleted());
        copy.setIsLatest(src.getIsLatest());
        copy.setTemporaryHold(src.getTemporaryHold());
        copy.setEventBasedHold(src.getEventBasedHold());
        copy.setRetentionExpirationTime(src.getRetentionExpirationTime());
        return copy;
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
