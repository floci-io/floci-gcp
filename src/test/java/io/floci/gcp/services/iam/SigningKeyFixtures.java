package io.floci.gcp.services.iam;

import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.services.iam.model.StoredServiceAccount;
import io.floci.gcp.services.iam.model.StoredSigningKey;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/** Lets tests outside this package build an {@link IamService} and check what it signed. */
public final class SigningKeyFixtures {

    private SigningKeyFixtures() {}

    public static IamService iamService(InMemoryStorage<String, StoredSigningKey> signingKeys) {
        return new IamService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), signingKeys);
    }

    public static IamService iamService(StorageBackend<String, StoredServiceAccount> accounts,
                                        InMemoryStorage<String, StoredSigningKey> signingKeys) {
        return new IamService(accounts, new InMemoryStorage<>(), new InMemoryStorage<>(), signingKeys);
    }

    /** The public half of a stored signing key, rebuilt from the private key's modulus and exponent. */
    public static PublicKey publicKey(StoredSigningKey key) throws Exception {
        KeyFactory factory = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) factory.generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(key.getPrivateKey())));
        return factory.generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
    }

    public static boolean verifies(PublicKey key, byte[] data, byte[] signature) throws Exception {
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key);
        verifier.update(data);
        return verifier.verify(signature);
    }
}
