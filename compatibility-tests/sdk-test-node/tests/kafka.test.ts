import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { ENDPOINT, PROJECT_ID, authHeaders, uniqueName } from './setup';

const base = () =>
  `${ENDPOINT}/v1/projects/${PROJECT_ID}/locations/us-central1`;

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

async function patch(url: string, body: unknown = {}): Promise<unknown> {
  const resp = await fetch(url, {
    method: 'PATCH',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(body),
  });
  expect(resp.status).toBe(200);
  return resp.json();
}

async function del(url: string): Promise<void> {
  await fetch(url, { method: 'DELETE', headers: authHeaders() });
}

describe('Managed Kafka', () => {
  let clusterId: string;
  let topicId: string;

  beforeAll(() => {
    clusterId = uniqueName('node-cluster');
    topicId = uniqueName('node-topic');
  });

  afterAll(async () => {
    if (clusterId) {
      await del(`${base()}/clusters/${clusterId}`);
    }
  });

  it('should create a cluster', async () => {
    const resp = await post(`${base()}/clusters?clusterId=${clusterId}`, {
      capacityConfig: { vcpuCount: 3, memoryBytes: 3221225472 },
      gcpConfig: { accessConfig: { networkConfigs: [] } },
    }) as { done: boolean; response: Record<string, string> };

    expect(resp.done).toBe(true);
    expect(resp.response.name).toContain(clusterId);
    expect(resp.response.state).toBe('ACTIVE');
  });

  it('should get a cluster', async () => {
    const resp = await get(`${base()}/clusters/${clusterId}`) as Record<string, string>;
    expect(resp.name).toContain(clusterId);
    expect(resp.state).toBe('ACTIVE');
    expect(resp.bootstrapAddress).toBeTruthy();
  });

  it('should list clusters', async () => {
    const resp = await get(`${base()}/clusters`) as { clusters: Array<{ name: string }> };
    expect(resp.clusters.some((c) => c.name.includes(clusterId))).toBe(true);
  });

  it('should update a cluster', async () => {
    const resp = await patch(`${base()}/clusters/${clusterId}`, {
      capacityConfig: { vcpuCount: 6, memoryBytes: 6442450944 },
    }) as { done: boolean; response: Record<string, unknown> };
    expect(resp.done).toBe(true);
    expect(resp.response.vcpuCount).toBe(6);
  });

  it('should create a topic', async () => {
    const resp = await post(
      `${base()}/clusters/${clusterId}/topics?topicId=${topicId}`,
      { partitionCount: 3, replicationFactor: 1 },
    ) as Record<string, unknown>;

    expect((resp.name as string)).toContain(topicId);
    expect(resp.partitionCount).toBe(3);
  });

  it('should get a topic', async () => {
    const resp = await get(
      `${base()}/clusters/${clusterId}/topics/${topicId}`,
    ) as Record<string, unknown>;
    expect((resp.name as string)).toContain(topicId);
    expect(resp.partitionCount).toBe(3);
  });

  it('should list topics', async () => {
    const resp = await get(
      `${base()}/clusters/${clusterId}/topics`,
    ) as { topics: Array<{ name: string }> };
    expect(resp.topics.some((t) => t.name.includes(topicId))).toBe(true);
  });

  it('should update a topic', async () => {
    const resp = await patch(
      `${base()}/clusters/${clusterId}/topics/${topicId}`,
      { partitionCount: 6 },
    ) as Record<string, unknown>;
    expect(resp.partitionCount).toBe(6);
  });

  it('should list consumer groups', async () => {
    const resp = await get(
      `${base()}/clusters/${clusterId}/consumerGroups`,
    ) as Record<string, unknown>;
    expect(resp).toHaveProperty('consumerGroups');
  });

  it('should delete topic', async () => {
    await del(`${base()}/clusters/${clusterId}/topics/${topicId}`);

    const resp = await get(
      `${base()}/clusters/${clusterId}/topics`,
    ) as { topics: Array<{ name: string }> };
    expect(resp.topics.every((t) => !t.name.includes(topicId))).toBe(true);
    topicId = '';
  });

  it('should delete cluster', async () => {
    await del(`${base()}/clusters/${clusterId}`);

    const resp = await get(`${base()}/clusters`) as { clusters: Array<{ name: string }> };
    expect(resp.clusters.every((c) => !c.name.includes(clusterId))).toBe(true);
    clusterId = '';
  });
});
