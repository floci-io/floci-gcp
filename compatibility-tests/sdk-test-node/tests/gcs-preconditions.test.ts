import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { Storage } from '@google-cloud/storage';
import { ENDPOINT, PROJECT_ID, uniqueName } from './setup';

// Regression coverage for issue #2: preconditions were accepted but ignored, so
// unsafe writes silently succeeded. Real GCS returns 412 Precondition Failed.

// The status surfaces differently across SDK code paths (resumable upload vs.
// metadata patch), so probe the common fields.
function statusOf(err: unknown): number | undefined {
  const e = err as { code?: number; status?: number; response?: { status?: number } };
  return e.code ?? e.status ?? e.response?.status;
}

async function expectStatus(promise: Promise<unknown>, status: number): Promise<void> {
  await promise.then(
    () => {
      throw new Error(`expected rejection with status ${status}`);
    },
    (err) => {
      expect(statusOf(err)).toBe(status);
    },
  );
}

describe('GCS preconditions', () => {
  let storage: Storage;
  let bucketName: string;
  const objectName = 'o';
  let generation: number;

  beforeAll(async () => {
    storage = new Storage({ apiEndpoint: ENDPOINT, projectId: PROJECT_ID });
    bucketName = uniqueName('precond-bucket');
    await storage.createBucket(bucketName);
    await storage.bucket(bucketName).file(objectName).save('v1');
    const [metadata] = await storage.bucket(bucketName).file(objectName).getMetadata();
    generation = Number(metadata.generation);
  });

  afterAll(async () => {
    await storage.bucket(bucketName).file(objectName).delete().catch(() => {});
    await storage.bucket(bucketName).delete().catch(() => {});
  });

  it('ifGenerationMatch=0 on an existing object fails with 412', async () => {
    const file = storage.bucket(bucketName).file(objectName);
    await expectStatus(file.save('v2', { preconditionOpts: { ifGenerationMatch: 0 } }), 412);
  });

  it('a stale ifGenerationMatch fails with 412', async () => {
    const file = storage.bucket(bucketName).file(objectName);

    // A matching generation precondition succeeds and bumps the generation.
    await file.save('v3', { preconditionOpts: { ifGenerationMatch: generation } });

    await expectStatus(file.save('v4', { preconditionOpts: { ifGenerationMatch: generation } }), 412);
  });

  it('a wrong ifMetagenerationMatch fails with 412', async () => {
    const file = storage.bucket(bucketName).file(objectName);
    await expectStatus(
      file.setMetadata({ metadata: { a: 'b' } }, { ifMetagenerationMatch: 999 }),
      412,
    );
  });
});
