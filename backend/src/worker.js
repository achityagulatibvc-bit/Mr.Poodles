import { composePersonality } from './personality.js';
import { runResearch } from './research.js';
import { ResearchError, bounded } from './bounded-http.js';
import { eligible } from './provider-budget.js';
import { legacyFallback } from './model-fallback.js';
import { MODELS, estimateNeurons, normalizeStream, providerFailure } from './inference.js';
import { bucketFor } from './quota.js';
import { RECIPE_SCHEMA, PLAN_SCHEMA } from './schemas.js';
export { MODELS } from './inference.js';
export { PoodlesQuota } from './quota.js';

function response(status, code, headers = {}, causes) {
  return Response.json({ error: { code, ...(causes ? { causes } : {}) } }, { status, headers: { 'Cache-Control': 'no-store', ...headers } });
}
export async function tokenMatches(token, expected) {
  if (!token || token.length < 32 || token.length > 256 || !/^[a-f0-9]{64}$/i.test(expected || '')) return false;
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(token));
  const hex = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
  let difference = 0;
  for (let i = 0; i < hex.length; i++) difference |= hex.charCodeAt(i) ^ expected.toLowerCase().charCodeAt(i);
  return difference === 0;
}

export function validateRequest(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body) || !Object.hasOwn(MODELS, body.task)) throw new Error('invalid');
  if (typeof body.input !== 'string' || body.input.length > 6000 || typeof body.instructions !== 'string' || body.instructions.length > 14000) throw new Error('invalid');
  if (!Number.isInteger(body.maxTokens) || body.maxTokens < 1 || body.maxTokens > 2400) throw new Error('invalid');
  if (!Array.isArray(body.history) || body.history.length > 10 || body.history.some(m => !m || !['user', 'assistant'].includes(m.role) || typeof m.content !== 'string' || m.content.length > 1800)) throw new Error('invalid');
  if (body.stream !== (body.task === 'chat')) throw new Error('invalid');
  if (body.image !== undefined) throw new Error('invalid');
  const structured = ['structured', 'recipe', 'plan'].includes(body.task);
  const system = composePersonality(body.instructions, structured);
  const history = body.history.map(({ role, content }) => ({ role, content }));
  const messages = [{ role: 'system', content: system }, ...history,
    { role: 'user', content: body.input }];
  if (body.task === 'recipe' || body.task === 'plan') messages[messages.length - 1].content += '\n/no_think';
  if (body.task === 'chat') {
    const bytes = () => new TextEncoder().encode(messages.map(m => m.content).join('\n')).length;
    while (bytes() > 12000 && messages.length > 2) messages.splice(1, 1);
    if (bytes() > 12000) throw new Error('large');
  }
  const maxTokens = Math.min(body.maxTokens, body.task === 'chat' ? 320 : body.task === 'recipe' ? 2400 : 1200);
  return { task: body.task, model: MODELS[body.task], allowExternalFallback: body.allowExternalFallback === true, input: {
    messages, max_tokens: maxTokens, stream: body.stream,
    temperature: body.task === 'recipe' ? .85 : body.task === 'chat' ? .65 : structured ? .4 : .15,
    top_p: .9,
    ...(structured ? { response_format: body.task === 'recipe' || body.task === 'plan' ?
      { type: 'json_schema', json_schema: body.task === 'recipe' ? RECIPE_SCHEMA : PLAN_SCHEMA } : { type: 'json_object' } } : {}),
  } };
}

async function readLimited(request, limit = 160000, signal) {
  if (Number(request.headers.get('content-length') || 0) > limit) throw new Error('large');
  const reader = request.body?.getReader();
  if (!reader) throw new Error('invalid');
  let text = '', size = 0;
  const decoder = new TextDecoder();
  const abort = () => { reader.cancel().catch(() => {}); };
  signal?.addEventListener('abort', abort, { once: true });
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > limit) { await reader.cancel(); throw new Error('large'); }
      text += decoder.decode(value, { stream: true });
    }
    return JSON.parse(text + decoder.decode());
  } finally { signal?.removeEventListener('abort', abort); reader.releaseLock(); }
}

export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname;
    if (request.method === 'GET' && path === '/health') return Response.json({ service: 'Mr. Poodles', version: '0.4.1' });
    if (!((request.method === 'POST' && ['/v1/help', '/v2/research'].includes(path)) || (request.method === 'GET' && path === '/v1/status'))) return response(404, 'not_found');
    if (!env.APP_TOKEN_SHA256 || !env.QUOTA || path !== '/v2/research' && !env.AI) return response(503, 'not_ready');
    if (!await tokenMatches(request.headers.get('Authorization')?.replace(/^Bearer /, '') || '', env.APP_TOKEN_SHA256)) return response(401, 'unauthorized');
    const quota = env.QUOTA.get(env.QUOTA.idFromName('personal-app'));
    if (path === '/v1/status') {
      const status = await quota.fetch(new Request('https://quota/status'));
      return new Response(status.body, { headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' } });
    }
    if (!(request.headers.get('content-type') || '').startsWith('application/json')) return response(400, 'invalid_request');
    if (path === '/v2/research') {
      try {
        const body = await bounded(inner => readLimited(request, 6000, inner), request.signal, 5000);
        const result = await runResearch(body, env, quota, request.signal);
        return Response.json(result, { headers: { 'Cache-Control': 'no-store' } });
      } catch (error) {
        const failure = error instanceof ResearchError ? error : new ResearchError('invalid_request', error.message === 'large' ? 413 : 400);
        return response(failure.status, failure.code, { 'X-Poodles-Limit': failure.scope,
          ...(failure.retry ? { 'Retry-After': String(failure.retry) } : {}) }, failure.causes);
      }
    }
    let validated;
    try { validated = validateRequest(await bounded(inner => readLimited(request, 160000, inner), request.signal, 5000)); }
    catch (error) { return response(error.message === 'large' ? 413 : 400, 'invalid_request'); }
    const { task, model, input, allowExternalFallback } = validated;
    const neurons = estimateNeurons(task, input);
    if (neurons > 1000) return response(413, 'invalid_request');
    const allowed = await quota.fetch(new Request('https://quota/consume', { method: 'POST', body: JSON.stringify({ bucket: bucketFor(task), neurons }) }));
    if (!allowed.ok) return response(429, 'free_limit', {
      'X-Poodles-Limit': allowed.headers.get('X-Poodles-Limit') || 'provider_daily',
      'Retry-After': allowed.headers.get('Retry-After') || '60',
    });
    try {
      let result;
      try { result = await bounded(() => env.AI.run(model, input), request.signal, 30000); }
      catch (error) {
        if (error.status === 499 || request.signal.aborted || !allowExternalFallback || !eligible(env, 'groq')) throw error;
        result = await legacyFallback(task, input, env, quota, request.signal);
      }
      if (input.stream) {
        if (!result || typeof result.getReader !== 'function') return response(502, 'invalid_response');
        return new Response(normalizeStream(result), { headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store' } });
      }
      const finish = result?.choices?.[0]?.finish_reason;
      if (finish === 'length' || finish === 'error') return response(502, 'incomplete_response');
      const output = result?.response ?? result?.choices?.[0]?.message?.content;
      // Workers AI JSON mode may return a decoded object instead of a JSON string.
      const text = structuredTask(task) && output && typeof output === 'object' ? JSON.stringify(output) : output;
      if (typeof text !== 'string' || !text.trim() || text.length > 20000) return response(502, 'invalid_response');
      if (structuredTask(task)) { try { JSON.parse(text); } catch { return response(502, 'invalid_response'); } }
      return Response.json({ text }, { headers: { 'Cache-Control': 'no-store' } });
    } catch (error) {
      if (error instanceof ResearchError) return response(error.status, error.code, {
        'X-Poodles-Limit': error.scope, ...(error.retry ? { 'Retry-After': String(error.retry) } : {}) });
      const failure = providerFailure(error);
      return response(failure.status, failure.status === 429 ? 'free_limit' : 'service_unavailable', { 'X-Poodles-Limit': failure.scope, 'Retry-After': String(failure.retry) });
    }
  },
};
function structuredTask(task) { return ['structured', 'recipe', 'plan'].includes(task); }
