package io.floci.gcp.services.gcs.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GcsObjectMeta {

    private String kind = "storage#object";
    private String id;
    private String selfLink;
    private String name;
    private String bucket;
    private String generation;
    private String metageneration = "1";
    private String contentType;
    private String storageClass;
    private String size;
    private String timeCreated;
    private String updated;
    private String crc32c;
    private String md5Hash;
    private String mediaLink;
    private String etag;
    private String contentDisposition;
    private String contentEncoding;
    private String contentLanguage;
    private Map<String, String> metadata;
    private Map<String, String> customerEncryption;

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSelfLink() { return selfLink; }
    public void setSelfLink(String selfLink) { this.selfLink = selfLink; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }

    public String getGeneration() { return generation; }
    public void setGeneration(String generation) { this.generation = generation; }

    public String getMetageneration() { return metageneration; }
    public void setMetageneration(String metageneration) { this.metageneration = metageneration; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public String getStorageClass() { return storageClass; }
    public void setStorageClass(String storageClass) { this.storageClass = storageClass; }

    public String getSize() { return size; }
    public void setSize(String size) { this.size = size; }

    public String getTimeCreated() { return timeCreated; }
    public void setTimeCreated(String timeCreated) { this.timeCreated = timeCreated; }

    public String getUpdated() { return updated; }
    public void setUpdated(String updated) { this.updated = updated; }

    public String getCrc32c() { return crc32c; }
    public void setCrc32c(String crc32c) { this.crc32c = crc32c; }

    public String getMd5Hash() { return md5Hash; }
    public void setMd5Hash(String md5Hash) { this.md5Hash = md5Hash; }

    public String getMediaLink() { return mediaLink; }
    public void setMediaLink(String mediaLink) { this.mediaLink = mediaLink; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    public String getContentDisposition() { return contentDisposition; }
    public void setContentDisposition(String contentDisposition) { this.contentDisposition = contentDisposition; }

    public String getContentEncoding() { return contentEncoding; }
    public void setContentEncoding(String contentEncoding) { this.contentEncoding = contentEncoding; }

    public String getContentLanguage() { return contentLanguage; }
    public void setContentLanguage(String contentLanguage) { this.contentLanguage = contentLanguage; }

    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }

    public Map<String, String> getCustomerEncryption() { return customerEncryption; }
    public void setCustomerEncryption(Map<String, String> customerEncryption) { this.customerEncryption = customerEncryption; }

    private String timeDeleted;
    private Boolean isLatest;
    private Boolean temporaryHold;
    private Boolean eventBasedHold;
    private String retentionExpirationTime;

    public String getTimeDeleted() { return timeDeleted; }
    public void setTimeDeleted(String timeDeleted) { this.timeDeleted = timeDeleted; }

    public Boolean getIsLatest() { return isLatest; }
    public void setIsLatest(Boolean isLatest) { this.isLatest = isLatest; }

    public Boolean getTemporaryHold() { return temporaryHold; }
    public void setTemporaryHold(Boolean temporaryHold) { this.temporaryHold = temporaryHold; }

    public Boolean getEventBasedHold() { return eventBasedHold; }
    public void setEventBasedHold(Boolean eventBasedHold) { this.eventBasedHold = eventBasedHold; }

    public String getRetentionExpirationTime() { return retentionExpirationTime; }
    public void setRetentionExpirationTime(String retentionExpirationTime) { this.retentionExpirationTime = retentionExpirationTime; }
}
