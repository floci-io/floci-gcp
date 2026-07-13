# Datastore

floci-gcp emulates Google Cloud Datastore over gRPC using the real `google.datastore.v1` protocol.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_DATASTORE_ENABLED` | `true` | Enable/disable Datastore |

## Emulator Variable

```bash
export DATASTORE_EMULATOR_HOST=localhost:4588
```

The GCP Datastore SDK uses this variable to route requests to floci-gcp instead of `datastore.googleapis.com`.

## Quick Start

=== "Java"

    ```java
    DatastoreOptions options = DatastoreOptions.newBuilder()
        .setHost("http://localhost:4588")
        .setProjectId("floci-local")
        .setCredentials(NoCredentials.getInstance())
        .build();

    Datastore datastore = options.getService();

    // Create entity
    KeyFactory keyFactory = datastore.newKeyFactory().setKind("Task");
    Key taskKey = datastore.allocateId(keyFactory.newKey());

    Entity task = Entity.newBuilder(taskKey)
        .set("description", "Buy groceries")
        .set("done", false)
        .set("priority", 4L)
        .build();

    datastore.put(task);

    // Query entities
    Query<Entity> query = Query.newEntityQueryBuilder()
        .setKind("Task")
        .setFilter(PropertyFilter.eq("done", false))
        .build();

    QueryResults<Entity> results = datastore.run(query);
    while (results.hasNext()) {
        Entity e = results.next();
        System.out.println(e.getString("description"));
    }
    ```

=== "Python"

    ```python
    import os
    os.environ["DATASTORE_EMULATOR_HOST"] = "localhost:4588"

    from google.cloud import datastore

    client = datastore.Client(project="floci-local")

    # Create entity
    key = client.key("Task")
    task = datastore.Entity(key=key)
    task.update({
        "description": "Buy groceries",
        "done": False,
        "priority": 4,
    })
    client.put(task)

    # Query entities
    query = client.query(kind="Task")
    query.add_filter("done", "=", False)
    results = list(query.fetch())
    for task in results:
        print(task["description"])
    ```

=== "Node.js"

    ```javascript
    process.env.DATASTORE_EMULATOR_HOST = "localhost:4588";

    import { Datastore } from "@google-cloud/datastore";

    const datastore = new Datastore({ projectId: "floci-local" });

    // Create entity
    const key = datastore.key("Task");
    const data = [
        { name: "description", value: "Buy groceries" },
        { name: "done", value: false },
        { name: "priority", value: 4 },
    ];

    await datastore.save({ key, data });

    // Query entities
    const query = datastore.createQuery("Task").filter("done", "=", false);
    const [entities] = await datastore.runQuery(query);
    entities.forEach(e => console.log(e.description));
    ```

=== "gcloud CLI"

    ```bash
    export DATASTORE_EMULATOR_HOST=localhost:4588
    gcloud config set project floci-local

    # Use gcloud datastore operations
    gcloud datastore operations list
    ```

## Transactions

```java
TransactionCallable<Void> callable = transaction -> {
    Key taskKey = datastore.newKeyFactory().setKind("Task").newKey(1);
    Entity task = transaction.get(taskKey);

    if (task != null) {
        Entity updated = Entity.newBuilder(task)
            .set("done", true)
            .build();
        transaction.put(updated);
    }
    return null;
};

datastore.runInTransaction(callable);
```

## Indexes

Datastore requires indexes for composite queries (queries with multiple inequality filters or `ORDER BY` on a field that's not the sort field). In the emulator, basic indexes are created automatically; complex composite indexes can be defined in `datastore.indexes.yaml`.

## GQL Queries

Datastore supports GQL (Google Query Language) syntax:

```java
Query<Entity> query = Query.newGqlQueryBuilder(Query.ResultType.ENTITY,
    "SELECT * FROM Task WHERE done = @done LIMIT @limit")
    .setBinding("done", false)
    .setBinding("limit", 10)
    .build();

QueryResults<Entity> results = datastore.run(query);
```

Supported GQL syntax:
- `SELECT * FROM Kind`
- `WHERE prop = value` (and `!=`, `<`, `<=`, `>`, `>=`)
- `WHERE cond1 AND cond2`
- `LIMIT n` / `OFFSET n`
- Named bindings (`@name`) and positional bindings (`@1`)

## Supported Operations

- `Lookup`
- `RunQuery` (structured query and GQL)
- `RunAggregationQuery` (COUNT)
- `BeginTransaction`
- `Commit`
- `Rollback`
- `AllocateIds`
- `ReserveIds`

## CTF fork

When IAM enforcement is enabled (`floci-gcp.services.iam.enforcement-enabled`):

- Datastore **HTTP** (`POST /v1/projects/{project}:{method}` with protobuf) requires a registered Bearer token and a matching project allow-policy binding.
- `IamPermissionMapper` maps custom methods to `datastore.entities.*`:
  - `lookup`, `beginTransaction`, `rollback` → `datastore.entities.get`
  - `runQuery`, `runAggregationQuery` → `datastore.entities.list`
  - `commit` → `datastore.entities.create` (Stage 0 write gate for mutations)
  - `allocateIds`, `reserveIds` → `datastore.entities.allocateIds`
- `roles/datastore.viewer` grants get and list.
- `roles/datastore.user` grants the entity permissions used by the CTF mapper (including create and allocateIds).
- Datastore **gRPC** is gated by `IamEnforcementGrpcInterceptor` with the same `datastore.entities.*` permissions via `IamGrpcPermissionMapper` (for example `Lookup` → `datastore.entities.get`).
- Operator root bypasses IAM evaluation on both HTTP and gRPC.

Regression: `DatastoreIamEnforcementIntegrationTest` (HTTP), `DatastoreGrpcIamEnforcementIntegrationTest` (gRPC).
