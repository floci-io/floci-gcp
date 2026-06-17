package tests

import (
	"context"
	"fmt"
	"testing"

	"floci-gcp-sdk-test-go/internal/testutil"

	loggingpb "cloud.google.com/go/logging/apiv2/loggingpb"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	mrpb "google.golang.org/genproto/googleapis/api/monitoredres"
	ltype "google.golang.org/genproto/googleapis/logging/type"
	"google.golang.org/api/iterator"
)

func TestLogging(t *testing.T) {
	ctx := context.Background()
	client := testutil.LoggingClient(ctx)
	defer client.Close()

	project := testutil.ProjectID()
	parent := fmt.Sprintf("projects/%s", project)
	logName := fmt.Sprintf("projects/%s/logs/%s", project, uniqueName("go-log"))

	entry := func(severity ltype.LogSeverity, text string) *loggingpb.LogEntry {
		return &loggingpb.LogEntry{
			LogName:  logName,
			Resource: &mrpb.MonitoredResource{Type: "global"},
			Severity: severity,
			Payload:  &loggingpb.LogEntry_TextPayload{TextPayload: text},
		}
	}

	listEntries := func(filter string) []*loggingpb.LogEntry {
		it := client.ListLogEntries(ctx, &loggingpb.ListLogEntriesRequest{
			ResourceNames: []string{parent},
			Filter:        filter,
		})
		var entries []*loggingpb.LogEntry
		for {
			e, err := it.Next()
			if err == iterator.Done {
				break
			}
			require.NoError(t, err)
			entries = append(entries, e)
		}
		return entries
	}

	t.Cleanup(func() {
		client.DeleteLog(ctx, &loggingpb.DeleteLogRequest{LogName: logName})
	})

	_, err := client.WriteLogEntries(ctx, &loggingpb.WriteLogEntriesRequest{
		Entries: []*loggingpb.LogEntry{
			entry(ltype.LogSeverity_INFO, "info-message"),
			entry(ltype.LogSeverity_ERROR, "error-message"),
		},
	})
	require.NoError(t, err)

	t.Run("ListAllEntries", func(t *testing.T) {
		entries := listEntries(fmt.Sprintf("logName=%q", logName))
		assert.Len(t, entries, 2)
		for _, e := range entries {
			assert.Equal(t, "global", e.GetResource().GetType())
		}
	})

	t.Run("ListWithSeverityFilter", func(t *testing.T) {
		entries := listEntries(fmt.Sprintf("logName=%q AND severity>=WARNING", logName))
		require.Len(t, entries, 1)
		assert.Equal(t, "error-message", entries[0].GetTextPayload())
		assert.Equal(t, ltype.LogSeverity_ERROR, entries[0].GetSeverity())
	})

	t.Run("ListLogs", func(t *testing.T) {
		it := client.ListLogs(ctx, &loggingpb.ListLogsRequest{Parent: parent})
		found := false
		for {
			name, err := it.Next()
			if err == iterator.Done {
				break
			}
			require.NoError(t, err)
			if name == logName {
				found = true
			}
		}
		assert.True(t, found, "written log should appear in ListLogs")
	})
}
