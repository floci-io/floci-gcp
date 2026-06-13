FROM ghcr.io/opentofu/opentofu:1.12.2 AS opentofu

FROM alpine:3.20

# OpenTofu binary from the official image
COPY --from=opentofu /usr/local/bin/tofu /usr/local/bin/tofu

RUN apk add --no-cache bash curl jq git ncurses

WORKDIR /app

# Install bats and helpers (loaded via BATS_LIB_PATH in test_helper/common-setup)
RUN git clone --depth 1 https://github.com/bats-core/bats-core.git /opt/bats-core \
    && git clone --depth 1 https://github.com/bats-core/bats-support.git /opt/bats-support \
    && git clone --depth 1 https://github.com/bats-core/bats-assert.git /opt/bats-assert

COPY . .

# Defaults target the floci-gcp container on the compat-net Docker network;
# override with -e at runtime for other endpoints.
ENV FLOCI_ENDPOINT=http://floci-gcp:4588
ENV FLOCI_HOST=floci-gcp:4588
ENV FLOCI_PROJECT=test-project
ENV GOOGLE_OAUTH_ACCESS_TOKEN=fake-token-floci-gcp
ENV BATS_LIB_PATH=/opt

RUN chmod +x /app/run-bats-in-container.sh && mkdir -p /results
ENTRYPOINT ["./run-bats-in-container.sh"]
