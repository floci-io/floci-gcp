import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { Storage } from '@google-cloud/storage';
import { ENDPOINT, PROJECT_ID, uniqueName } from './setup';

describe('Cloud Storage (GCS)', () => {
  let storage: Storage;
  let bucketName: string;
  const objectName = 'test-object.txt';
  const objectContent = 'Hello, GCP Cloud Storage from Node.js!';

  beforeAll(() => {
    storage = new Storage({
      apiEndpoint: ENDPOINT,
      projectId: PROJECT_ID,
    });
    bucketName = uniqueName('test-bucket');
  });

  afterAll(async () => {
    try {
      await storage.bucket(bucketName).file(objectName).delete().catch(() => {});
      await storage.bucket(bucketName).delete().catch(() => {});
    } catch {
      // ignore cleanup errors
    }
  });

  it('should create a bucket', async () => {
    const [bucket] = await storage.createBucket(bucketName);
    expect(bucket.name).toBe(bucketName);
  });

  it('should list buckets and find created bucket', async () => {
    const [buckets] = await storage.getBuckets();
    expect(buckets.some((b) => b.name === bucketName)).toBe(true);
  });

  it('should upload an object', async () => {
    await storage.bucket(bucketName).file(objectName).save(objectContent, {
      contentType: 'text/plain',
    });
  });

  it('should upload a large object (>8MB) using resumable upload', async () => {
    const largeContent = Buffer.alloc(9 * 1024 * 1024, 'x');
    const largeObjectName = 'large-test-object.bin';
    await storage.bucket(bucketName).file(largeObjectName).save(largeContent, {
      contentType: 'application/octet-stream',
    });
    const [downloaded] = await storage.bucket(bucketName).file(largeObjectName).download();
    expect(downloaded.length).toBe(largeContent.length);
    await storage.bucket(bucketName).file(largeObjectName).delete();
  });

  it('should download and verify object content', async () => {
    const [content] = await storage.bucket(bucketName).file(objectName).download();
    expect(content.toString()).toBe(objectContent);
  });

  it('should get object metadata', async () => {
    const [metadata] = await storage.bucket(bucketName).file(objectName).getMetadata();
    expect(metadata.name).toBe(objectName);
    expect(metadata.contentType).toBe('text/plain');
    expect(Number(metadata.size)).toBeGreaterThan(0);
  });

  it('should list objects in bucket', async () => {
    const [files] = await storage.bucket(bucketName).getFiles();
    expect(files.some((f) => f.name === objectName)).toBe(true);
  });

  it('should copy an object', async () => {
    const destName = 'test-object-copy.txt';
    await storage.bucket(bucketName).file(objectName).copy(
      storage.bucket(bucketName).file(destName)
    );
    const [exists] = await storage.bucket(bucketName).file(destName).exists();
    expect(exists).toBe(true);
    await storage.bucket(bucketName).file(destName).delete();
  });

  it('should delete object', async () => {
    await storage.bucket(bucketName).file(objectName).delete();
    const [exists] = await storage.bucket(bucketName).file(objectName).exists();
    expect(exists).toBe(false);
  });

  it('should delete bucket', async () => {
    await storage.bucket(bucketName).delete();
    const [exists] = await storage.bucket(bucketName).exists();
    expect(exists).toBe(false);
    bucketName = '';
  });
});
