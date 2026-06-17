import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { v2 } from '@google-cloud/logging';
import * as grpc from '@grpc/grpc-js';
import { PROJECT_ID, LOGGING_HOST, uniqueName } from './setup';

describe('Cloud Logging', () => {
  let client: InstanceType<typeof v2.LoggingServiceV2Client>;
  let logName: string;
  const parent = `projects/${PROJECT_ID}`;

  beforeAll(() => {
    const [host, port] = LOGGING_HOST.split(':');
    client = new v2.LoggingServiceV2Client({
      servicePath: host,
      port: port ? parseInt(port, 10) : 4588,
      sslCreds: grpc.credentials.createInsecure(),
    });
    logName = `${parent}/logs/${uniqueName('node-log')}`;
  });

  afterAll(async () => {
    await client.deleteLog({ logName }).catch(() => {});
    await client.close();
  });

  function entry(severity: string, text: string) {
    return {
      logName,
      resource: { type: 'global' },
      severity,
      textPayload: text,
    };
  }

  it('writes and lists log entries', async () => {
    await client.writeLogEntries({
      entries: [entry('INFO', 'info-message'), entry('ERROR', 'error-message')],
    });

    const [entries] = await client.listLogEntries({
      resourceNames: [parent],
      filter: `logName="${logName}"`,
    });

    expect(entries.length).toBe(2);
    expect(entries.every((e) => e.resource?.type === 'global')).toBe(true);
    expect(entries.map((e) => e.textPayload).sort()).toEqual(['error-message', 'info-message']);
  });

  it('filters by severity', async () => {
    const [entries] = await client.listLogEntries({
      resourceNames: [parent],
      filter: `logName="${logName}" AND severity>=WARNING`,
    });
    expect(entries.length).toBe(1);
    expect(entries[0].textPayload).toBe('error-message');
    expect(entries[0].severity).toBe('ERROR');
  });

  it('lists logs', async () => {
    const [logNames] = await client.listLogs({ parent });
    expect(logNames).toContain(logName);
  });
});
