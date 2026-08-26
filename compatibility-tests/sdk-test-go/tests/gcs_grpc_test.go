package tests

import (
	"bytes"
	"context"
	"io"
	"testing"

	"floci-gcp-sdk-test-go/internal/testutil"

	"cloud.google.com/go/storage"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"google.golang.org/api/iterator"
)

func TestGCSGRPC(t *testing.T) {
	ctx := context.Background()
	client := testutil.StorageGRPCClient(ctx)
	defer client.Close()

	bucketName := uniqueName("go-gcs-grpc")
	directName := "direct.txt"
	resumableName := "resumable.bin"
	composedName := "composed.bin"
	directPayload := []byte("go bidi grpc payload")
	resumablePayload := bytes.Repeat([]byte("resumable-grpc-"), 24*1024)
	bucket := client.Bucket(bucketName)

	t.Cleanup(func() {
		for _, name := range []string{directName, resumableName, composedName} {
			_ = bucket.Object(name).Delete(ctx)
		}
		_ = bucket.Delete(ctx)
	})

	require.NoError(t, bucket.Create(ctx, testutil.ProjectID(), &storage.BucketAttrs{
		Location: "US",
		Labels:   map[string]string{"transport": "grpc"},
	}))

	bucketAttrs, err := bucket.Attrs(ctx)
	require.NoError(t, err)
	assert.Equal(t, "grpc", bucketAttrs.Labels["transport"])

	direct := bucket.Object(directName)
	directWriter := direct.NewWriter(ctx)
	directWriter.ChunkSize = 0
	directWriter.ContentType = "text/plain"
	directWriter.Metadata = map[string]string{"mode": "bidi"}
	_, err = directWriter.Write(directPayload)
	require.NoError(t, err)
	require.NoError(t, directWriter.Close())

	resumable := bucket.Object(resumableName)
	resumableWriter := resumable.NewWriter(ctx)
	resumableWriter.ChunkSize = 256 * 1024
	_, err = resumableWriter.Write(resumablePayload)
	require.NoError(t, err)
	require.NoError(t, resumableWriter.Close())

	reader, err := resumable.NewReader(ctx)
	require.NoError(t, err)
	readBack, err := io.ReadAll(reader)
	require.NoError(t, err)
	require.NoError(t, reader.Close())
	assert.Equal(t, resumablePayload, readBack)

	updated, err := direct.Update(ctx, storage.ObjectAttrsToUpdate{
		Metadata: map[string]string{"updated": "true"},
	})
	require.NoError(t, err)
	assert.Equal(t, "true", updated.Metadata["updated"])

	composed := bucket.Object(composedName)
	composedAttrs, err := composed.ComposerFrom(direct, resumable).Run(ctx)
	require.NoError(t, err)
	assert.EqualValues(t, 2, composedAttrs.ComponentCount)

	found := false
	objects := bucket.Objects(ctx, nil)
	for {
		attrs, nextErr := objects.Next()
		if nextErr == iterator.Done {
			break
		}
		require.NoError(t, nextErr)
		if attrs.Name == composedName {
			found = true
		}
	}
	assert.True(t, found)
}
