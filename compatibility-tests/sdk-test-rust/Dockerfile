FROM rust:1.97-slim
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Install cargo-nextest for JUnit XML output
RUN ARCH=$(uname -m) && case "$ARCH" in aarch64) TARGET=linux-arm ;; *) TARGET=linux ;; esac \
    && curl -LsSf "https://get.nexte.st/latest/$TARGET" | tar zxf - -C /usr/local/cargo/bin

WORKDIR /app
COPY . .

RUN cargo build --tests --locked

ENV FLOCI_GCP_ENDPOINT=http://floci-gcp:4588
ENV FLOCI_GCP_PROJECT=test-project
ENV STORAGE_EMULATOR_HOST=http://floci-gcp:4588

RUN mkdir -p /results
ENTRYPOINT ["sh", "-c", "cargo nextest run --locked --profile ci; status=$?; cp target/nextest/ci/junit.xml /results/junit.xml 2>/dev/null || true; exit $status"]
