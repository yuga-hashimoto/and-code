import { test } from 'node:test';
import assert from 'node:assert/strict';

let worker;
try {
  ({ default: worker } = await import('../src/worker.mjs'));
} catch (error) {
  if (error.code !== 'ERR_MODULE_NOT_FOUND') throw error;
}

function environment({ rateAllowed = true, dbSuccess = true } = {}) {
  const writes = [];
  const env = {
    REPORTS: { prepare(sql) { return { bind(...args) { return { async run() { writes.push({ sql, args }); return { success: dbSuccess }; } }; } }; } },
    REPORT_RATE_LIMIT: { async limit() { return { success: rateAllowed }; } },
  };
  return { env, writes };
}
function request(body, extra = {}) {
  return new Request('https://reports.example.test/v1/reports', {
    method: 'POST', headers: { 'content-type': 'application/json', 'cf-connecting-ip': '203.0.113.1', ...extra },
    body: JSON.stringify(body),
  });
}

test('accepts an anonymous report and persists only the reason and user-entered excerpt', async () => {
  assert.ok(worker, 'a private report receiver is required');
  const { env, writes } = environment();
  const response = await worker.fetch(request({ reason: 'HARMFUL', excerpt: 'reported example' }), env);
  assert.equal(response.status, 201);
  assert.equal(writes.length, 1);
  assert.ok(writes[0].args.includes('HARMFUL'));
  assert.ok(writes[0].args.includes('reported example'));
  assert.ok(!JSON.stringify(writes).includes('203.0.113.1'));
  assert.equal(response.headers.get('cache-control'), 'no-store');
});

test('rejects unexpected fields and known credential patterns before writing', async () => {
  assert.ok(worker, 'a private report receiver is required');
  for (const body of [
    { reason: 'HARMFUL', excerpt: 'text', conversation: 'private' },
    { reason: 'OTHER', excerpt: 'ghp_abcdefghijklmnopqrstuvwxyz1234567890' },
    { reason: 'OTHER', excerpt: '' },
    { reason: 'UNKNOWN', excerpt: 'text' },
  ]) {
    const { env, writes } = environment();
    assert.equal((await worker.fetch(request(body), env)).status, 400);
    assert.equal(writes.length, 0);
  }
});

test('rate limiting and storage failures do not claim delivery', async () => {
  assert.ok(worker, 'a private report receiver is required');
  for (const [options, status] of [[{ rateAllowed: false }, 429], [{ dbSuccess: false }, 503]]) {
    const { env } = environment(options);
    assert.equal((await worker.fetch(request({ reason: 'OTHER', excerpt: 'text' }), env)).status, status);
  }
});

test('rejects oversized and unsupported requests', async () => {
  assert.ok(worker, 'a private report receiver is required');
  const { env, writes } = environment();
  assert.equal((await worker.fetch(request({ reason: 'OTHER', excerpt: 'x'.repeat(20000) }), env)).status, 413);
  assert.equal((await worker.fetch(new Request('https://reports.example.test/v1/reports'), env)).status, 405);
  assert.equal(writes.length, 0);
});

test('scheduled cleanup removes expired reports without exposing a read API', async () => {
  assert.ok(worker, 'a private report receiver is required');
  const { env, writes } = environment();
  await worker.scheduled({ scheduledTime: Date.UTC(2026, 0, 1) }, env);
  assert.match(writes[0].sql, /DELETE FROM reports/);
  assert.equal(writes[0].args[0], Date.UTC(2026, 0, 1) - 90 * 86400000);
});

test('rejects lookalike JSON media types', async () => {
  const { env, writes } = environment();
  const response = await worker.fetch(request({ reason: 'OTHER', excerpt: 'text' }, { 'content-type': 'application/jsonp' }), env);
  assert.equal(response.status, 415);
  assert.equal(writes.length, 0);
});
