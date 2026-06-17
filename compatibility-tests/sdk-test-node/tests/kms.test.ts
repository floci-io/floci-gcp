import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { KeyManagementServiceClient } from '@google-cloud/kms';
import * as grpc from '@grpc/grpc-js';
import * as crypto from 'node:crypto';
import { PROJECT_ID, KMS_HOST, uniqueName } from './setup';

const LOCATION = 'us-central1';

describe('Cloud KMS', () => {
  let client: KeyManagementServiceClient;
  let keyRingName: string;

  beforeAll(async () => {
    const [host, port] = KMS_HOST.split(':');
    client = new KeyManagementServiceClient({
      servicePath: host,
      port: port ? parseInt(port, 10) : 4588,
      sslCreds: grpc.credentials.createInsecure(),
    });
    const locationPath = `projects/${PROJECT_ID}/locations/${LOCATION}`;
    const keyRingId = uniqueName('node-kr');
    const [keyRing] = await client.createKeyRing({ parent: locationPath, keyRingId, keyRing: {} });
    keyRingName = keyRing.name!;
  });

  afterAll(() => {
    client.close();
  });

  async function createKey(keyId: string, purpose: string, algorithm?: string) {
    const cryptoKey: Record<string, unknown> = { purpose };
    if (algorithm) {
      cryptoKey.versionTemplate = { algorithm };
    }
    const [key] = await client.createCryptoKey({ parent: keyRingName, cryptoKeyId: keyId, cryptoKey });
    return key;
  }

  it('round-trips symmetric encrypt/decrypt', async () => {
    const key = await createKey(uniqueName('sym'), 'ENCRYPT_DECRYPT');
    const plaintext = Buffer.from('envelope-encryption-payload');

    const [enc] = await client.encrypt({ name: key.name, plaintext });
    expect(Buffer.from(enc.ciphertext as Uint8Array).equals(plaintext)).toBe(false);

    const [dec] = await client.decrypt({ name: key.name, ciphertext: enc.ciphertext });
    expect(Buffer.from(dec.plaintext as Uint8Array).toString()).toBe('envelope-encryption-payload');
    expect(dec.usedPrimary).toBe(true);
  });

  it('fails to decrypt with the wrong key', async () => {
    const k1 = await createKey(uniqueName('a'), 'ENCRYPT_DECRYPT');
    const k2 = await createKey(uniqueName('b'), 'ENCRYPT_DECRYPT');

    const [enc] = await client.encrypt({ name: k1.name, plaintext: Buffer.from('secret') });
    await expect(client.decrypt({ name: k2.name, ciphertext: enc.ciphertext })).rejects.toThrow();
  });

  it('EC signs and verifies with the returned public key', async () => {
    const key = await createKey(uniqueName('ec'), 'ASYMMETRIC_SIGN', 'EC_SIGN_P256_SHA256');
    const versionName = `${key.name}/cryptoKeyVersions/1`;

    const data = Buffer.from('ecdsa-message');
    const digest = crypto.createHash('sha256').update(data).digest();
    const [signResponse] = await client.asymmetricSign({ name: versionName, digest: { sha256: digest } });

    const [publicKey] = await client.getPublicKey({ name: versionName });
    const verifier = crypto.createVerify('SHA256');
    verifier.update(data);
    const verified = verifier.verify(publicKey.pem as string, Buffer.from(signResponse.signature as Uint8Array));
    expect(verified).toBe(true);
  });

  it('generates random bytes', async () => {
    const [response] = await client.generateRandomBytes({
      location: `projects/${PROJECT_ID}/locations/${LOCATION}`,
      lengthBytes: 32,
      protectionLevel: 'SOFTWARE',
    });
    expect((response.data as Uint8Array).length).toBe(32);
  });
});
