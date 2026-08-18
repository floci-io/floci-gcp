FROM rust:1.97-slim
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Install cargo-nextest for JUnit XML output
ENV NEXTEST_VERSION=0.9.143
ENV NEXTEST_LINUX_SHA256=66786b9abe23920d022a182d1416b1bbc8130dd4872a9553d76985a1708dcd1e
ENV NEXTEST_LINUX_ARM_SHA256=2a64b3566a92508550a7ab29c3e8db25472ca37730ecb4d22100b6aa440c2a68
RUN ARCH=$(uname -m) \
    && case "$ARCH" in \
        aarch64) TARGET=linux-arm; SHA256="$NEXTEST_LINUX_ARM_SHA256" ;; \
        *) TARGET=linux; SHA256="$NEXTEST_LINUX_SHA256" ;; \
    esac \
    && curl -LsSf "https://get.nexte.st/$NEXTEST_VERSION/$TARGET" -o /tmp/nextest.tar.gz \
    && echo "$SHA256  /tmp/nextest.tar.gz" | sha256sum -c - \
    && tar zxf /tmp/nextest.tar.gz -C /usr/local/cargo/bin \
    && rm /tmp/nextest.tar.gz

WORKDIR /app
COPY . .

RUN cargo build --tests --locked

ENV FLOCI_GCP_ENDPOINT=http://floci-gcp:4588
ENV FLOCI_GCP_PROJECT=test-project
ENV STORAGE_EMULATOR_HOST=http://floci-gcp:4588

RUN mkdir -p /results
ENTRYPOINT ["sh", "-c", "cargo nextest run --locked --profile ci; status=$?; cp target/nextest/ci/junit.xml /results/junit.xml 2>/dev/null || true; exit $status"]
