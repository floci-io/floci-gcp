import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { Storage } from '@google-cloud/storage';
import { ENDPOINT, PROJECT_ID, uniqueName } from './setup';

// Regression coverage for issue #1: object compose was broken
// ("400 Unsupported method override: null"). bucket.combine() must work.
describe('GCS compose', () => {
  let storage: Storage;
  let bucketName: string;

  beforeAll(async () => {
    storage = new Storage({ apiEndpoint: ENDPOINT, projectId: PROJECT_ID });
    bucketName = uniqueName('compose-bucket');
    await storage.createBucket(bucketName);
  });

  afterAll(async () => {
    const bucket = storage.bucket(bucketName);
    for (const name of ['p1', 'p2', 'composed']) {
      await bucket.file(name).delete().catch(() => {});
    }
    await bucket.delete().catch(() => {});
  });

  it('concatenates source objects into the destination', async () => {
    const bucket = storage.bucket(bucketName);
    await bucket.file('p1').save('hello ', { contentType: 'text/plain' });
    await bucket.file('p2').save('world', { contentType: 'text/plain' });

    await bucket.combine(['p1', 'p2'], 'composed');

    const [content] = await bucket.file('composed').download();
    expect(content.toString()).toBe('hello world');

    const [metadata] = await bucket.file('composed').getMetadata();
    expect(Number(metadata.size)).toBe(Buffer.byteLength('hello world'));
  });
});
