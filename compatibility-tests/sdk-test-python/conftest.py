"""Shared fixtures for GCP service integration tests."""

import os
import uuid
import pytest

from google.api_core.client_options import ClientOptions
from google.oauth2.credentials import Credentials


DEFAULT_CTF_TOKEN = "fake-token-floci-gcp"


def access_token() -> str:
    """CTF operator Bearer token for local/CI runs against floci-gcp-ctf."""
    return (
        os.environ.get("GOOGLE_OAUTH_ACCESS_TOKEN")
        or os.environ.get("FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN")
        or DEFAULT_CTF_TOKEN
    )


@pytest.fixture(scope="session")
def gcp_credentials():
    return Credentials(token=access_token())


@pytest.fixture(scope="session")
def project_id():
    return os.environ.get("FLOCI_GCP_PROJECT", "test-project")


@pytest.fixture(scope="session")
def endpoint():
    return os.environ.get("FLOCI_GCP_ENDPOINT", "http://localhost:4588")


@pytest.fixture(scope="session")
def pubsub_emulator_host():
    return os.environ.get("PUBSUB_EMULATOR_HOST", "localhost:4588")


@pytest.fixture(scope="session")
def storage_emulator_host():
    return os.environ.get("STORAGE_EMULATOR_HOST", "http://localhost:4588")


@pytest.fixture(scope="session")
def firestore_emulator_host():
    return os.environ.get("FIRESTORE_EMULATOR_HOST", "localhost:4588")


@pytest.fixture(scope="session")
def datastore_emulator_host():
    return os.environ.get("DATASTORE_EMULATOR_HOST", "localhost:4588")


@pytest.fixture(scope="session")
def secret_manager_emulator_host():
    return os.environ.get("SECRET_MANAGER_EMULATOR_HOST", "localhost:4588")


@pytest.fixture(scope="session")
def logging_emulator_host():
    return os.environ.get("LOGGING_EMULATOR_HOST", "localhost:4588")


@pytest.fixture(scope="session")
def kms_emulator_host():
    return os.environ.get("KMS_EMULATOR_HOST", "localhost:4588")


@pytest.fixture
def unique_name():
    return f"pytest-{uuid.uuid4().hex[:8]}"


# ── GCP clients ──────────────────────────────────────────────────────────────

@pytest.fixture(scope="session")
def storage_client(storage_emulator_host, project_id, gcp_credentials):
    from google.cloud import storage
    os.environ["STORAGE_EMULATOR_HOST"] = storage_emulator_host
    return storage.Client(
        project=project_id,
        credentials=gcp_credentials,
        client_options=ClientOptions(api_endpoint=storage_emulator_host),
    )


@pytest.fixture(scope="session")
def pubsub_publisher(pubsub_emulator_host, project_id):
    from google.cloud import pubsub_v1
    os.environ["PUBSUB_EMULATOR_HOST"] = pubsub_emulator_host
    return pubsub_v1.PublisherClient()


@pytest.fixture(scope="session")
def pubsub_subscriber(pubsub_emulator_host):
    from google.cloud import pubsub_v1
    os.environ["PUBSUB_EMULATOR_HOST"] = pubsub_emulator_host
    return pubsub_v1.SubscriberClient()


@pytest.fixture(scope="session")
def firestore_client(firestore_emulator_host, project_id):
    from google.cloud import firestore
    os.environ["FIRESTORE_EMULATOR_HOST"] = firestore_emulator_host
    return firestore.Client(project=project_id)


@pytest.fixture(scope="session")
def datastore_client(datastore_emulator_host, project_id):
    from google.cloud import datastore
    os.environ["DATASTORE_EMULATOR_HOST"] = datastore_emulator_host
    return datastore.Client(project=project_id)


@pytest.fixture(scope="session")
def secret_manager_client(secret_manager_emulator_host):
    import grpc
    from google.cloud import secretmanager
    from google.cloud.secretmanager_v1.services.secret_manager_service.transports.grpc import (
        SecretManagerServiceGrpcTransport,
    )

    transport = SecretManagerServiceGrpcTransport(
        channel=grpc.insecure_channel(secret_manager_emulator_host)
    )
    return secretmanager.SecretManagerServiceClient(transport=transport)


@pytest.fixture(scope="session")
def logging_client(logging_emulator_host):
    import grpc
    from google.cloud import logging_v2
    from google.cloud.logging_v2.services.logging_service_v2.transports.grpc import (
        LoggingServiceV2GrpcTransport,
    )

    transport = LoggingServiceV2GrpcTransport(
        channel=grpc.insecure_channel(logging_emulator_host)
    )
    return logging_v2.services.logging_service_v2.LoggingServiceV2Client(transport=transport)


@pytest.fixture(scope="session")
def kms_client(kms_emulator_host):
    import grpc
    from google.cloud import kms
    from google.cloud.kms_v1.services.key_management_service.transports.grpc import (
        KeyManagementServiceGrpcTransport,
    )

    transport = KeyManagementServiceGrpcTransport(
        channel=grpc.insecure_channel(kms_emulator_host)
    )
    return kms.KeyManagementServiceClient(transport=transport)
