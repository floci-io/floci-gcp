FROM golang:1.25-alpine
RUN apk add --no-cache ca-certificates git
WORKDIR /app

# Install gotestsum for JUnit XML output
RUN go install gotest.tools/gotestsum@latest

COPY . .

RUN go mod tidy

ENV FLOCI_GCP_ENDPOINT=http://floci-gcp:4588
ENV FLOCI_GCP_PROJECT=test-project
ENV PUBSUB_EMULATOR_HOST=floci-gcp:4588
ENV FIRESTORE_EMULATOR_HOST=floci-gcp:4588
ENV DATASTORE_EMULATOR_HOST=floci-gcp:4588
ENV STORAGE_EMULATOR_HOST=http://floci-gcp:4588
ENV SECRET_MANAGER_EMULATOR_HOST=floci-gcp:4588
ENV LOGGING_EMULATOR_HOST=floci-gcp:4588
ENV KMS_EMULATOR_HOST=floci-gcp:4588

ENTRYPOINT ["gotestsum", "--format", "testname", "--junitfile", "/results/junit.xml", "./tests/..."]
