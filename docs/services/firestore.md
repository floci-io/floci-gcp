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

## CTF fork

When IAM enforcement is enabled (`floci-gcp.services.iam.enforcement-enabled`):

- Firestore in floci-gcp is **gRPC-only**. IAM is enforced by `IamEnforcementGrpcInterceptor` (wired in `GrpcServerManager` after Bearer token validation).
- `IamGrpcPermissionMapper` maps Firestore RPCs to `datastore.entities.*` (native-mode style):
  - `GetDocument`, `BatchGetDocuments`, `BeginTransaction`, `Rollback`, `Listen` → `datastore.entities.get`
  - `RunQuery`, `RunAggregationQuery`, `ListDocuments`, `ListCollectionIds`, `PartitionQuery` → `datastore.entities.list`
  - `Commit`, `BatchWrite`, `CreateDocument`, `Write` → `datastore.entities.create`
  - `UpdateDocument` → `datastore.entities.update`
  - `DeleteDocument` → `datastore.entities.delete`
- `roles/datastore.viewer` grants get and list (enough for `GetDocument`).
- `roles/datastore.user` grants the entity permissions used by the CTF mapper.
- Operator root (`FLOCI_GCP_AUTH_ROOT_SERVICE_ACCOUNT` / `FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN`) bypasses IAM evaluation.

Regression: `FirestoreIamEnforcementIntegrationTest`.
