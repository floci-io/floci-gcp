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
import io.floci.gcp.services.gcs.model.CompletedResumableUpload;
import io.floci.gcp.services.gcs.model.GcsBucket;
import io.floci.gcp.services.gcs.model.GcsContentRange;
import io.floci.gcp.services.gcs.model.GcsObjectDownload;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import io.floci.gcp.services.gcs.model.GcsObjectPreconditions;
import io.floci.gcp.services.gcs.model.ResumableChunkOutcome;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32C;

@ApplicationScoped
public class GcsService {

    private static final Logger LOG = Logger.getLogger(GcsService.class);
    private static final int OBJECT_LOCK_COUNT = 256;
    private static final int COMPLETED_RESUMABLE_UPLOAD_HISTORY = 1024;

    private final StorageBackend<String, GcsBucket> bucketStore;
    private final StorageBackend<String, GcsObjectMeta> objectMetaStore;
    private final StorageBackend<String, byte[]> objectDataStore;
    private final StorageBackend<String, StoredAcl> aclStore;
    private final StorageBackend<String, StoredNotification> notificationStore;
    private final ConcurrentHashMap<String, ResumableUpload> resumableUploads = new ConcurrentHashMap<>();
    private final Map<String, CompletedResumableUpload> completedResumableUploads = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CompletedResumableUpload> eldest) {
                    return size() > COMPLETED_RESUMABLE_UPLOAD_HISTORY;
                }
            });
    private final Object[] objectLocks = createObjectLocks();
    private final Object[] uploadLocks = createObjectLocks();
    private final AtomicLong generationSequence;

    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final String defaultProjectId;
    private final PubSubService pubSubService;

    @Inject
    jakarta.enterprise.inject.Instance<io.floci.gcp.services.eventarc.EventarcService> eventarcServiceInstance;

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
        this.generationSequence = new AtomicLong(maxGeneration(objectMetaStore));
        this.objectDataStore = storageFactory.createGlobal("gcs-object-data", "gcs-object-data.json",
                new TypeReference<Map<String, byte[]>>() {});
        this.aclStore = storageFactory.createGlobal("gcs-acls", "gcs-acls.json",
                new TypeReference<Map<String, StoredAcl>>() {});
        this.notificationStore = storageFactory.createGlobal("gcs-notifications", "gcs-notifications.json",
                new TypeReference<Map<String, StoredNotification>>() {});
    }

    GcsService(StorageBackend<String, GcsBucket> bucketStore,
            StorageBackend<String, GcsObjectMeta> objectMetaStore,
            StorageBackend<String, StoredAcl> aclStore,
            String defaultProjectId) {
        this(bucketStore, objectMetaStore, new io.floci.gcp.core.storage.InMemoryStorage<>(),
                aclStore, defaultProjectId);
    }

    GcsService(StorageBackend<String, GcsBucket> bucketStore,
            StorageBackend<String, GcsObjectMeta> objectMetaStore,
            StorageBackend<String, byte[]> objectDataStore,
            StorageBackend<String, StoredAcl> aclStore,
            String defaultProjectId) {
        this.bucketStore = bucketStore;
        this.objectMetaStore = objectMetaStore;
        this.generationSequence = new AtomicLong(maxGeneration(objectMetaStore));
        this.objectDataStore = objectDataStore;
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
            if (body.containsKey("iamConfiguration")) {
                bucket.setIamConfiguration((Map<String, Object>) body.get("iamConfiguration"));
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
        if (patch.containsKey("iamConfiguration")) {
            bucket.setIamConfiguration((Map<String, Object>) patch.get("iamConfiguration"));
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
        return putObject(bucket, objectName, contentType, data, customerEncryption, null,
                GcsObjectPreconditions.NONE, baseUrl);
    }

    public GcsObjectMeta putObject(String bucket, String objectName, String contentType, byte[] data,
            GcsCustomerEncryption customerEncryption, Map<String, String> userMetadata, String baseUrl) {
        return putObject(bucket, objectName, contentType, data, customerEncryption, userMetadata,
                GcsObjectPreconditions.NONE, baseUrl);
    }

    public GcsObjectMeta putObject(String bucket, String objectName, String contentType, byte[] data,
            GcsCustomerEncryption customerEncryption, GcsObjectPreconditions preconditions, String baseUrl) {
        return putObject(bucket, objectName, contentType, data, customerEncryption, null, preconditions, baseUrl);
    }

    public GcsObjectMeta putObject(String bucket, String objectName, String contentType, byte[] data,
            GcsCustomerEncryption customerEncryption, Map<String, String> userMetadata,
            GcsObjectPreconditions preconditions, String baseUrl) {
        synchronized (objectLock(bucket, objectName)) {
            checkPreconditions(bucket, objectName, preconditions);
            return putObjectLocked(bucket, objectName, contentType, data, customerEncryption, userMetadata, baseUrl);
        }
    }

    private GcsObjectMeta putObjectLocked(String bucket, String objectName, String contentType, byte[] data,
            GcsCustomerEncryption customerEncryption, Map<String, String> userMetadata, String baseUrl) {
        LOG.debugf("putObject bucket=%s name=%s contentType=%s size=%d", bucket, objectName, contentType, data.length);
        GcsBucket b = bucketStore.get(bucket).orElse(null);
        if (b == null) {
            LOG.warnf("putObject failed: bucket not found bucket=%s", bucket);
            throw GcpException.notFound("Bucket not found: " + bucket);
        }
        String key = objectKey(bucket, objectName);
        String now = nowTimestamp();
        String encodedName = urlEncode(objectName);

        GcsObjectMeta existing = getLiveObjectMeta(bucket, objectName).orElse(null);
        if (existing != null && existing.getTimeDeleted() == null) {
            checkObjectMutable(existing);
        }
        long generation = nextGeneration();

        if (isVersioningEnabled(bucket)) {
            if (existing != null) {
                String archiveKey = key + "\0" + existing.getGeneration();
                GcsObjectMeta archived = cloneMeta(existing);
                archived.setIsLatest(false);
                objectDataStore.get(key).ifPresent(oldData -> objectDataStore.put(archiveKey, oldData));
                objectMetaStore.put(archiveKey, archived);
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
        if (userMetadata != null && !userMetadata.isEmpty()) {
            meta.setMetadata(new LinkedHashMap<>(userMetadata));
        }
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

        objectDataStore.put(key, data);
        objectMetaStore.put(key, meta);
        publishNotificationEvent(bucket, objectName, meta, "OBJECT_FINALIZE");
        if (eventarcServiceInstance != null && eventarcServiceInstance.isResolvable()) {
            try {
                eventarcServiceInstance.get().onGcsEvent(bucket, objectName, meta, "google.cloud.storage.object.v1.finalized");
            } catch (Exception e) {
                LOG.warnf(e, "Eventarc GCS object finalize event dispatch failed bucket=%s object=%s", bucket, objectName);
            }
        }
        return meta;
    }

    public GcsObjectMeta putObject(String bucket, String objectName, String contentType, byte[] data, String baseUrl) {
        return putObject(bucket, objectName, contentType, data, GcsCustomerEncryption.none(), baseUrl);
    }

    public GcsObjectMeta getObjectMeta(String bucket, String objectName) {
        LOG.debugf("getObjectMeta bucket=%s name=%s", bucket, objectName);
        GcsObjectMeta meta = getLiveObjectMeta(bucket, objectName)
                .orElseThrow(() -> GcpException.notFound("Object not found: " + objectName));
        return meta;
    }

    public GcsObjectMeta getObjectMeta(String bucket, String objectName, String generation) {
        LOG.debugf("getObjectMeta bucket=%s name=%s generation=%s", bucket, objectName, generation);
        String liveKey = objectKey(bucket, objectName);
        GcsObjectMeta live = objectMetaStore.get(liveKey).orElse(null);
        if (live != null && generation.equals(live.getGeneration())) {
            if (!isReadableLiveObject(liveKey, live)) {
                throw GcpException.notFound("Object not found: " + objectName);
            }
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
        GcsObjectMeta meta = getLiveObjectMeta(bucket, objectName)
                .orElseThrow(() -> GcpException.notFound("Object not found: " + objectName));
        checkCustomerEncryption(meta, customerEncryption);
        byte[] data = objectDataStore.get(key).orElse(null);
        if (data == null) {
            LOG.warnf("getObjectData failed: object not found bucket=%s name=%s", bucket, objectName);
            throw GcpException.notFound("Object not found: " + objectName);
        }
        return data;
    }

    public byte[] getObjectData(String bucket, String objectName) {
        return getObjectData(bucket, objectName, GcsCustomerEncryption.none());
    }

    public byte[] getObjectData(String bucket, String objectName, String generation,
            GcsCustomerEncryption customerEncryption) {
        LOG.debugf("getObjectData bucket=%s name=%s generation=%s", bucket, objectName, generation);
        String liveKey = objectKey(bucket, objectName);
        GcsObjectMeta live = objectMetaStore.get(liveKey).orElse(null);
        if (live != null && generation.equals(live.getGeneration())) {
            if (!isReadableLiveObject(liveKey, live)) {
                throw GcpException.notFound("Object not found: " + objectName);
            }
            checkCustomerEncryption(live, customerEncryption);
            byte[] data = objectDataStore.get(liveKey).orElse(null);
            if (data != null) {
                return data;
            }
        }
        String archiveKey = liveKey + "\0" + generation;
        checkCustomerEncryption(objectMetaStore.get(archiveKey).orElse(null), customerEncryption);
        byte[] data = objectDataStore.get(archiveKey).orElse(null);
        if (data == null) {
            throw GcpException.notFound("Object version not found: " + objectName + "@" + generation);
        }
        return data;
    }

    public GcsObjectDownload getObjectForDownload(String bucket, String objectName, String generation,
            GcsCustomerEncryption customerEncryption) {
        // Every mutation path holds this lock, so metadata and bytes resolved
        // under it always come from the same generation.
        synchronized (objectLock(bucket, objectName)) {
            if (generation != null) {
                return new GcsObjectDownload(getObjectMeta(bucket, objectName, generation),
                        getObjectData(bucket, objectName, generation, customerEncryption));
            }
            var meta = getObjectMeta(bucket, objectName);
            return new GcsObjectDownload(meta,
                    getObjectData(bucket, objectName, meta.getGeneration(), customerEncryption));
        }
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
        return deleteObject(bucket, objectName, GcsObjectPreconditions.NONE);
    }

    public boolean deleteObject(String bucket, String objectName, GcsObjectPreconditions preconditions) {
        synchronized (objectLock(bucket, objectName)) {
            checkPreconditions(bucket, objectName, preconditions);
            return deleteObjectLocked(bucket, objectName);
        }
    }

    private boolean deleteObjectLocked(String bucket, String objectName) {
        LOG.debugf("deleteObject bucket=%s name=%s", bucket, objectName);
        String key = objectKey(bucket, objectName);
        GcsObjectMeta live = getLiveObjectMeta(bucket, objectName).orElse(null);
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
            objectDataStore.get(key).ifPresent(oldData -> objectDataStore.put(archiveKey, oldData));
            objectMetaStore.put(archiveKey, archived);
            long markerGen = nextGeneration();
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
        objectDataStore.delete(key);
        if (deletedMeta != null) {
            publishNotificationEvent(bucket, objectName, deletedMeta, "OBJECT_DELETE");
            if (eventarcServiceInstance != null && eventarcServiceInstance.isResolvable()) {
                try {
                    eventarcServiceInstance.get().onGcsEvent(bucket, objectName, deletedMeta, "google.cloud.storage.object.v1.deleted");
                } catch (Exception e) {
                    LOG.warnf(e, "Eventarc GCS object delete event dispatch failed bucket=%s object=%s", bucket, objectName);
                }
            }
        }
        return true;
    }

    public void deleteObjectVersion(String bucket, String objectName, String generation) {
        deleteObjectVersion(bucket, objectName, generation, GcsObjectPreconditions.NONE);
    }

    public void deleteObjectVersion(String bucket, String objectName, String generation,
            GcsObjectPreconditions preconditions) {
        synchronized (objectLock(bucket, objectName)) {
            deleteObjectVersionLocked(bucket, objectName, generation, preconditions);
        }
    }

    private void deleteObjectVersionLocked(String bucket, String objectName, String generation,
            GcsObjectPreconditions preconditions) {
        LOG.debugf("deleteObjectVersion bucket=%s name=%s generation=%s", bucket, objectName, generation);
        String liveKey = objectKey(bucket, objectName);
        GcsObjectMeta live = objectMetaStore.get(liveKey).orElse(null);
        if (live != null && generation.equals(live.getGeneration())) {
            checkPreconditions(Optional.of(live), preconditions);
            objectMetaStore.delete(liveKey);
            objectDataStore.delete(liveKey);
            return;
        }
        String archiveKey = liveKey + "\0" + generation;
        Optional<GcsObjectMeta> archived = objectMetaStore.get(archiveKey);
        if (archived.isEmpty()) {
            throw GcpException.notFound("Object version not found: " + objectName + "@" + generation);
        }
        checkPreconditions(archived, preconditions);
        objectMetaStore.delete(archiveKey);
        objectDataStore.delete(archiveKey);
    }

    public GcsObjectMeta patchObject(String bucket, String objectName, Map<String, Object> patch) {
        return patchObject(bucket, objectName, patch, GcsObjectPreconditions.NONE);
    }

    public GcsObjectMeta patchObject(String bucket, String objectName, Map<String, Object> patch,
            GcsObjectPreconditions preconditions) {
        synchronized (objectLock(bucket, objectName)) {
            checkPreconditions(bucket, objectName, preconditions);
            return patchObjectLocked(bucket, objectName, patch);
        }
    }

    private GcsObjectMeta patchObjectLocked(String bucket, String objectName, Map<String, Object> patch) {
        LOG.debugf("patchObject bucket=%s name=%s", bucket, objectName);
        String key = objectKey(bucket, objectName);
        GcsObjectMeta meta = getLiveObjectMeta(bucket, objectName)
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
        return composeObject(bucket, destObject, sourceNames, contentType, GcsObjectPreconditions.NONE, baseUrl);
    }

    public GcsObjectMeta composeObject(String bucket, String destObject,
            List<String> sourceNames, String contentType, GcsObjectPreconditions preconditions, String baseUrl) {
        LOG.debugf("composeObject bucket=%s dest=%s sources=%d", bucket, destObject, sourceNames.size());
        if (bucketStore.get(bucket).isEmpty()) {
            throw GcpException.notFound("Bucket not found: " + bucket);
        }
        byte[] composed = new byte[0];
        GcsObjectMeta firstSourceMeta = null;
        var componentCount = 0;
        for (String src : sourceNames) {
            var source = getObjectForDownload(bucket, src, null, GcsCustomerEncryption.none());
            if (firstSourceMeta == null) {
                firstSourceMeta = source.meta();
            }
            var sourceComponents = source.meta().getComponentCount();
            componentCount += sourceComponents != null ? sourceComponents : 1;
            var data = source.data();
            var merged = new byte[composed.length + data.length];
            System.arraycopy(composed, 0, merged, 0, composed.length);
            System.arraycopy(data, 0, merged, composed.length, data.length);
            composed = merged;
        }
        String resolvedType = contentType;
        if (resolvedType == null && firstSourceMeta != null) {
            resolvedType = firstSourceMeta.getContentType();
        }
        var meta = putObject(bucket, destObject, resolvedType != null ? resolvedType : "application/octet-stream",
                composed, GcsCustomerEncryption.none(), preconditions, baseUrl);
        // Real GCS composite objects report a componentCount and no md5Hash, and
        // composing an already composite source adds its component count.
        meta.setComponentCount(componentCount);
        meta.setMd5Hash(null);
        objectMetaStore.put(objectKey(bucket, destObject), meta);
        return meta;
    }

    private void checkPreconditions(String bucket, String objectName,
            GcsObjectPreconditions preconditions) {
        checkPreconditions(getLiveObjectMeta(bucket, objectName), preconditions);
    }

    private void checkPreconditions(Optional<GcsObjectMeta> metaOpt, GcsObjectPreconditions preconditions) {
        if (preconditions.isEmpty()) {
            return;
        }
        Long ifGenerationMatch = preconditions.ifGenerationMatch();
        Long ifGenerationNotMatch = preconditions.ifGenerationNotMatch();
        Long ifMetagenerationMatch = preconditions.ifMetagenerationMatch();
        Long ifMetagenerationNotMatch = preconditions.ifMetagenerationNotMatch();
        if (metaOpt.isEmpty()) {
            if (ifGenerationMatch != null && ifGenerationMatch != 0) {
                throw GcpException.conditionNotMet("ifGenerationMatch: object does not exist");
            }
            if (ifGenerationNotMatch != null) {
                throw GcpException.conditionNotMet("ifGenerationNotMatch: object does not exist");
            }
            if (ifMetagenerationMatch != null) {
                throw GcpException.conditionNotMet("ifMetagenerationMatch: object does not exist");
            }
            if (ifMetagenerationNotMatch != null) {
                throw GcpException.conditionNotMet("ifMetagenerationNotMatch: object does not exist");
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
        return copyObject(srcBucket, srcObject, dstBucket, dstObject, GcsObjectPreconditions.NONE, baseUrl);
    }

    public GcsObjectMeta copyObject(String srcBucket, String srcObject, String dstBucket, String dstObject,
            GcsObjectPreconditions preconditions, String baseUrl) {
        LOG.debugf("copyObject src=%s/%s dst=%s/%s", srcBucket, srcObject, dstBucket, dstObject);
        // Read the source before taking the destination lock. Nesting two
        // stripe locks could deadlock with a copy running in the other direction.
        var src = getObjectForDownload(srcBucket, srcObject, null, GcsCustomerEncryption.none());
        synchronized (objectLock(dstBucket, dstObject)) {
            checkPreconditions(dstBucket, dstObject, preconditions);
            return copyObjectLocked(src, dstBucket, dstObject, baseUrl);
        }
    }

    private GcsObjectMeta copyObjectLocked(GcsObjectDownload src, String dstBucket, String dstObject, String baseUrl) {
        var srcMeta = src.meta();
        var dstMeta = putObjectLocked(dstBucket, dstObject, srcMeta.getContentType(), src.data(),
                GcsCustomerEncryption.none(), null, baseUrl);
        if (srcMeta.getMetadata() != null) {
            dstMeta.setMetadata(new LinkedHashMap<>(srcMeta.getMetadata()));
        }
        dstMeta.setContentDisposition(srcMeta.getContentDisposition());
        dstMeta.setContentEncoding(srcMeta.getContentEncoding());
        dstMeta.setContentLanguage(srcMeta.getContentLanguage());
        objectMetaStore.put(objectKey(dstBucket, dstObject), dstMeta);
        return dstMeta;
    }

    public GcsObjectMeta moveObject(String bucket, String srcObject, String dstObject,
            GcsObjectPreconditions sourcePreconditions, GcsObjectPreconditions destinationPreconditions,
            String baseUrl) {
        LOG.debugf("moveObject bucket=%s src=%s dst=%s", bucket, srcObject, dstObject);
        if (srcObject.equals(dstObject)) {
            throw GcpException.invalidArgument("Source and destination object names must be different.");
        }

        int sourceLockIndex = objectLockIndex(bucket, srcObject);
        int destinationLockIndex = objectLockIndex(bucket, dstObject);
        // Lock stripes in a stable order so opposite-direction moves cannot deadlock.
        synchronized (objectLocks[Math.min(sourceLockIndex, destinationLockIndex)]) {
            synchronized (objectLocks[Math.max(sourceLockIndex, destinationLockIndex)]) {
                var source = getObjectForDownload(bucket, srcObject, null, GcsCustomerEncryption.none());
                checkPreconditions(Optional.of(source.meta()), sourcePreconditions);
                checkObjectMutable(source.meta());
                checkPreconditions(bucket, dstObject, destinationPreconditions);

                GcsObjectMeta moved = copyObjectLocked(source, bucket, dstObject, baseUrl);
                deleteObjectLocked(bucket, srcObject);
                return moved;
            }
        }
    }

    public List<GcsObjectMeta> listObjects(String bucket) {
        LOG.debugf("listObjects bucket=%s", bucket);
        if (bucketStore.get(bucket).isEmpty()) {
            LOG.warnf("listObjects failed: bucket not found bucket=%s", bucket);
            throw GcpException.notFound("Bucket not found: " + bucket);
        }
        String prefix = bucket + "\0";
        int prefixLen = prefix.length();
        List<GcsObjectMeta> objects = new ArrayList<>();
        for (String key : objectMetaStore.keys()) {
            if (!key.startsWith(prefix) || key.indexOf('\0', prefixLen) != -1) {
                continue;
            }
            objectMetaStore.get(key)
                    .filter(meta -> isReadableLiveObject(key, meta))
                    .ifPresent(objects::add);
        }
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
            GcsCustomerEncryption customerEncryption, Map<String, String> metadata) {
        return startResumableUpload(bucket, objectName, contentType, customerEncryption, metadata,
                GcsObjectPreconditions.NONE);
    }

    public String startResumableUpload(String bucket, String objectName, String contentType,
            GcsCustomerEncryption customerEncryption, GcsObjectPreconditions preconditions) {
        return startResumableUpload(bucket, objectName, contentType, customerEncryption, null, preconditions);
    }

    public String startResumableUpload(String bucket, String objectName, String contentType,
            GcsCustomerEncryption customerEncryption, Map<String, String> metadata,
            GcsObjectPreconditions preconditions) {
        synchronized (objectLock(bucket, objectName)) {
            return startResumableUploadLocked(bucket, objectName, contentType, customerEncryption, metadata,
                    preconditions);
        }
    }

    private String startResumableUploadLocked(String bucket, String objectName, String contentType,
            GcsCustomerEncryption customerEncryption, Map<String, String> metadata,
            GcsObjectPreconditions preconditions) {
        LOG.debugf("startResumableUpload bucket=%s name=%s contentType=%s", bucket, objectName, contentType);
        if (bucketStore.get(bucket).isEmpty()) {
            LOG.warnf("startResumableUpload failed: bucket not found bucket=%s", bucket);
            throw GcpException.notFound("Bucket not found: " + bucket);
        }
        String uploadId = UUID.randomUUID().toString();
        resumableUploads.put(uploadId, new ResumableUpload(bucket, objectName, contentType,
                customerEncryption.metadata(), metadata, preconditions, new byte[0], null));
        LOG.debugf("startResumableUpload uploadId=%s", uploadId);
        return uploadId;
    }

	public ResumableUpload findResumableUpload(String uploadId) {
		return resumableUploads.get(uploadId);
	}

    // Every chunk of one session is handled under the same lock, so a retry that overlaps
    // finalization waits and then replays the stored metadata instead of uploading twice.
    public ResumableChunkOutcome applyResumableChunk(String uploadId, GcsContentRange range, byte[] data,
            String baseUrl) {
        synchronized (uploadLock(uploadId)) {
            CompletedResumableUpload completed = completedResumableUploads.get(uploadId);
            if (completed != null) {
                return ResumableChunkOutcome.completed(completed.meta());
            }
            ResumableUpload upload = resumableUploads.get(uploadId);
            if (upload == null) {
                LOG.warnf("applyResumableChunk failed: upload not found uploadId=%s", uploadId);
                throw GcpException.notFound("Resumable upload not found: " + uploadId);
            }
            if (range == null) {
                byte[] combined = appendChunk(upload, upload.data().length, data);
                validateResumableTotalSize(upload, (long) combined.length);
                return ResumableChunkOutcome.completed(finishResumableUpload(uploadId, upload, combined, baseUrl));
            }
            if (range.statusQuery()) {
                validateResumableTotalSize(upload, range.totalSize());
                byte[] received = upload.data();
                if (range.totalSize() != null && received.length == range.totalSize()) {
                    return ResumableChunkOutcome.completed(
                            finishResumableUpload(uploadId, upload, received, baseUrl));
                }
                return ResumableChunkOutcome.incomplete(received.length);
            }
            validateResumableTotalSize(upload, range.totalSize());
            byte[] combined = appendChunk(upload, range.start(), data);
            if (range.totalSize() == null || range.end() + 1 < range.totalSize()) {
                resumableUploads.put(uploadId, new ResumableUpload(
                        upload.bucket(), upload.objectName(), upload.contentType(), upload.customerEncryption(),
                        upload.metadata(), upload.preconditions(), combined,
                        upload.totalSize() != null ? upload.totalSize() : range.totalSize()));
                return ResumableChunkOutcome.incomplete(combined.length);
            }
            if (combined.length != range.totalSize()) {
                throw GcpException.invalidArgument(
                        "Content-Range total size does not match uploaded bytes: " + range.totalSize());
            }
            return ResumableChunkOutcome.completed(finishResumableUpload(uploadId, upload, combined, baseUrl));
        }
    }

    public CompletedResumableUpload completedResumableUpload(String uploadId) {
        return completedResumableUploads.get(uploadId);
    }

    // The completed session is recorded before the active one is dropped. Callers rely on
    // that order: a miss on the active map means the completed entry is already visible.
    private GcsObjectMeta finishResumableUpload(String uploadId, ResumableUpload upload, byte[] data, String baseUrl) {
        GcsObjectMeta meta = putObject(upload.bucket(), upload.objectName(), upload.contentType(), data,
                GcsCustomerEncryption.fromMetadata(upload.customerEncryption()), upload.metadata(),
                upload.preconditions(), baseUrl);
        completedResumableUploads.put(uploadId,
                new CompletedResumableUpload(upload.bucket(), upload.objectName(), meta));
        resumableUploads.remove(uploadId);
        LOG.debugf("finishResumableUpload uploadId=%s size=%d", uploadId, data.length);
        return meta;
    }

    private static void validateResumableTotalSize(ResumableUpload upload, Long totalSize) {
        if (upload.totalSize() != null && totalSize != null && !upload.totalSize().equals(totalSize)) {
            throw GcpException.invalidArgument(
                    "Content-Range total size does not match previous chunks: " + totalSize);
        }
    }

    private static byte[] appendChunk(ResumableUpload upload, long start, byte[] data) {
        byte[] existing = upload.data();
        if (start > existing.length) {
            throw GcpException.unavailable("Invalid request. According to the Content-Range header, the upload offset is "
                    + start + " byte(s), which exceeds already uploaded size of " + existing.length + " byte(s).");
        }
        if (start + data.length <= existing.length) {
            return existing;
        }
        if (start != existing.length) {
            throw GcpException.invalidArgument("Content-Range start does not match uploaded bytes: " + start);
        }
        byte[] combined = new byte[existing.length + data.length];
        System.arraycopy(existing, 0, combined, 0, existing.length);
        System.arraycopy(data, 0, combined, existing.length, data.length);
        return combined;
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
        String fmt = (String) body.get("payload_format");
        if (fmt != null) notif.setPayloadFormat(fmt);
        if (body.containsKey("event_types")) {
            notif.setEventTypes((List<String>) body.get("event_types"));
        }
        if (body.containsKey("custom_attributes")) {
            notif.setCustomAttributes((Map<String, String>) body.get("custom_attributes"));
        }
        if (body.containsKey("object_name_prefix")) {
            notif.setObjectNamePrefix((String) body.get("object_name_prefix"));
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

    private Optional<GcsObjectMeta> getLiveObjectMeta(String bucket, String objectName) {
        String key = objectKey(bucket, objectName);
        return objectMetaStore.get(key)
                .filter(meta -> isReadableLiveObject(key, meta));
    }

    public boolean objectExists(String bucket, String objectName) {
        return getLiveObjectMeta(bucket, objectName).isPresent();
    }

    private boolean isReadableLiveObject(String key, GcsObjectMeta meta) {
        if (meta.getTimeDeleted() != null) {
            return false;
        }
        if (objectDataStore.get(key).isPresent()) {
            return true;
        }
        LOG.warnf("Removing stale GCS object metadata without data bucket=%s name=%s generation=%s",
                meta.getBucket(), meta.getName(), meta.getGeneration());
        objectMetaStore.delete(key);
        return false;
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

    private static Object[] createObjectLocks() {
        Object[] locks = new Object[OBJECT_LOCK_COUNT];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    private Object uploadLock(String uploadId) {
        return uploadLocks[Math.floorMod(uploadId.hashCode(), uploadLocks.length)];
    }

    private Object objectLock(String bucket, String objectName) {
        return objectLocks[objectLockIndex(bucket, objectName)];
    }

    private int objectLockIndex(String bucket, String objectName) {
        return Math.floorMod(objectKey(bucket, objectName).hashCode(), objectLocks.length);
    }

    private static long maxGeneration(StorageBackend<String, GcsObjectMeta> objectMetaStore) {
        return objectMetaStore.scan(key -> true).stream()
                .map(GcsObjectMeta::getGeneration)
                .filter(generation -> generation != null)
                .mapToLong(Long::parseLong)
                .max()
                .orElse(0);
    }

    private long nextGeneration() {
        return generationSequence.updateAndGet(previous -> Math.max(previous + 1, System.currentTimeMillis()));
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
