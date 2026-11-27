const MAX_BYTES = 16 * 1024;
const MAX_EXCERPT_LENGTH = 4000;
const RETENTION_MS = 90 * 24 * 60 * 60 * 1000;
const REASONS = new Set(['HARMFUL', 'HARASSMENT', 'DECEPTIVE', 'OTHER']);
const CREDENTIAL = /(?:github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{30,})/;

function response(status, body) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' },
  });
}

async function boundedBody(request) {
  const declared = request.headers.get('content-length');
  if (declared !== null && (!/^\d+$/.test(declared) || Number(declared) > MAX_BYTES)) return null;
  if (!request.body) return new Uint8Array();
  const reader = request.body.getReader();
  const chunks = [];
  let size = 0;
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > MAX_BYTES) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return bytes;
}

export default {
  async fetch(request, env) {
    if (new URL(request.url).pathname !== '/v1/reports') return response(404, { error: 'not_found' });
    if (request.method !== 'POST') return response(405, { error: 'method_not_allowed' });
    if (!/^application\/json(?:\s*;|\s*$)/i.test(request.headers.get('content-type') || '')) {
      return response(415, { error: 'unsupported_media_type' });
    }
    const ip = request.headers.get('cf-connecting-ip');
    if (!ip || ip.length > 64) return response(400, { error: 'invalid_request' });
    try {
      const { success } = await env.REPORT_RATE_LIMIT.limit({ key: ip });
      if (!success) return response(429, { error: 'rate_limited' });
      const bytes = await boundedBody(request);
      if (bytes === null) return response(413, { error: 'too_large' });
      let payload;
      try {
        payload = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
      } catch {
        return response(400, { error: 'invalid_request' });
      }
      if (!payload || Array.isArray(payload) || typeof payload !== 'object' ||
          Object.keys(payload).sort().join(',') !== 'excerpt,reason' ||
          !REASONS.has(payload.reason) || typeof payload.excerpt !== 'string') {
        return response(400, { error: 'invalid_request' });
      }
      const excerpt = payload.excerpt.trim();
      if (!excerpt || excerpt.length > MAX_EXCERPT_LENGTH || CREDENTIAL.test(excerpt)) {
        return response(400, { error: 'invalid_request' });
      }
      const id = crypto.randomUUID();
      const result = await env.REPORTS.prepare(
        'INSERT INTO reports (id, reason, excerpt, created_at) VALUES (?1, ?2, ?3, ?4)',
      ).bind(id, payload.reason, excerpt, Date.now()).run();
      if (!result.success) return response(503, { error: 'unavailable' });
      return response(201, { id });
    } catch {
      // Do not log request bodies, IP addresses, or database error details.
      return response(503, { error: 'unavailable' });
    }
  },

  async scheduled(controller, env) {
    const result = await env.REPORTS.prepare('DELETE FROM reports WHERE created_at < ?1')
      .bind(controller.scheduledTime - RETENTION_MS).run();
    if (!result.success) throw new Error('Report retention cleanup failed');
  },
};
