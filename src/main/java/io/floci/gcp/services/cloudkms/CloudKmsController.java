package io.floci.gcp.services.cloudkms;

import com.google.cloud.kms.v1.*;
import com.google.protobuf.ByteString;
import com.google.protobuf.Int64Value;
import com.google.protobuf.Timestamp;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.GcpGrpcController;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.services.cloudkms.model.StoredCryptoKey;
import io.floci.gcp.services.cloudkms.model.StoredCryptoKeyVersion;
import io.floci.gcp.services.cloudkms.model.StoredKeyRing;
import io.grpc.stub.StreamObserver;
import org.jboss.logging.Logger;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
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
                    input.getPurpose().name(), algorithm, request.getSkipInitialVersionCreation());
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
            StoredCryptoKey stored = service.updateCryptoKey(input.getName(),
                    input.getLabelsCount() > 0 ? input.getLabelsMap() : null, versionTemplateAlgorithm);
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
            byte[] signature = service.asymmetricSign(request.getName(), digest);
            responseObserver.onNext(AsymmetricSignResponse.newBuilder()
                    .setSignature(ByteString.copyFrom(signature))
                    .setSignatureCrc32C(Int64Value.of(crc32c(signature)))
                    .setVerifiedDigestCrc32C(verifiedDigest)
                    .setName(request.getName())
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

    // ── proto mapping ────────────────────────────────────────────────────────

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
                        .build());
        if (stored.getLabels() != null && !stored.getLabels().isEmpty()) {
            builder.putAllLabels(stored.getLabels());
        }
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
        return builder.build();
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
