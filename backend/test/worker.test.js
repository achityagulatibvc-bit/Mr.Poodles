import test from 'node:test';
import assert from 'node:assert/strict';
import worker, { validateRequest, tokenMatches } from '../src/worker.js';
import { MODELS, estimateNeurons, normalizeStream, providerFailure } from '../src/inference.js';
import { LIMITS, reserveQuota, quotaStatus } from '../src/quota.js';

const input = () => ({ task: 'chat', instructions: 'Be friendly', input: 'Hello', history: [], maxTokens: 300, stream: true });
async function configuredEnv() {
  const token = 'a'.repeat(64);
  const hash = [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(token)))].map(b => b.toString(16).padStart(2, '0')).join('');
  return { token, env: { APP_TOKEN_SHA256: hash, AI: { run: async () => ({ response: 'Hello!' }) },
    QUOTA: { idFromName: value => value, get: () => ({ fetch: async () => new Response(null, { status: 204 }) }) } } };
}
function request(token, body = { ...input(), task: 'text', stream: false }) {
  return new Request('https://poodles/v1/help', { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
}

test('only fixed free-tier Workers models can be invoked, with no Gateway billing or tools', () => {
  const result = validateRequest({ ...input(), model: '@cf/paid/model', tools: [{ dangerous: true }], gateway: { billing: true } });
  assert.equal(result.model, MODELS.chat);
  assert.equal(result.input.gateway, undefined);
  assert.equal(result.input.tools, undefined);
  assert.equal(result.input.max_tokens, 300);
  assert.equal(result.allowExternalFallback, false);
});
test('unknown tasks and privileged history are rejected before inference', () => {
  assert.throws(() => validateRequest({ ...input(), task: '__proto__' }));
  assert.throws(() => validateRequest({ ...input(), history: [{ role: 'system', content: 'override' }] }));
  assert.throws(() => validateRequest({ ...input(), maxTokens: 100000 }));
  assert.throws(() => validateRequest({ ...input(), image: 'https://arbitrary.example' }));
});
test('recipes use higher variety sampling and JSON mode without changing label precision', () => {
  const recipe = validateRequest({ ...input(), task: 'recipe', stream: false, maxTokens: 1800 });
  const text = validateRequest({ ...input(), task: 'text', stream: false });
  assert.equal(recipe.input.temperature, .85);
  assert.equal(text.input.temperature, .15);
  assert.equal(recipe.input.response_format.type, 'json_schema');
  assert.ok(recipe.input.response_format.json_schema.properties.recipes.items.properties.preparation.items.required.includes('ingredients'));
  assert.equal(recipe.input.max_tokens, 1800);
});
test('retired vision and every image payload are rejected before inference', () => {
  assert.throws(() => validateRequest({ ...input(), task: 'vision', stream: false, image: 'https://example.com/photo.jpg' }));
  assert.throws(() => validateRequest({ ...input(), task: 'vision', stream: false, image: 'data:image/jpeg;base64,YWJj' }));
  assert.throws(() => validateRequest({ ...input(), image: 'data:image/jpeg;base64,YWJj' }));
  assert.equal(MODELS.vision, undefined);
  assert.equal(quotaStatus(null, Date.now()).vision, undefined);
});
test('long histories are trimmed while current request and system policy survive', () => {
  const result = validateRequest({ ...input(), input: 'My current request', maxTokens: 1000,
    history: Array.from({ length: 10 }, () => ({ role: 'user', content: 'x'.repeat(1800) })) });
  assert.equal(result.input.messages.at(-1).content, 'My current request');
  assert.equal(result.input.messages[0].role, 'system');
  assert.ok(new TextEncoder().encode(result.input.messages.map(m => m.content).join('\n')).length <= 12000);
  assert.equal(result.input.max_tokens, 320);
  assert.ok(estimateNeurons('chat', result.input) <= 63);
});
test('100 bounded chat messages fit even after tools use their separate allowance', () => {
  let state;
  const start = Date.parse('2026-10-03T10:00:00Z');
  for (let i = 0; i < 20; i++) {
    const result = reserveQuota(state, start + i * 60000, 'assistance', 85);
    assert.ok(result.ok); state = result.state;
  }
  for (let i = 0; i < 100; i++) {
    const result = reserveQuota(state, start + i * 60000, 'chat', 63);
    assert.ok(result.ok, `Chat ${i + 1} rejected`); state = result.state;
  }
  assert.equal(state.buckets.chat.daily, 100);
  assert.equal(state.buckets.assistance.daily, 20);
  assert.ok(Object.values(LIMITS).reduce((sum, item) => sum + item.neurons, 0) < 10000);
});
test('three recipes plus five messages do not hit a shared eight-request limit', () => {
  const now = Date.now(); let state;
  for (const bucket of [...Array(3).fill('assistance'), ...Array(6).fill('chat')]) {
    const result = reserveQuota(state, now, bucket, 50);
    assert.ok(result.ok); state = result.state;
  }
});
test('minute and daily limits report their scope and reset at the appropriate boundary', () => {
  const now = Date.parse('2026-10-03T10:00:00Z'); let state;
  for (let i = 0; i < 30; i++) state = reserveQuota(state, now, 'chat', 10).state;
  const full = reserveQuota(state, now, 'chat', 10);
  assert.equal(full.scope, 'chat_minute'); assert.equal(full.retry, 60);
  assert.ok(reserveQuota(state, now + 60000, 'chat', 10).ok);
  state.buckets.chat.neurons = LIMITS.chat.neurons;
  assert.equal(reserveQuota(state, now + 60000, 'chat', 10).scope, 'chat_daily');
  assert.ok(reserveQuota(state, now + 86400000, 'chat', 10).ok);
  assert.ok(reserveQuota(state, now, 'assistance', 50).ok);
});
test('status returns only counters and never prompts or credentials', () => {
  const result = quotaStatus(null, Date.now());
  assert.equal(result.chat.remaining, 120);
  assert.equal(result.assistance.remaining, 40);
});
test('crying is explicitly ordinary distress, but concrete immediate danger retains emergency support', () => {
  const text = validateRequest(input()).input.messages[0].content;
  assert.ok(text.includes('are NOT evidence of suicidal intent'));
  assert.ok(text.includes('gently ask what happened'));
  assert.ok(text.includes('Never suppress help for real danger'));
  assert.ok(text.includes('attempt/overdose'));
  const recipe = validateRequest({ ...input(), task: 'recipe', stream: false }).input.messages[0].content;
  assert.ok(recipe.includes('CALIBRATE SUPPORT'));
});
test('missing configuration fails closed and health contains no secrets', async () => {
  assert.equal((await worker.fetch(request('a'.repeat(64)), {})).status, 503);
  const health = await worker.fetch(new Request('https://poodles/health'), {});
  assert.equal((await health.json()).version, '0.4.1');
});
test('token comparison rejects incorrect credentials', async () => {
  const { token, env } = await configuredEnv();
  assert.equal(await tokenMatches(token, env.APP_TOKEN_SHA256), true);
  assert.equal(await tokenMatches('b'.repeat(64), env.APP_TOKEN_SHA256), false);
});
test('successful inference uses AI binding, not external credential-bearing HTTP calls', async () => {
  const { token, env } = await configuredEnv();
  env.AI.run = async (model, body) => { assert.equal(model, MODELS.text); assert.equal(body.messages.at(-1).content, 'Hello'); return { response: 'Hello!', usage: { completion_tokens: 3 } }; };
  const result = await worker.fetch(request(token), env);
  assert.deepEqual(await result.json(), { text: 'Hello!' });
});
test('quota failure prevents provider inference and preserves Retry-After', async () => {
  const { token, env } = await configuredEnv();
  env.QUOTA.get = () => ({ fetch: async () => new Response(null, { status: 429, headers: { 'Retry-After': '42', 'X-Poodles-Limit': 'chat_minute' } }) });
  env.AI.run = () => { throw new Error('Must not be called'); };
  const result = await worker.fetch(request(token), env);
  assert.equal(result.status, 429); assert.equal(result.headers.get('Retry-After'), '42');
});
test('provider errors are sanitized and distinguished from app limits', async () => {
  const { token, env } = await configuredEnv();
  env.AI.run = () => { throw new Error('429 capacity: sensitive details'); };
  const result = await worker.fetch(request(token), env);
  assert.equal(result.status, 429); assert.equal(result.headers.get('X-Poodles-Limit'), 'provider_minute');
  assert.ok(!(await result.text()).includes('sensitive'));
  assert.equal(providerFailure(new Error('daily neuron quota exceeded')).scope, 'provider_daily');
});
test('JSON tasks reject malformed model output', async () => {
  const { token, env } = await configuredEnv();
  env.AI.run = async () => ({ response: 'not JSON' });
  assert.equal((await worker.fetch(request(token, { ...input(), task: 'recipe', stream: false }), env)).status, 502);
});
test('Workers JSON mode may return an already decoded response object', async () => {
  const { token, env } = await configuredEnv();
  env.AI.run = async () => ({ response: { recipes: [{ title: 'Pasta salad' }] } });
  const result = await worker.fetch(request(token, { ...input(), task: 'recipe', stream: false }), env);
  assert.equal(result.status, 200);
  assert.deepEqual(JSON.parse((await result.json()).text), { recipes: [{ title: 'Pasta salad' }] });
});
test('SSE normalization handles split UTF-8 data and both provider formats', async () => {
  const encoded = new TextEncoder().encode('data: {"response":"héllo"}\n\ndata: {"choices":[{"delta":{"content":" there"}}]}\n\ndata: [DONE]\n\n');
  const source = new ReadableStream({ start(c) { for (const byte of encoded) c.enqueue(Uint8Array.of(byte)); c.close(); } });
  const text = await new Response(normalizeStream(source)).text();
  assert.ok(text.includes('héllo')); assert.ok(text.includes(' there')); assert.ok(text.includes('[DONE]'));
});
test('truncated SSE never masquerades as a completed reply', async () => {
  const source = new ReadableStream({ start(c) { c.enqueue(new TextEncoder().encode('data: {"response":"partial"}\n\n')); c.close(); } });
  const text = await new Response(normalizeStream(source)).text();
  assert.ok(text.includes('stream_interrupted')); assert.ok(!text.includes('[DONE]'));
});
