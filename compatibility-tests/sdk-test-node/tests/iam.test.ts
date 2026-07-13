import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { ENDPOINT, PROJECT_ID, authHeaders, uniqueName } from './setup';

const saBase = () => `${ENDPOINT}/v1/projects/${PROJECT_ID}/serviceAccounts`;

async function post(url: string, body: unknown = {}): Promise<unknown> {
  const resp = await fetch(url, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(body),
  });
  expect(resp.status).toBe(200);
  return resp.json();
}

async function get(url: string): Promise<unknown> {
  const resp = await fetch(url, { headers: authHeaders() });
  expect(resp.status).toBe(200);
  return resp.json();
}

async function del(url: string): Promise<void> {
  await fetch(url, { method: 'DELETE', headers: authHeaders() });
}

describe('IAM', () => {
  let accountId: string;
  let email: string;

  beforeAll(() => {
    accountId = uniqueName('node-sa');
    email = `${accountId}@${PROJECT_ID}.iam.gserviceaccount.com`;
  });

  afterAll(async () => {
    if (email) {
      await del(`${saBase()}/${email}`);
    }
  });

  it('should create a service account', async () => {
    const resp = await post(saBase(), {
      accountId,
      serviceAccount: { displayName: 'Node Test SA' },
    }) as Record<string, string>;

    expect(resp.email).toBe(email);
    expect(resp.projectId).toBe(PROJECT_ID);
    expect(resp.name).toContain(accountId);
  });

  it('should get a service account', async () => {
    const resp = await get(`${saBase()}/${email}`) as Record<string, string>;
    expect(resp.email).toBe(email);
    expect(resp.displayName).toBe('Node Test SA');
  });

  it('should list service accounts', async () => {
    const resp = await get(saBase()) as { accounts: Array<{ email: string }> };
    expect(resp.accounts.some((a) => a.email === email)).toBe(true);
  });

  it('should get an empty IAM policy', async () => {
    const resp = await post(`${saBase()}/${email}:getIamPolicy`) as {
      version: number;
      bindings: unknown[];
    };
    expect(resp.version).toBe(1);
    expect(resp.bindings).toHaveLength(0);
  });

  it('should set and get IAM policy', async () => {
    const resp = await post(`${saBase()}/${email}:setIamPolicy`, {
      policy: {
        version: 1,
        bindings: [{ role: 'roles/iam.serviceAccountUser', members: ['user:alice@example.com'] }],
      },
    }) as { bindings: Array<{ role: string }> };

    expect(resp.bindings).toHaveLength(1);
    expect(resp.bindings[0].role).toBe('roles/iam.serviceAccountUser');
  });

  it('should test IAM permissions', async () => {
    const permissions = ['iam.serviceAccounts.get', 'iam.serviceAccounts.list'];
    const resp = await post(`${saBase()}/${email}:testIamPermissions`, { permissions }) as {
      permissions: string[];
    };
    expect(resp.permissions).toEqual(expect.arrayContaining(permissions));
  });

  it('should delete a service account', async () => {
    await del(`${saBase()}/${email}`);

    const resp = await get(saBase()) as { accounts: Array<{ email: string }> };
    expect(resp.accounts.some((a) => a.email === email)).toBe(false);
    email = '';
  });
});
