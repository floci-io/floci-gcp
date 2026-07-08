package io.floci.gcp.services.firebaseauth.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProviderUserInfo {

    private String providerId;
    private String rawId;
    private String federatedId;
    private String displayName;
    private String photoUrl;
    private String email;
    private String phoneNumber;
    private String screenName;

    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }
    public String getRawId() { return rawId; }
    public void setRawId(String rawId) { this.rawId = rawId; }
    public String getFederatedId() { return federatedId; }
    public void setFederatedId(String federatedId) { this.federatedId = federatedId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public String getScreenName() { return screenName; }
    public void setScreenName(String screenName) { this.screenName = screenName; }

    public static ProviderUserInfo password(String email, String displayName, String photoUrl) {
        ProviderUserInfo info = new ProviderUserInfo();
        info.setProviderId("password");
        info.setRawId(email);
        info.setFederatedId(email);
        info.setEmail(email);
        info.setDisplayName(displayName);
        info.setPhotoUrl(photoUrl);
        return info;
    }
}
