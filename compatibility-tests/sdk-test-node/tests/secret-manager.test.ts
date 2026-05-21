import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { SecretManagerServiceClient } from '@google-cloud/secret-manager';
import * as grpc from '@grpc/grpc-js';
import { PROJECT_ID, SECRET_MANAGER_HOST, uniqueName } from './setup';

describe('Secret Manager', () => {
  let client: SecretManagerServiceClient;
  let secretId: string;
  let secretName: string;
  const secretPayload = 'super-secret-password-from-node';

  beforeAll(() => {
    const [host, port] = SECRET_MANAGER_HOST.split(':');
    client = new SecretManagerServiceClient({
      servicePath: host,
      port: port ? parseInt(port, 10) : 4588,
      sslCreds: grpc.credentials.createInsecure(),
    });
    secretId = uniqueName('test-secret');
    secretName = `projects/${PROJECT_ID}/secrets/${secretId}`;
  });

  afterAll(async () => {
    try {
      await client.deleteSecret({ name: secretName }).catch(() => {});
      client.close();
    } catch {
      // ignore
    }
  });

  it('should create a secret', async () => {
    const [secret] = await client.createSecret({
      parent: `projects/${PROJECT_ID}`,
      secretId,
      secret: {
        replication: { automatic: {} },
      },
    });
    expect(secret.name).toContain(secretId);
  });

  it('should add a secret version', async () => {
    const [version] = await client.addSecretVersion({
      parent: secretName,
      payload: { data: Buffer.from(secretPayload) },
    });
    expect(version.name).toContain(secretId);
    expect(version.state).toBe('ENABLED');
  });

  it('should access the latest secret version and verify payload', async () => {
    const [response] = await client.accessSecretVersion({
      name: `${secretName}/versions/latest`,
    });
    const payload = response.payload?.data?.toString();
    expect(payload).toBe(secretPayload);
  });

  it('should list secret versions', async () => {
    const [versions] = await client.listSecretVersions({ parent: secretName });
    expect(versions.length).toBeGreaterThan(0);
    expect(versions.every((v) => v.name?.includes(secretId))).toBe(true);
  });

  it('should list secrets and find created secret', async () => {
    const [secrets] = await client.listSecrets({ parent: `projects/${PROJECT_ID}` });
    expect(secrets.some((s) => s.name?.endsWith(secretId))).toBe(true);
  });

  it('should delete a secret', async () => {
    await client.deleteSecret({ name: secretName });
    const [secrets] = await client.listSecrets({ parent: `projects/${PROJECT_ID}` });
    expect(secrets.some((s) => s.name?.endsWith(secretId))).toBe(false);
    secretName = '';
  });
});
