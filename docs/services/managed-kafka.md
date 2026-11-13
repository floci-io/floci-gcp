# Managed Kafka

floci-gcp emulates Google Cloud Managed Service for Apache Kafka (MSK) over REST JSON using the real GCP Managed Kafka API.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_KAFKA_ENABLED` | `true` | Enable/disable Managed Kafka |
| `FLOCI_GCP_SERVICES_KAFKA_MOCK` | `false` | Use mock mode (no Docker; cluster operations are done at once and the cluster is `ACTIVE` immediately) |
| `FLOCI_GCP_SERVICES_KAFKA_CONNECT_IMAGE` | `apache/kafka:4.3.1` | Kafka Connect worker image for Connect clusters; must keep the Apache Kafka layout under `/opt/kafka` |

## Quick Start

=== "REST API"

    ```bash
    # Create a cluster
    curl -X POST \
      "http://localhost:4588/v1/projects/floci-local/locations/us-central1/clusters" \
      -H "Content-Type: application/json" \
      -d '{
        "clusterId": "my-cluster",
        "cluster": {
          "capacityConfig": { "vcpuCount": 3, "memoryBytes": 3221225472 },
          "gcpConfig": { "accessConfig": { "networkConfigs": [{ "subnet": "projects/floci-local/regions/us-central1/subnetworks/default" }] } }
        }
      }'

    # List clusters
    curl "http://localhost:4588/v1/projects/floci-local/locations/us-central1/clusters"

    # Create a topic
    curl -X POST \
      "http://localhost:4588/v1/projects/floci-local/locations/us-central1/clusters/my-cluster/topics" \
      -H "Content-Type: application/json" \
      -d '{"topicId":"my-topic","topic":{"partitionCount":3,"replicationFactor":1}}'

    # List topics
    curl "http://localhost:4588/v1/projects/floci-local/locations/us-central1/clusters/my-cluster/topics"
    ```

## Mock Mode

Set `FLOCI_GCP_SERVICES_KAFKA_MOCK=true` to use mock mode. In mock mode, clusters are created in memory and return `ACTIVE` state immediately without requiring a backing Redpanda container. Useful for testing Terraform or SDK code that provisions Kafka resources but does not produce or consume messages.

## Long-running operations

`CreateCluster`, `UpdateCluster` and `DeleteCluster` return a `google.longrunning.Operation` whose
metadata is `google.cloud.managedkafka.v1.OperationMetadata` (`createTime`, `endTime`, `target`,
`verb`, `apiVersion`). The response is the `Cluster` for create and update and `google.protobuf.Empty`
for delete, each with its `@type`. Operations are served at
`/v1/projects/{project}/locations/{location}/operations` (`get`, `list`, `delete`, `cancel`), so clients
that poll, such as `createClusterAsync(...).get()` in the Java SDK, work against floci.

With Docker (the default):

- `CreateCluster` stores the cluster as `CREATING` and returns the operation running. Redpanda starts in
  the background; when its admin API answers, the cluster turns `ACTIVE` and the operation finishes with
  the cluster. If the broker cannot start, or is not ready within 90 seconds, the operation finishes with
  an error (`INTERNAL` or `DEADLINE_EXCEEDED`) and the cluster is removed. If Docker cannot remove
  the broker either, the cluster is kept as `ACTIVE` instead, so floci still stops its broker on exit
  and a delete can retry the removal.
- `DeleteCluster` turns the cluster `DELETING` and returns the operation running; it finishes once
  Docker confirms the broker container and (under the volume retention policy) its volume are gone. If
  Docker cannot remove them, the cluster returns to `ACTIVE` and the operation carries the error, so the
  delete can be retried.
- `UpdateCluster` only changes stored capacity (the broker is not resized), so its operation is done at
  once.
- While a cluster is not `ACTIVE`, `UpdateCluster`, `DeleteCluster`, topic writes, ACL writes and
  every consumer group call return `400 FAILED_PRECONDITION`. Topic and ACL reads still work.
- Work still running when floci stops cannot finish. On the next start every cluster left `CREATING`
  or `DELETING` has its broker container removed, including one the previous run left running, and is
  removed itself; its operation, if it still exists, finishes as `ABORTED` for a create and done for a
  delete. If Docker cannot remove the broker, the cluster becomes `ACTIVE` again so it can be deleted,
  and a delete operation fails with `INTERNAL`.

In mock mode every operation is done when it is returned and the cluster is `ACTIVE` at once.

Deviations and unverified behaviour:

- `CancelOperation` succeeds and leaves the operation unchanged; provisioning and teardown always
  run to completion.
- Which calls real Managed Kafka refuses on a `CREATING` or `DELETING` cluster, and with which status,
  was not verified against the live service.
- Operation ids are UUIDs, not the `operation-<timestamp>-<hash>` form GCP uses.
- Eventarc serves operations on the same path. A list never mixes the two APIs, since an SDK client
  cannot unpack the other API's metadata type: a request whose host is `managedkafka.googleapis.com`
  lists Managed Kafka operations, and any other host (such as `localhost:4588`) lists Eventarc's, as
  before Managed Kafka shared the path. `get`, `delete` and `cancel` find an operation by name
  whichever API owns it, so polling a Kafka operation works through any host. To list Kafka
  operations through floci's endpoint, a Managed Kafka client must send the
  `managedkafka.googleapis.com` host (for example a `Host` header); without it the list is
  Eventarc's.
- A name no API owns is `404 NOT_FOUND`, unless Managed Kafka and Eventarc are both disabled, when
  it is `503 UNAVAILABLE` as on their own routes.

## Consumer Groups

```bash
# List consumer groups
curl "http://localhost:4588/v1/projects/floci-local/locations/us-central1/clusters/my-cluster/consumerGroups"

# Get a specific consumer group
curl "http://localhost:4588/v1/projects/floci-local/locations/us-central1/clusters/my-cluster/consumerGroups/my-group"

# Delete a consumer group
curl -X DELETE \
  "http://localhost:4588/v1/projects/floci-local/locations/us-central1/clusters/my-cluster/consumerGroups/my-group"
```

## Supported Operations

**Clusters:**

- `CreateCluster`
- `GetCluster`
- `ListClusters`
- `UpdateCluster`
- `DeleteCluster`

**Operations:** `GetOperation`, `ListOperations`, `DeleteOperation`, `CancelOperation`

**Topics:**

- `CreateTopic`
- `GetTopic`
- `ListTopics`
- `UpdateTopic`
- `DeleteTopic`

**Consumer Groups:**

- `GetConsumerGroup`
- `ListConsumerGroups`
- `UpdateConsumerGroup`
- `DeleteConsumerGroup`

**ACLs:**

- `CreateAcl`, `GetAcl`, `ListAcls`, `UpdateAcl`, `DeleteAcl`
- `AddAclEntry`, `RemoveAclEntry`

**Kafka Connect (control plane only, no Connect runtime):**

- `CreateConnectCluster`
- `GetConnectCluster`
- `ListConnectClusters`
- `UpdateConnectCluster`
- `DeleteConnectCluster`
- `CreateConnector`
- `GetConnector`
- `ListConnectors`
- `UpdateConnector`
- `DeleteConnector`
- `PauseConnector`
- `ResumeConnector`
- `RestartConnector`
- `StopConnector`

**Schema Registry (`ManagedSchemaRegistry`):**

- `CreateSchemaRegistry`, `GetSchemaRegistry`, `ListSchemaRegistries`, `DeleteSchemaRegistry`
- `GetContext`, `ListContexts`
- `CreateVersion`, `LookupVersion`, `GetVersion`, `GetRawSchemaVersion`, `ListVersions`, `DeleteVersion`
- `ListSubjects`, `DeleteSubject`, `ListReferencedSchemas`
- `GetSchema`, `GetRawSchema`, `ListSchemaVersions`, `ListSubjectsBySchemaId`, `ListSchemaTypes`
- `CheckCompatibility`
- `GetSchemaConfig`, `UpdateSchemaConfig`, `DeleteSchemaConfig`
- `GetSchemaMode`, `UpdateSchemaMode`, `DeleteSchemaMode`

This is the full `ManagedKafka` v1 RPC surface plus `ManagedKafkaConnect` and `ManagedSchemaRegistry`.

## ACLs

An ACL is addressed by an `acl_id` that encodes the Kafka resource pattern, exactly as the real
API spells it: `cluster`; `topic/{name}`, `consumerGroup/{name}`, `transactionalId/{name}`;
`topicPrefixed/{name}`, `consumerGroupPrefixed/{name}`, `transactionalIdPrefixed/{name}`; and
`allTopics`, `allConsumerGroups`, `allTransactionalIds`. Anything else is `400 INVALID_ARGUMENT`.
The output-only `resourceType`, `resourceName` and `patternType` fields are derived from the id.

```bash
B=http://localhost:4588/v1/projects/p/locations/us-central1/clusters/c
curl -s -X POST "$B/acls?aclId=topic/orders" -H 'Content-Type: application/json' \
  -d '{"aclEntries":[{"principal":"User:svc@p.iam.gserviceaccount.com","permissionType":"ALLOW","operation":"READ","host":"*"}]}'
curl -s -X POST "$B/acls/topic/orders:addAclEntry" -H 'Content-Type: application/json' \
  -d '{"principal":"User:svc@p.iam.gserviceaccount.com","permissionType":"ALLOW","operation":"WRITE","host":"*"}'
curl -s "$B/acls/topic/orders"
```

- Entries follow the proto's field rules: `principal` carries the `User:` prefix (or is `User:*`),
  `permissionType` is `ALLOW` or `DENY`, `operation` is one of the Kafka operations (`ALL`, `READ`,
  `WRITE`, `CREATE`, `DELETE`, `ALTER`, `DESCRIBE`, `CLUSTER_ACTION`, `DESCRIBE_CONFIGS`,
  `ALTER_CONFIGS`, `IDEMPOTENT_WRITE`), matched case-insensitively and stored upper-case, and
  `host` must be `*`. At most 100 entries per ACL.
- `addAclEntry` creates the ACL if it does not exist (`aclCreated: true`); `removeAclEntry`
  deletes it when the last entry goes (`aclDeleted: true`). Adding an identical entry twice is
  a no-op.
- `etag` changes on every write. `UpdateAcl` with a stale `etag` is `409 ABORTED`; without one it
  is unconditional. `updateMask` may only name `aclEntries`.
- ACLs are control-plane metadata, like topics in this emulator: the Redpanda container runs
  without an authorizer, so entries are recorded and read back but do not gate produce or consume.

## Kafka Connect

The `ManagedKafkaConnect` service (`connectClusters` and their `connectors`) attaches a Connect
cluster to a Kafka cluster in the same project and location.

Outside mock mode, each Connect cluster runs a real Kafka Connect worker (distributed mode, one
member) in a container beside the Kafka cluster's Redpanda container:

- `CreateConnectCluster` returns once the worker serves its REST API, usually within ten seconds.
  The Kafka cluster must be `ACTIVE`, otherwise the call is `400 FAILED_PRECONDITION`.
- Connectors are created, updated, paused, resumed, restarted, stopped and deleted on the worker,
  and `state` is read back from it, so a misconfigured connector reports `FAILED` and one the
  worker has not assigned yet reports `UNASSIGNED`. A connector with any failed task also reports
  `FAILED`, since that task is not processing records. A config the worker refuses (for example
  an unknown `connector.class`) is `400 INVALID_ARGUMENT` and nothing is stored.
- Deleting the Kafka cluster a Connect cluster is attached to stops that Connect cluster's worker.
  The Connect cluster remains, its connectors report `FAILED`, and connector writes are
  `400 FAILED_PRECONDITION` until its `config` is changed (which starts a new worker on the
  current Kafka cluster, without the old connectors) or it is deleted. The same applies after an
  emulator restart, since workers are not restarted.
- The worker's REST API is published on `127.0.0.1` only when the emulator runs on the host, and
  is not published at all when it runs in a container: only the emulator drives it.
- The worker keeps connector configs, offsets and status in three internal topics on the Kafka
  cluster (`_floci-connect-<id>-configs`, `-offsets`, `-status`), which is what makes that cluster
  the Connect cluster's primary cluster. Deleting the Connect cluster removes the worker and those
  topics; topics the connectors wrote are kept.
- `config` on the Connect cluster is applied as Kafka Connect worker properties. Changing it
  restarts the worker, and connectors survive the restart. `bootstrap.servers`, `group.id`,
  `listeners` and the three storage topics are owned by the emulator and cannot be overridden.
- The default image ships the MirrorMaker 2 connectors (`MirrorSourceConnector`,
  `MirrorCheckpointConnector`, `MirrorHeartbeatConnector`). To run other connectors, build an image
  from `apache/kafka` with the plugins added, set `FLOCI_GCP_SERVICES_KAFKA_CONNECT_IMAGE`, and point
  `plugin.path` at them through the Connect cluster's `config`. Inside the worker, the Kafka cluster
  is reachable as `floci-kafka-broker:29092`.

In mock mode nothing is started: a Connect cluster is `ACTIVE` immediately, connectors are
metadata, any `connector.class` is accepted, and `state` follows the lifecycle calls (`RUNNING`,
`PAUSED`, `STOPPED`).

```bash
BASE=http://localhost:4588/v1/projects/floci-local/locations/us-central1

# Create a Connect cluster bound to an existing Kafka cluster (returns a done LRO)
curl -X POST "$BASE/connectClusters?connectClusterId=my-connect" -H 'Content-Type: application/json' -d '{
  "kafkaCluster": "projects/floci-local/locations/us-central1/clusters/my-cluster",
  "capacityConfig": {"vcpuCount": 12, "memoryBytes": "21474836480"},
  "gcpConfig": {"accessConfig": {"networkConfigs": [
    {"primarySubnet": "projects/floci-local/regions/us-central1/subnetworks/default"}]}}
}'

# Create a MirrorMaker 2 heartbeat connector, then pause and resume it
curl -X POST "$BASE/connectClusters/my-connect/connectors?connectorId=heartbeats" -H 'Content-Type: application/json' -d '{
  "configs": {
    "connector.class": "org.apache.kafka.connect.mirror.MirrorHeartbeatConnector",
    "source.cluster.alias": "primary", "target.cluster.alias": "primary",
    "source.cluster.bootstrap.servers": "floci-kafka-broker:29092",
    "target.cluster.bootstrap.servers": "floci-kafka-broker:29092",
    "tasks.max": "1"
  },
  "taskRestartPolicy": {"minimumBackoff": "60s", "maximumBackoff": "1800s"}
}'
curl -X POST "$BASE/connectClusters/my-connect/connectors/heartbeats:pause" -H 'Content-Type: application/json' -d '{}'
curl "$BASE/connectClusters/my-connect/connectors/heartbeats"     # "state": "PAUSED"
curl -X POST "$BASE/connectClusters/my-connect/connectors/heartbeats:resume" -H 'Content-Type: application/json' -d '{}'
```

`kafkaCluster` is immutable after create; `PATCH` honours `updateMask` (`labels`, `capacityConfig`,
`gcpConfig`, `config` on a Connect cluster; `configs`, `taskRestartPolicy` on a connector) and, like
`UpdateCluster`, applies the mutable fields present in the body when no mask is sent. Deleting a
Connect cluster deletes its connectors. `taskRestartPolicy` has no Kafka Connect counterpart, so it
is stored and read back but does not change how the worker restarts tasks. Workers do not outlive
the emulator process: they stop on shutdown, like the Redpanda containers, and are not restarted
on the next start.

## Schema Registry

A schema registry lives under a project and location, not under a Kafka cluster, and a project
can have several. The routes under a registry mirror the Confluent Schema Registry API, so a
Confluent serializer or deserializer configured with
`schema.registry.url=http://localhost:4588/v1/projects/<project>/locations/<location>/schemaRegistries/<registry>`
works unchanged (request bodies are accepted with `application/vnd.schemaregistry.v1+json`).

Outside mock mode, each registry runs in its own Redpanda container (the same
`FLOCI_GCP_SERVICES_KAFKA_DEFAULT_IMAGE`), whose built-in schema registry stores the schemas and
checks Avro, Protobuf and JSON Schema compatibility. `CreateSchemaRegistry` returns once the
registry answers, usually within a few seconds. The container stops with the emulator and starts
again on the registry's first use after a restart; its data is kept on the registry's volume.
Deleting the registry removes the container. Its data is removed too when storage mode is
`memory`; in the persistent modes the volume (or host directory) is kept unless
`prune-volumes-on-delete` is enabled, which it is not by default.

In mock mode the registry is kept in the emulator's storage with the Confluent rules for schema
ids, versions, soft and hard deletes, config and mode, but schemas are not checked for
compatibility (`CheckCompatibility` always answers `is_compatible: true`) and only Avro and JSON
Schema payloads are checked for being valid JSON.

```bash
R=http://localhost:4588/v1/projects/floci-local/locations/us-central1/schemaRegistries

curl -X POST "$R" -H 'Content-Type: application/json' -d '{"schemaRegistryId":"my_registry"}'

# Register a schema (CreateVersion returns {"id": ...}) and read it back by id
curl -X POST "$R/my_registry/subjects/orders-value/versions" \
  -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  -d '{"schema":"{\"type\":\"record\",\"name\":\"Order\",\"fields\":[{\"name\":\"id\",\"type\":\"string\"}]}"}'
curl "$R/my_registry/schemas/ids/1"
curl "$R/my_registry/subjects/orders-value/versions/latest"

# Compatibility level and mode
curl -X PUT "$R/my_registry/config/orders-value" -H 'Content-Type: application/json' -d '{"compatibility":"FULL"}'
curl -X PUT "$R/my_registry/mode" -H 'Content-Type: application/json' -d '{"mode":"READONLY"}'
```

### Contexts

Every route under a registry also takes the proto's `contexts/{context}` binding, which scopes it
to that context: `.../schemaRegistries/r/contexts/.team/subjects/orders/versions` registers
`orders` in context `.team`. Contexts follow the Confluent API: each has its own schema ids, a
subject written `:.team:orders` belongs to `.team` wherever it is addressed, subjects outside the
default context are reported in that qualified form, and a context with no config or mode of its
own uses the built in default (`BACKWARD`, `READWRITE`), not the registry's. A context exists once a subject is registered in it; the default context,
`.`, always exists. HTTP clients collapse a `contexts/.` path segment, so the default context is
addressed by leaving the `contexts/{context}` binding out.

`ListContexts` returns the Confluent context ids (`[".", ".team"]`), `SchemaRegistry.contexts`
their resource names (`.../schemaRegistries/r/contexts/.team`), and `GetContext` a context's name
and the resource names of its subjects (`.../contexts/.team/subjects/orders`). Listing registries reads each
registry's contexts, so after an emulator restart it starts every registry's container.

Deviations and unverified behaviour:

- The methods that return `google.api.HttpBody` in the proto (`ListContexts`, `ListSubjects`,
  `DeleteSubject`, `ListVersions`, `DeleteVersion`, `ListSchemaVersions`, `ListSchemaTypes`,
  `ListSubjectsBySchemaId`, `ListReferencedSchemas`) return the Confluent JSON as the body.
  `GetRawSchema` and `GetRawSchemaVersion` return the schema text: `application/json` for Avro and
  JSON Schema, `text/plain` for a Protobuf schema.
- The resource names in `SchemaRegistry.contexts` and `Context.subjects`, and the name of the
  default context (`.`), follow the proto's patterns but were not checked against production.
- Registry errors use the Google error envelope with the Confluent message: an unknown subject,
  version or schema is `404 NOT_FOUND`, an incompatible schema is `400 FAILED_PRECONDITION`, and an
  invalid schema, compatibility level or mode, or a write in `READONLY` mode, is
  `400 INVALID_ARGUMENT`. Production's status codes and messages for these were not verified.
- A context with no context level config answers `404` with code `40408` and the message
  `Context '.team' does not have context-level compatibility configured`, as Redpanda does;
  Confluent's message for the same case is
  `Subject ':.team:' does not have subject-level compatibility configured`.
- Which config and mode production applies to a context with none of its own was not verified;
  floci follows Confluent 7.9 and Redpanda (the built in default, not the registry's).
- `UpdateSchemaConfig` with `normalize: true` is `400 INVALID_ARGUMENT`: Redpanda does not store
  `normalize` in a config. Pass `normalize` on `CreateVersion` or `LookupVersion` instead.
- `CheckCompatibility` against `compatibility/subjects/<subject>/versions` (no version) checks
  every version for the `*_TRANSITIVE` levels and the latest version otherwise, and answers
  `is_compatible: true` for a subject with no versions.
- The registry id must contain only letters, digits and underscores, up to 63 characters, and
  must not start with a digit, as the proto describes; other ids are `400 INVALID_ARGUMENT`.
