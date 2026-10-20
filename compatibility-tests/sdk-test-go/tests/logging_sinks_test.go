package tests

import (
	"context"
	"fmt"
	"os"
	"strings"
	"testing"
	"time"

	"floci-gcp-sdk-test-go/internal/testutil"

	logging "cloud.google.com/go/logging/apiv2"
	loggingpb "cloud.google.com/go/logging/apiv2/loggingpb"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"google.golang.org/api/googleapi"
	"google.golang.org/api/iterator"
	loggingapi "google.golang.org/api/logging/v2"
	"google.golang.org/api/option"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/types/known/fieldmaskpb"
)

// Exercises organization sink management through Google's discovery-based REST SDK.
func TestOrganizationSinkManagementRESTSDK(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	endpoint := os.Getenv("FLOCI_GCP_ENDPOINT")
	if endpoint == "" {
		endpoint = "http://localhost:4588"
	}
	service, err := loggingapi.NewService(ctx, option.WithEndpoint(strings.TrimRight(endpoint, "/")+"/"), option.WithoutAuthentication())
	require.NoError(t, err)
	parent := "organizations/123456789012"
	sinkID := uniqueName("rest-sink")
	name := parent + "/sinks/" + sinkID
	destination := "pubsub.googleapis.com/projects/" + testutil.ProjectID() + "/topics/audit-logs"
	filter := "logName=\"organizations/123456789012/logs/cloudaudit.googleapis.com%2Factivity\"\n" +
		"protoPayload.serviceName=\"admin.googleapis.com\"\nprotoPayload.methodName:\"Group\""
	sink := &loggingapi.LogSink{Name: sinkID, Destination: destination, Filter: filter}

	_, err = service.Organizations.Sinks.Get(name).Context(ctx).Do()
	assertLoggingAPIError(t, err, 404)
	created, err := service.Organizations.Sinks.Create(parent, sink).UniqueWriterIdentity(true).Context(ctx).Do()
	require.NoError(t, err)
	require.Equal(t, sinkID, created.Name)
	require.NotEmpty(t, created.WriterIdentity)
	t.Cleanup(func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, _ = service.Organizations.Sinks.Delete(name).Context(cleanup).Do()
	})

	got, err := service.Organizations.Sinks.Get(name).Context(ctx).Do()
	require.NoError(t, err)
	assert.Equal(t, destination, got.Destination)
	assert.Equal(t, filter, got.Filter)
	assert.Equal(t, created.WriterIdentity, got.WriterIdentity)
	_, err = service.Organizations.Sinks.Update(name, &loggingapi.LogSink{Description: "updated without identity flag"}).
		UpdateMask("description").Context(ctx).Do()
	require.NoError(t, err)
	got, err = service.Organizations.Sinks.Get(name).Context(ctx).Do()
	require.NoError(t, err)
	assert.Equal(t, filter, got.Filter)
	assert.Equal(t, "updated without identity flag", got.Description)
	assert.Equal(t, created.WriterIdentity, got.WriterIdentity)

	listed, err := service.Organizations.Sinks.List(parent).Context(ctx).Do()
	require.NoError(t, err)
	found := false
	for _, candidate := range listed.Sinks {
		if candidate.Name == sinkID {
			assert.Equal(t, created.WriterIdentity, candidate.WriterIdentity)
			found = true
		}
	}
	assert.True(t, found)

	// Identical parent IDs and sink IDs remain distinct between projects and organizations.
	projectParent := "projects/123456789012"
	projectName := projectParent + "/sinks/" + sinkID
	_, err = service.Projects.Sinks.Create(projectParent, &loggingapi.LogSink{
		Name: sinkID + "-legacy", Destination: "pubsub.googleapis.com/projects/other/topics/logs",
	}).Context(ctx).Do()
	assertLoggingAPIError(t, err, 400)
	projectDestination := "storage.googleapis.com/" + uniqueName("sink-bucket")
	projectConfig := &loggingapi.LogSink{Name: sinkID, Destination: projectDestination, Filter: filter}
	projectSink, err := service.Projects.Sinks.Create(projectParent, projectConfig).UniqueWriterIdentity(true).Context(ctx).Do()
	require.NoError(t, err)
	t.Cleanup(func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, _ = service.Projects.Sinks.Delete(projectName).Context(cleanup).Do()
	})
	assert.NotEqual(t, created.WriterIdentity, projectSink.WriterIdentity)
	_, err = service.Organizations.Sinks.Get("organizations/987654321012/sinks/" + sinkID).Context(ctx).Do()
	assertLoggingAPIError(t, err, 404)

	want := &loggingapi.LogSink{Name: sinkID, Destination: destination + "-repaired", Filter: "severity>=ERROR"}
	updated, err := service.Organizations.Sinks.Update(name, want).UniqueWriterIdentity(true).
		UpdateMask("destination,filter").Context(ctx).Do()
	require.NoError(t, err)
	assert.Equal(t, want.Destination, updated.Destination)
	assert.Equal(t, want.Filter, updated.Filter)
	assert.Equal(t, created.WriterIdentity, updated.WriterIdentity)

	_, err = service.Organizations.Sinks.Update(name, sink).UniqueWriterIdentity(true).
		UpdateMask("destination,writerIdentity").Context(ctx).Do()
	assertLoggingAPIError(t, err, 400)
	unchanged, err := service.Organizations.Sinks.Get(name).Context(ctx).Do()
	require.NoError(t, err)
	assert.Equal(t, want.Destination, unchanged.Destination)
	assert.Equal(t, want.Filter, unchanged.Filter)
	assert.Equal(t, created.WriterIdentity, unchanged.WriterIdentity)
	projectReadback, err := service.Projects.Sinks.Get(projectName).Context(ctx).Do()
	require.NoError(t, err)
	assert.Equal(t, projectDestination, projectReadback.Destination)
	assert.Equal(t, filter, projectReadback.Filter)

	options, err := service.Organizations.Sinks.Update(name, &loggingapi.LogSink{
		Name: sinkID, Destination: "bigquery.googleapis.com/projects/test-project/datasets/logs",
		BigqueryOptions: &loggingapi.BigQueryOptions{UsePartitionedTables: true},
		Exclusions:      []*loggingapi.LogExclusion{{Name: "low-severity", Filter: "severity<WARNING"}},
	}).UniqueWriterIdentity(true).UpdateMask("destination,bigqueryOptions,exclusions").Context(ctx).Do()
	require.NoError(t, err)
	require.NotNil(t, options.BigqueryOptions)
	assert.True(t, options.BigqueryOptions.UsePartitionedTables)
	require.Len(t, options.Exclusions, 1)
	assert.Equal(t, "low-severity", options.Exclusions[0].Name)
	assert.Equal(t, created.WriterIdentity, options.WriterIdentity)

	_, err = service.Organizations.Sinks.Delete(name).Context(ctx).Do()
	require.NoError(t, err)
	_, err = service.Organizations.Sinks.Get(name).Context(ctx).Do()
	assertLoggingAPIError(t, err, 404)
}

func assertLoggingAPIError(t *testing.T, err error, code int) {
	t.Helper()
	var apiError *googleapi.Error
	require.ErrorAs(t, err, &apiError)
	assert.Equal(t, code, apiError.Code)
}

func TestSinkPaginationRESTSDK(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	endpoint := os.Getenv("FLOCI_GCP_ENDPOINT")
	if endpoint == "" {
		endpoint = "http://localhost:4588"
	}
	service, err := loggingapi.NewService(ctx, option.WithEndpoint(strings.TrimRight(endpoint, "/")+"/"), option.WithoutAuthentication())
	require.NoError(t, err)
	parent := "projects/" + uniqueName("sink-pagination")
	for _, id := range []string{"a", "b"} {
		_, err = service.Projects.Sinks.Create(parent, &loggingapi.LogSink{
			Name: id, Destination: "storage.googleapis.com/test-logs",
		}).Context(ctx).Do()
		require.NoError(t, err)
		t.Cleanup(func() {
			cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			_, _ = service.Projects.Sinks.Delete(parent + "/sinks/" + id).Context(cleanup).Do()
		})
	}
	first, err := service.Projects.Sinks.List(parent).PageSize(1).Context(ctx).Do()
	require.NoError(t, err)
	require.Len(t, first.Sinks, 1)
	assert.Equal(t, "a", first.Sinks[0].Name)
	require.NotEmpty(t, first.NextPageToken)
	for _, pageSize := range []int64{0, -1} {
		remaining, err := service.Projects.Sinks.List(parent).PageToken(first.NextPageToken).PageSize(pageSize).Context(ctx).Do()
		require.NoError(t, err)
		require.Len(t, remaining.Sinks, 1)
		assert.Equal(t, "b", remaining.Sinks[0].Name)
		assert.Empty(t, remaining.NextPageToken)
	}
}

func TestOrganizationSinkManagementGRPCSDK(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	host := os.Getenv("LOGGING_EMULATOR_HOST")
	if host == "" {
		host = "localhost:4588"
	}
	conn, err := grpc.NewClient(host, grpc.WithTransportCredentials(insecure.NewCredentials()))
	require.NoError(t, err)
	t.Cleanup(func() { _ = conn.Close() })
	client, err := logging.NewConfigClient(ctx, option.WithGRPCConn(conn))
	require.NoError(t, err)
	t.Cleanup(func() { _ = client.Close() })

	parent := "organizations/123456789012"
	sinkID := uniqueName("grpc-sink")
	name := parent + "/sinks/" + sinkID
	sink := &loggingpb.LogSink{Name: sinkID, Destination: "pubsub.googleapis.com/projects/test-project/topics/logs",
		Filter: "protoPayload.methodName:\"Group\"", IncludeChildren: true}
	created, err := client.CreateSink(ctx, &loggingpb.CreateSinkRequest{Parent: parent, Sink: sink, UniqueWriterIdentity: true})
	require.NoError(t, err)
	t.Cleanup(func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = client.DeleteSink(cleanup, &loggingpb.DeleteSinkRequest{SinkName: name})
	})
	assert.Equal(t, sinkID, created.GetName())
	require.NotEmpty(t, created.GetWriterIdentity())
	got, err := client.GetSink(ctx, &loggingpb.GetSinkRequest{SinkName: name})
	require.NoError(t, err)
	assert.Equal(t, sink.GetFilter(), got.GetFilter())
	assert.Equal(t, created.GetWriterIdentity(), got.GetWriterIdentity())
	_, err = client.UpdateSink(ctx, &loggingpb.UpdateSinkRequest{SinkName: name,
		Sink:       &loggingpb.LogSink{Description: "updated without identity flag"},
		UpdateMask: &fieldmaskpb.FieldMask{Paths: []string{"description"}}})
	require.NoError(t, err)
	got, err = client.GetSink(ctx, &loggingpb.GetSinkRequest{SinkName: name})
	require.NoError(t, err)
	assert.Equal(t, sink.GetFilter(), got.GetFilter())
	assert.Equal(t, "updated without identity flag", got.GetDescription())
	assert.Equal(t, created.GetWriterIdentity(), got.GetWriterIdentity())
	_, err = client.CreateSink(ctx, &loggingpb.CreateSinkRequest{Parent: "projects/core",
		Sink: &loggingpb.LogSink{Name: sinkID + "-legacy", Destination: "bigquery.googleapis.com/projects/other/datasets/logs"}})
	assert.Equal(t, codes.InvalidArgument, status.Code(err))

	found := false
	sinks := client.ListSinks(ctx, &loggingpb.ListSinksRequest{Parent: parent, PageSize: 1})
	for {
		candidate, err := sinks.Next()
		if err == iterator.Done {
			break
		}
		require.NoError(t, err)
		if candidate.GetName() == sinkID {
			found = true
		}
	}
	assert.True(t, found)

	updated, err := client.UpdateSink(ctx, &loggingpb.UpdateSinkRequest{SinkName: name, UniqueWriterIdentity: true,
		Sink:       &loggingpb.LogSink{Name: sinkID, Filter: "severity>=ERROR"},
		UpdateMask: &fieldmaskpb.FieldMask{Paths: []string{"filter", "include_children"}}})
	require.NoError(t, err)
	assert.Equal(t, sink.GetDestination(), updated.GetDestination())
	assert.Equal(t, "severity>=ERROR", updated.GetFilter())
	assert.False(t, updated.GetIncludeChildren())
	assert.Equal(t, created.GetWriterIdentity(), updated.GetWriterIdentity())
	_, err = client.UpdateSink(ctx, &loggingpb.UpdateSinkRequest{SinkName: name, Sink: sink, UniqueWriterIdentity: true,
		UpdateMask: &fieldmaskpb.FieldMask{Paths: []string{"filter", "writer_identity"}}})
	assert.Equal(t, codes.InvalidArgument, status.Code(err))
	got, err = client.GetSink(ctx, &loggingpb.GetSinkRequest{SinkName: name})
	require.NoError(t, err)
	assert.Equal(t, updated.GetFilter(), got.GetFilter())
	require.NoError(t, client.DeleteSink(ctx, &loggingpb.DeleteSinkRequest{SinkName: name}))
	_, err = client.GetSink(ctx, &loggingpb.GetSinkRequest{SinkName: name})
	assert.Equal(t, codes.NotFound, status.Code(err))
}

func TestFolderSinkWriterIdentityFlagsRESTSDK(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	endpoint := os.Getenv("FLOCI_GCP_ENDPOINT")
	if endpoint == "" {
		endpoint = "http://localhost:4588"
	}
	service, err := loggingapi.NewService(ctx, option.WithEndpoint(strings.TrimRight(endpoint, "/")+"/"), option.WithoutAuthentication())
	require.NoError(t, err)
	parent := "folders/123456789012"
	for _, createFlag := range []bool{false, true} {
		t.Run(fmt.Sprint(createFlag), func(t *testing.T) {
			id := uniqueName("folder-rest-flags")
			name := parent + "/sinks/" + id
			created, err := service.Folders.Sinks.Create(parent, &loggingapi.LogSink{
				Name: id, Destination: "pubsub.googleapis.com/projects/test-project/topics/logs", Disabled: true,
			}).UniqueWriterIdentity(createFlag).Context(ctx).Do()
			require.NoError(t, err)
			require.NotEmpty(t, created.WriterIdentity)
			t.Cleanup(func() {
				cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
				defer cancel()
				_, _ = service.Folders.Sinks.Delete(name).Context(cleanup).Do()
			})
			for index, updateFlag := range []bool{false, true, false} {
				description := fmt.Sprintf("flag update %d", index)
				updated, err := service.Folders.Sinks.Update(name, &loggingapi.LogSink{Description: description}).
					UniqueWriterIdentity(updateFlag).UpdateMask("description").Context(ctx).Do()
				require.NoError(t, err)
				assert.Equal(t, created.WriterIdentity, updated.WriterIdentity)
				assert.Equal(t, description, updated.Description)
				assert.True(t, updated.Disabled)
				got, err := service.Folders.Sinks.Get(name).Context(ctx).Do()
				require.NoError(t, err)
				assert.Equal(t, created.WriterIdentity, got.WriterIdentity)
				assert.Equal(t, description, got.Description)
				assert.True(t, got.Disabled)
			}
		})
	}
}

func TestFolderSinkWriterIdentityFlagsGRPCSDK(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	host := os.Getenv("LOGGING_EMULATOR_HOST")
	if host == "" {
		host = "localhost:4588"
	}
	conn, err := grpc.NewClient(host, grpc.WithTransportCredentials(insecure.NewCredentials()))
	require.NoError(t, err)
	t.Cleanup(func() { _ = conn.Close() })
	client, err := logging.NewConfigClient(ctx, option.WithGRPCConn(conn))
	require.NoError(t, err)
	t.Cleanup(func() { _ = client.Close() })
	parent := "folders/123456789012"
	for _, createFlag := range []bool{false, true} {
		t.Run(fmt.Sprint(createFlag), func(t *testing.T) {
			id := uniqueName("folder-grpc-flags")
			name := parent + "/sinks/" + id
			created, err := client.CreateSink(ctx, &loggingpb.CreateSinkRequest{
				Parent: parent, UniqueWriterIdentity: createFlag,
				Sink: &loggingpb.LogSink{Name: id, Destination: "pubsub.googleapis.com/projects/test-project/topics/logs", Disabled: true},
			})
			require.NoError(t, err)
			require.NotEmpty(t, created.GetWriterIdentity())
			t.Cleanup(func() {
				cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
				defer cancel()
				_ = client.DeleteSink(cleanup, &loggingpb.DeleteSinkRequest{SinkName: name})
			})
			for index, updateFlag := range []bool{false, true, false} {
				description := fmt.Sprintf("flag update %d", index)
				updated, err := client.UpdateSink(ctx, &loggingpb.UpdateSinkRequest{
					SinkName: name, UniqueWriterIdentity: updateFlag,
					Sink: &loggingpb.LogSink{Description: description}, UpdateMask: &fieldmaskpb.FieldMask{Paths: []string{"description"}},
				})
				require.NoError(t, err)
				assert.Equal(t, created.GetWriterIdentity(), updated.GetWriterIdentity())
				assert.Equal(t, description, updated.GetDescription())
				assert.True(t, updated.GetDisabled())
				got, err := client.GetSink(ctx, &loggingpb.GetSinkRequest{SinkName: name})
				require.NoError(t, err)
				assert.Equal(t, created.GetWriterIdentity(), got.GetWriterIdentity())
				assert.Equal(t, description, got.GetDescription())
				assert.True(t, got.GetDisabled())
			}
		})
	}
}
