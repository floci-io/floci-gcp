import { randomUUID } from 'node:crypto';

export const ENDPOINT = process.env.FLOCI_GCP_ENDPOINT || 'http://localhost:4588';
export const PROJECT_ID = process.env.FLOCI_GCP_PROJECT || 'test-project';
export const PUBSUB_HOST = process.env.PUBSUB_EMULATOR_HOST || 'localhost:4588';
export const FIRESTORE_HOST = process.env.FIRESTORE_EMULATOR_HOST || 'localhost:4588';
export const DATASTORE_HOST = process.env.DATASTORE_EMULATOR_HOST || 'localhost:4588';
export const STORAGE_HOST = process.env.STORAGE_EMULATOR_HOST || 'http://localhost:4588';
export const SECRET_MANAGER_HOST = process.env.SECRET_MANAGER_EMULATOR_HOST || 'localhost:4588';
export const LOGGING_HOST = process.env.LOGGING_EMULATOR_HOST || 'localhost:4588';
export const KMS_HOST = process.env.KMS_EMULATOR_HOST || 'localhost:4588';

export function uniqueName(prefix = 'test'): string {
  return `${prefix}-${randomUUID().slice(0, 8)}`;
}

export function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}
