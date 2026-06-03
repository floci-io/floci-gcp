import { describe, it, expect } from 'vitest';
import { ENDPOINT, PROJECT_ID, uniqueName } from './setup';

// Regression coverage for issue #5: bucket creation returned 415 when the
// request did not declare Content-Type: application/json. Real GCS is lenient.
// The high-level SDK always sends application/json, so this uses raw fetch.
describe('GCS bucket insert content-type tolerance', () => {
  const url = `${ENDPOINT}/storage/v1/b?project=${PROJECT_ID}`;

  it('accepts a JSON body without a Content-Type header', async () => {
    const name = uniqueName('raw-bucket');
    const response = await fetch(url, {
      method: 'POST',
      body: JSON.stringify({ name }),
    });
    expect(response.status).toBe(200);
    const json = (await response.json()) as { name?: string };
    expect(json.name).toBe(name);
  });

  it('still accepts a JSON body with an explicit Content-Type header', async () => {
    const name = uniqueName('raw-bucket-json');
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name }),
    });
    expect(response.status).toBe(200);
    const json = (await response.json()) as { name?: string };
    expect(json.name).toBe(name);
  });
});
