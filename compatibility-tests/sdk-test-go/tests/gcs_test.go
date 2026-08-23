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

	t.Run("DownloadReaderAttrs", func(t *testing.T) {
		obj := client.Bucket(bucketName).Object(objectName)
		attrs, err := obj.Attrs(ctx)
		require.NoError(t, err)

		r, err := obj.NewReader(ctx)
		require.NoError(t, err)
		defer r.Close()

		// The reader attrs come from the x-goog-* headers of the media
		// response, not from the object resource. Without them the values
		// silently degrade here, and the Rust SDK fails every read.
		assert.Equal(t, attrs.Generation, r.Attrs.Generation)
		assert.Equal(t, attrs.Metageneration, r.Attrs.Metageneration)
		assert.Equal(t, int64(len(content)), r.Attrs.Size)
		assert.Equal(t, attrs.CRC32C, r.Attrs.CRC32C)
		assert.NotZero(t, r.Attrs.CRC32C)

		// A full read verifies the payload against the crc32c announced in
		// x-goog-hash.
		_, err = io.Copy(io.Discard, r)
		require.NoError(t, err)
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

	t.Run("ComposeObject", func(t *testing.T) {
		bucket := client.Bucket(bucketName)
		part1 := bucket.Object("compose-part1")
		part2 := bucket.Object("compose-part2")
		w := part1.NewWriter(ctx)
		_, err := io.WriteString(w, "hello ")
		require.NoError(t, err)
		require.NoError(t, w.Close())
		w = part2.NewWriter(ctx)
		_, err = io.WriteString(w, "world")
		require.NoError(t, err)
		require.NoError(t, w.Close())

		composed := bucket.Object("composed")
		attrs, err := composed.ComposerFrom(part1, part2).Run(ctx)
		require.NoError(t, err)

		// Real GCS gives composite objects a componentCount and no MD5, so
		// downloads validate crc32c only.
		assert.Empty(t, attrs.MD5)
		assert.EqualValues(t, 2, attrs.ComponentCount)
		assert.NotZero(t, attrs.CRC32C)

		r, err := composed.NewReader(ctx)
		require.NoError(t, err)
		defer r.Close()
		var buf bytes.Buffer
		_, err = buf.ReadFrom(r)
		require.NoError(t, err)
		assert.Equal(t, "hello world", buf.String())

		require.NoError(t, part1.Delete(ctx))
		require.NoError(t, part2.Delete(ctx))
		require.NoError(t, composed.Delete(ctx))
	})

	// The Go SDK sends resumable chunks as POST requests to the session URL, so a
	// payload larger than ChunkSize exercises multi-chunk session tracking.
	t.Run("ChunkedResumableUpload", func(t *testing.T) {
		chunkedName := "chunked-" + objectName
		obj := client.Bucket(bucketName).Object(chunkedName)
		payload := randomBytes(1024 * 1024)

		w := obj.NewWriter(ctx)
		w.ChunkSize = 256 * 1024
		_, err := w.Write(payload)
		require.NoError(t, err)
		require.NoError(t, w.Close())

		attrs, err := obj.Attrs(ctx)
		require.NoError(t, err)
		assert.EqualValues(t, len(payload), attrs.Size)

		r, err := obj.NewReader(ctx)
		require.NoError(t, err)
		defer r.Close()

		var buf bytes.Buffer
		_, err = buf.ReadFrom(r)
		require.NoError(t, err)
		assert.Equal(t, payload, buf.Bytes())

		require.NoError(t, obj.Delete(ctx))
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
