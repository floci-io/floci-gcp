FROM node:22-slim
WORKDIR /app

COPY package*.json ./
RUN npm install --no-fund --no-audit

COPY tsconfig.json vitest.config.ts ./
COPY tests/ tests/

ENV FLOCI_GCP_ENDPOINT=http://floci-gcp:4588
ENV FLOCI_GCP_PROJECT=test-project
ENV PUBSUB_EMULATOR_HOST=floci-gcp:4588
ENV FIRESTORE_EMULATOR_HOST=floci-gcp:4588
ENV DATASTORE_EMULATOR_HOST=floci-gcp:4588
ENV STORAGE_EMULATOR_HOST=http://floci-gcp:4588
ENV SECRET_MANAGER_EMULATOR_HOST=floci-gcp:4588
ENV LOGGING_EMULATOR_HOST=floci-gcp:4588
ENV KMS_EMULATOR_HOST=floci-gcp:4588
ENV TEST_RESULTS_DIR=/results

RUN mkdir -p /results
ENTRYPOINT ["npm", "test"]
