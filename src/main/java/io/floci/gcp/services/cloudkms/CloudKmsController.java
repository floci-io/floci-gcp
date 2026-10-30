package io.floci.gcp.services.cloudkms;

import com.google.cloud.kms.v1.AsymmetricDecryptRequest;
import com.google.cloud.kms.v1.AsymmetricDecryptResponse;
import com.google.cloud.kms.v1.AsymmetricSignRequest;
import com.google.cloud.kms.v1.AsymmetricSignResponse;
import com.google.cloud.kms.v1.CreateCryptoKeyRequest;
import com.google.cloud.kms.v1.CreateCryptoKeyVersionRequest;
import com.google.cloud.kms.v1.CreateImportJobRequest;
import com.google.cloud.kms.v1.CreateKeyRingRequest;
import com.google.cloud.kms.v1.CryptoKey;
import com.google.cloud.kms.v1.CryptoKeyVersion;
import com.google.cloud.kms.v1.CryptoKeyVersionTemplate;
import com.google.cloud.kms.v1.DecryptRequest;
import com.google.cloud.kms.v1.DecryptResponse;
import com.google.cloud.kms.v1.DestroyCryptoKeyVersionRequest;
import com.google.cloud.kms.v1.Digest;
import com.google.cloud.kms.v1.EncryptRequest;
import com.google.cloud.kms.v1.EncryptResponse;
import com.google.cloud.kms.v1.GenerateRandomBytesRequest;
import com.google.cloud.kms.v1.GenerateRandomBytesResponse;
import com.google.cloud.kms.v1.GetCryptoKeyRequest;
import com.google.cloud.kms.v1.GetCryptoKeyVersionRequest;
import com.google.cloud.kms.v1.GetImportJobRequest;
import com.google.cloud.kms.v1.GetKeyRingRequest;
import com.google.cloud.kms.v1.GetPublicKeyRequest;
import com.google.cloud.kms.v1.ImportCryptoKeyVersionRequest;
import com.google.cloud.kms.v1.ImportJob;
import com.google.cloud.kms.v1.KeyManagementServiceGrpc;
import com.google.cloud.kms.v1.KeyRing;
import com.google.cloud.kms.v1.ListCryptoKeyVersionsRequest;
import com.google.cloud.kms.v1.ListCryptoKeyVersionsResponse;
import com.google.cloud.kms.v1.ListCryptoKeysRequest;
import com.google.cloud.kms.v1.ListCryptoKeysResponse;
import com.google.cloud.kms.v1.ListImportJobsRequest;
import com.google.cloud.kms.v1.ListImportJobsResponse;
import com.google.cloud.kms.v1.ListKeyRingsRequest;
import com.google.cloud.kms.v1.ListKeyRingsResponse;
import com.google.cloud.kms.v1.MacSignRequest;
import com.google.cloud.kms.v1.MacSignResponse;
import com.google.cloud.kms.v1.MacVerifyRequest;
import com.google.cloud.kms.v1.MacVerifyResponse;
import com.google.cloud.kms.v1.ProtectionLevel;
import com.google.cloud.kms.v1.PublicKey;
import com.google.cloud.kms.v1.RestoreCryptoKeyVersionRequest;
import com.google.cloud.kms.v1.UpdateCryptoKeyPrimaryVersionRequest;
import com.google.cloud.kms.v1.UpdateCryptoKeyRequest;
import com.google.cloud.kms.v1.UpdateCryptoKeyVersionRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.Int64Value;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Durations;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.GcpGrpcController;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.services.cloudkms.model.StoredCryptoKey;
import io.floci.gcp.services.cloudkms.model.StoredCryptoKeyVersion;
import io.floci.gcp.services.cloudkms.model.StoredImportJob;
import io.floci.gcp.services.cloudkms.model.StoredKeyRing;
import io.grpc.stub.StreamObserver;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import org.jboss.logging.Logger;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.Set;
import java.util.zip.CRC32C;

public class CloudKmsController extends KeyManagementServiceGrpc.KeyManagementServiceImplBase {

    private static final Logger LOG = Logger.getLogger(CloudKmsController.class);

    private final CloudKmsService service;

    CloudKmsController(CloudKmsService service) {
        this.service = service;
    }

    // ── KeyRings ─────────────────────────────────────────────────────────────

    @Override
    public void createKeyRing(CreateKeyRingRequest request, StreamObserver<KeyRing> responseObserver) {
        LOG.infof("createKeyRing parent=%s keyRingId=%s", request.getParent(), request.getKeyRingId());
        try {
            StoredKeyRing stored = service.createKeyRing(request.getParent(), request.getKeyRingId());
            responseObserver.onNext(toProto(stored));
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void getKeyRing(GetKeyRingRequest request, StreamObserver<KeyRing> responseObserver) {
        try {
            responseObserver.onNext(toProto(service.getKeyRing(request.getName())));
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void listKeyRings(ListKeyRingsRequest request, StreamObserver<ListKeyRingsResponse> responseObserver) {
        try {
            List<StoredKeyRing> all = service.listKeyRings(request.getParent());
            PageToken.Page<StoredKeyRing> page = PageToken.paginate(all, request.getPageSize(), request.getPageToken());
            ListKeyRingsResponse.Builder response = ListKeyRingsResponse.newBuilder().setTotalSize(all.size());
            for (StoredKeyRing kr : page.items()) {
                response.addKeyRings(toProto(kr));
            }
            if (page.nextPageToken() != null) {
                response.setNextPageToken(page.nextPageToken());
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    // ── CryptoKeys ───────────────────────────────────────────────────────────

    @Override
    public void createCryptoKey(CreateCryptoKeyRequest request, StreamObserver<CryptoKey> responseObserver) {
        LOG.infof("createCryptoKey parent=%s cryptoKeyId=%s", request.getParent(), request.getCryptoKeyId());
        try {
            CryptoKey input = request.getCryptoKey();
            String algorithm = input.getVersionTemplate().getAlgorithm().name();
            StoredCryptoKey stored = service.createCryptoKey(request.getParent(), request.getCryptoKeyId(),
                    input.getPurpose().name(), algorithm, request.getSkipInitialVersionCreation(),
                    cryptoKeyFields(input), input.getImportOnly());
            responseObserver.onNext(toProto(stored));
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void getCryptoKey(GetCryptoKeyRequest request, StreamObserver<CryptoKey> responseObserver) {
        try {
            responseObserver.onNext(toProto(service.getCryptoKey(request.getName())));
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void listCryptoKeys(ListCryptoKeysRequest request, StreamObserver<ListCryptoKeysResponse> responseObserver) {
        try {
            List<StoredCryptoKey> all = service.listCryptoKeys(request.getParent());
            PageToken.Page<StoredCryptoKey> page = PageToken.paginate(all, request.getPageSize(), request.getPageToken());
            ListCryptoKeysResponse.Builder response = ListCryptoKeysResponse.newBuilder().setTotalSize(all.size());
            for (StoredCryptoKey k : page.items()) {
                response.addCryptoKeys(toProto(k));
            }
            if (page.nextPageToken() != null) {
                response.setNextPageToken(page.nextPageToken());
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void updateCryptoKey(UpdateCryptoKeyRequest request, StreamObserver<CryptoKey> responseObserver) {
        try {
            CryptoKey input = request.getCryptoKey();
            String versionTemplateAlgorithm = input.hasVersionTemplate()
                    ? input.getVersionTemplate().getAlgorithm().name() : null;
            Set<String> paths = request.hasUpdateMask()
                    ? CloudKmsService.snakeCasePaths(request.getUpdateMask().getPathsList())
                    : presentFields(input);
            StoredCryptoKey stored = service.updateCryptoKey(input.getName(), cryptoKeyFields(input),
                    versionTemplateAlgorithm, paths);
            responseObserver.onNext(toProto(stored));
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void updateCryptoKeyPrimaryVersion(UpdateCryptoKeyPrimaryVersionRequest request,
            StreamObserver<CryptoKey> responseObserver) {
        try {
            StoredCryptoKey stored = service.updateCryptoKeyPrimaryVersion(
                    request.getName(), request.getCryptoKeyVersionId());
            responseObserver.onNext(toProto(stored));
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    // ── CryptoKeyVersions ────────────────────────────────────────────────────

    @Override
    public void createCryptoKeyVersion(CreateCryptoKeyVersionRequest request,
            StreamObserver<CryptoKeyVersion> responseObserver) {
        LOG.infof("createCryptoKeyVersion parent=%s", request.getParent());
        try {
            responseObserver.onNext(toProto(service.createCryptoKeyVersion(request.getParent())));
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void getCryptoKeyVersion(GetCryptoKeyVersionRequest request,
            StreamObserver<CryptoKeyVersion> responseObserver) {
        try {
            responseObserver.onNext(toProto(service.getCryptoKeyVersion(request.getName())));
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void listCryptoKeyVersions(ListCryptoKeyVersionsRequest request,
            StreamObserver<ListCryptoKeyVersionsResponse> responseObserver) {
        try {
            List<StoredCryptoKeyVersion> all = service.listCryptoKeyVersions(request.getParent());
            PageToken.Page<StoredCryptoKeyVersion> page =
                    PageToken.paginate(all, request.getPageSize(), request.getPageToken());
            ListCryptoKeyVersionsResponse.Builder response =
                    ListCryptoKeyVersionsResponse.newBuilder().setTotalSize(all.size());
            for (StoredCryptoKeyVersion v : page.items()) {
                response.addCryptoKeyVersions(toProto(v));
            }
            if (page.nextPageToken() != null) {
                response.setNextPageToken(page.nextPageToken());
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void updateCryptoKeyVersion(UpdateCryptoKeyVersionRequest request,
            StreamObserver<CryptoKeyVersion> responseObserver) {
        try {
            CryptoKeyVersion input = request.getCryptoKeyVersion();
            StoredCryptoKeyVersion stored =
                    service.updateCryptoKeyVersionState(input.getName(), input.getState().name());
            responseObserver.onNext(toProto(stored));
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void destroyCryptoKeyVersion(DestroyCryptoKeyVersionRequest request,
            StreamObserver<CryptoKeyVersion> responseObserver) {
        try {
            responseObserver.onNext(toProto(service.destroyCryptoKeyVersion(request.getName())));
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void restoreCryptoKeyVersion(RestoreCryptoKeyVersionRequest request,
            StreamObserver<CryptoKeyVersion> responseObserver) {
        try {
            responseObserver.onNext(toProto(service.restoreCryptoKeyVersion(request.getName())));
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void importCryptoKeyVersion(ImportCryptoKeyVersionRequest request,
            StreamObserver<CryptoKeyVersion> responseObserver) {
        LOG.infof("importCryptoKeyVersion parent=%s importJob=%s", request.getParent(), request.getImportJob());
        try {
            if (!request.getCryptoKeyVersion().isEmpty()) {
                throw GcpException.unimplemented("Re-importing into an existing CryptoKeyVersion is not supported");
            }
            ByteString wrappedKey = request.getWrappedKey().isEmpty() ? request.getRsaAesWrappedKey() : request.getWrappedKey();
            StoredCryptoKeyVersion stored = service.importCryptoKeyVersion(request.getParent(),
                    request.getAlgorithm().name(), request.getImportJob(), wrappedKey.toByteArray());
            responseObserver.onNext(toProto(stored));
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    // ── ImportJobs ───────────────────────────────────────────────────────────

    @Override
    public void createImportJob(CreateImportJobRequest request, StreamObserver<ImportJob> responseObserver) {
        LOG.infof("createImportJob parent=%s importJobId=%s", request.getParent(), request.getImportJobId());
        ImportJob input = request.getImportJob();
        // Generating a 3072/4096-bit wrapping key takes up to ~1.5 s: keep it off the event loop.
        runBlocking(responseObserver, () -> toProto(service.createImportJob(request.getParent(),
                request.getImportJobId(), input.getImportMethod().name(), input.getProtectionLevel().name())));
    }

    @Override
    public void getImportJob(GetImportJobRequest request, StreamObserver<ImportJob> responseObserver) {
        try {
            responseObserver.onNext(toProto(service.getImportJob(request.getName())));
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void listImportJobs(ListImportJobsRequest request, StreamObserver<ListImportJobsResponse> responseObserver) {
        try {
            List<StoredImportJob> all = service.listImportJobs(request.getParent());
            PageToken.Page<StoredImportJob> page = PageToken.paginate(all, request.getPageSize(), request.getPageToken());
            ListImportJobsResponse.Builder response = ListImportJobsResponse.newBuilder().setTotalSize(all.size());
            for (StoredImportJob job : page.items()) {
                response.addImportJobs(toProto(job));
            }
            if (page.nextPageToken() != null) {
                response.setNextPageToken(page.nextPageToken());
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    // ── Crypto operations ────────────────────────────────────────────────────

    @Override
    public void getPublicKey(GetPublicKeyRequest request, StreamObserver<PublicKey> responseObserver) {
        try {
            StoredCryptoKeyVersion version = service.getPublicKeyVersion(request.getName());
            String pem = KmsCrypto.toPem(version.getPublicKeyBase64());
            responseObserver.onNext(PublicKey.newBuilder()
                    .setPem(pem)
                    .setAlgorithm(CryptoKeyVersion.CryptoKeyVersionAlgorithm.valueOf(version.getAlgorithm()))
                    .setPemCrc32C(Int64Value.of(crc32c(pem.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                    .setName(version.getName())
                    .setProtectionLevel(ProtectionLevel.SOFTWARE)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void encrypt(EncryptRequest request, StreamObserver<EncryptResponse> responseObserver) {
        try {
            byte[] plaintext = request.getPlaintext().toByteArray();
            byte[] aad = request.getAdditionalAuthenticatedData().toByteArray();
            boolean verifiedPlaintext = verifyCrc32c(request.hasPlaintextCrc32C(),
                    request.getPlaintextCrc32C().getValue(), plaintext);
            boolean verifiedAad = verifyCrc32c(request.hasAdditionalAuthenticatedDataCrc32C(),
                    request.getAdditionalAuthenticatedDataCrc32C().getValue(), aad);
            CloudKmsService.EncryptResult result = service.encrypt(request.getName(), plaintext, aad);
            responseObserver.onNext(EncryptResponse.newBuilder()
                    .setName(result.versionName())
                    .setCiphertext(ByteString.copyFrom(result.ciphertext()))
                    .setCiphertextCrc32C(Int64Value.of(crc32c(result.ciphertext())))
                    .setVerifiedPlaintextCrc32C(verifiedPlaintext)
                    .setVerifiedAdditionalAuthenticatedDataCrc32C(verifiedAad)
                    .setProtectionLevel(ProtectionLevel.SOFTWARE)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void decrypt(DecryptRequest request, StreamObserver<DecryptResponse> responseObserver) {
        try {
            byte[] ciphertext = request.getCiphertext().toByteArray();
            byte[] aad = request.getAdditionalAuthenticatedData().toByteArray();
            verifyCrc32c(request.hasCiphertextCrc32C(), request.getCiphertextCrc32C().getValue(), ciphertext);
            verifyCrc32c(request.hasAdditionalAuthenticatedDataCrc32C(),
                    request.getAdditionalAuthenticatedDataCrc32C().getValue(), aad);
            CloudKmsService.DecryptResult result = service.decrypt(request.getName(), ciphertext, aad);
            responseObserver.onNext(DecryptResponse.newBuilder()
                    .setPlaintext(ByteString.copyFrom(result.plaintext()))
                    .setPlaintextCrc32C(Int64Value.of(crc32c(result.plaintext())))
                    .setUsedPrimary(result.usedPrimary())
                    .setProtectionLevel(ProtectionLevel.SOFTWARE)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void asymmetricSign(AsymmetricSignRequest request, StreamObserver<AsymmetricSignResponse> responseObserver) {
        try {
            byte[] digest = resolveDigest(request);
            boolean verifiedDigest = verifyCrc32c(request.hasDigestCrc32C(),
                    request.getDigestCrc32C().getValue(), digestBytes(request));
            boolean verifiedData = verifyCrc32c(request.hasDataCrc32C(),
                    request.getDataCrc32C().getValue(), request.getData().toByteArray());
            byte[] signature = service.asymmetricSign(request.getName(), digest);
            responseObserver.onNext(AsymmetricSignResponse.newBuilder()
                    .setSignature(ByteString.copyFrom(signature))
                    .setSignatureCrc32C(Int64Value.of(crc32c(signature)))
                    .setVerifiedDigestCrc32C(verifiedDigest)
                    .setVerifiedDataCrc32C(verifiedData)
                    .setName(request.getName())
                    .setProtectionLevel(ProtectionLevel.SOFTWARE)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void macSign(MacSignRequest request, StreamObserver<MacSignResponse> responseObserver) {
        try {
            byte[] data = request.getData().toByteArray();
            boolean verifiedData = verifyCrc32c(request.hasDataCrc32C(), request.getDataCrc32C().getValue(), data);
            byte[] mac = service.macSign(request.getName(), data);
            responseObserver.onNext(MacSignResponse.newBuilder()
                    .setName(request.getName())
                    .setMac(ByteString.copyFrom(mac))
                    .setMacCrc32C(Int64Value.of(crc32c(mac)))
                    .setVerifiedDataCrc32C(verifiedData)
                    .setProtectionLevel(ProtectionLevel.SOFTWARE)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void macVerify(MacVerifyRequest request, StreamObserver<MacVerifyResponse> responseObserver) {
        try {
            byte[] data = request.getData().toByteArray();
            byte[] mac = request.getMac().toByteArray();
            boolean verifiedData = verifyCrc32c(request.hasDataCrc32C(), request.getDataCrc32C().getValue(), data);
            boolean verifiedMac = verifyCrc32c(request.hasMacCrc32C(), request.getMacCrc32C().getValue(), mac);
            boolean success = service.macVerify(request.getName(), data, mac);
            responseObserver.onNext(MacVerifyResponse.newBuilder()
                    .setName(request.getName())
                    .setSuccess(success)
                    .setVerifiedDataCrc32C(verifiedData)
                    .setVerifiedMacCrc32C(verifiedMac)
                    // Repeats success so a client can detect a corrupted success field.
                    .setVerifiedSuccessIntegrity(success)
                    .setProtectionLevel(ProtectionLevel.SOFTWARE)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void asymmetricDecrypt(AsymmetricDecryptRequest request,
            StreamObserver<AsymmetricDecryptResponse> responseObserver) {
        try {
            byte[] ciphertext = request.getCiphertext().toByteArray();
            boolean verifiedCiphertext = verifyCrc32c(request.hasCiphertextCrc32C(),
                    request.getCiphertextCrc32C().getValue(), ciphertext);
            byte[] plaintext = service.asymmetricDecrypt(request.getName(), ciphertext);
            responseObserver.onNext(AsymmetricDecryptResponse.newBuilder()
                    .setPlaintext(ByteString.copyFrom(plaintext))
                    .setPlaintextCrc32C(Int64Value.of(crc32c(plaintext)))
                    .setVerifiedCiphertextCrc32C(verifiedCiphertext)
                    .setProtectionLevel(ProtectionLevel.SOFTWARE)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void generateRandomBytes(GenerateRandomBytesRequest request,
            StreamObserver<GenerateRandomBytesResponse> responseObserver) {
        try {
            byte[] data = service.generateRandomBytes(request.getLengthBytes());
            responseObserver.onNext(GenerateRandomBytesResponse.newBuilder()
                    .setData(ByteString.copyFrom(data))
                    .setDataCrc32C(Int64Value.of(crc32c(data)))
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    /**
     * Runs {@code work} on a worker thread and answers on the calling Vert.x context. Without a
     * context (no Vert.x caller) it runs inline.
     */
    private static <T> void runBlocking(StreamObserver<T> responseObserver, Callable<T> work) {
        Context context = Vertx.currentContext();
        if (context == null) {
            try {
                responseObserver.onNext(work.call());
                responseObserver.onCompleted();
            } catch (Exception e) {
                GcpGrpcController.grpcError(responseObserver, e);
            }
            return;
        }
        context.executeBlocking(work, false).onComplete(result -> {
            if (result.succeeded()) {
                responseObserver.onNext(result.result());
                responseObserver.onCompleted();
            } else {
                GcpGrpcController.grpcError(responseObserver, result.cause());
            }
        });
    }

    // ── proto mapping ────────────────────────────────────────────────────────

    private static CloudKmsService.CryptoKeyFields cryptoKeyFields(CryptoKey input) {
        return new CloudKmsService.CryptoKeyFields(
                input.hasRotationPeriod() ? Duration.ofSeconds(input.getRotationPeriod().getSeconds(),
                        input.getRotationPeriod().getNanos()) : null,
                input.hasNextRotationTime() ? Instant.ofEpochSecond(input.getNextRotationTime().getSeconds(),
                        input.getNextRotationTime().getNanos()) : null,
                input.hasDestroyScheduledDuration() ? Duration.ofSeconds(
                        input.getDestroyScheduledDuration().getSeconds(),
                        input.getDestroyScheduledDuration().getNanos()) : null,
                input.getLabelsMap());
    }

    /** The update paths implied by a request without an update mask: the fields it sets. */
    private static Set<String> presentFields(CryptoKey input) {
        Set<String> paths = new HashSet<>();
        if (input.getLabelsCount() > 0) {
            paths.add("labels");
        }
        if (input.hasVersionTemplate()) {
            paths.add("version_template");
        }
        if (input.hasRotationPeriod()) {
            paths.add("rotation_period");
        }
        if (input.hasNextRotationTime()) {
            paths.add("next_rotation_time");
        }
        return paths;
    }

    private static KeyRing toProto(StoredKeyRing stored) {
        return KeyRing.newBuilder()
                .setName(stored.getName())
                .setCreateTime(toTimestamp(stored.getCreateTime()))
                .build();
    }

    private CryptoKey toProto(StoredCryptoKey stored) {
        CryptoKey.Builder builder = CryptoKey.newBuilder()
                .setName(stored.getName())
                .setPurpose(CryptoKey.CryptoKeyPurpose.valueOf(stored.getPurpose()))
                .setCreateTime(toTimestamp(stored.getCreateTime()))
                .setVersionTemplate(CryptoKeyVersionTemplate.newBuilder()
                        .setProtectionLevel(ProtectionLevel.SOFTWARE)
                        .setAlgorithm(CryptoKeyVersion.CryptoKeyVersionAlgorithm.valueOf(stored.getAlgorithm()))
                        .build())
                .setImportOnly(stored.isImportOnly());
        if (stored.getLabels() != null && !stored.getLabels().isEmpty()) {
            builder.putAllLabels(stored.getLabels());
        }
        if (stored.getNextRotationTime() != null) {
            builder.setNextRotationTime(toTimestamp(stored.getNextRotationTime()));
        }
        if (stored.getRotationPeriod() != null) {
            builder.setRotationPeriod(Durations.parseUnchecked(stored.getRotationPeriod()));
        }
        builder.setDestroyScheduledDuration(
                Durations.parseUnchecked(CloudKmsService.destroyScheduledDurationOf(stored)));
        if (stored.getPrimaryVersion() != null) {
            service.getCryptoKeyVersionOptional(stored.getName() + "/cryptoKeyVersions/" + stored.getPrimaryVersion())
                    .ifPresent(v -> builder.setPrimary(toProto(v)));
        }
        return builder.build();
    }

    private static CryptoKeyVersion toProto(StoredCryptoKeyVersion stored) {
        CryptoKeyVersion.Builder builder = CryptoKeyVersion.newBuilder()
                .setName(stored.getName())
                .setState(CryptoKeyVersion.CryptoKeyVersionState.valueOf(stored.getState()))
                .setProtectionLevel(ProtectionLevel.SOFTWARE)
                .setAlgorithm(CryptoKeyVersion.CryptoKeyVersionAlgorithm.valueOf(stored.getAlgorithm()))
                .setCreateTime(toTimestamp(stored.getCreateTime()));
        if (stored.getGenerateTime() != null) {
            builder.setGenerateTime(toTimestamp(stored.getGenerateTime()));
        }
        if (stored.getDestroyTime() != null) {
            builder.setDestroyTime(toTimestamp(stored.getDestroyTime()));
        }
        if (stored.getImportJob() != null) {
            builder.setImportJob(stored.getImportJob());
        }
        if (stored.getImportTime() != null) {
            builder.setImportTime(toTimestamp(stored.getImportTime()));
        }
        if (stored.getImportFailureReason() != null) {
            builder.setImportFailureReason(stored.getImportFailureReason());
        }
        return builder.build();
    }

    /** The public key is only returned while the job is ACTIVE; expire_event_time only once it is EXPIRED. */
    private static ImportJob toProto(StoredImportJob stored) {
        String state = CloudKmsService.importJobState(stored);
        ImportJob.Builder job = ImportJob.newBuilder()
                .setName(stored.getName())
                .setImportMethod(ImportJob.ImportMethod.valueOf(stored.getImportMethod()))
                .setProtectionLevel(ProtectionLevel.valueOf(stored.getProtectionLevel()))
                .setCreateTime(toTimestamp(stored.getCreateTime()))
                .setGenerateTime(toTimestamp(stored.getGenerateTime()))
                .setExpireTime(toTimestamp(stored.getExpireTime()))
                .setState(ImportJob.ImportJobState.valueOf(state));
        if ("ACTIVE".equals(state)) {
            job.setPublicKey(ImportJob.WrappingPublicKey.newBuilder()
                    .setPem(KmsCrypto.toPem(stored.getPublicKeyBase64()))
                    .build());
        } else if ("EXPIRED".equals(state)) {
            job.setExpireEventTime(toTimestamp(stored.getExpireTime()));
        }
        return job.build();
    }

    private static Timestamp toTimestamp(String isoTime) {
        if (isoTime == null) {
            return Timestamp.getDefaultInstance();
        }
        try {
            Instant instant = Instant.parse(isoTime);
            return Timestamp.newBuilder()
                    .setSeconds(instant.getEpochSecond())
                    .setNanos(instant.getNano())
                    .build();
        } catch (Exception e) {
            return Timestamp.getDefaultInstance();
        }
    }

    // ── CRC32C / digest helpers ──────────────────────────────────────────────

    private static long crc32c(byte[] data) {
        CRC32C crc = new CRC32C();
        crc.update(data);
        return crc.getValue();
    }

    private static boolean verifyCrc32c(boolean provided, long expected, byte[] data) {
        if (!provided) {
            return false;
        }
        if (crc32c(data) != expected) {
            throw GcpException.invalidArgument("Checksum verification failed");
        }
        return true;
    }

    private static byte[] digestBytes(AsymmetricSignRequest request) {
        return request.getDigest().getSha256().toByteArray();
    }

    private static byte[] resolveDigest(AsymmetricSignRequest request) {
        if (request.getDigest().getDigestCase() != Digest.DigestCase.DIGEST_NOT_SET && !request.getData().isEmpty()) {
            throw GcpException.invalidArgument("Only one of digest or data may be supplied for AsymmetricSign");
        }
        ByteString sha256 = request.getDigest().getSha256();
        if (!sha256.isEmpty()) {
            return sha256.toByteArray();
        }
        if (!request.getData().isEmpty()) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(request.getData().toByteArray());
            } catch (Exception e) {
                throw GcpException.internal("Digest computation failed: " + e.getMessage());
            }
        }
        throw GcpException.invalidArgument("A SHA-256 digest or data is required for AsymmetricSign");
    }
}
