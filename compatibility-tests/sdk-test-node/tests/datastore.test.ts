import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { Datastore } from '@google-cloud/datastore';
import { PROJECT_ID, DATASTORE_HOST, authClient, uniqueName } from './setup';

describe('Datastore', () => {
  let datastore: Datastore;
  const kind = 'TestTask';
  let entityKey: ReturnType<Datastore['key']>;

  beforeAll(() => {
    process.env.DATASTORE_EMULATOR_HOST = DATASTORE_HOST;
    datastore = new Datastore({ projectId: PROJECT_ID, authClient: authClient() });
    entityKey = datastore.key([kind, uniqueName('task')]);
  });

  afterAll(async () => {
    try {
      await datastore.delete(entityKey).catch(() => {});
    } catch {
      // ignore
    }
  });

  it('should save an entity', async () => {
    await datastore.save({
      key: entityKey,
      data: [
        { name: 'description', value: 'Buy groceries' },
        { name: 'done', value: false },
        { name: 'priority', value: 4 },
      ],
    });
  });

  it('should get an entity by key', async () => {
    const [entity] = await datastore.get(entityKey);
    expect(entity).toBeDefined();
    expect(entity.description).toBe('Buy groceries');
    expect(entity.done).toBe(false);
    expect(entity.priority).toBe(4);
  });

  it('should run a query', async () => {
    const query = datastore.createQuery(kind).filter('done', '=', false);
    const [entities] = await datastore.runQuery(query);
    expect(entities.length).toBeGreaterThan(0);
    expect(entities.some((e: any) => e.description === 'Buy groceries')).toBe(true);
  });

  it('should update an entity', async () => {
    await datastore.update({
      key: entityKey,
      data: [
        { name: 'description', value: 'Buy groceries' },
        { name: 'done', value: true },
        { name: 'priority', value: 4 },
      ],
    });
    const [entity] = await datastore.get(entityKey);
    expect(entity.done).toBe(true);
  });

  it('should delete an entity', async () => {
    await datastore.delete(entityKey);
    const [entity] = await datastore.get(entityKey);
    expect(entity).toBeUndefined();
  });
});
