package io.floci.gcp.services.cloudkms.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class StoredImportJob {

    private String name;
    private String importMethod;
    private String protectionLevel;
    private String createTime;
    private String generateTime;
    private String expireTime;

    /** Base64 of the PKCS8 wrapping private key. */
    private String privateKeyBase64;

    /** Base64 of the X.509 SubjectPublicKeyInfo of the wrapping key. */
    private String publicKeyBase64;

    public StoredImportJob() {}

    public StoredImportJob(String name, String importMethod, String protectionLevel, String createTime,
            String expireTime) {
        this.name = name;
        this.importMethod = importMethod;
        this.protectionLevel = protectionLevel;
        this.createTime = createTime;
        this.generateTime = createTime;
        this.expireTime = expireTime;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getImportMethod() { return importMethod; }
    public void setImportMethod(String importMethod) { this.importMethod = importMethod; }

    public String getProtectionLevel() { return protectionLevel; }
    public void setProtectionLevel(String protectionLevel) { this.protectionLevel = protectionLevel; }

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }

    public String getGenerateTime() { return generateTime; }
    public void setGenerateTime(String generateTime) { this.generateTime = generateTime; }

    public String getExpireTime() { return expireTime; }
    public void setExpireTime(String expireTime) { this.expireTime = expireTime; }

    public String getPrivateKeyBase64() { return privateKeyBase64; }
    public void setPrivateKeyBase64(String privateKeyBase64) { this.privateKeyBase64 = privateKeyBase64; }

    public String getPublicKeyBase64() { return publicKeyBase64; }
    public void setPublicKeyBase64(String publicKeyBase64) { this.publicKeyBase64 = publicKeyBase64; }
}
