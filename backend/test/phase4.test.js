import test from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/worker.js';
import { PoodlesQuota, LIMITS, reserveQuota } from '../src/quota.js';
import { MODELS } from '../src/inference.js';
import { CHAT_PERSONALITY } from '../src/personality.js';

const now = Date.parse('2026-10-04T10:00:00Z');
const textBody = (extra = {}) => ({ task: 'text', instructions: 'Keep missing label evidence uncertain.',
  input: 'Ingredients: rice, water', history: [], maxTokens: 100, stream: false, ...extra });
const researchBody = (extra = {}) => ({ requestId: 'phase4-request', profileRevision: 2,
  task: 'food_check', subject: 'Synthetic product', ...extra });
function request(token, body, path = '/v1/help', headers = {}) {
  return new Request('https://poodles' + path, { method: 'POST', headers: {
    Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...headers },
    body: typeof body === 'string' ? body : JSON.stringify(body) });
}
async function harness(t) {
  t.mock.method(Date, 'now', () => now);
  const token = 'phase4-test-only-credential-'.repeat(2);
  const hash = [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(token)))].map(b => b.toString(16).padStart(2, '0')).join('');
  const bucket = (daily, neurons) => ({ daily, neurons, minute: Math.floor(now / 60000) - 1, perMinute: 2 });
  const values = new Map([
    ['quota-v2', { date: '2026-10-04', buckets: { chat: bucket(10, 400), assistance: bucket(4, 200), vision: bucket(3, 600) } }],
    ['provider-budgets-v1', { providers: {} }],
  ]);
  const calls = { quota: [], writes: [], ai: [], external: [] };
  // Clone reads/writes just as durable storage does; avoid mutating stored fixtures by reference.
  const storage = { get: async key => structuredClone(values.get(key)),
    put: async (key, value) => { calls.writes.push(key); values.set(key, structuredClone(value)); },
    transaction: callback => callback(storage) };
  const quota = new PoodlesQuota({ storage });
  const env = { APP_TOKEN_SHA256: hash, FREE_PROVIDERS_VERIFIED: 'groq,exa,tavily',
    GROQ_API_KEY: 'fixture-only', EXA_API_KEY: 'fixture-only', TAVILY_API_KEY: 'fixture-only',
    AI: { run: async (model, input) => { calls.ai.push({ model, input }); return { response: 'Let us check the missing details together.' }; } },
    QUOTA: { idFromName: name => name, get: () => ({ fetch: async req => {
      calls.quota.push(new URL(req.url).pathname); return quota.fetch(req);
    } }) } };
  t.mock.method(globalThis, 'fetch', async (...args) => { calls.external.push(args); throw new Error('Unexpected external provider call'); });
  return { token, env, calls, values, quota };
}
function assertNoWork(h, before) {
  assert.deepEqual(h.calls, { quota: [], writes: [], ai: [], external: [] });
  assert.deepEqual(h.values, before);
}

test('retired vision requests fail at the HTTP boundary before quota or provider work', async t => {
  const h = await harness(t), before = structuredClone(h.values);
  for (const extra of [{}, { image: 'data:image/jpeg;base64,YWJj' }, { image: 'https://example.org/label.jpg' }]) {
    const result = await worker.fetch(request(h.token, textBody({ task: 'vision', allowExternalFallback: true, ...extra })), h.env);
    assert.equal(result.status, 400);
    assert.deepEqual(await result.json(), { error: { code: 'invalid_request' } });
    assert.equal(result.headers.get('Cache-Control'), 'no-store');
  }
  assertNoWork(h, before);
});

test('every JSON image value is rejected on all surviving v1 tasks without consuming allowance', async t => {
  const h = await harness(t), before = structuredClone(h.values);
  for (const task of ['chat', 'text', 'structured', 'recipe', 'plan']) {
    for (const image of [null, false, 0, '', [], {}, 'data:image/png;base64,YWJj']) {
      const result = await worker.fetch(request(h.token, textBody({ task, stream: task === 'chat', image, allowExternalFallback: true })), h.env);
      assert.equal(result.status, 400, `${task} with ${JSON.stringify(image)}`);
      assert.equal((await result.json()).error.code, 'invalid_request');
    }
  }
  assertNoWork(h, before);
});

test('research cannot act as an alternate image route or revive the vision task', async t => {
  const h = await harness(t), before = structuredClone(h.values);
  for (const body of [researchBody({ task: 'vision' }), ...[null, false, '', {}, 'data:image/jpeg;base64,YWJj'].map(image => researchBody({ image, allowExternalModel: true }))]) {
    const result = await worker.fetch(request(h.token, body, '/v2/research'), h.env);
    assert.equal(result.status, 400);
    assert.equal((await result.json()).error.code, 'invalid_request');
  }
  assertNoWork(h, before);
});

test('the 160000-byte v1 cap rejects declared and actual oversized bodies before reservations', async t => {
  const h = await harness(t), before = structuredClone(h.values);
  // Multibyte JSON is below 160000 characters but above 160000 UTF-8 bytes.
  const oversized = JSON.stringify(textBody({ image: 'é'.repeat(80000) }));
  assert.ok(oversized.length < 160000);
  assert.ok(new TextEncoder().encode(oversized).length > 160000);
  for (const req of [request(h.token, textBody(), '/v1/help', { 'Content-Length': '160001' }),
    request(h.token, oversized), request(h.token, oversized, '/v1/help', { 'Content-Length': '1' })]) {
    const result = await worker.fetch(req, h.env);
    assert.equal(result.status, 413);
    assert.equal((await result.json()).error.code, 'invalid_request');
  }
  assertNoWork(h, before);
});

test('streamed oversized input is cancelled without trusting a missing Content-Length', async t => {
  const h = await harness(t), before = structuredClone(h.values);
  let cancelled = false;
  const body = new ReadableStream({ pull(controller) { controller.enqueue(new Uint8Array(40001)); },
    cancel() { cancelled = true; } });
  const req = new Request('https://poodles/v1/help', { method: 'POST', duplex: 'half', body,
    headers: { Authorization: `Bearer ${h.token}`, 'Content-Type': 'application/json' } });
  assert.equal((await worker.fetch(req, h.env)).status, 413);
  assert.equal(cancelled, true);
  assertNoWork(h, before);
});

test('exactly 160000 UTF-8 bytes still accepts a valid legacy text request', async t => {
  const h = await harness(t);
  const json = JSON.stringify(textBody());
  // Legal trailing JSON whitespace exercises the transport limit without exceeding field limits.
  const body = json + ' '.repeat(160000 - new TextEncoder().encode(json).length);
  assert.equal(new TextEncoder().encode(body).length, 160000);
  const result = await worker.fetch(request(h.token, body), h.env);
  assert.equal(result.status, 200);
  assert.deepEqual(await result.json(), { text: 'Let us check the missing details together.' });
  assert.deepEqual(h.calls.quota, ['/consume']);
  assert.equal(h.calls.ai.length, 1);
  assert.equal(h.calls.external.length, 0);
});

test('vision has no configured model or reservable quota even when its historical bucket exists', async t => {
  const h = await harness(t), before = structuredClone(h.values);
  assert.equal(Object.hasOwn(MODELS, 'vision'), false);
  assert.equal(Object.hasOwn(LIMITS, 'vision'), false);
  assert.throws(() => reserveQuota(h.values.get('quota-v2'), now, 'vision', 1), /invalid/);
  assertNoWork(h, before);
});

test('text response and status preserve active quota usage and retired same-day vision history', async t => {
  const h = await harness(t), before = structuredClone(h.values.get('quota-v2'));
  const result = await worker.fetch(request(h.token, textBody()), h.env);
  assert.equal(result.status, 200);
  assert.deepEqual(await result.json(), { text: 'Let us check the missing details together.' });
  assert.deepEqual(h.calls.quota, ['/consume']);
  assert.equal(h.calls.ai.length, 1);
  assert.equal(h.calls.external.length, 0);
  const stored = h.values.get('quota-v2');
  assert.deepEqual(stored.buckets.vision, before.buckets.vision);
  assert.deepEqual(stored.buckets.chat, before.buckets.chat);
  assert.equal(stored.buckets.assistance.daily, before.buckets.assistance.daily + 1);
  assert.ok(stored.buckets.assistance.neurons > before.buckets.assistance.neurons);
  const status = await worker.fetch(new Request('https://poodles/v1/status', { headers: { Authorization: `Bearer ${h.token}` } }), h.env);
  const counters = await status.json();
  assert.deepEqual(Object.keys(counters).sort(), ['assistance', 'chat']);
  assert.equal(counters.chat.used, 10);
  assert.equal(counters.assistance.used, 5);
  assert.equal(h.calls.writes.length, 1, 'status must not rewrite legacy storage');
});

test('research compute reservations preserve historical buckets and respect existing assistance exhaustion', async t => {
  const h = await harness(t), before = structuredClone(h.values.get('quota-v2'));
  const reserve = () => h.quota.fetch(new Request('https://quota/v2/reserve', { method: 'POST',
    body: JSON.stringify({ provider: 'cloudflare', cost: { requests: 1, neurons: 50 } }) }));
  assert.equal((await reserve()).status, 204);
  assert.deepEqual(h.values.get('quota-v2').buckets.vision, before.buckets.vision);
  assert.deepEqual(h.values.get('quota-v2').buckets.chat, before.buckets.chat);
  assert.equal(h.values.get('quota-v2').buckets.assistance.daily, 5);
  h.values.get('quota-v2').buckets.assistance.neurons = 1800;
  const exhausted = structuredClone(h.values);
  const result = await reserve();
  assert.equal(result.status, 429);
  assert.equal(result.headers.get('X-Poodles-Limit'), 'research_provider');
  assert.deepEqual(h.values, exhausted, 'failed reservation must not reset histories or debit provider state');
});

test('retired requests do not rewrite prior-day history; valid text retains ordinary UTC rollover', async t => {
  const h = await harness(t);
  h.values.get('quota-v2').date = '2026-10-03';
  const before = structuredClone(h.values);
  const retired = await worker.fetch(request(h.token, textBody({ task: 'vision' })), h.env);
  assert.equal(retired.status, 400);
  assertNoWork(h, before);
  const active = await worker.fetch(request(h.token, textBody()), h.env);
  assert.equal(active.status, 200);
  const stored = h.values.get('quota-v2');
  assert.equal(stored.date, '2026-10-04');
  assert.deepEqual(Object.keys(stored.buckets), ['assistance']);
  assert.equal(stored.buckets.assistance.daily, 1);
  assert.deepEqual(h.values.get('provider-budgets-v1'), before.get('provider-budgets-v1'));
});

test('all nonstream text tasks preserve the legacy envelope and shared personality', async t => {
  const h = await harness(t);
  for (const task of ['text', 'structured', 'recipe', 'plan']) {
    const output = task === 'text' ? 'Let us check together.' : { fixture: task };
    h.env.AI.run = async (model, input) => {
      h.calls.ai.push({ model, input });
      assert.equal(model, MODELS[task]);
      assert.ok(input.messages[0].content.includes(CHAT_PERSONALITY));
      assert.ok(input.messages[0].content.includes(textBody().instructions));
      assert.equal(input.stream, false);
      assert.equal(Object.hasOwn(input, 'image'), false);
      if (task !== 'text') assert.equal(input.response_format.type, task === 'structured' ? 'json_object' : 'json_schema');
      return { response: output };
    };
    const result = await worker.fetch(request(h.token, textBody({ task })), h.env);
    assert.equal(result.status, 200, task);
    const envelope = await result.json();
    assert.deepEqual(Object.keys(envelope), ['text']);
    assert.deepEqual(task === 'text' ? envelope.text : JSON.parse(envelope.text), output);
  }
  assert.deepEqual(h.calls.quota, Array(4).fill('/consume'));
  assert.equal(h.calls.ai.length, 4);
  assert.equal(h.calls.external.length, 0, 'old clients still omit external model consent');
});

test('exhausted legacy assistance prevents both primary and consented fallback calls', async t => {
  const h = await harness(t);
  h.values.get('quota-v2').buckets.assistance.neurons = LIMITS.assistance.neurons;
  const before = structuredClone(h.values);
  const result = await worker.fetch(request(h.token, textBody({ allowExternalFallback: true })), h.env);
  assert.equal(result.status, 429);
  assert.equal((await result.json()).error.code, 'free_limit');
  assert.equal(result.headers.get('X-Poodles-Limit'), 'assistance_daily');
  assert.equal(result.headers.get('Retry-After'), String(14 * 60 * 60));
  assert.deepEqual(h.calls, { quota: ['/consume'], writes: [], ai: [], external: [] });
  assert.deepEqual(h.values, before);
});

test('chat SSE and opt-in Groq fallback retain the same companion policy after image removal', async t => {
  const h = await harness(t);
  const body = textBody({ task: 'chat', stream: true, input: "I'm crying" });
  let primaryInput;
  h.env.AI.run = async (_, input) => {
    primaryInput = input;
    return new ReadableStream({ start(c) { c.enqueue(new TextEncoder().encode('data: {"response":"I am here."}\n\ndata: [DONE]\n\n')); c.close(); } });
  };
  const primary = await worker.fetch(request(h.token, body), h.env);
  assert.equal(primary.status, 200);
  assert.equal(primary.headers.get('Content-Type'), 'text/event-stream');
  assert.match(await primary.text(), /"content":"I am here\."/);
  assert.ok(primaryInput.messages[0].content.includes(CHAT_PERSONALITY));
  assert.ok(primaryInput.messages[0].content.includes(body.instructions));
  h.env.AI.run = async () => { throw new Error('Primary unavailable'); };
  const withoutConsent = await worker.fetch(request(h.token, body), h.env);
  assert.equal(withoutConsent.status, 503);
  assert.equal(h.calls.external.length, 0);
  let fallbackInput;
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    h.calls.external.push(url);
    assert.equal(url, 'https://api.groq.com/openai/v1/chat/completions');
    fallbackInput = JSON.parse(options.body);
    return Response.json({ choices: [{ finish_reason: 'stop', message: { content: 'I am here with you.' } }] });
  });
  const fallback = await worker.fetch(request(h.token, { ...body, allowExternalFallback: true }), h.env);
  assert.equal(fallback.status, 200);
  assert.equal(fallback.headers.get('Content-Type'), 'text/event-stream');
  const stream = await fallback.text();
  assert.match(stream, /"content":"I am here with you\."/);
  assert.match(stream, /data: \[DONE\]/);
  assert.equal(h.calls.external.length, 1);
  assert.deepEqual(fallbackInput.messages, primaryInput.messages);
  assert.ok(!Object.hasOwn(fallbackInput, 'image'));
});
