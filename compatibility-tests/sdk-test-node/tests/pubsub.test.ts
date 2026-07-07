import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { PubSub, v1 } from '@google-cloud/pubsub';
import { PROJECT_ID, PUBSUB_HOST, uniqueName, sleep } from './setup';

describe('Pub/Sub', () => {
  let pubsub: PubSub;
  let subscriberClient: v1.SubscriberClient;
  let topicName: string;
  let subscriptionName: string;

  beforeAll(() => {
    process.env.PUBSUB_EMULATOR_HOST = PUBSUB_HOST;
    pubsub = new PubSub({ projectId: PROJECT_ID });
    subscriberClient = new v1.SubscriberClient({
      servicePath: PUBSUB_HOST.split(':')[0],
      port: parseInt(PUBSUB_HOST.split(':')[1] || '4588'),
      sslCreds: require('@grpc/grpc-js').credentials.createInsecure(),
    });
    topicName = uniqueName('test-topic');
    subscriptionName = uniqueName('test-sub');
  });

  afterAll(async () => {
    try {
      await pubsub.subscription(subscriptionName).delete().catch(() => {});
      await pubsub.topic(topicName).delete().catch(() => {});
    } catch {
      // ignore cleanup errors
    }
  });

  it('should create a topic', async () => {
    const [topic] = await pubsub.createTopic(topicName);
    expect(topic.name).toContain(topicName);
  });

  it('should list topics and find created topic', async () => {
    const [topics] = await pubsub.getTopics();
    expect(topics.some((t) => t.name.endsWith(topicName))).toBe(true);
  });

  it('should create a subscription', async () => {
    const [subscription] = await pubsub.topic(topicName).createSubscription(subscriptionName);
    expect(subscription.name).toContain(subscriptionName);
  });

  it('should list subscriptions and find created subscription', async () => {
    const [subscriptions] = await pubsub.getSubscriptions();
    expect(subscriptions.some((s) => s.name.endsWith(subscriptionName))).toBe(true);
  });

  it('should publish messages', async () => {
    const topic = pubsub.topic(topicName);
    const id1 = await topic.publishMessage({ data: Buffer.from('Hello, GCP Pub/Sub!'), attributes: { source: 'node-test' } });
    const id2 = await topic.publishMessage({ data: Buffer.from('Second message') });
    expect(id1).toBeTruthy();
    expect(id2).toBeTruthy();
    expect(id1).not.toBe(id2);
  });

  it('should pull messages and acknowledge', async () => {
    await sleep(200);

    const subPath = `projects/${PROJECT_ID}/subscriptions/${subscriptionName}`;
    const [response] = await subscriberClient.pull({ subscription: subPath, maxMessages: 10 });
    const messages = response.receivedMessages ?? [];
    expect(messages.length).toBeGreaterThanOrEqual(2);

    const bodies = messages.map((m) => m.message?.data?.toString() ?? '');
    expect(bodies).toContain('Hello, GCP Pub/Sub!');
    expect(bodies).toContain('Second message');

    await subscriberClient.acknowledge({
      subscription: subPath,
      ackIds: messages.map((m) => m.ackId ?? ''),
    });
  });

  it('should stream messages published after the listener starts', async () => {
    const streamingTopicName = uniqueName('test-stream-topic');
    const streamingSubscriptionName = uniqueName('test-stream-sub');
    const [topic] = await pubsub.createTopic(streamingTopicName);
    const [subscription] = await topic.createSubscription(streamingSubscriptionName);

    try {
      const received = new Promise<string>((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('No streaming message received')), 10000);
        subscription.on('message', (message) => {
          clearTimeout(timer);
          message.ack();
          resolve(message.data.toString());
        });
        subscription.on('error', (error) => {
          clearTimeout(timer);
          reject(error);
        });
      });

      await sleep(200);
      await topic.publishMessage({
        data: Buffer.from('StreamingPull message'),
        attributes: { source: 'node-streaming-test' },
      });

      await expect(received).resolves.toBe('StreamingPull message');
    } finally {
      await subscription.close().catch(() => {});
      await subscription.delete().catch(() => {});
      await topic.delete().catch(() => {});
    }
  });

  it('should delete subscription', async () => {
    await pubsub.subscription(subscriptionName).delete();
    const [subscriptions] = await pubsub.getSubscriptions();
    expect(subscriptions.some((s) => s.name.endsWith(subscriptionName))).toBe(false);
    subscriptionName = '';
  });

  it('should delete topic', async () => {
    await pubsub.topic(topicName).delete();
    const [topics] = await pubsub.getTopics();
    expect(topics.some((t) => t.name.endsWith(topicName))).toBe(false);
    topicName = '';
  });
});
