FROM alpine:3.20

ARG OPENTOFU_VERSION=1.12.2
ARG TARGETARCH

RUN apk add --no-cache bash curl jq git ncurses unzip ca-certificates \
    && case "${TARGETARCH}" in \
        amd64) OPENTOFU_ARCH=amd64 ;; \
        arm64) OPENTOFU_ARCH=arm64 ;; \
        *) echo "Unsupported OpenTofu architecture: ${TARGETARCH}" >&2; exit 1 ;; \
    esac \
    && curl -fsSL \
        "https://github.com/opentofu/opentofu/releases/download/v${OPENTOFU_VERSION}/tofu_${OPENTOFU_VERSION}_linux_${OPENTOFU_ARCH}.zip" \
        -o /tmp/tofu.zip \
    && unzip /tmp/tofu.zip -d /usr/local/bin tofu \
    && chmod +x /usr/local/bin/tofu \
    && rm /tmp/tofu.zip

WORKDIR /app

# Install bats and helpers (loaded via BATS_LIB_PATH in test_helper/common-setup)
RUN git clone --depth 1 https://github.com/bats-core/bats-core.git /opt/bats-core \
    && git clone --depth 1 https://github.com/bats-core/bats-support.git /opt/bats-support \
    && git clone --depth 1 https://github.com/bats-core/bats-assert.git /opt/bats-assert

COPY . .

# Defaults target the floci-gcp container on the compat-net Docker network.
# Override with -e at runtime for other endpoints.
# GOOGLE_OAUTH_ACCESS_TOKEN must match the emulator FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN
# when FLOCI_GCP_AUTH_VALIDATE_TOKENS is on.
ENV FLOCI_ENDPOINT=http://floci-gcp:4588
ENV FLOCI_HOST=floci-gcp:4588
ENV FLOCI_PROJECT=test-project
ENV GOOGLE_OAUTH_ACCESS_TOKEN=fake-token-floci-gcp
ENV BATS_LIB_PATH=/opt

RUN chmod +x /app/run-bats-in-container.sh && mkdir -p /results
ENTRYPOINT ["./run-bats-in-container.sh"]
