package io.floci.gcp.services.iam.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredServiceAccountKey {

    private String name;
    private String keyId;
    private String keyAlgorithm = "KEY_ALG_RSA_2048";
    private String keyOrigin = "GOOGLE_PROVIDED";
    private String keyType = "USER_MANAGED";
    private String privateKeyData;
    private String publicKeyData;
    private String validAfterTime;
    private String validBeforeTime;

    public StoredServiceAccountKey() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getKeyAlgorithm() { return keyAlgorithm; }
    public void setKeyAlgorithm(String keyAlgorithm) { this.keyAlgorithm = keyAlgorithm; }

    public String getKeyOrigin() { return keyOrigin; }
    public void setKeyOrigin(String keyOrigin) { this.keyOrigin = keyOrigin; }

    public String getKeyType() { return keyType; }
    public void setKeyType(String keyType) { this.keyType = keyType; }

    public String getPrivateKeyData() { return privateKeyData; }
    public void setPrivateKeyData(String privateKeyData) { this.privateKeyData = privateKeyData; }

    public String getPublicKeyData() { return publicKeyData; }
    public void setPublicKeyData(String publicKeyData) { this.publicKeyData = publicKeyData; }

    public String getValidAfterTime() { return validAfterTime; }
    public void setValidAfterTime(String validAfterTime) { this.validAfterTime = validAfterTime; }

    public String getValidBeforeTime() { return validBeforeTime; }
    public void setValidBeforeTime(String validBeforeTime) { this.validBeforeTime = validBeforeTime; }
}
