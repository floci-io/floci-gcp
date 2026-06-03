import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { Storage } from '@google-cloud/storage';
import { ENDPOINT, PROJECT_ID, uniqueName } from './setup';

// Regression coverage for issue #3: copy/rewrite dropped custom object metadata
// on the destination. The destination should inherit the source metadata.
describe('GCS copy/rewrite metadata', () => {
  let storage: Storage;
  let bucketName: string;

  beforeAll(async () => {
    storage = new Storage({ apiEndpoint: ENDPOINT, projectId: PROJECT_ID });
    bucketName = uniqueName('copy-bucket');
    await storage.createBucket(bucketName);
  });

  afterAll(async () => {
    const bucket = storage.bucket(bucketName);
    for (const name of ['src', 'dst-copy']) {
      await bucket.file(name).delete().catch(() => {});
    }
    await bucket.delete().catch(() => {});
  });

  it('copy preserves source metadata', async () => {
    const bucket = storage.bucket(bucketName);
    await bucket.file('src').save('x', { contentType: 'text/plain' });
    // Set custom metadata on the source (mirrors the bug report's PATCH step).
    await bucket.file('src').setMetadata({ metadata: { tag: 'keep-me' } });

    // File.copy() drives the objects.rewrite API under the hood.
    await bucket.file('src').copy(bucket.file('dst-copy'));

    const [metadata] = await bucket.file('dst-copy').getMetadata();
    expect(metadata.metadata).toMatchObject({ tag: 'keep-me' });
  });
});
