package io.floci.gcp.services.cloudkms;

import io.floci.gcp.core.common.GcpException;
import org.bouncycastle.asn1.ASN1BitString;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.DigestInfo;
import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.engines.RSABlindedEngine;
import org.bouncycastle.crypto.params.ParametersWithRandom;
import org.bouncycastle.crypto.signers.PSSSigner;
import org.bouncycastle.crypto.util.PrivateKeyFactory;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Real local crypto backing Cloud KMS operations. Symmetric keys use AES-256-GCM; asymmetric
 * keys use standard JCA RSA/EC primitives, and BouncyCastle for RSA-PSS over a precomputed
 * digest; MAC keys are random HMAC keys the size of the hash output. Key material is generated at
 * version-create time and stored Base64-encoded (PKCS8 private, X.509 public) on the {@code StoredCryptoKeyVersion}.
 *
 * <p>The symmetric envelope is {@code versionNumber(4 bytes BE) || IV(12 bytes) || ciphertext+tag}.
 * Embedding the version number lets {@code Decrypt} locate the deciphering version; a different
 * key's AES material fails GCM authentication, mirroring real GCP behavior on cross-key decrypt.
 */
final class KmsCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int AES_256_KEY_BYTES = 32;
    private static final String UNWRAP_FAILED = "wrapped key could not be unwrapped with the import job's key";
    private static final X9ECParameters P256 = ECNamedCurveTable.getByName("secp256r1");
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private static final String EC_SIGN_ALGORITHM = "EC_SIGN_P256_SHA256";

    private enum RsaScheme { PSS, PKCS1, OAEP }

    /**
     * Modulus size, hash (JCA name) and padding of each RSA algorithm. As on Cloud KMS, PSS and OAEP
     * use the same hash for MGF1, and PSS a salt as long as the hash.
     */
    private record RsaSpec(int bits, String hash, RsaScheme scheme) {}

    private static final Map<String, RsaSpec> RSA_SPECS = Map.ofEntries(
            Map.entry("RSA_SIGN_PSS_2048_SHA256", new RsaSpec(2048, "SHA-256", RsaScheme.PSS)),
            Map.entry("RSA_SIGN_PSS_3072_SHA256", new RsaSpec(3072, "SHA-256", RsaScheme.PSS)),
            Map.entry("RSA_SIGN_PSS_4096_SHA256", new RsaSpec(4096, "SHA-256", RsaScheme.PSS)),
            Map.entry("RSA_SIGN_PSS_4096_SHA512", new RsaSpec(4096, "SHA-512", RsaScheme.PSS)),
            Map.entry("RSA_SIGN_PKCS1_2048_SHA256", new RsaSpec(2048, "SHA-256", RsaScheme.PKCS1)),
            Map.entry("RSA_SIGN_PKCS1_3072_SHA256", new RsaSpec(3072, "SHA-256", RsaScheme.PKCS1)),
            Map.entry("RSA_SIGN_PKCS1_4096_SHA256", new RsaSpec(4096, "SHA-256", RsaScheme.PKCS1)),
            Map.entry("RSA_SIGN_PKCS1_4096_SHA512", new RsaSpec(4096, "SHA-512", RsaScheme.PKCS1)),
            Map.entry("RSA_DECRYPT_OAEP_2048_SHA256", new RsaSpec(2048, "SHA-256", RsaScheme.OAEP)),
            Map.entry("RSA_DECRYPT_OAEP_3072_SHA256", new RsaSpec(3072, "SHA-256", RsaScheme.OAEP)),
            Map.entry("RSA_DECRYPT_OAEP_4096_SHA256", new RsaSpec(4096, "SHA-256", RsaScheme.OAEP)),
            Map.entry("RSA_DECRYPT_OAEP_4096_SHA512", new RsaSpec(4096, "SHA-512", RsaScheme.OAEP)),
            Map.entry("RSA_DECRYPT_OAEP_2048_SHA1", new RsaSpec(2048, "SHA-1", RsaScheme.OAEP)),
            Map.entry("RSA_DECRYPT_OAEP_3072_SHA1", new RsaSpec(3072, "SHA-1", RsaScheme.OAEP)),
            Map.entry("RSA_DECRYPT_OAEP_4096_SHA1", new RsaSpec(4096, "SHA-1", RsaScheme.OAEP)));

    static final Set<String> SIGN_ALGORITHMS = Stream.concat(Stream.of(EC_SIGN_ALGORITHM),
            rsaAlgorithms(RsaScheme.PSS, RsaScheme.PKCS1).stream()).collect(Collectors.toUnmodifiableSet());
    static final Set<String> DECRYPT_ALGORITHMS = rsaAlgorithms(RsaScheme.OAEP);

    /** JCA name and key length in bytes per HMAC algorithm; Cloud KMS sizes the key to the hash output. */
    private record HmacSpec(String jcaName, int keyBytes) {}

    private static final Map<String, HmacSpec> HMAC_SPECS = Map.of(
            "HMAC_SHA256", new HmacSpec("HmacSHA256", 32),
            "HMAC_SHA1", new HmacSpec("HmacSHA1", 20),
            "HMAC_SHA384", new HmacSpec("HmacSHA384", 48),
            "HMAC_SHA512", new HmacSpec("HmacSHA512", 64),
            "HMAC_SHA224", new HmacSpec("HmacSHA224", 28));

    static final Set<String> HMAC_ALGORITHMS = HMAC_SPECS.keySet();

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
                case EC_SIGN_ALGORITHM -> {
                    KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
                    gen.initialize(new ECGenParameterSpec("secp256r1"));
                    yield encodePair(gen.generateKeyPair());
                }
                default -> {
                    RsaSpec rsa = RSA_SPECS.get(algorithm);
                    if (rsa != null) {
                        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
                        gen.initialize(rsa.bits());
                        yield encodePair(gen.generateKeyPair());
                    }
                    HmacSpec hmac = HMAC_SPECS.get(algorithm);
                    if (hmac == null) {
                        throw GcpException.invalidArgument("Unsupported algorithm: " + algorithm);
                    }
                    yield new GeneratedKey(Base64.getEncoder().encodeToString(randomBytes(hmac.keyBytes())), null);
                }
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

    // ── Key import ───────────────────────────────────────────────────────────

    /**
     * Wrapping parameters of each {@code ImportJob.ImportMethod}: the RSA modulus size, the OAEP
     * digest (also used for MGF1), and whether the target key is AES-KWP wrapped under an ephemeral
     * AES-256 key (the {@code *_AES_256} methods) or RSA-OAEP encrypted directly.
     */
    record ImportMethod(int rsaBits, String oaepDigest, MGF1ParameterSpec mgf1, boolean aesKeyWrap) {

        static ImportMethod of(String importMethod) {
            return switch (importMethod) {
                case "RSA_OAEP_3072_SHA1_AES_256" -> new ImportMethod(3072, "SHA-1", MGF1ParameterSpec.SHA1, true);
                case "RSA_OAEP_4096_SHA1_AES_256" -> new ImportMethod(4096, "SHA-1", MGF1ParameterSpec.SHA1, true);
                case "RSA_OAEP_3072_SHA256_AES_256" -> new ImportMethod(3072, "SHA-256", MGF1ParameterSpec.SHA256, true);
                case "RSA_OAEP_4096_SHA256_AES_256" -> new ImportMethod(4096, "SHA-256", MGF1ParameterSpec.SHA256, true);
                case "RSA_OAEP_3072_SHA256" -> new ImportMethod(3072, "SHA-256", MGF1ParameterSpec.SHA256, false);
                case "RSA_OAEP_4096_SHA256" -> new ImportMethod(4096, "SHA-256", MGF1ParameterSpec.SHA256, false);
                default -> throw GcpException.invalidArgument("Unsupported import method: " + importMethod);
            };
        }
    }

    static GeneratedKey generateWrappingKey(String importMethod) {
        ImportMethod method = ImportMethod.of(importMethod);
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(method.rsaBits());
            return encodePair(gen.generateKeyPair());
        } catch (Exception e) {
            throw GcpException.internal("Wrapping key generation failed: " + e.getMessage());
        }
    }

    /**
     * Recovers the target key material from {@code wrappedKey}. For the {@code *_AES_256} methods
     * that is {@code RSA-OAEP(ephemeral AES-256 key) || AES-KWP(ephemeral key, target key)}
     * (RFC 5649); for the others the target key is RSA-OAEP encrypted directly.
     *
     * @throws IllegalArgumentException with the same message for every failure
     */
    static byte[] unwrapImportedKey(String importMethod, String wrappingPrivateKeyBase64, byte[] wrappedKey) {
        ImportMethod method = ImportMethod.of(importMethod);
        int rsaBytes = method.rsaBits() / 8;
        try {
            PrivateKey wrappingKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(wrappingPrivateKeyBase64)));
            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
            rsa.init(Cipher.DECRYPT_MODE, wrappingKey, new OAEPParameterSpec(
                    method.oaepDigest(), "MGF1", method.mgf1(), PSource.PSpecified.DEFAULT));
            if (!method.aesKeyWrap()) {
                return rsa.doFinal(wrappedKey);
            }
            if (wrappedKey.length <= rsaBytes) {
                throw new GeneralSecurityException("wrapped key too short");
            }
            byte[] aesKey = rsa.doFinal(Arrays.copyOfRange(wrappedKey, 0, rsaBytes));
            if (aesKey.length != AES_256_KEY_BYTES) {
                throw new GeneralSecurityException("ephemeral key is not AES-256");
            }
            Cipher kwp = Cipher.getInstance("AES/KWP/NoPadding");
            kwp.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"));
            return kwp.doFinal(Arrays.copyOfRange(wrappedKey, rsaBytes, wrappedKey.length));
        } catch (Exception e) {
            // One reason for every failure: telling a bad RSA-OAEP layer apart from a bad AES-KWP
            // layer would be a decryption oracle on the job's wrapping key.
            throw new IllegalArgumentException(UNWRAP_FAILED, e);
        }
    }

    /**
     * Validates imported key material against {@code algorithm} and returns it in the stored form:
     * raw AES bytes for symmetric keys, PKCS8 private plus derived X.509 public for asymmetric.
     *
     * @throws IllegalArgumentException when the material does not fit the algorithm
     */
    static GeneratedKey importKey(String algorithm, byte[] material) {
        try {
            return switch (algorithm) {
                case "GOOGLE_SYMMETRIC_ENCRYPTION" -> {
                    if (material.length != AES_256_KEY_BYTES) {
                        throw new IllegalArgumentException(
                                "expected a 32-byte AES-256 key, got " + material.length + " bytes");
                    }
                    yield new GeneratedKey(Base64.getEncoder().encodeToString(material), null);
                }
                case EC_SIGN_ALGORITHM -> {
                    KeyFactory factory = KeyFactory.getInstance("EC");
                    if (!(factory.generatePrivate(new PKCS8EncodedKeySpec(material)) instanceof ECPrivateKey key)
                            || !P256.getN().equals(key.getParams().getOrder())) {
                        throw new IllegalArgumentException("expected a PKCS#8 EC private key on curve P-256");
                    }
                    BigInteger d = key.getS();
                    // BouncyCastle ECPoint and ECPrivateKey are qualified: they clash with the imported
                    // java.security.spec.ECPoint and java.security.interfaces.ECPrivateKey.
                    org.bouncycastle.math.ec.ECPoint q = P256.getG().multiply(d).normalize();
                    ASN1BitString embedded = org.bouncycastle.asn1.sec.ECPrivateKey
                            .getInstance(PrivateKeyInfo.getInstance(material).parsePrivateKey()).getPublicKey();
                    if (embedded != null && !P256.getCurve().decodePoint(embedded.getOctets()).equals(q)) {
                        throw new IllegalArgumentException("embedded public key does not match the private key");
                    }
                    PublicKey publicKey = factory.generatePublic(new ECPublicKeySpec(
                            new ECPoint(q.getAffineXCoord().toBigInteger(), q.getAffineYCoord().toBigInteger()),
                            key.getParams()));
                    yield new GeneratedKey(Base64.getEncoder().encodeToString(key.getEncoded()),
                            Base64.getEncoder().encodeToString(publicKey.getEncoded()));
                }
                default -> {
                    RsaSpec rsa = RSA_SPECS.get(algorithm);
                    if (rsa == null) {
                        throw GcpException.invalidArgument("Unsupported algorithm for import: " + algorithm);
                    }
                    yield importRsaKey(rsa, material);
                }
            };
        } catch (GcpException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("key material is not a valid " + algorithm + " key", e);
        }
    }

    private static GeneratedKey importRsaKey(RsaSpec rsa, byte[] material) throws GeneralSecurityException {
        KeyFactory factory = KeyFactory.getInstance("RSA");
        if (!(factory.generatePrivate(new PKCS8EncodedKeySpec(material)) instanceof RSAPrivateCrtKey key)) {
            throw new IllegalArgumentException("expected a PKCS#8 RSA private key with CRT parameters");
        }
        if (key.getModulus().bitLength() != rsa.bits()) {
            throw new IllegalArgumentException(
                    "expected a " + rsa.bits() + "-bit RSA key, got " + key.getModulus().bitLength() + " bits");
        }
        PublicKey publicKey = factory.generatePublic(new RSAPublicKeySpec(key.getModulus(), key.getPublicExponent()));
        return new GeneratedKey(Base64.getEncoder().encodeToString(key.getEncoded()),
                Base64.getEncoder().encodeToString(publicKey.getEncoded()));
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

    /** The hash, by JCA name, whose digest a signing algorithm signs. */
    static String signingHash(String algorithm) {
        if (EC_SIGN_ALGORITHM.equals(algorithm)) {
            return "SHA-256";
        }
        RsaSpec rsa = RSA_SPECS.get(algorithm);
        if (rsa == null || rsa.scheme() == RsaScheme.OAEP) {
            throw GcpException.invalidArgument("Algorithm does not support signing: " + algorithm);
        }
        return rsa.hash();
    }

    static byte[] sign(String algorithm, String privateKeyBase64, byte[] digest) {
        String hash = signingHash(algorithm);
        try {
            byte[] der = Base64.getDecoder().decode(privateKeyBase64);
            if (EC_SIGN_ALGORITHM.equals(algorithm)) {
                PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
                Signature sig = Signature.getInstance("NONEwithECDSA");
                sig.initSign(key);
                sig.update(digest);
                return sig.sign();
            }
            return RSA_SPECS.get(algorithm).scheme() == RsaScheme.PSS
                    ? signPss(hash, der, digest)
                    : signPkcs1(hash, der, digest);
        } catch (Exception e) {
            throw GcpException.internal("Signing failed: " + e.getMessage());
        }
    }

    /** RSASSA-PSS over a digest the caller already computed, which the JCA signatures cannot take. */
    private static byte[] signPss(String hash, byte[] privateKeyDer, byte[] digest) throws Exception {
        Digest content = bcDigest(hash);
        PSSSigner signer = PSSSigner.createRawSigner(new RSABlindedEngine(), content, bcDigest(hash),
                content.getDigestSize(), PSSSigner.TRAILER_IMPLICIT);
        signer.init(true, new ParametersWithRandom(PrivateKeyFactory.createKey(privateKeyDer), RANDOM));
        signer.update(digest, 0, digest.length);
        return signer.generateSignature();
    }

    /** RSASSA-PKCS1-v1_5: the digest wrapped in its DER DigestInfo, then padded and signed. */
    private static byte[] signPkcs1(String hash, byte[] privateKeyDer, byte[] digest) throws Exception {
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateKeyDer));
        byte[] digestInfo = new DigestInfo(new AlgorithmIdentifier(hashOid(hash), DERNull.INSTANCE), digest)
                .getEncoded(ASN1Encoding.DER);
        Signature sig = Signature.getInstance("NONEwithRSA");
        sig.initSign(key);
        sig.update(digestInfo);
        return sig.sign();
    }

    private static Digest bcDigest(String hash) {
        return switch (hash) {
            case "SHA-256" -> new SHA256Digest();
            case "SHA-512" -> new SHA512Digest();
            default -> throw new IllegalArgumentException("No signing digest for " + hash);
        };
    }

    private static ASN1ObjectIdentifier hashOid(String hash) {
        return switch (hash) {
            case "SHA-256" -> NISTObjectIdentifiers.id_sha256;
            case "SHA-512" -> NISTObjectIdentifiers.id_sha512;
            default -> throw new IllegalArgumentException("No signing digest for " + hash);
        };
    }

    static byte[] asymmetricDecrypt(String algorithm, String privateKeyBase64, byte[] ciphertext) {
        RsaSpec rsa = RSA_SPECS.get(algorithm);
        if (rsa == null || rsa.scheme() != RsaScheme.OAEP) {
            throw GcpException.invalidArgument("Algorithm does not support asymmetric decryption: " + algorithm);
        }
        try {
            byte[] der = Base64.getDecoder().decode(privateKeyBase64);
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new OAEPParameterSpec(
                    rsa.hash(), "MGF1", new MGF1ParameterSpec(rsa.hash()), PSource.PSpecified.DEFAULT));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw GcpException.invalidArgument("Asymmetric decryption failed: the ciphertext is invalid");
        }
    }

    // ── MAC (HMAC) ───────────────────────────────────────────────────────────

    static byte[] macSign(String algorithm, String keyMaterialBase64, byte[] data) {
        HmacSpec hmac = HMAC_SPECS.get(algorithm);
        if (hmac == null) {
            throw GcpException.invalidArgument("Algorithm does not support MAC signing: " + algorithm);
        }
        try {
            Mac mac = Mac.getInstance(hmac.jcaName());
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(keyMaterialBase64), hmac.jcaName()));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw GcpException.internal("MAC signing failed: " + e.getMessage());
        }
    }

    /** Compares in constant time, so a wrong tag of any length is a plain mismatch, not an error. */
    static boolean macVerify(String algorithm, String keyMaterialBase64, byte[] data, byte[] tag) {
        return MessageDigest.isEqual(macSign(algorithm, keyMaterialBase64, data), tag);
    }

    static String toPem(String publicKeyBase64) {
        StringBuilder pem = new StringBuilder("-----BEGIN PUBLIC KEY-----\n");
        for (int i = 0; i < publicKeyBase64.length(); i += 64) {
            pem.append(publicKeyBase64, i, Math.min(i + 64, publicKeyBase64.length())).append('\n');
        }
        return pem.append("-----END PUBLIC KEY-----\n").toString();
    }

    private static Set<String> rsaAlgorithms(RsaScheme... schemes) {
        Set<RsaScheme> wanted = Set.of(schemes);
        return RSA_SPECS.entrySet().stream()
                .filter(e -> wanted.contains(e.getValue().scheme()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
