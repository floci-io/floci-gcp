# Managed Kafka

floci-gcp emulates Google Cloud Managed Service for Apache Kafka (MSK) over REST JSON using the real GCP Managed Kafka API.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_KAFKA_ENABLED` | `true` | Enable/disable Managed Kafka |
| `FLOCI_GCP_SERVICES_KAFKA_MOCK` | `false` | Use mock mode (no Docker; cluster state returns `ACTIVE` immediately) |

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

## ACLs

```bash
# Create an ACL
curl -X POST \
  "http://localhost:4588/v1/projects/floci-local/locations/us-central1/clusters/my-cluster/acls?aclId=topic/my-topic" \
  -H "Content-Type: application/json" \
  -d '{
    "aclEntries": [
      { "principal": "User:alice", "permissionType": "ALLOW", "operation": "READ", "host": "*" }
    ]
  }'

# List ACLs
curl "http://localhost:4588/v1/projects/floci-local/locations/us-central1/clusters/my-cluster/acls"

# Add an ACL entry
curl -X POST \
  "http://localhost:4588/v1/projects/floci-local/locations/us-central1/clusters/my-cluster/acls/topic/my-topic:addAclEntry" \
  -H "Content-Type: application/json" \
  -d '{
    "aclEntry": { "principal": "User:bob", "permissionType": "ALLOW", "operation": "WRITE", "host": "*" }
  }'

# Remove an ACL entry
curl -X POST \
  "http://localhost:4588/v1/projects/floci-local/locations/us-central1/clusters/my-cluster/acls/topic/my-topic:removeAclEntry" \
  -H "Content-Type: application/json" \
  -d '{
    "aclEntry": { "principal": "User:alice", "permissionType": "ALLOW", "operation": "READ", "host": "*" }
  }'
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

- `CreateAcl`
- `GetAcl`
- `ListAcls`
- `UpdateAcl`
- `DeleteAcl`
- `AddAclEntry`
- `RemoveAclEntry`

