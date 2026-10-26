package io.floci.gcp.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.HybridStorage;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.core.storage.StorageException;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.floci.gcp.services.iam.model.StoredServiceAccount;
import io.floci.gcp.services.iam.model.StoredServiceAccountKey;
import io.floci.gcp.services.iam.model.StoredSigningKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class IamServiceTest {

    private IamService service;

    @BeforeEach
    void setUp() {
        service = new IamService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>());
    }

    @Test
    void signBlobReturnsAnRsaSha256SignatureNamedSignature() throws Exception {
        InMemoryStorage<String, StoredSigningKey> signingKeys = new InMemoryStorage<>();
        IamService iam = SigningKeyFixtures.iamService(signingKeys);
        iam.createServiceAccount("p1", "signer", "", "");
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);

        Map<String, String> response = iam.signBlob("p1", "signer@p1.iam.gserviceaccount.com",
                Base64.getEncoder().encodeToString(data));

        assertEquals(Set.of("keyId", "signature"), response.keySet());
        StoredSigningKey key = signingKeys.scan(k -> true).get(0);
        assertEquals(key.getKeyId(), response.get("keyId"));
        assertTrue(SigningKeyFixtures.verifies(SigningKeyFixtures.publicKey(key), data,
                Base64.getDecoder().decode(response.get("signature"))));
    }

    @Test
    void eachAccountKeepsOneSigningKeyAcrossCalls() {
        service.createServiceAccount("p1", "a", "", "");
        service.createServiceAccount("p1", "b", "", "");

        String first = service.signingKeyId("p1", "a@p1.iam.gserviceaccount.com");

        assertEquals(first, service.sign("p1", "a@p1.iam.gserviceaccount.com", new byte[] {1}).keyId());
        assertEquals(first, service.signingKeyId("-", "a@p1.iam.gserviceaccount.com"));
        assertNotEquals(first, service.signingKeyId("p1", "b@p1.iam.gserviceaccount.com"));
        assertTrue(first.matches("[0-9a-f]{40}"));
    }

    @Test
    void theSigningKeyIsNotListedAmongUserManagedKeys() {
        service.createServiceAccount("p1", "signer", "", "");
        service.signingKeyId("p1", "signer@p1.iam.gserviceaccount.com");

        assertTrue(service.listKeys("p1", "signer@p1.iam.gserviceaccount.com").isEmpty());
    }

    @Test
    void aRecreatedAccountSignsWithANewKey() {
        service.createServiceAccount("p1", "signer", "", "");
        String before = service.signingKeyId("p1", "signer@p1.iam.gserviceaccount.com");

        service.deleteServiceAccount("p1", "signer@p1.iam.gserviceaccount.com");
        service.createServiceAccount("p1", "signer", "", "");

        assertNotEquals(before, service.signingKeyId("p1", "signer@p1.iam.gserviceaccount.com"));
    }

    @Test
    void deletingAnAccountWaitsForAFirstSigningCallSoItsKeyIsNotWrittenBack() throws Exception {
        CountDownLatch signerInStore = new CountDownLatch(1);
        CountDownLatch releaseSigner = new CountDownLatch(1);
        InMemoryStorage<String, StoredSigningKey> signingKeys = new InMemoryStorage<>() {
            @Override
            public Optional<StoredSigningKey> get(String key) {
                signerInStore.countDown();
                await(releaseSigner);
                return super.get(key);
            }
        };
        IamService iam = SigningKeyFixtures.iamService(signingKeys);
        iam.createServiceAccount("p1", "signer", "", "");
        String email = "signer@p1.iam.gserviceaccount.com";
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<IamService.SignedBytes> signing = executor.submit(() -> iam.sign("p1", email, new byte[] {1}));
            await(signerInStore);
            Future<?> deleting = executor.submit(() -> iam.deleteServiceAccount("p1", email));
            assertThrows(TimeoutException.class, () -> deleting.get(200, TimeUnit.MILLISECONDS),
                    "deletion must wait while the signing call holds the key lock");

            releaseSigner.countDown();
            signing.get(5, TimeUnit.SECONDS);
            deleting.get(5, TimeUnit.SECONDS);
        } finally {
            releaseSigner.countDown();
            executor.shutdownNow();
        }

        assertTrue(signingKeys.keys().isEmpty());
    }

    @Test
    void theSigningInputGetsTheIdOfTheKeyThatSignsIt() throws Exception {
        InMemoryStorage<String, StoredSigningKey> signingKeys = new InMemoryStorage<>();
        IamService iam = SigningKeyFixtures.iamService(signingKeys);
        iam.createServiceAccount("p1", "signer", "", "");
        String email = "signer@p1.iam.gserviceaccount.com";
        String[] named = new String[1];

        IamService.SignedBytes signed = iam.sign("p1", email, keyId -> {
            named[0] = keyId;
            // The account is replaced after the key is chosen: the chosen key still signs.
            iam.deleteServiceAccount("p1", email);
            iam.createServiceAccount("p1", "signer", "", "");
            return keyId.getBytes(StandardCharsets.UTF_8);
        });

        assertEquals(named[0], signed.keyId());
        assertNotEquals(named[0], iam.signingKeyId("p1", email));
    }

    @Test
    void signingForAMissingAccountIsNotFoundAndBadBase64IsInvalid() {
        GcpException missing = assertThrows(GcpException.class,
                () -> service.signBlob("p1", "ghost@p1.iam.gserviceaccount.com", "aGk="));
        assertEquals(io.grpc.Status.Code.NOT_FOUND, missing.getGrpcCode());

        service.createServiceAccount("p1", "signer", "", "");
        GcpException invalid = assertThrows(GcpException.class,
                () -> service.signBlob("p1", "signer@p1.iam.gserviceaccount.com", "not base64!"));
        assertEquals(io.grpc.Status.Code.INVALID_ARGUMENT, invalid.getGrpcCode());
    }

    @Test
    void createServiceAccountStoredAndRetrievable() {
        service.createServiceAccount("p1", "sa1", "Test SA", "");

        StoredServiceAccount sa = service.getServiceAccount("p1", "sa1@p1.iam.gserviceaccount.com");
        assertEquals("sa1@p1.iam.gserviceaccount.com", sa.getEmail());
        assertEquals("Test SA", sa.getDisplayName());
    }

    @Test
    void createServiceAccountWithoutDisplayNameLeavesItEmpty() {
        service.createServiceAccount("p1", "plain", null, null);

        assertNull(service.getServiceAccount("p1", "plain@p1.iam.gserviceaccount.com").getDisplayName());
    }

    @Test
    void updateServiceAccountWithMaskClearsNamedFieldsTheBodyOmits() {
        service.createServiceAccount("p1", "sa1", "Named", "Described");
        String email = "sa1@p1.iam.gserviceaccount.com";

        service.updateServiceAccount("p1", email, null, null, Set.of("displayName"));

        StoredServiceAccount sa = service.getServiceAccount("p1", email);
        assertNull(sa.getDisplayName());
        assertEquals("Described", sa.getDescription());
    }

    @Test
    void updateServiceAccountWithoutMaskSetsOnlyNonNullFields() {
        service.createServiceAccount("p1", "sa1", "Named", "Described");
        String email = "sa1@p1.iam.gserviceaccount.com";

        service.updateServiceAccount("p1", email, null, null, null);
        service.updateServiceAccount("p1", email, "Renamed", null);

        StoredServiceAccount sa = service.getServiceAccount("p1", email);
        assertEquals("Renamed", sa.getDisplayName());
        assertEquals("Described", sa.getDescription());
    }

    @Test
    void createServiceAccountDuplicateThrowsAlreadyExists() {
        service.createServiceAccount("p1", "sa1", "Test SA", "");

        GcpException ex = assertThrows(GcpException.class,
                () -> service.createServiceAccount("p1", "sa1", "Duplicate", ""));
        assertEquals("ALREADY_EXISTS", ex.getGcpStatus());
    }

    @Test
    void getServiceAccountMissingThrowsNotFound() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.getServiceAccount("p1", "missing@p1.iam.gserviceaccount.com"));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void listServiceAccountsFiltersByProject() {
        service.createServiceAccount("p1", "sa1", "SA1", "");
        service.createServiceAccount("p1", "sa2", "SA2", "");

        List<StoredServiceAccount> accounts = service.listServiceAccounts("p1");
        assertEquals(2, accounts.size());
    }

    @Test
    void deleteServiceAccountRemovedFromList() {
        service.createServiceAccount("p1", "sa1", "SA1", "");
        service.deleteServiceAccount("p1", "sa1@p1.iam.gserviceaccount.com");

        GcpException ex = assertThrows(GcpException.class,
                () -> service.getServiceAccount("p1", "sa1@p1.iam.gserviceaccount.com"));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void wildcardProjectResolvesServiceAccountByEmailInOwningProject() {
        StoredServiceAccount created = service.createServiceAccount("other-proj", "tf", "TF", "");
        String email = "tf@other-proj.iam.gserviceaccount.com";

        StoredServiceAccount sa = service.getServiceAccount("-", email);
        assertEquals(created.getName(), sa.getName());
        assertEquals("other-proj", sa.getProjectId());

        StoredServiceAccountKey key = service.createKey("-", email);
        assertTrue(key.getName().startsWith("projects/other-proj/serviceAccounts/" + email + "/keys/"));
        assertEquals(key.getKeyId(), service.getKey("-", email, key.getKeyId()).getKeyId());
        assertEquals(1, service.listKeys("-", email).size());
        assertEquals(1, service.listKeys("other-proj", email).size());
        service.deleteKey("-", email, key.getKeyId());
        assertTrue(service.listKeys("other-proj", email).isEmpty());

        service.updateServiceAccount("-", email, "Renamed", null);
        assertEquals("Renamed", service.getServiceAccount("other-proj", email).getDisplayName());
        assertEquals("projects/other-proj/serviceAccounts/" + email,
                service.serviceAccountResource("-", email));

        service.deleteServiceAccount("-", email);
        assertTrue(service.listServiceAccounts("other-proj").isEmpty());
    }

    @Test
    void wildcardProjectResolvesServiceAccountByUniqueId() {
        StoredServiceAccount created = service.createServiceAccount("p2", "by-id", "ById", "");

        StoredServiceAccount sa = service.getServiceAccount("-", created.getUniqueId());
        assertEquals("by-id@p2.iam.gserviceaccount.com", sa.getEmail());
    }

    @Test
    void everyServiceAccountAddressMapsToOnePolicyResource() {
        StoredServiceAccount created = service.createServiceAccount("p3", "policy-sa", "Policy", "");
        String canonical = "projects/p3/serviceAccounts/policy-sa@p3.iam.gserviceaccount.com";

        assertEquals(canonical, service.serviceAccountResource("p3", "policy-sa"));
        assertEquals(canonical, service.serviceAccountResource("p3", created.getEmail()));
        assertEquals(canonical, service.serviceAccountResource("p3", created.getUniqueId()));
        assertEquals(canonical, service.serviceAccountResource("-", created.getEmail()));
        assertEquals(canonical, service.serviceAccountResource("-", created.getUniqueId()));
    }

    @Test
    void policyStoredUnderTheAccountIdMovesToTheEmailKey() {
        service.createServiceAccount("p5", "legacy-sa", "Legacy", "");
        StoredPolicy legacy = service.setPolicy("projects/p5/serviceAccounts/legacy-sa", new StoredPolicy());

        String canonical = service.serviceAccountResource("p5", "legacy-sa");

        assertEquals("projects/p5/serviceAccounts/legacy-sa@p5.iam.gserviceaccount.com", canonical);
        assertEquals(legacy.getEtag(), service.getPolicy(canonical).getEtag());
        assertEquals(IamService.EMPTY_POLICY_ETAG,
                service.getPolicy("projects/p5/serviceAccounts/legacy-sa").getEtag());
    }

    @Test
    void allDigitAccountIdStillResolvesWhenNoUniqueIdMatches() {
        StoredServiceAccount numeric = service.createServiceAccount("p6", "123456", "Numeric", "");

        assertEquals(numeric.getEmail(), service.getServiceAccount("p6", "123456").getEmail());
    }

    @Test
    void uniqueIdsAreTwentyOneDigitsAndDistinct() {
        StoredServiceAccount first = service.createServiceAccount("p4", "first-sa", "First", "");
        StoredServiceAccount second = service.createServiceAccount("p4", "second-sa", "Second", "");

        assertTrue(first.getUniqueId().matches("[1-9][0-9]{20}"), first.getUniqueId());
        assertNotEquals(first.getUniqueId(), second.getUniqueId());
        assertEquals(second.getEmail(), service.getServiceAccount("p4", second.getUniqueId()).getEmail());
    }

    @Test
    void wildcardProjectMissingServiceAccountIsPermissionDenied() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.getServiceAccount("-", "fake@example.com"));
        assertEquals("PERMISSION_DENIED", ex.getGcpStatus());
        assertEquals(403, ex.getHttpStatus());

        GcpException keys = assertThrows(GcpException.class,
                () -> service.listKeys("-", "missing@p1.iam.gserviceaccount.com"));
        assertEquals("PERMISSION_DENIED", keys.getGcpStatus());
    }

    @Test
    void createKeyAndListKeys() {
        service.createServiceAccount("p1", "sa1", "SA1", "");
        StoredServiceAccountKey key = service.createKey("p1", "sa1@p1.iam.gserviceaccount.com");

        assertNotNull(key.getKeyId());
        assertNotNull(key.getName());

        List<StoredServiceAccountKey> keys = service.listKeys("p1", "sa1@p1.iam.gserviceaccount.com");
        assertEquals(1, keys.size());
        assertEquals(key.getKeyId(), keys.get(0).getKeyId());
    }

    @Test
    void createKeyWritesAPkcs8PrivateKeyThatPairsWithThePublicKey() throws Exception {
        service.createServiceAccount("p1", "sa1", "SA1", "");
        StoredServiceAccountKey key = service.createKey("p1", "sa1@p1.iam.gserviceaccount.com");

        Map<?, ?> keyFile = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                Base64.getDecoder().decode(key.getPrivateKeyData()), Map.class);
        String privatePem = (String) keyFile.get("private_key");
        assertTrue(privatePem.startsWith("-----BEGIN PRIVATE KEY-----\n"), privatePem);
        assertTrue(privatePem.endsWith("\n-----END PRIVATE KEY-----\n"), privatePem);

        KeyFactory rsa = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = rsa.generatePrivate(new PKCS8EncodedKeySpec(pemBody(privatePem)));
        String publicPem = new String(Base64.getDecoder().decode(key.getPublicKeyData()), StandardCharsets.UTF_8);
        PublicKey publicKey = rsa.generatePublic(new X509EncodedKeySpec(pemBody(publicPem)));

        byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(data);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(data);
        assertTrue(verifier.verify(signature));
    }

    private static byte[] pemBody(String pem) {
        return Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", ""));
    }

    @Test
    void deleteKeyRemovedFromList() {
        service.createServiceAccount("p1", "sa1", "SA1", "");
        StoredServiceAccountKey key = service.createKey("p1", "sa1@p1.iam.gserviceaccount.com");

        service.deleteKey("p1", "sa1@p1.iam.gserviceaccount.com", key.getKeyId());

        List<StoredServiceAccountKey> keys = service.listKeys("p1", "sa1@p1.iam.gserviceaccount.com");
        assertTrue(keys.isEmpty());
    }

    @Test
    void aRecreatedAccountHasANewUniqueIdAndNoKeysOfTheDeletedOne() {
        String email = "target@p1.iam.gserviceaccount.com";
        String oldUniqueId = service.createServiceAccount("p1", "target", "", "").getUniqueId();
        StoredServiceAccountKey oldKey = service.createKey("p1", email);

        service.deleteServiceAccount("p1", email);
        String newUniqueId = service.createServiceAccount("p1", "target", "", "").getUniqueId();

        assertNotEquals(oldUniqueId, newUniqueId);
        assertTrue(service.listKeys("p1", email).isEmpty());
        GcpException error = assertThrows(GcpException.class,
                () -> service.getKey("p1", email, oldKey.getKeyId()));
        assertEquals(404, error.getHttpStatus());
    }

    @Test
    void deletingAnAccountKeepsKeysOfSiblingAccountsAndOtherProjects() {
        service.createServiceAccount("p1", "a", "", "");
        service.createServiceAccount("p1", "ab", "", "");
        service.createServiceAccount("p2", "a", "", "");
        service.createKey("p1", "a@p1.iam.gserviceaccount.com");
        StoredServiceAccountKey sibling = service.createKey("p1", "ab@p1.iam.gserviceaccount.com");
        StoredServiceAccountKey otherProject = service.createKey("p2", "a@p2.iam.gserviceaccount.com");

        service.deleteServiceAccount("p1", "a@p1.iam.gserviceaccount.com");

        assertTrue(service.listKeys("p1", "a@p1.iam.gserviceaccount.com").isEmpty());
        assertEquals(List.of(sibling.getKeyId()), service.listKeys("p1", "ab@p1.iam.gserviceaccount.com")
                .stream().map(StoredServiceAccountKey::getKeyId).toList());
        assertEquals(List.of(otherProject.getKeyId()), service.listKeys("p2", "a@p2.iam.gserviceaccount.com")
                .stream().map(StoredServiceAccountKey::getKeyId).toList());
    }

    @Test
    void anAccountCreatedOverOrphanedPersistedKeysStartsWithoutKeys() {
        InMemoryStorage<String, StoredServiceAccountKey> keyStore = new InMemoryStorage<>();
        StoredServiceAccountKey orphan = new StoredServiceAccountKey();
        orphan.setKeyId("orphan");
        orphan.setName("projects/p1/serviceAccounts/target@p1.iam.gserviceaccount.com/keys/orphan");
        keyStore.put("key:p1:target@p1.iam.gserviceaccount.com:orphan", orphan);
        StoredServiceAccountKey unrelated = new StoredServiceAccountKey();
        unrelated.setKeyId("unrelated");
        keyStore.put("key:p1:other@p1.iam.gserviceaccount.com:unrelated", unrelated);
        IamService iam = new IamService(new InMemoryStorage<>(), keyStore, new InMemoryStorage<>());

        iam.createServiceAccount("p1", "target", "", "");

        assertTrue(iam.listKeys("p1", "target@p1.iam.gserviceaccount.com").isEmpty());
        assertThrows(GcpException.class, () -> iam.getKey("p1", "target@p1.iam.gserviceaccount.com", "orphan"));
        assertTrue(keyStore.get("key:p1:other@p1.iam.gserviceaccount.com:unrelated").isPresent());
    }

    @Test
    void deletedAccountKeysStayGoneAfterARestartWithHybridStorage(@TempDir Path tempDir) {
        Path accountsPath = tempDir.resolve("iam-service-accounts.json");
        Path keysPath = tempDir.resolve("iam-sa-keys.json");
        TypeReference<Map<String, StoredServiceAccount>> accountType = new TypeReference<>() {};
        TypeReference<Map<String, StoredServiceAccountKey>> keyType = new TypeReference<>() {};
        HybridStorage<String, StoredServiceAccount> accounts = new HybridStorage<>(
                accountsPath, accountType, TimeUnit.HOURS.toMillis(1));
        HybridStorage<String, StoredServiceAccountKey> keys = new HybridStorage<>(
                keysPath, keyType, TimeUnit.HOURS.toMillis(1));
        String email = "target@p1.iam.gserviceaccount.com";
        try {
            IamService first = new IamService(accounts, keys, new InMemoryStorage<>());
            first.createServiceAccount("p1", "target", "", "");
            first.createKey("p1", email);
            accounts.flush();
            keys.flush();
            first.deleteServiceAccount("p1", email);
        } finally {
            accounts.shutdown();
            keys.shutdown();
        }

        HybridStorage<String, StoredServiceAccount> restoredAccounts = new HybridStorage<>(
                accountsPath, accountType, TimeUnit.HOURS.toMillis(1));
        HybridStorage<String, StoredServiceAccountKey> restoredKeys = new HybridStorage<>(
                keysPath, keyType, TimeUnit.HOURS.toMillis(1));
        try {
            restoredAccounts.load();
            restoredKeys.load();
            IamService second = new IamService(restoredAccounts, restoredKeys, new InMemoryStorage<>());
            second.createServiceAccount("p1", "target", "", "");

            assertTrue(second.listKeys("p1", email).isEmpty());
        } finally {
            restoredAccounts.shutdown();
            restoredKeys.shutdown();
        }
    }

    @Test
    void aKeyCreatedBeforeItsAccountIsDeletedIsRemovedWithTheAccount() throws Exception {
        CountDownLatch keyPublishing = new CountDownLatch(1);
        CountDownLatch releaseKey = new CountDownLatch(1);
        InMemoryStorage<String, StoredServiceAccountKey> keyStore = new InMemoryStorage<>() {
            @Override
            public void put(String key, StoredServiceAccountKey value) {
                keyPublishing.countDown();
                await(releaseKey);
                super.put(key, value);
            }
        };
        IamService iam = new IamService(new InMemoryStorage<>(), keyStore, new InMemoryStorage<>());
        String email = "target@p1.iam.gserviceaccount.com";
        iam.createServiceAccount("p1", "target", "", "");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<StoredServiceAccountKey> creating = executor.submit(() -> iam.createKey("p1", email));
            assertTrue(keyPublishing.await(5, TimeUnit.SECONDS));
            Future<?> deleting = executor.submit(() -> iam.deleteServiceAccount("p1", email));
            assertThrows(TimeoutException.class, () -> deleting.get(200, TimeUnit.MILLISECONDS),
                    "deletion must wait while the key is being published");

            releaseKey.countDown();
            creating.get(5, TimeUnit.SECONDS);
            deleting.get(5, TimeUnit.SECONDS);
        } finally {
            releaseKey.countDown();
            executor.shutdownNow();
        }

        assertTrue(keyStore.keys().isEmpty());
        iam.createServiceAccount("p1", "target", "", "");
        assertTrue(iam.listKeys("p1", email).isEmpty());
    }

    @Test
    void aKeyForAnAccountDeletedDuringCreationIsNotPublished() throws Exception {
        assertKeyCreationRacingDeletionIsNotPublished(false);
    }

    @Test
    void aKeyForAnAccountRecreatedDuringCreationIsNotAttachedToTheReplacement() throws Exception {
        assertKeyCreationRacingDeletionIsNotPublished(true);
    }

    private static void assertKeyCreationRacingDeletionIsNotPublished(boolean recreate) throws Exception {
        CountDownLatch accountChecked = new CountDownLatch(1);
        CountDownLatch releaseCreator = new CountDownLatch(1);
        AtomicBoolean paused = new AtomicBoolean();
        Thread[] creator = new Thread[1];
        InMemoryStorage<String, StoredServiceAccount> accounts = new InMemoryStorage<>() {
            @Override
            public Optional<StoredServiceAccount> get(String key) {
                Optional<StoredServiceAccount> result = super.get(key);
                if (Thread.currentThread() == creator[0] && paused.compareAndSet(false, true)) {
                    accountChecked.countDown();
                    await(releaseCreator);
                }
                return result;
            }
        };
        InMemoryStorage<String, StoredServiceAccountKey> keyStore = new InMemoryStorage<>();
        IamService iam = new IamService(accounts, keyStore, new InMemoryStorage<>());
        String email = "target@p1.iam.gserviceaccount.com";
        iam.createServiceAccount("p1", "target", "", "");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<StoredServiceAccountKey> creating = executor.submit(() -> {
                creator[0] = Thread.currentThread();
                return iam.createKey("p1", email);
            });
            assertTrue(accountChecked.await(5, TimeUnit.SECONDS));
            iam.deleteServiceAccount("p1", email);
            if (recreate) {
                iam.createServiceAccount("p1", "target", "", "");
            }

            releaseCreator.countDown();
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> creating.get(5, TimeUnit.SECONDS));
            assertInstanceOf(GcpException.class, error.getCause());
            assertEquals(404, ((GcpException) error.getCause()).getHttpStatus());
        } finally {
            releaseCreator.countDown();
            executor.shutdownNow();
        }

        assertTrue(keyStore.keys().isEmpty());
        if (recreate) {
            assertTrue(iam.listKeys("p1", email).isEmpty());
        }
    }

    @Test
    void getPolicyReturnsEmptyBindingsByDefault() {
        service.createServiceAccount("p1", "sa1", "SA1", "");
        StoredPolicy policy = service.getPolicy("projects/p1/serviceAccounts/sa1@p1.iam.gserviceaccount.com");

        assertNotNull(policy);
        assertEquals(1, policy.getVersion());
        assertTrue(policy.getBindings() == null || policy.getBindings().isEmpty());
    }

    @Test
    void unsetPolicyCarriesEmptyPolicyEtag() {
        assertEquals("ACAB", service.getPolicy("projects/p1/resource/r1").getEtag());
    }

    @Test
    void setPolicyRotatesEtagOnEveryWrite() {
        StoredPolicy first = service.setPolicy("projects/p1/resource/r1", new StoredPolicy());
        String firstEtag = first.getEtag();
        assertNotNull(firstEtag);
        assertFalse(firstEtag.isEmpty());
        assertNotEquals("ACAB", firstEtag);

        StoredPolicy second = service.setPolicy("projects/p1/resource/r1", new StoredPolicy());
        assertNotEquals(firstEtag, second.getEtag());
    }

    @Test
    void setPolicyWithoutEtagIsBlindWrite() {
        service.setPolicy("projects/p1/resource/r1", new StoredPolicy());

        StoredPolicy blind = new StoredPolicy();
        assertEquals("", blind.getEtag());
        assertDoesNotThrow(() -> service.setPolicy("projects/p1/resource/r1", blind));
    }

    @Test
    void setPolicyWithCurrentEtagSucceeds() {
        service.setPolicy("projects/p1/resource/r1", new StoredPolicy());
        String current = service.getPolicy("projects/p1/resource/r1").getEtag();

        StoredPolicy update = new StoredPolicy();
        update.setEtag(current);
        assertDoesNotThrow(() -> service.setPolicy("projects/p1/resource/r1", update));
    }

    @Test
    void setPolicyWithStaleEtagIsAborted() {
        StoredPolicy reader = service.setPolicy("projects/p1/resource/r1", new StoredPolicy());
        String stale = reader.getEtag();
        service.setPolicy("projects/p1/resource/r1", new StoredPolicy());

        StoredPolicy lostUpdate = new StoredPolicy();
        lostUpdate.setEtag(stale);
        GcpException e = assertThrows(GcpException.class,
                () -> service.setPolicy("projects/p1/resource/r1", lostUpdate));
        assertEquals(409, e.getHttpStatus());
        assertEquals("ABORTED", e.getGcpStatus());
    }

    @Test
    void setPolicyAgainstUnsetPolicyAcceptsEmptyPolicyEtag() {
        StoredPolicy update = new StoredPolicy();
        update.setEtag("ACAB");
        assertDoesNotThrow(() -> service.setPolicy("projects/p1/resource/r1", update));
    }

    @Test
    void deletePolicyRemovesStoredPolicy() {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of("role", "roles/viewer", "members", List.of("user:x@example.com"))));
        service.setPolicy("projects/p1/resource/r1", policy);

        service.deletePolicy("projects/p1/resource/r1");

        assertTrue(service.getPolicy("projects/p1/resource/r1").getBindings().isEmpty());
        assertEquals("ACAB", service.getPolicy("projects/p1/resource/r1").getEtag());
    }

    @Test
    void testPermissionsEchoesWhenNoResolverMatches() {
        assertEquals(List.of("a.b.c", "d.e.f"),
                service.testPermissions("projects/p1/gadgets/g1", List.of("a.b.c", "d.e.f")));
    }

    @Test
    void testPermissionsFailsOpenEmptyForMissingResolvedResource() {
        service.registerPolicyResourceResolver("projects/*/widgets/*", resource -> {
            throw GcpException.notFound("Widget not found: " + resource);
        });

        assertEquals(List.of(), service.testPermissions("projects/p1/widgets/w1", List.of("a.b.c")));
    }

    @Test
    void policiesSurviveServiceRestart() {
        InMemoryStorage<String, StoredPolicy> policyStore = new InMemoryStorage<>();
        IamService first = new IamService(new InMemoryStorage<>(), new InMemoryStorage<>(), policyStore);
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of("role", "roles/pubsub.publisher", "members", List.of("user:x@example.com"))));
        first.setPolicy("projects/p1/topics/t1", policy);

        IamService second = new IamService(new InMemoryStorage<>(), new InMemoryStorage<>(), policyStore);

        assertEquals("roles/pubsub.publisher",
                second.getPolicy("projects/p1/topics/t1").getBindings().get(0).get("role"));
    }

    @Test
    void resolverEnforcesExistenceOnlyForMatchingResources() {
        service.registerPolicyResourceResolver("projects/*/widgets/*", resource -> {
            throw GcpException.notFound("Widget not found: " + resource);
        });

        GcpException e = assertThrows(GcpException.class,
                () -> service.getPolicy("projects/p1/widgets/w1"));
        assertEquals(404, e.getHttpStatus());
        assertThrows(GcpException.class,
                () -> service.setPolicy("projects/p1/widgets/w1", new StoredPolicy()));

        assertDoesNotThrow(() -> service.getPolicy("projects/p1/gadgets/g1"));
        assertDoesNotThrow(() -> service.getPolicy("projects/p1/widgets/w1/sub/s1"));
    }

    @Test
    void deleteSerializesPolicyCleanupAgainstAConcurrentWriter() throws Exception {
        String resource = "buckets/concurrent-bucket";
        AtomicBoolean exists = new AtomicBoolean(true);
        service.registerPolicyResourceResolver("buckets/*", ignored -> {
            if (!exists.get()) {
                throw GcpException.notFound("Bucket not found");
            }
        });
        StoredPolicy initial = new StoredPolicy();
        initial.setBindings(List.of(Map.of("role", "roles/storage.admin", "members", List.of("allUsers"))));
        service.setPolicy(resource, initial);

        CountDownLatch resourceDeleted = new CountDownLatch(1);
        CountDownLatch allowDeleteToFinish = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> deletion = executor.submit(() -> service.deleteResourceAndPolicy(resource, () -> {
                exists.set(false);
                resourceDeleted.countDown();
                await(allowDeleteToFinish);
            }));
            assertTrue(resourceDeleted.await(5, TimeUnit.SECONDS));

            StoredPolicy concurrent = new StoredPolicy();
            concurrent.setBindings(List.of(Map.of(
                    "role", "roles/storage.objectViewer", "members", List.of("allUsers"))));
            Future<?> writer = executor.submit(() -> service.setPolicy(resource, concurrent));
            assertThrows(TimeoutException.class, () -> writer.get(100, TimeUnit.MILLISECONDS));

            allowDeleteToFinish.countDown();
            deletion.get(5, TimeUnit.SECONDS);
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> writer.get(5, TimeUnit.SECONDS));
            assertInstanceOf(GcpException.class, error.getCause());
            assertEquals(404, ((GcpException) error.getCause()).getHttpStatus());
        } finally {
            allowDeleteToFinish.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void createClearsPolicyLeftByAnOlderResourceWithTheSameName() {
        String resource = "buckets/recreated-bucket";
        StoredPolicy stale = new StoredPolicy();
        stale.setBindings(List.of(Map.of("role", "roles/storage.admin", "members", List.of("allUsers"))));
        service.setPolicy(resource, stale);

        String created = service.createResourceAndPolicy(resource, null, establishPolicy -> {
            establishPolicy.run();
            return "created";
        });

        assertEquals("created", created);
        assertTrue(service.getPolicy(resource).getBindings().isEmpty());
    }

    @Test
    void createCheckpointsInitialPolicyInHybridStorage(@TempDir Path tempDir) {
        Path policyPath = tempDir.resolve("iam-policies.json");
        TypeReference<Map<String, StoredPolicy>> policyType = new TypeReference<>() {};
        HybridStorage<String, StoredPolicy> policyStore = new HybridStorage<>(
                policyPath, policyType, TimeUnit.HOURS.toMillis(1));
        HybridStorage<String, StoredPolicy> restoredStore = new HybridStorage<>(
                policyPath, policyType, TimeUnit.HOURS.toMillis(1));
        try {
            IamService durableService = new IamService(
                    new InMemoryStorage<>(), new InMemoryStorage<>(), policyStore);
            StoredPolicy initialPolicy = new StoredPolicy();
            initialPolicy.setBindings(List.of(Map.of(
                    "role", "roles/storage.admin",
                    "members", List.of("serviceAccount:creator@example.test"))));

            durableService.createResourceAndPolicy(
                    "buckets/durable-bucket", initialPolicy, establishPolicy -> {
                        establishPolicy.run();
                        return "created";
                    });

            restoredStore.load();
            IamService restoredService = new IamService(
                    new InMemoryStorage<>(), new InMemoryStorage<>(), restoredStore);
            assertEquals("roles/storage.admin",
                    restoredService.getPolicy("buckets/durable-bucket").getBindings().get(0).get("role"));
        } finally {
            restoredStore.shutdown();
            policyStore.shutdown();
        }
    }

    @Test
    void failedCreateCheckpointDurablyRestoresPreviousPolicy(@TempDir Path tempDir) {
        String resource = "buckets/checkpoint-failure";
        Path policyPath = tempDir.resolve("iam-policies.json");
        TypeReference<Map<String, StoredPolicy>> policyType = new TypeReference<>() {};
        FailingOnceHybridStorage policyStore = new FailingOnceHybridStorage(
                policyPath, policyType, TimeUnit.HOURS.toMillis(1));
        HybridStorage<String, StoredPolicy> restoredStore = new HybridStorage<>(
                policyPath, policyType, TimeUnit.HOURS.toMillis(1));
        try {
            IamService durableService = new IamService(
                    new InMemoryStorage<>(), new InMemoryStorage<>(), policyStore);
            StoredPolicy previousPolicy = new StoredPolicy();
            previousPolicy.setBindings(List.of(Map.of(
                    "role", "roles/storage.objectViewer", "members", List.of("allUsers"))));
            durableService.setPolicy(resource, previousPolicy);
            policyStore.checkpoint();
            StoredPolicy initialPolicy = new StoredPolicy();
            initialPolicy.setBindings(List.of(Map.of(
                    "role", "roles/storage.admin",
                    "members", List.of("serviceAccount:creator@example.test"))));
            policyStore.failNextCheckpoint();

            assertThrows(StorageException.class, () -> durableService.createResourceAndPolicy(
                    resource,
                    initialPolicy,
                    establishPolicy -> {
                        establishPolicy.run();
                        return "created";
                    }));

            restoredStore.load();
            IamService restoredService = new IamService(
                    new InMemoryStorage<>(), new InMemoryStorage<>(), restoredStore);
            assertEquals("roles/storage.objectViewer",
                    restoredService.getPolicy(resource).getBindings().get(0).get("role"));
        } finally {
            restoredStore.shutdown();
            policyStore.shutdown();
        }
    }

    @Test
    void failedPolicyRollbackCheckpointIsReportedOnOriginalFailure() {
        IamService durableService = new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new AlwaysFailCheckpointStorage());

        StorageException failure = assertThrows(StorageException.class,
                () -> durableService.createResourceAndPolicy(
                        "buckets/checkpoint-failure",
                        null,
                        establishPolicy -> {
                            establishPolicy.run();
                            return "created";
                        }));

        assertEquals(1, failure.getSuppressed().length);
        assertInstanceOf(StorageException.class, failure.getSuppressed()[0]);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting test latch", e);
        }
    }

    private static final class FailingOnceHybridStorage extends HybridStorage<String, StoredPolicy> {
        private final AtomicBoolean failNextCheckpoint = new AtomicBoolean();

        private FailingOnceHybridStorage(Path filePath,
                TypeReference<Map<String, StoredPolicy>> typeReference, long flushIntervalMs) {
            super(filePath, typeReference, flushIntervalMs);
        }

        private void failNextCheckpoint() {
            failNextCheckpoint.set(true);
        }

        @Override
        public void checkpoint() {
            if (failNextCheckpoint.compareAndSet(true, false)) {
                throw new StorageException("checkpoint failed", new IllegalStateException("test failure"));
            }
            super.checkpoint();
        }
    }

    private static final class AlwaysFailCheckpointStorage extends InMemoryStorage<String, StoredPolicy> {
        @Override
        public void checkpoint() {
            throw new StorageException("checkpoint failed", new IllegalStateException("test failure"));
        }
    }
}
