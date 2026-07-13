// Package testutil provides shared test utilities and GCP client factories.
package testutil

import (
	"context"
	"fmt"
	"os"

	"cloud.google.com/go/datastore"
	"cloud.google.com/go/firestore"
	kms "cloud.google.com/go/kms/apiv1"
	logging "cloud.google.com/go/logging/apiv2"
	"cloud.google.com/go/pubsub"
	secretmanager "cloud.google.com/go/secretmanager/apiv1"
	secretmanagerpb "cloud.google.com/go/secretmanager/apiv1/secretmanagerpb"
	"cloud.google.com/go/storage"
	"golang.org/x/oauth2"
	"google.golang.org/api/option"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

const defaultCTFToken = "fake-token-floci-gcp"

// AccessToken returns the CTF operator Bearer token for local/CI runs.
func AccessToken() string {
	if t := os.Getenv("GOOGLE_OAUTH_ACCESS_TOKEN"); t != "" {
		return t
	}
	if t := os.Getenv("FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN"); t != "" {
		return t
	}
	return defaultCTFToken
}

func tokenSource() oauth2.TokenSource {
	return oauth2.StaticTokenSource(&oauth2.Token{AccessToken: AccessToken()})
}

func clientOpts(extra ...option.ClientOption) []option.ClientOption {
	opts := []option.ClientOption{option.WithTokenSource(tokenSource())}
	return append(opts, extra...)
}

// ProjectID returns the GCP project ID from the environment or a default.
func ProjectID() string {
	if p := os.Getenv("FLOCI_GCP_PROJECT"); p != "" {
		return p
	}
	return "test-project"
}

// SecretParent returns the Secret Manager parent resource name for the current project.
func SecretParent() string {
	return fmt.Sprintf("projects/%s", ProjectID())
}

// StorageClient returns a GCS client configured for the emulator.
// Reads STORAGE_EMULATOR_HOST (e.g. http://localhost:4588).
func StorageClient(ctx context.Context) *storage.Client {
	client, err := storage.NewClient(ctx, clientOpts()...)
	if err != nil {
		panic("failed to create storage client: " + err.Error())
	}
	return client
}

// PubSubClient returns a Pub/Sub client configured for the emulator.
// Reads PUBSUB_EMULATOR_HOST (e.g. localhost:4588).
func PubSubClient(ctx context.Context) *pubsub.Client {
	client, err := pubsub.NewClient(ctx, ProjectID(), clientOpts()...)
	if err != nil {
		panic("failed to create pubsub client: " + err.Error())
	}
	return client
}

// FirestoreClient returns a Firestore client configured for the emulator.
// Reads FIRESTORE_EMULATOR_HOST (e.g. localhost:4588).
func FirestoreClient(ctx context.Context) *firestore.Client {
	client, err := firestore.NewClient(ctx, ProjectID(), clientOpts()...)
	if err != nil {
		panic("failed to create firestore client: " + err.Error())
	}
	return client
}

// DatastoreClient returns a Datastore client configured for the emulator.
// Reads DATASTORE_EMULATOR_HOST (e.g. localhost:4588).
func DatastoreClient(ctx context.Context) *datastore.Client {
	client, err := datastore.NewClient(ctx, ProjectID(), clientOpts()...)
	if err != nil {
		panic("failed to create datastore client: " + err.Error())
	}
	return client
}

// SecretManagerClient returns a Secret Manager client configured for the emulator.
// Reads SECRET_MANAGER_EMULATOR_HOST (e.g. localhost:4588).
func SecretManagerClient(ctx context.Context) *secretmanager.Client {
	host := os.Getenv("SECRET_MANAGER_EMULATOR_HOST")
	if host == "" {
		host = "localhost:4588"
	}
	conn, err := grpc.NewClient(host, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		panic("failed to create gRPC connection: " + err.Error())
	}
	client, err := secretmanager.NewClient(ctx, clientOpts(option.WithGRPCConn(conn))...)
	if err != nil {
		panic("failed to create secret manager client: " + err.Error())
	}
	return client
}

// LoggingClient returns a Cloud Logging (v2) client configured for the emulator.
// Reads LOGGING_EMULATOR_HOST (e.g. localhost:4588).
func LoggingClient(ctx context.Context) *logging.Client {
	host := os.Getenv("LOGGING_EMULATOR_HOST")
	if host == "" {
		host = "localhost:4588"
	}
	conn, err := grpc.NewClient(host, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		panic("failed to create gRPC connection: " + err.Error())
	}
	client, err := logging.NewClient(ctx, clientOpts(option.WithGRPCConn(conn))...)
	if err != nil {
		panic("failed to create logging client: " + err.Error())
	}
	return client
}

// KMSClient returns a Cloud KMS client configured for the emulator.
// Reads KMS_EMULATOR_HOST (e.g. localhost:4588).
func KMSClient(ctx context.Context) *kms.KeyManagementClient {
	host := os.Getenv("KMS_EMULATOR_HOST")
	if host == "" {
		host = "localhost:4588"
	}
	conn, err := grpc.NewClient(host, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		panic("failed to create gRPC connection: " + err.Error())
	}
	client, err := kms.NewKeyManagementClient(ctx, clientOpts(option.WithGRPCConn(conn))...)
	if err != nil {
		panic("failed to create kms client: " + err.Error())
	}
	return client
}

// KMSLocation returns the KMS location resource name for the current project.
func KMSLocation(location string) string {
	return fmt.Sprintf("projects/%s/locations/%s", ProjectID(), location)
}

// NewSecret builds a Secret proto with automatic replication.
func NewSecret() *secretmanagerpb.Secret {
	return &secretmanagerpb.Secret{
		Replication: &secretmanagerpb.Replication{
			Replication: &secretmanagerpb.Replication_Automatic_{
				Automatic: &secretmanagerpb.Replication_Automatic{},
			},
		},
	}
}
