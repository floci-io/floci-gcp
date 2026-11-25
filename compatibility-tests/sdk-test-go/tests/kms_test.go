package tests

import (
	"context"
	"crypto/ecdsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/pem"
	"fmt"
	"testing"

	"floci-gcp-sdk-test-go/internal/testutil"

	kmspb "cloud.google.com/go/kms/apiv1/kmspb"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"google.golang.org/protobuf/types/known/fieldmaskpb"
)

const kmsLocation = "us-central1"

func TestKMS(t *testing.T) {
	ctx := context.Background()
	client := testutil.KMSClient(ctx)
	defer client.Close()

	locationPath := testutil.KMSLocation(kmsLocation)
	keyRingID := uniqueName("go-kr")
	keyRing := fmt.Sprintf("%s/keyRings/%s", locationPath, keyRingID)

	_, err := client.CreateKeyRing(ctx, &kmspb.CreateKeyRingRequest{
		Parent:    locationPath,
		KeyRingId: keyRingID,
	})
	require.NoError(t, err)

	createKey := func(t *testing.T, keyID string, purpose kmspb.CryptoKey_CryptoKeyPurpose,
		algorithm kmspb.CryptoKeyVersion_CryptoKeyVersionAlgorithm) *kmspb.CryptoKey {
		ck := &kmspb.CryptoKey{Purpose: purpose}
		if algorithm != kmspb.CryptoKeyVersion_CRYPTO_KEY_VERSION_ALGORITHM_UNSPECIFIED {
			ck.VersionTemplate = &kmspb.CryptoKeyVersionTemplate{Algorithm: algorithm}
		}
		key, err := client.CreateCryptoKey(ctx, &kmspb.CreateCryptoKeyRequest{
			Parent:      keyRing,
			CryptoKeyId: keyID,
			CryptoKey:   ck,
		})
		require.NoError(t, err)
		return key
	}

	t.Run("SymmetricEncryptDecrypt", func(t *testing.T) {
		key := createKey(t, uniqueName("sym"), kmspb.CryptoKey_ENCRYPT_DECRYPT,
			kmspb.CryptoKeyVersion_CRYPTO_KEY_VERSION_ALGORITHM_UNSPECIFIED)

		plaintext := []byte("envelope-encryption-payload")
		enc, err := client.Encrypt(ctx, &kmspb.EncryptRequest{Name: key.Name, Plaintext: plaintext})
		require.NoError(t, err)
		assert.NotEqual(t, plaintext, enc.Ciphertext)

		dec, err := client.Decrypt(ctx, &kmspb.DecryptRequest{Name: key.Name, Ciphertext: enc.Ciphertext})
		require.NoError(t, err)
		assert.Equal(t, plaintext, dec.Plaintext)
		assert.True(t, dec.UsedPrimary)
	})

	t.Run("DecryptWithWrongKeyFails", func(t *testing.T) {
		k1 := createKey(t, uniqueName("a"), kmspb.CryptoKey_ENCRYPT_DECRYPT,
			kmspb.CryptoKeyVersion_CRYPTO_KEY_VERSION_ALGORITHM_UNSPECIFIED)
		k2 := createKey(t, uniqueName("b"), kmspb.CryptoKey_ENCRYPT_DECRYPT,
			kmspb.CryptoKeyVersion_CRYPTO_KEY_VERSION_ALGORITHM_UNSPECIFIED)

		enc, err := client.Encrypt(ctx, &kmspb.EncryptRequest{Name: k1.Name, Plaintext: []byte("secret")})
		require.NoError(t, err)

		_, err = client.Decrypt(ctx, &kmspb.DecryptRequest{Name: k2.Name, Ciphertext: enc.Ciphertext})
		assert.Error(t, err)
	})

	t.Run("ECSignAndVerify", func(t *testing.T) {
		key := createKey(t, uniqueName("ec"), kmspb.CryptoKey_ASYMMETRIC_SIGN,
			kmspb.CryptoKeyVersion_EC_SIGN_P256_SHA256)
		versionName := key.Name + "/cryptoKeyVersions/1"

		data := []byte("ecdsa-message")
		digest := sha256.Sum256(data)
		signResp, err := client.AsymmetricSign(ctx, &kmspb.AsymmetricSignRequest{
			Name:   versionName,
			Digest: &kmspb.Digest{Digest: &kmspb.Digest_Sha256{Sha256: digest[:]}},
		})
		require.NoError(t, err)

		pubResp, err := client.GetPublicKey(ctx, &kmspb.GetPublicKeyRequest{Name: versionName})
		require.NoError(t, err)

		block, _ := pem.Decode([]byte(pubResp.Pem))
		require.NotNil(t, block)
		pub, err := x509.ParsePKIXPublicKey(block.Bytes)
		require.NoError(t, err)
		ecPub, ok := pub.(*ecdsa.PublicKey)
		require.True(t, ok)
		assert.True(t, ecdsa.VerifyASN1(ecPub, digest[:], signResp.Signature))
	})

	t.Run("GenerateRandomBytes", func(t *testing.T) {
		resp, err := client.GenerateRandomBytes(ctx, &kmspb.GenerateRandomBytesRequest{
			Location:        locationPath,
			LengthBytes:     32,
			ProtectionLevel: kmspb.ProtectionLevel_SOFTWARE,
		})
		require.NoError(t, err)
		assert.Len(t, resp.Data, 32)
	})
}

func TestKMSREST(t *testing.T) {
	ctx := context.Background()
	client := testutil.KMSRESTClient(ctx)
	defer client.Close()

	locationPath := testutil.KMSLocation(kmsLocation)
	keyRingID := uniqueName("go-rest-kr")
	keyRing := fmt.Sprintf("%s/keyRings/%s", locationPath, keyRingID)

	_, err := client.CreateKeyRing(ctx, &kmspb.CreateKeyRingRequest{
		Parent:    locationPath,
		KeyRingId: keyRingID,
	})
	require.NoError(t, err)

	key, err := client.CreateCryptoKey(ctx, &kmspb.CreateCryptoKeyRequest{
		Parent:      keyRing,
		CryptoKeyId: uniqueName("sym"),
		CryptoKey: &kmspb.CryptoKey{
			Purpose: kmspb.CryptoKey_ENCRYPT_DECRYPT,
			VersionTemplate: &kmspb.CryptoKeyVersionTemplate{
				Algorithm:       kmspb.CryptoKeyVersion_GOOGLE_SYMMETRIC_ENCRYPTION,
				ProtectionLevel: kmspb.ProtectionLevel_SOFTWARE,
			},
		},
	})
	require.NoError(t, err)
	assert.Equal(t, kmspb.CryptoKey_ENCRYPT_DECRYPT, key.Purpose)
	assert.Equal(t, kmspb.CryptoKeyVersion_GOOGLE_SYMMETRIC_ENCRYPTION, key.VersionTemplate.GetAlgorithm())

	t.Run("EncryptDecrypt", func(t *testing.T) {
		plaintext := []byte("rest-transport-payload")
		enc, err := client.Encrypt(ctx, &kmspb.EncryptRequest{Name: key.Name, Plaintext: plaintext})
		require.NoError(t, err)

		dec, err := client.Decrypt(ctx, &kmspb.DecryptRequest{Name: key.Name, Ciphertext: enc.Ciphertext})
		require.NoError(t, err)
		assert.Equal(t, plaintext, dec.Plaintext)
	})

	t.Run("DisableVersion", func(t *testing.T) {
		version, err := client.UpdateCryptoKeyVersion(ctx, &kmspb.UpdateCryptoKeyVersionRequest{
			CryptoKeyVersion: &kmspb.CryptoKeyVersion{
				Name:  key.Name + "/cryptoKeyVersions/1",
				State: kmspb.CryptoKeyVersion_DISABLED,
			},
			UpdateMask: &fieldmaskpb.FieldMask{Paths: []string{"state"}},
		})
		require.NoError(t, err)
		assert.Equal(t, kmspb.CryptoKeyVersion_DISABLED, version.State)
	})

	t.Run("CreateImportJob", func(t *testing.T) {
		job, err := client.CreateImportJob(ctx, &kmspb.CreateImportJobRequest{
			Parent:      keyRing,
			ImportJobId: uniqueName("job"),
			ImportJob: &kmspb.ImportJob{
				ImportMethod:    kmspb.ImportJob_RSA_OAEP_3072_SHA256_AES_256,
				ProtectionLevel: kmspb.ProtectionLevel_SOFTWARE,
			},
		})
		require.NoError(t, err)
		assert.Equal(t, kmspb.ImportJob_RSA_OAEP_3072_SHA256_AES_256, job.ImportMethod)
		assert.Equal(t, kmspb.ProtectionLevel_SOFTWARE, job.ProtectionLevel)
	})
}
