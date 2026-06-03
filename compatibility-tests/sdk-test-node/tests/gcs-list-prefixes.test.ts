import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { Storage } from '@google-cloud/storage';
import { ENDPOINT, PROJECT_ID, uniqueName } from './setup';

// Regression coverage for issue #4: listing with a delimiter omitted the
// top-level prefixes[] array. The third callback arg of getFiles() exposes the
// raw API response, whose `prefixes` field must be populated.
describe('GCS list with delimiter', () => {
  let storage: Storage;
  let bucketName: string;

  beforeAll(async () => {
    storage = new Storage({ apiEndpoint: ENDPOINT, projectId: PROJECT_ID });
    bucketName = uniqueName('prefixes-bucket');
    await storage.createBucket(bucketName);
  });

  afterAll(async () => {
    const bucket = storage.bucket(bucketName);
    for (const name of ['a/1', 'b/2']) {
      await bucket.file(name).delete().catch(() => {});
    }
    await bucket.delete().catch(() => {});
  });

  it('returns prefixes for delimiter-collapsed names', async () => {
    const bucket = storage.bucket(bucketName);
    await bucket.file('a/1').save('1');
    await bucket.file('b/2').save('2');

    const [, , apiResponse] = await bucket.getFiles({
      delimiter: '/',
      autoPaginate: false,
    });

    expect(apiResponse?.prefixes).toEqual(['a/', 'b/']);
  });
});
