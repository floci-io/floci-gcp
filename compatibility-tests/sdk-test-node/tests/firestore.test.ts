import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { Firestore } from '@google-cloud/firestore';
import { PROJECT_ID, FIRESTORE_HOST, authClient, uniqueName } from './setup';

describe('Firestore', () => {
  let db: Firestore;
  let collectionName: string;
  let docId: string;

  beforeAll(() => {
    process.env.FIRESTORE_EMULATOR_HOST = FIRESTORE_HOST;
    db = new Firestore({ projectId: PROJECT_ID, authClient: authClient() });
    collectionName = uniqueName('test-col');
    docId = uniqueName('doc');
  });

  afterAll(async () => {
    try {
      await db.collection(collectionName).doc(docId).delete().catch(() => {});
      await db.terminate();
    } catch {
      // ignore
    }
  });

  it('should add a document', async () => {
    await db.collection(collectionName).doc(docId).set({
      name: 'Alice',
      age: 30,
      active: true,
    });
    const doc = await db.collection(collectionName).doc(docId).get();
    expect(doc.exists).toBe(true);
    expect(doc.data()?.name).toBe('Alice');
  });

  it('should get a document by ID', async () => {
    const doc = await db.collection(collectionName).doc(docId).get();
    expect(doc.exists).toBe(true);
    expect(doc.data()?.age).toBe(30);
  });

  it('should query documents', async () => {
    const snapshot = await db.collection(collectionName)
      .where('name', '==', 'Alice')
      .get();
    expect(snapshot.empty).toBe(false);
    expect(snapshot.docs[0].data().name).toBe('Alice');
  });

  it('should update a document', async () => {
    await db.collection(collectionName).doc(docId).update({ age: 31 });
    const doc = await db.collection(collectionName).doc(docId).get();
    expect(doc.data()?.age).toBe(31);
  });

  it('should list documents in collection', async () => {
    const snapshot = await db.collection(collectionName).get();
    expect(snapshot.empty).toBe(false);
    expect(snapshot.docs.some((d) => d.id === docId)).toBe(true);
  });

  it('should delete a document', async () => {
    await db.collection(collectionName).doc(docId).delete();
    const doc = await db.collection(collectionName).doc(docId).get();
    expect(doc.exists).toBe(false);
    docId = '';
  });
});
