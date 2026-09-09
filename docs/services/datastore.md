# Datastore

floci-gcp emulates Google Cloud Datastore over gRPC and binary HTTP/protobuf using the real `google.datastore.v1` protocol. Current Google Cloud Datastore SDK clients use the HTTP/protobuf path.

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

Transaction RPCs are available over both transports, but transaction IDs are currently advisory. Commits apply their mutations without isolation or conflict detection, and rollback is a no-op.

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

floci-gcp does not load `datastore.indexes.yaml` or enforce Datastore index requirements. Structured and GQL queries scan the emulator's stored entities, so a query that requires a composite index in GCP may still run locally.

## GQL Queries

Datastore supports GQL (Google Query Language) over HTTP/protobuf. The gRPC `RunQuery` handler currently supports structured queries only.

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

- `Lookup` (gRPC and HTTP/protobuf)
- `RunQuery` (structured queries over both transports; GQL over HTTP/protobuf)
- `RunAggregationQuery` (COUNT over HTTP/protobuf)
- `BeginTransaction` (gRPC and HTTP/protobuf; limited semantics described above)
- `Commit` (gRPC and HTTP/protobuf; limited semantics described above)
- `Rollback` (gRPC and HTTP/protobuf; no-op)
- `AllocateIds` (gRPC and HTTP/protobuf)
- `ReserveIds` (gRPC and HTTP/protobuf)
