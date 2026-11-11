"""Managed Kafka schema registry through the official client
(google-cloud-managedkafka-schemaregistry) over REST, plus the Confluent-shaped
route a Confluent serializer calls under the registry's path.

The proto gives SchemaVersion and Schema Confluent's JSON names (json_name
"version", "id", "schema"). The Python client is generated with proto-plus,
which drops json_name, so it reads those fields as unset from a server that
follows the proto. Those values are checked over plain HTTP instead."""

import json
import urllib.error
import urllib.request
import uuid

import pytest
from google.api_core import exceptions
from google.api_core.client_options import ClientOptions
from google.auth.credentials import AnonymousCredentials
from google.cloud import managedkafka_schemaregistry_v1 as sr

USER_V1 = json.dumps({"type": "record", "name": "User",
                      "fields": [{"name": "a", "type": "string"}]})
USER_V2 = json.dumps({"type": "record", "name": "User",
                      "fields": [{"name": "a", "type": "string"},
                                 {"name": "b", "type": "int", "default": 0}]})


@pytest.fixture
def client(endpoint):
    return sr.ManagedSchemaRegistryClient(
        credentials=AnonymousCredentials(),
        transport="rest",
        client_options=ClientOptions(api_endpoint=endpoint),
    )


@pytest.fixture
def registry(client, project_id):
    parent = f"projects/{project_id}/locations/us-central1"
    registry_id = f"py_sr_{uuid.uuid4().hex[:8]}"
    created = client.create_schema_registry(request=sr.CreateSchemaRegistryRequest(
        parent=parent, schema_registry_id=registry_id, schema_registry=sr.SchemaRegistry()))
    yield created.name
    try:
        client.delete_schema_registry(name=created.name)
    except exceptions.NotFound:
        pass


def test_registry_lifecycle(client, registry, project_id):
    parent = f"projects/{project_id}/locations/us-central1"

    assert client.get_schema_registry(name=registry).name == registry
    names = [r.name for r in client.list_schema_registries(parent=parent).schema_registries]
    assert registry in names

    client.delete_schema_registry(name=registry)
    with pytest.raises(exceptions.NotFound):
        client.get_schema_registry(name=registry)


def _http_get(url):
    with urllib.request.urlopen(url) as r:
        return json.loads(r.read())


def test_versions_round_trip(client, registry, endpoint):
    subject = f"{registry}/subjects/orders-value"

    created = client.create_version(parent=subject, schema=USER_V1)
    assert created.id > 0

    version = client.get_version(name=f"{subject}/versions/1")
    assert version.subject == "orders-value"
    assert version.schema_type == sr.Schema.SchemaType.AVRO
    raw = _http_get(f"{endpoint}/v1/{subject}/versions/1")
    assert raw["version"] == 1
    assert raw["id"] == created.id
    assert json.loads(raw["schema"])["name"] == "User"

    found = client.lookup_version(parent=subject, schema=USER_V1)
    assert found.subject == "orders-value"

    client.get_schema(name=f"{registry}/schemas/ids/{created.id}")
    raw = _http_get(f"{endpoint}/v1/{registry}/schemas/ids/{created.id}")
    assert json.loads(raw["schema"])["name"] == "User"

    second = client.create_version(parent=subject, schema=USER_V2,
                                   schema_type=sr.Schema.SchemaType.AVRO)
    assert second.id != created.id


def test_compatibility_config_and_mode(client, registry):
    subject = f"{registry}/subjects/compat-value"
    client.create_version(parent=subject, schema=USER_V1)

    result = client.check_compatibility(
        name=f"{registry}/compatibility/subjects/compat-value/versions/latest", schema=USER_V2)
    assert result.is_compatible

    config = client.update_schema_config(
        name=f"{registry}/config/compat-value",
        compatibility=sr.SchemaConfig.CompatibilityType.FULL)
    assert config.compatibility == sr.SchemaConfig.CompatibilityType.FULL
    assert client.get_schema_config(name=f"{registry}/config/compat-value").compatibility \
        == sr.SchemaConfig.CompatibilityType.FULL
    client.delete_schema_config(name=f"{registry}/config/compat-value")
    # The client cannot address the registry-wide ".../config" (its config/** template
    # needs a segment), so read it through the subject with default_to_global.
    fallback = client.get_schema_config(request=sr.GetSchemaConfigRequest(
        name=f"{registry}/config/compat-value", default_to_global=True))
    assert fallback.compatibility == sr.SchemaConfig.CompatibilityType.BACKWARD

    mode = client.update_schema_mode(name=f"{registry}/mode/compat-value",
                                     mode=sr.SchemaMode.ModeType.READONLY)
    assert mode.mode == sr.SchemaMode.ModeType.READONLY
    with pytest.raises(exceptions.GoogleAPICallError):
        client.create_version(parent=subject, schema=USER_V2)
    client.delete_schema_mode(name=f"{registry}/mode/compat-value")


def test_missing_subject_is_not_found(client, registry):
    with pytest.raises(exceptions.NotFound):
        client.get_version(name=f"{registry}/subjects/missing/versions/1")


def test_confluent_serializer_route(endpoint, registry):
    """A Confluent serializer posts application/vnd.schemaregistry.v1+json to
    <registry>/subjects/<subject>/versions and reads the id back."""
    url = f"{endpoint}/v1/{registry}/subjects/serializer-value/versions"
    req = urllib.request.Request(
        url, data=json.dumps({"schema": USER_V1}).encode(), method="POST",
        headers={"Content-Type": "application/vnd.schemaregistry.v1+json",
                 "Accept": "application/vnd.schemaregistry.v1+json, application/json"})
    with urllib.request.urlopen(req) as r:
        schema_id = json.loads(r.read())["id"]

    with urllib.request.urlopen(f"{endpoint}/v1/{registry}/schemas/ids/{schema_id}") as r:
        assert json.loads(json.loads(r.read())["schema"])["name"] == "User"


def test_contexts_and_raw_schemas(client, registry, endpoint):
    """GetContext and SchemaRegistry.contexts through the client. The methods
    returning google.api.HttpBody are read over HTTP: the client's REST transport
    parses the response as an HttpBody message ({"contentType", "data"}), while
    HTTP transcoding serves an HttpBody as the raw body, which is what a
    Confluent serializer reads."""
    subject = f"{registry}/contexts/.team/subjects/ctx-value"
    created = client.create_version(parent=subject, schema=USER_V1)

    context = client.get_context(name=f"{registry}/contexts/.team")
    assert context.name == f"{registry}/contexts/.team"
    assert list(context.subjects) == [f"{registry}/contexts/.team/subjects/ctx-value"]
    assert f"{registry}/contexts/.team" in client.get_schema_registry(name=registry).contexts

    base = f"{endpoint}/v1/{registry}"
    assert _http_get(f"{base}/contexts") == [".", ".team"]
    assert _http_get(f"{base}/contexts/.team/schemas/ids/{created.id}/schema")["name"] == "User"
    assert _http_get(f"{base}/contexts/.team/subjects/ctx-value/versions/1/schema")["name"] == "User"
    assert sorted(_http_get(f"{base}/schemas/types")) == ["AVRO", "JSON", "PROTOBUF"]
    assert _http_get(f"{base}/contexts/.team/schemas/ids/{created.id}/subjects") == [":.team:ctx-value"]
