# Managed Kafka

floci-gcp emulates Google Cloud Managed Service for Apache Kafka (MSK) over REST JSON using the real GCP Managed Kafka API.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_KAFKA_ENABLED` | `true` | Enable/disable Managed Kafka |
| `FLOCI_GCP_SERVICES_KAFKA_MOCK` | `false` | Use mock mode (no Docker; cluster state returns `ACTIVE` immediately) |
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

This is the full `ManagedKafka` v1 RPC surface plus `ManagedKafkaConnect`. The Schema Registry
service is not served.

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
