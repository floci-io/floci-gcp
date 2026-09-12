# Storage Modes

floci-gcp supports four storage backends configurable via `FLOCI_GCP_STORAGE_MODE`.

## Modes

| Mode | Data survives restart | Write performance | Use case |
|---|---|---|---|
| `memory` | No | Fastest | Unit tests, CI pipelines |
| `persistent` | Yes | Synchronous disk write on every change | Development with durable state |
| `hybrid` | Yes | In-memory reads, async flush to disk every 5 seconds | General local development |
| `wal` | Yes | Append-only write-ahead log with compaction | High-write workloads |

## Global Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_STORAGE_MODE` | `memory` | Storage backend (`memory`, `persistent`, `hybrid`, `wal`) |
| `FLOCI_GCP_STORAGE_PERSISTENT_PATH` | `./data` | Base directory for all persistent data |
| `FLOCI_GCP_STORAGE_WAL_COMPACTION_INTERVAL_MS` | `30000` | WAL compaction interval (milliseconds) |

!!! tip "Use `memory` for CI"
    Use **`memory`** for fast CI pipelines. Use **`hybrid`** for local development when you want state preserved across restarts.

## Recommended Profiles

=== "Fast CI"

    All in memory: fastest possible startup and test execution:

    ```bash
    FLOCI_GCP_STORAGE_MODE=memory
    ```

=== "Local development"

    Hybrid: survive restarts without slowing down writes:

    ```bash
    FLOCI_GCP_STORAGE_MODE=hybrid
    FLOCI_GCP_STORAGE_PERSISTENT_PATH=/app/data
    ```

    Docker Compose:

    ```yaml
    volumes:
      - floci-gcp-data:/app/data
    environment:
      FLOCI_GCP_STORAGE_MODE: hybrid
      FLOCI_GCP_STORAGE_PERSISTENT_PATH: /app/data
    ```

=== "Durable development"

    Persistent: every write is immediately on disk:

    ```bash
    FLOCI_GCP_STORAGE_MODE=persistent
    FLOCI_GCP_STORAGE_PERSISTENT_PATH=/app/data
    ```

    Docker Compose:

    ```yaml
    volumes:
      - floci-gcp-data:/app/data
    environment:
      FLOCI_GCP_STORAGE_MODE: persistent
      FLOCI_GCP_STORAGE_PERSISTENT_PATH: /app/data
    ```
