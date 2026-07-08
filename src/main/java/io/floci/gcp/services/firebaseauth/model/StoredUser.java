package io.floci.gcp.services.firebaseauth.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Identity Toolkit v1 UserInfo. Timestamp conventions follow the Firebase Auth emulator:
 * createdAt/lastLoginAt are ms-epoch strings, validSince is a seconds-epoch string,
 * passwordUpdatedAt is a numeric ms value, lastRefreshAt is ISO-8601.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class StoredUser {

    private String localId;
    private String email;
    private Boolean emailVerified;
    private String displayName;
    private String photoUrl;
    private String phoneNumber;
    private Boolean disabled;
    private String passwordHash;
    private String salt;
    private Long passwordUpdatedAt;
    private String validSince;
    private String createdAt;
    private String lastLoginAt;
    private String lastRefreshAt;
    private String customAttributes;
    private Boolean customAuth;
    private List<ProviderUserInfo> providerUserInfo;

    public String getLocalId() { return localId; }
    public void setLocalId(String localId) { this.localId = localId; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public Boolean getEmailVerified() { return emailVerified; }
    public void setEmailVerified(Boolean emailVerified) { this.emailVerified = emailVerified; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public Boolean getDisabled() { return disabled; }
    public void setDisabled(Boolean disabled) { this.disabled = disabled; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getSalt() { return salt; }
    public void setSalt(String salt) { this.salt = salt; }
    public Long getPasswordUpdatedAt() { return passwordUpdatedAt; }
    public void setPasswordUpdatedAt(Long passwordUpdatedAt) { this.passwordUpdatedAt = passwordUpdatedAt; }
    public String getValidSince() { return validSince; }
    public void setValidSince(String validSince) { this.validSince = validSince; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(String lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public String getLastRefreshAt() { return lastRefreshAt; }
    public void setLastRefreshAt(String lastRefreshAt) { this.lastRefreshAt = lastRefreshAt; }
    public String getCustomAttributes() { return customAttributes; }
    public void setCustomAttributes(String customAttributes) { this.customAttributes = customAttributes; }
    public Boolean getCustomAuth() { return customAuth; }
    public void setCustomAuth(Boolean customAuth) { this.customAuth = customAuth; }
    public List<ProviderUserInfo> getProviderUserInfo() { return providerUserInfo; }
    public void setProviderUserInfo(List<ProviderUserInfo> providerUserInfo) { this.providerUserInfo = providerUserInfo; }

    public boolean isDisabled() {
        return Boolean.TRUE.equals(disabled);
    }
}
