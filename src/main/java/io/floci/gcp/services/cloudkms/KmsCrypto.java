package io.floci.gcp.services.cloudkms;

import io.floci.gcp.core.common.GcpException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Real local crypto backing Cloud KMS operations. Symmetric keys use AES-256-GCM; asymmetric
 * keys use standard JCA RSA/EC primitives. Key material is generated at version-create time and
 * stored Base64-encoded (PKCS8 private, X.509 public) on the {@code StoredCryptoKeyVersion}.
 *
 * <p>The symmetric envelope is {@code versionNumber(4 bytes BE) || IV(12 bytes) || ciphertext+tag}.
 * Embedding the version number lets {@code Decrypt} locate the deciphering version; a different
 * key's AES material fails GCM authentication, mirroring real GCP behavior on cross-key decrypt.
 */
final class KmsCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    /** Standard DER DigestInfo prefix for a SHA-256 digest (RSASSA-PKCS1-v1_5). */
    private static final byte[] SHA256_DIGEST_INFO_PREFIX = {
            0x30, 0x31, 0x30, 0x0d, 0x06, 0x09, 0x60, (byte) 0x86, 0x48, 0x01,
            0x65, 0x03, 0x04, 0x02, 0x01, 0x05, 0x00, 0x04, 0x20
    };

    private KmsCrypto() {}

    record GeneratedKey(String keyMaterialBase64, String publicKeyBase64) {}

    static GeneratedKey generate(String algorithm) {
        try {
            return switch (algorithm) {
                case "GOOGLE_SYMMETRIC_ENCRYPTION" -> {
                    KeyGenerator gen = KeyGenerator.getInstance("AES");
                    gen.init(256);
                    SecretKey key = gen.generateKey();
                    yield new GeneratedKey(Base64.getEncoder().encodeToString(key.getEncoded()), null);
                }
                case "RSA_SIGN_PKCS1_2048_SHA256", "RSA_DECRYPT_OAEP_2048_SHA256" -> {
                    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
                    gen.initialize(2048);
                    yield encodePair(gen.generateKeyPair());
                }
                case "EC_SIGN_P256_SHA256" -> {
                    KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
                    gen.initialize(new ECGenParameterSpec("secp256r1"));
                    yield encodePair(gen.generateKeyPair());
                }
                default -> throw GcpException.invalidArgument("Unsupported algorithm: " + algorithm);
            };
        } catch (GcpException e) {
            throw e;
        } catch (Exception e) {
            throw GcpException.internal("Key generation failed: " + e.getMessage());
        }
    }

    private static GeneratedKey encodePair(KeyPair pair) {
        return new GeneratedKey(
                Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
    }

    // ── Symmetric (AES-256-GCM) ──────────────────────────────────────────────

    static byte[] encrypt(int versionNumber, String keyMaterialBase64, byte[] plaintext, byte[] aad) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(keyMaterialBase64);
            byte[] iv = new byte[GCM_IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            byte[] ct = cipher.doFinal(plaintext);
            byte[] out = new byte[4 + GCM_IV_BYTES + ct.length];
            out[0] = (byte) (versionNumber >>> 24);
            out[1] = (byte) (versionNumber >>> 16);
            out[2] = (byte) (versionNumber >>> 8);
            out[3] = (byte) versionNumber;
            System.arraycopy(iv, 0, out, 4, GCM_IV_BYTES);
            System.arraycopy(ct, 0, out, 4 + GCM_IV_BYTES, ct.length);
            return out;
        } catch (Exception e) {
            throw GcpException.internal("Encryption failed: " + e.getMessage());
        }
    }

    static int versionFromCiphertext(byte[] ciphertext) {
        if (ciphertext.length < 4 + GCM_IV_BYTES) {
            throw GcpException.invalidArgument("Decryption failed: ciphertext is malformed");
        }
        return ((ciphertext[0] & 0xFF) << 24) | ((ciphertext[1] & 0xFF) << 16)
                | ((ciphertext[2] & 0xFF) << 8) | (ciphertext[3] & 0xFF);
    }

    static byte[] decrypt(String keyMaterialBase64, byte[] ciphertext, byte[] aad) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(keyMaterialBase64);
            byte[] iv = new byte[GCM_IV_BYTES];
            System.arraycopy(ciphertext, 4, iv, 0, GCM_IV_BYTES);
            int ctLen = ciphertext.length - 4 - GCM_IV_BYTES;
            byte[] ct = new byte[ctLen];
            System.arraycopy(ciphertext, 4 + GCM_IV_BYTES, ct, 0, ctLen);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(ct);
        } catch (Exception e) {
            throw GcpException.invalidArgument("Decryption failed: the ciphertext is invalid");
        }
    }

    // ── Asymmetric ───────────────────────────────────────────────────────────

    static byte[] sign(String algorithm, String privateKeyBase64, byte[] digestSha256) {
        try {
            byte[] der = Base64.getDecoder().decode(privateKeyBase64);
            return switch (algorithm) {
                case "EC_SIGN_P256_SHA256" -> {
                    PrivateKey key = KeyFactory.getInstance("EC")
                            .generatePrivate(new PKCS8EncodedKeySpec(der));
                    Signature sig = Signature.getInstance("NONEwithECDSA");
                    sig.initSign(key);
                    sig.update(digestSha256);
                    yield sig.sign();
                }
                case "RSA_SIGN_PKCS1_2048_SHA256" -> {
                    PrivateKey key = KeyFactory.getInstance("RSA")
                            .generatePrivate(new PKCS8EncodedKeySpec(der));
                    byte[] digestInfo = new byte[SHA256_DIGEST_INFO_PREFIX.length + digestSha256.length];
                    System.arraycopy(SHA256_DIGEST_INFO_PREFIX, 0, digestInfo, 0, SHA256_DIGEST_INFO_PREFIX.length);
                    System.arraycopy(digestSha256, 0, digestInfo, SHA256_DIGEST_INFO_PREFIX.length, digestSha256.length);
                    Signature sig = Signature.getInstance("NONEwithRSA");
                    sig.initSign(key);
                    sig.update(digestInfo);
                    yield sig.sign();
                }
                default -> throw GcpException.invalidArgument(
                        "Algorithm does not support signing: " + algorithm);
            };
        } catch (GcpException e) {
            throw e;
        } catch (Exception e) {
            throw GcpException.internal("Signing failed: " + e.getMessage());
        }
    }

    static byte[] asymmetricDecrypt(String algorithm, String privateKeyBase64, byte[] ciphertext) {
        if (!"RSA_DECRYPT_OAEP_2048_SHA256".equals(algorithm)) {
            throw GcpException.invalidArgument("Algorithm does not support asymmetric decryption: " + algorithm);
        }
        try {
            byte[] der = Base64.getDecoder().decode(privateKeyBase64);
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new OAEPParameterSpec(
                    "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw GcpException.invalidArgument("Asymmetric decryption failed: the ciphertext is invalid");
        }
    }

    static String toPem(String publicKeyBase64) {
        StringBuilder pem = new StringBuilder("-----BEGIN PUBLIC KEY-----\n");
        for (int i = 0; i < publicKeyBase64.length(); i += 64) {
            pem.append(publicKeyBase64, i, Math.min(i + 64, publicKeyBase64.length())).append('\n');
        }
        return pem.append("-----END PUBLIC KEY-----\n").toString();
    }

    static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
