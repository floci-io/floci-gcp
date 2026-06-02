# Installation

floci-gcp can be run as a Docker image or built from source.

## Docker (Recommended)

No installation required beyond Docker itself.

```bash
docker pull floci/floci-gcp:latest
```

### Requirements

- Docker 20.10+
- `docker compose` v2+ (plugin syntax, not standalone `docker-compose`)

## Image Tags

| Tag | Description |
|---|---|
| `latest` | Current stable release (native image — fast startup, low memory) |
| `x.y.z` | Pinned release |
| `nightly` | Latest nightly build (floating) |
| `nightly-mmddyyyy` | Pinned nightly |

## Choosing a tag

```yaml title="docker-compose.yml"
# Standard release — recommended for most use cases
services:
  floci-gcp:
    image: floci/floci-gcp:latest
    ports:
      - "4588:4588"
```

## Build from Source

### Prerequisites

- Java 25+
- Maven 3.9+
- (Optional) GraalVM Mandrel for native compilation

### Clone and run

```bash
git clone https://github.com/floci-io/floci-gcp.git
cd floci-gcp
./mvnw quarkus:dev          # dev mode with hot reload on port 4588
```

### Build a production JAR

```bash
./mvnw clean package -DskipTests
java -jar target/quarkus-app/quarkus-run.jar
```

### Build a native executable

```bash
./mvnw clean package -Pnative -DskipTests
./target/floci-gcp-runner
```

!!! note
    Native compilation requires GraalVM or Mandrel with the `native-image` tool on your PATH. Build time is typically 2–5 minutes.
