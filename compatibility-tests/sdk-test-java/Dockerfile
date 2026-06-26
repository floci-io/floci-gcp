FROM maven:3.9-eclipse-temurin-21

WORKDIR /app

# Copy pom.xml and pre-download dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source and pre-compile tests
COPY src/ src/
RUN mvn test-compile -q

# Emulator environment variables
ENV FLOCI_GCP_ENDPOINT=http://floci-gcp:4588
ENV FLOCI_GCP_PROJECT=test-project
ENV PUBSUB_EMULATOR_HOST=floci-gcp:4588
ENV FIRESTORE_EMULATOR_HOST=floci-gcp:4588
ENV DATASTORE_EMULATOR_HOST=floci-gcp:4588
ENV STORAGE_EMULATOR_HOST=http://floci-gcp:4588
# GKE uses host-mode routing: the "container." first label triggers the emulator's
# ServiceRoutingFilter. container.localhost.floci.io is a docker-compose network alias.
ENV GKE_EMULATOR_ENDPOINT=http://container.localhost.floci.io:4588

ENTRYPOINT ["/bin/bash", "-c", "mvn test; status=$?; mkdir -p /results && cp -r target/surefire-reports/*.xml /results/ 2>/dev/null || true; exit $status"]
