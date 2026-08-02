# Firestore

floci-gcp emulates Google Cloud Firestore over gRPC using the real `google.firestore.v1` protocol.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_FIRESTORE_ENABLED` | `true` | Enable/disable Firestore |

## Emulator Variable

```bash
export FIRESTORE_EMULATOR_HOST=localhost:4588
```

The GCP Firestore SDK uses this variable to route requests to floci-gcp instead of `firestore.googleapis.com`.

## Quick Start

=== "Java"

    ```java
    FirestoreOptions options = FirestoreOptions.newBuilder()
        .setHost("localhost:4588")
        .setProjectId("floci-local")
        .setCredentials(NoCredentials.getInstance())
        .build();

    Firestore db = options.getService();

    // Add a document
    Map<String, Object> user = new HashMap<>();
    user.put("name", "Alice");
    user.put("age", 30);

    db.collection("users").add(user).get();

    // Query documents
    ApiFuture<QuerySnapshot> future = db.collection("users")
        .whereEqualTo("name", "Alice")
        .get();

    QuerySnapshot snapshot = future.get();
    snapshot.getDocuments().forEach(doc ->
        System.out.println(doc.getData()));
    ```

=== "Python"

    ```python
    import os
    os.environ["FIRESTORE_EMULATOR_HOST"] = "localhost:4588"

    from google.cloud import firestore

    db = firestore.Client(project="floci-local")

    # Add a document
    db.collection("users").add({"name": "Alice", "age": 30})

    # Query documents
    docs = db.collection("users").where("name", "==", "Alice").stream()
    for doc in docs:
        print(doc.to_dict())

    # Set a document
    db.collection("users").document("alice").set({"name": "Alice", "age": 30})

    # Get a document
    doc = db.collection("users").document("alice").get()
    print(doc.to_dict())
    ```

=== "Node.js"

    ```javascript
    process.env.FIRESTORE_EMULATOR_HOST = "localhost:4588";

    import { Firestore } from "@google-cloud/firestore";

    const db = new Firestore({ projectId: "floci-local" });

    // Add a document
    await db.collection("users").add({ name: "Alice", age: 30 });

    // Query documents
    const snapshot = await db.collection("users")
        .where("name", "==", "Alice")
        .get();
    snapshot.forEach(doc => console.log(doc.data()));

    // Set a document
    await db.collection("users").doc("alice").set({ name: "Alice", age: 30 });
    ```

=== "gcloud CLI"

    ```bash
    export FIRESTORE_EMULATOR_HOST=localhost:4588
    gcloud config set project floci-local

    # Firestore does not have gcloud CLI data commands.
    # Use the SDK clients or the Firestore emulator UI instead.
    ```

## Transactions

```java
db.runTransaction(transaction -> {
    DocumentReference docRef = db.collection("counters").document("visits");
    DocumentSnapshot snapshot = transaction.get(docRef).get();

    long currentCount = snapshot.exists() ? snapshot.getLong("count") : 0;
    transaction.set(docRef, Map.of("count", currentCount + 1));
    return null;
}).get();
```

Transactions use optimistic concurrency, matching real Firestore: every document
read inside a transaction is tracked, and the commit fails with `ABORTED` if any
of those documents changed after being read. SDK clients retry aborted
transactions automatically, so concurrent increments like the example above never
lose updates.

## Preconditions

Write preconditions (`currentDocument`) are enforced on all write paths:

- `create()` fails with `ALREADY_EXISTS` if the document exists
- `update()` fails with `NOT_FOUND` if the document does not exist
- `Precondition.updatedAt(...)` fails with `FAILED_PRECONDITION` if the
  document's update time no longer matches

`Commit` is atomic: if any write's precondition fails, no writes in the request
are applied. `BatchWrite` is non-atomic and reports a per-write status, matching
real Firestore.

## Batch Writes

```java
WriteBatch batch = db.batch();

DocumentReference ref1 = db.collection("users").document("alice");
DocumentReference ref2 = db.collection("users").document("bob");

batch.set(ref1, Map.of("name", "Alice"));
batch.set(ref2, Map.of("name", "Bob"));

batch.commit().get();
```

## Real-time Listeners

```java
DocumentReference docRef = db.collection("users").document("alice");

ListenerRegistration registration = docRef.addSnapshotListener((snapshot, e) -> {
    if (snapshot != null && snapshot.exists()) {
        System.out.println("Current data: " + snapshot.getData());
    }
});

// Later: stop listening
registration.remove();
```

## Supported Operations

- `GetDocument`
- `CreateDocument`
- `UpdateDocument`
- `DeleteDocument`
- `ListDocuments`
- `BatchGetDocuments`
- `BatchWrite`
- `BeginTransaction`
- `Commit`
- `Rollback`
- `RunQuery`
- `RunAggregationQuery`
- `PartitionQuery`
- `Write` (streaming)
- `Listen` (real-time change streams)
- `ListCollectionIds`

## Deviations from real Firestore

- Transaction conflict detection covers documents actually read. Queries inside
  a transaction track only the documents they return, so phantom reads (a
  concurrent write creating a document that would have matched the query) do not
  abort the transaction.
- Transactions expire after 15 minutes instead of Firestore's shorter
  server-side deadlines.
- Transaction state is held in memory; transactions do not survive an emulator
  restart.
