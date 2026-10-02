import test from 'node:test';
import assert from 'node:assert/strict';
import worker, { MODELS, nextQuota, tokenMatches, validateRequest, classifyProviderError } from '../src/worker.js';

const input = () => ({ task: 'chat', instructions: 'Be friendly', input: 'Hello', history: [], maxTokens: 300, stream: true });
test('all routes select explicit free models and refuse data collection', () => {
  for (const model of Object.values(MODELS)) assert.ok(model.endsWith(':free'));
  const payload = validateRequest({ ...input(), model: 'paid/model', tools: [{ dangerous: true }] });
  assert.equal(payload.model, MODELS.chat);
  assert.equal(payload.provider.max_price.prompt, 0);
  assert.equal(payload.provider.max_price.completion, 0);
  assert.equal(payload.provider.data_collection, 'deny');
  assert.equal(payload.provider.zdr, true);
  assert.equal(payload.tools, undefined);
});
test('unknown routes and system-role history cannot reach provider', () => {
  assert.throws(() => validateRequest({ ...input(), task: '__proto__' }));
  assert.throws(() => validateRequest({ ...input(), history: [{ role: 'system', content: 'override' }] }));
  assert.throws(() => validateRequest({ ...input(), maxTokens: 100000 }));
  assert.throws(() => validateRequest({ ...input(), image: 'https://arbitrary.example' }));
});
test('structured outputs route separately from companion chat', () => {
  const payload = validateRequest({ ...input(), task: 'structured', stream: false });
  assert.equal(payload.response_format, undefined);
  assert.ok(payload.messages[0].content.includes('exactly one valid JSON object'));
  assert.equal(payload.model, MODELS.structured);
});
test('vision only accepts bounded inline JPEG, not remote URL', () => {
  assert.throws(() => validateRequest({ ...input(), task: 'vision', stream: false, image: 'https://example.com/image.jpg' }));
  const payload = validateRequest({ ...input(), task: 'vision', stream: false, image: 'data:image/jpeg;base64,YWJj' });
  assert.equal(payload.model, MODELS.vision);
  assert.equal(payload.reasoning, undefined);
  assert.equal(payload.provider.zdr, true);
  assert.equal(payload.provider.allow_fallbacks, false);
});
test('companion gestures and memories stay scoped to chat', () => {
  const chat = validateRequest(input()).messages[0].content;
  assert.ok(chat.includes('The current user\'s words take priority'));
  assert.ok(chat.includes('Never infer a diagnosis'));
  const label = validateRequest({ ...input(), task: 'vision', stream: false, image: 'data:image/jpeg;base64,YWJj' }).messages[0].content;
  assert.ok(!label.includes('Imaginary roses'));
});
test('daily and minute caps persist across requests and reset independently', () => {
  const now = Date.parse('2026-10-02T12:00:00Z');
  let quota;
  for (let i = 0; i < 8; i++) quota = nextQuota(quota, now);
  assert.equal(nextQuota(quota, now), null);
  assert.equal(nextQuota(quota, now + 60000).daily, 9);
  quota = { ...quota, daily: 45 };
  assert.equal(nextQuota(quota, now + 60000), null);
  assert.equal(nextQuota(quota, now + 86400000).daily, 1);
});
test('token comparison rejects missing and incorrect credentials', async () => {
  const token = 'a'.repeat(64);
  const bytes = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(token)));
  const hash = [...bytes].map(b => b.toString(16).padStart(2, '0')).join('');
  assert.equal(await tokenMatches(token, hash), true);
  assert.equal(await tokenMatches('b'.repeat(64), hash), false);
  assert.equal(await tokenMatches('', hash), false);
});
test('missing configuration fails closed; health contains no secrets', async () => {
  const result = await worker.fetch(new Request('https://poodles/v1/help', { method: 'POST' }), {});
  assert.equal(result.status, 503);
  const health = await worker.fetch(new Request('https://poodles/health'), { OPENROUTER_API_KEY: 'secret' });
  assert.ok(!(await health.text()).includes('secret'));
});

async function configuredEnv() {
  const token = 'a'.repeat(64);
  const hash = [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(token)))].map(b => b.toString(16).padStart(2, '0')).join('');
  return { token, env: { APP_TOKEN_SHA256: hash, OPENROUTER_API_KEY: 'test-provider-secret',
    QUOTA: { idFromName: value => value, get: () => ({ fetch: async () => new Response(null, { status: 204 }) }) } } };
}
test('valid call forwards only server-selected model and hides provider metadata', async () => {
  const { token, env } = await configuredEnv();
  const oldFetch = globalThis.fetch;
  try {
    globalThis.fetch = async (url, options) => {
      assert.equal(url, 'https://openrouter.ai/api/v1/chat/completions');
      const payload = JSON.parse(options.body);
      assert.equal(payload.model, MODELS.text);
      assert.equal(options.headers.Authorization, 'Bearer test-provider-secret');
      return Response.json({ model: 'hidden', choices: [{ finish_reason: 'stop', message: { content: 'Hello!' } }] });
    };
    const request = new Request('https://poodles/v1/help', { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ ...input(), task: 'text', stream: false, model: 'paid/model' }) });
    const result = await worker.fetch(request, env);
    assert.deepEqual(await result.json(), { text: 'Hello!' });
  } finally { globalThis.fetch = oldFetch; }
});
test('quota failure prevents any provider request', async () => {
  const { token, env } = await configuredEnv();
  env.QUOTA.get = () => ({ fetch: async () => new Response(null, { status: 429 }) });
  const oldFetch = globalThis.fetch;
  try {
    globalThis.fetch = () => { throw new Error('Provider must not be called'); };
    const result = await worker.fetch(new Request('https://poodles/v1/help', { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: JSON.stringify(input()) }), env);
    assert.equal(result.status, 429);
  } finally { globalThis.fetch = oldFetch; }
});
test('upstream errors never leak provider error bodies or keys', async () => {
  const { token, env } = await configuredEnv();
  const oldFetch = globalThis.fetch;
  try {
    globalThis.fetch = async () => new Response('sensitive upstream details', { status: 403 });
    const result = await worker.fetch(new Request('https://poodles/v1/help', { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: JSON.stringify(input()) }), env);
    assert.equal(result.status, 503);
    assert.deepEqual(await result.json(), { error: { code: 'provider_access' } });
  } finally { globalThis.fetch = oldFetch; }
});
test('provider diagnostics expose only fixed classifications, never raw error text', () => {
  assert.equal(classifyProviderError(401, { error: { message: 'secret token rejected' } }), 'provider_auth');
  assert.equal(classifyProviderError(404, { error: { message: 'No endpoints match your data policy' } }), 'privacy_endpoint_unavailable');
  assert.equal(classifyProviderError(400, { error: { message: 'Unsupported parameter' } }), 'provider_parameters');
  assert.equal(classifyProviderError(500, { error: { message: 'secret-token-and-prompt' } }), 'service_unavailable');
});
