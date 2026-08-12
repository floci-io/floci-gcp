package tests

import (
	"bytes"
	"context"
	"io"
	"testing"

	"floci-gcp-sdk-test-go/internal/testutil"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestGCS(t *testing.T) {
	ctx := context.Background()
	client := testutil.StorageClient(ctx)
	defer client.Close()

	projectID := testutil.ProjectID()
	bucketName := uniqueName("go-gcs")
	objectName := "test-object.txt"
	content := "Hello, GCP Cloud Storage from Go!"

	t.Cleanup(func() {
		bkt := client.Bucket(bucketName)
		bkt.Object(objectName).Delete(ctx)
		bkt.Object("copy-" + objectName).Delete(ctx)
		bkt.Object("meta-" + objectName).Delete(ctx)
		bkt.Delete(ctx)
	})

	t.Run("CreateBucket", func(t *testing.T) {
		err := client.Bucket(bucketName).Create(ctx, projectID, nil)
		require.NoError(t, err)
	})

	t.Run("ListBuckets", func(t *testing.T) {
		it := client.Buckets(ctx, projectID)
		found := false
		for {
			attrs, err := it.Next()
			if err != nil {
				break
			}
			if attrs.Name == bucketName {
				found = true
				break
			}
		}
		assert.True(t, found, "created bucket should appear in list")
	})

	t.Run("UploadObject", func(t *testing.T) {
		w := client.Bucket(bucketName).Object(objectName).NewWriter(ctx)
		w.ContentType = "text/plain"
		_, err := io.WriteString(w, content)
		require.NoError(t, err)
		require.NoError(t, w.Close())
	})

	t.Run("DownloadObject", func(t *testing.T) {
		r, err := client.Bucket(bucketName).Object(objectName).NewReader(ctx)
		require.NoError(t, err)
		defer r.Close()

		var buf bytes.Buffer
		_, err = buf.ReadFrom(r)
		require.NoError(t, err)
		assert.Equal(t, content, buf.String())
	})

	t.Run("ObjectMetadata", func(t *testing.T) {
		attrs, err := client.Bucket(bucketName).Object(objectName).Attrs(ctx)
		require.NoError(t, err)
		assert.Equal(t, objectName, attrs.Name)
		assert.Equal(t, "text/plain", attrs.ContentType)
		assert.Equal(t, int64(len(content)), attrs.Size)
	})

	t.Run("ObjectCustomMetadata", func(t *testing.T) {
		metadata := map[string]string{"originalname": "test.txt", "reviewer": "jane"}
		obj := client.Bucket(bucketName).Object("meta-" + objectName)

		w := obj.NewWriter(ctx)
		w.ContentType = "text/plain"
		w.Metadata = metadata
		_, err := io.WriteString(w, content)
		require.NoError(t, err)
		require.NoError(t, w.Close())

		attrs, err := obj.Attrs(ctx)
		require.NoError(t, err)
		assert.Equal(t, metadata, attrs.Metadata)

		r, err := obj.NewReader(ctx)
		require.NoError(t, err)
		defer r.Close()
		// net/http canonicalizes the x-goog-meta-* header names, so the
		// HTTP transport surfaces reader metadata keys in title case.
		assert.Equal(t, map[string]string{"Originalname": "test.txt", "Reviewer": "jane"}, r.Metadata())

		require.NoError(t, obj.Delete(ctx))
	})

	t.Run("ListObjects", func(t *testing.T) {
		it := client.Bucket(bucketName).Objects(ctx, nil)
		found := false
		for {
			attrs, err := it.Next()
			if err != nil {
				break
			}
			if attrs.Name == objectName {
				found = true
				break
			}
		}
		assert.True(t, found, "uploaded object should appear in list")
	})

	t.Run("CopyObject", func(t *testing.T) {
		src := client.Bucket(bucketName).Object(objectName)
		dst := client.Bucket(bucketName).Object("copy-" + objectName)
		_, err := dst.CopierFrom(src).Run(ctx)
		require.NoError(t, err)

		r, err := dst.NewReader(ctx)
		require.NoError(t, err)
		defer r.Close()

		var buf bytes.Buffer
		_, err = buf.ReadFrom(r)
		require.NoError(t, err)
		assert.Equal(t, content, buf.String())
	})

	t.Run("DeleteObject", func(t *testing.T) {
		err := client.Bucket(bucketName).Object(objectName).Delete(ctx)
		require.NoError(t, err)
	})

	t.Run("DeleteBucket", func(t *testing.T) {
		client.Bucket(bucketName).Object("copy-" + objectName).Delete(ctx)
		err := client.Bucket(bucketName).Delete(ctx)
		require.NoError(t, err)
	})
}
