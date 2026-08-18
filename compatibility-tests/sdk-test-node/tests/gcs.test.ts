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

  it('should emit x-goog system metadata headers on media download', async () => {
    const file = storage.bucket(bucketName).file(objectName);
    const [metadata] = await file.getMetadata();

    const stream = file.createReadStream({ validation: 'crc32c' });
    const headersPromise = new Promise<Record<string, string>>((resolve) => {
      stream.on('response', (res) => resolve(res.headers));
    });
    const chunks: Buffer[] = [];
    for await (const chunk of stream) {
      chunks.push(chunk as Buffer);
    }
    const headers = await headersPromise;

    expect(Buffer.concat(chunks).toString()).toBe(objectContent);
    expect(headers['x-goog-generation']).toBe(String(metadata.generation));
    expect(headers['x-goog-metageneration']).toBe(String(metadata.metageneration));
    expect(headers['x-goog-stored-content-length']).toBe(String(metadata.size));
    // Without this header the SDK silently skips checksum validation.
    expect(headers['x-goog-stored-content-encoding']).toBe('identity');
    expect(headers['x-goog-hash']).toContain(`crc32c=${metadata.crc32c}`);
    expect(headers['x-goog-hash']).toContain(`md5=${metadata.md5Hash}`);
  });

  it('should verify md5 from x-goog-hash when requested', async () => {
    // Fails with MD5_NOT_AVAILABLE when the x-goog-hash header is absent.
    const [content] = await storage
      .bucket(bucketName)
      .file(objectName)
      .download({ validation: 'md5' });
    expect(content.toString()).toBe(objectContent);
  });

  it('should get object metadata', async () => {
    const [metadata] = await storage.bucket(bucketName).file(objectName).getMetadata();
    expect(metadata.name).toBe(objectName);
    expect(metadata.contentType).toBe('text/plain');
    expect(Number(metadata.size)).toBeGreaterThan(0);
  });

  it('should round-trip custom object metadata', async () => {
    const metaObjectName = 'meta-test-object.txt';
    await storage.bucket(bucketName).file(metaObjectName).save(objectContent, {
      contentType: 'text/plain',
      metadata: { metadata: { originalname: 'test.txt', reviewer: 'jane' } },
    });
    const [metadata] = await storage.bucket(bucketName).file(metaObjectName).getMetadata();
    expect(metadata.metadata).toEqual({ originalname: 'test.txt', reviewer: 'jane' });
    const [content] = await storage.bucket(bucketName).file(metaObjectName).download();
    expect(content.toString()).toBe(objectContent);
    await storage.bucket(bucketName).file(metaObjectName).delete();
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
