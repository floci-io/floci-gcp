package io.floci.gcp.services.cloudkms.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class StoredCryptoKeyVersion {

    private String name;
    private int versionNumber;
    private String state;
    private String algorithm;
    private String createTime;
    private String generateTime;
    private String destroyTime;

    /** Base64 of the raw AES key (symmetric) or PKCS8 private key (asymmetric). */
    private String keyMaterialBase64;

    /** Base64 of the X.509 SubjectPublicKeyInfo (asymmetric only). */
    private String publicKeyBase64;

    public StoredCryptoKeyVersion() {}

    public StoredCryptoKeyVersion(String name, int versionNumber, String algorithm, String createTime) {
        this.name = name;
        this.versionNumber = versionNumber;
        this.algorithm = algorithm;
        this.state = "ENABLED";
        this.createTime = createTime;
        this.generateTime = createTime;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getVersionNumber() { return versionNumber; }
    public void setVersionNumber(int versionNumber) { this.versionNumber = versionNumber; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }

    public String getGenerateTime() { return generateTime; }
    public void setGenerateTime(String generateTime) { this.generateTime = generateTime; }

    public String getDestroyTime() { return destroyTime; }
    public void setDestroyTime(String destroyTime) { this.destroyTime = destroyTime; }

    public String getKeyMaterialBase64() { return keyMaterialBase64; }
    public void setKeyMaterialBase64(String keyMaterialBase64) { this.keyMaterialBase64 = keyMaterialBase64; }

    public String getPublicKeyBase64() { return publicKeyBase64; }
    public void setPublicKeyBase64(String publicKeyBase64) { this.publicKeyBase64 = publicKeyBase64; }
}
