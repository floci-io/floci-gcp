FROM gcr.io/google.com/cloudsdktool/google-cloud-cli:slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends git jq \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install bats and helpers (loaded via BATS_LIB_PATH in test_helper/common-setup)
RUN git clone --depth 1 https://github.com/bats-core/bats-core.git /opt/bats-core \
    && git clone --depth 1 https://github.com/bats-core/bats-support.git /opt/bats-support \
    && git clone --depth 1 https://github.com/bats-core/bats-assert.git /opt/bats-assert

COPY test/ test/
COPY run-bats-in-container.sh /usr/local/bin/run-bats-in-container.sh

ENV FLOCI_GCP_ENDPOINT=http://floci-gcp:4588
ENV CLOUDSDK_CORE_PROJECT=test-project
ENV BATS_LIB_PATH=/opt

RUN chmod +x /usr/local/bin/run-bats-in-container.sh && mkdir -p /results
ENTRYPOINT ["run-bats-in-container.sh"]
