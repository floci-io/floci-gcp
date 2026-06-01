FROM hashicorp/terraform:1.14.7 AS terraform

FROM alpine:3.20

# Terraform binary from the official image
COPY --from=terraform /bin/terraform /usr/local/bin/terraform

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