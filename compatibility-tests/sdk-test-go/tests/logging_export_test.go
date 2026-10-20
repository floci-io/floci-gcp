package tests

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strings"
	"testing"
	"time"

	"floci-gcp-sdk-test-go/internal/testutil"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"google.golang.org/api/iterator"
	loggingapi "google.golang.org/api/logging/v2"
	"google.golang.org/api/option"
)

func TestLoggingGCSExportRESTSDK(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	endpoint := os.Getenv("FLOCI_GCP_ENDPOINT")
	if endpoint == "" {
		endpoint = "http://localhost:4588"
	}
	logging, err := loggingapi.NewService(ctx, option.WithEndpoint(strings.TrimRight(endpoint, "/")+"/"), option.WithoutAuthentication())
	require.NoError(t, err)
	storage := testutil.StorageClient(ctx)
	defer storage.Close()
	id := uniqueName("rest-export")
	parent := "projects/" + testutil.ProjectID()
	log := parent + "/logs/" + id
	bucket := storage.Bucket(id)
	require.NoError(t, bucket.Create(ctx, testutil.ProjectID(), nil))
	defer func() {
		cleanup, stop := context.WithTimeout(context.Background(), 10*time.Second)
		defer stop()
		it := bucket.Objects(cleanup, nil)
		for {
			object, err := it.Next()
			if err == iterator.Done {
				break
			}
			if !assert.NoError(t, err) {
				break
			}
			assert.NoError(t, bucket.Object(object.Name).Delete(cleanup))
		}
		assert.NoError(t, bucket.Delete(cleanup))
	}()
	_, err = logging.Projects.Sinks.Create(parent, &loggingapi.LogSink{
		Name: id, Destination: "storage.googleapis.com/" + id,
		Filter:     fmt.Sprintf("logName=%q AND severity>=WARNING", log),
		Exclusions: []*loggingapi.LogExclusion{{Name: "noise", Filter: `labels.noise=true`}},
	}).UniqueWriterIdentity(true).Context(ctx).Do()
	require.NoError(t, err)
	defer func() {
		cleanup, stop := context.WithTimeout(context.Background(), 10*time.Second)
		defer stop()
		_, err := logging.Projects.Sinks.Delete(parent + "/sinks/" + id).Context(cleanup).Do()
		assert.NoError(t, err)
		_, err = logging.Projects.Logs.Delete(log).Context(cleanup).Do()
		assert.NoError(t, err)
	}()
	_, err = logging.Entries.Write(&loggingapi.WriteLogEntriesRequest{
		LogName: log, Resource: &loggingapi.MonitoredResource{Type: "global"},
		Entries: []*loggingapi.LogEntry{
			{Severity: "ERROR", TextPayload: "exported"},
			{Severity: "INFO", TextPayload: "filtered"},
			{Severity: "ERROR", TextPayload: "excluded", Labels: map[string]string{"noise": "true"}},
		},
	}).Context(ctx).Do()
	require.NoError(t, err)
	objects := bucket.Objects(ctx, nil)
	object, err := objects.Next()
	require.NoError(t, err)
	assert.True(t, strings.HasPrefix(object.Name, id+"/"))
	assert.Equal(t, "application/json", object.ContentType)
	_, err = objects.Next()
	require.Equal(t, iterator.Done, err)
	reader, err := bucket.Object(object.Name).NewReader(ctx)
	require.NoError(t, err)
	data, err := io.ReadAll(reader)
	require.NoError(t, err)
	require.NoError(t, reader.Close())
	var entries []struct {
		LogName          string `json:"logName"`
		TextPayload      string `json:"textPayload"`
		Severity         string `json:"severity"`
		InsertID         string `json:"insertId"`
		ReceiveTimestamp string `json:"receiveTimestamp"`
	}
	require.NoError(t, json.Unmarshal(data, &entries))
	require.Len(t, entries, 1)
	assert.Equal(t, log, entries[0].LogName)
	assert.Equal(t, "exported", entries[0].TextPayload)
	assert.Equal(t, "ERROR", entries[0].Severity)
	assert.NotEmpty(t, entries[0].InsertID)
	assert.NotEmpty(t, entries[0].ReceiveTimestamp)
}
