import { retrySeconds } from './bounded-http.js';
// These models are available in Workers AI's daily free allocation. No paid-plan upgrade or Gateway billing is enabled.
export const MODELS = Object.freeze({
  chat: '@cf/meta/llama-3.1-8b-instruct-fp8-fast',
  text: '@cf/meta/llama-3.1-8b-instruct-fp8-fast',
  structured: '@cf/meta/llama-3.1-8b-instruct-fp8-fast',
  recipe: '@cf/qwen/qwen3-30b-a3b-fp8',
  plan: '@cf/qwen/qwen3-30b-a3b-fp8',
});

export function estimateNeurons(task, input) {
  const text = input.messages.map(m => m.content).join('\n');
  const bytes = new TextEncoder().encode(text + (input.response_format ? JSON.stringify(input.response_format) : '')).length + 512;
  // UTF-8 byte count conservatively upper-bounds text tokens.
  if (task === 'recipe' || task === 'plan') return Math.ceil(bytes * .004625 + input.max_tokens * .030475);
  return Math.ceil(bytes * .004119 + input.max_tokens * .034868);
}

export function providerFailure(error) {
  const detail = String(error?.message || '');
  const headers = error?.headers || error?.response?.headers;
  const header = typeof headers?.get === 'function' ? headers.get('Retry-After') : headers?.['retry-after'];
  const explicit = header ? retrySeconds(String(header)) : 0;
  if (/daily.*(limit|quota)|neurons|allocation.*exceed|quota.*exceed/i.test(detail)) return { status: 429, scope: 'provider_daily', retry: Math.max(explicit, Math.ceil((86400000 - Date.now() % 86400000) / 1000)) };
  if (/429|rate.?limit|too many|capacity|overload/i.test(detail)) return { status: 429, scope: 'provider_minute', retry: explicit || 60 };
  return { status: 503, scope: 'provider_unavailable', retry: explicit || 30 };
}

/** Normalize both Workers AI response-delta SSE and OpenAI-shaped chunks for existing APKs. */
export function normalizeStream(source) {
  const decoder = new TextDecoder();
  const encoder = new TextEncoder();
  let pending = '', done = false;
  function line(value, controller) {
    if (!value.startsWith('data:') || done) return;
    const data = value.slice(5).trim();
    if (!data) return;
    if (data === '[DONE]') { done = true; controller.enqueue(encoder.encode('data: [DONE]\n\n')); return; }
    const event = JSON.parse(data);
    if (event.error || event.choices?.[0]?.finish_reason === 'error') {
      done = true;
      controller.enqueue(encoder.encode('data: {"error":{"code":"stream_interrupted"}}\n\n'));
      return;
    }
    const content = event.response ?? event.choices?.[0]?.delta?.content;
    if (typeof content === 'string' && content) controller.enqueue(encoder.encode('data: ' + JSON.stringify({ choices: [{ delta: { content } }] }) + '\n\n'));
    const finish = event.choices?.[0]?.finish_reason;
    if (finish === 'length') {
      done = true;
      controller.enqueue(encoder.encode('data: {"error":{"code":"incomplete_response"}}\n\n'));
    }
  }
  return source.pipeThrough(new TransformStream({
    transform(chunk, controller) {
      pending += decoder.decode(chunk, { stream: true });
      if (pending.length > 100000) throw new Error('oversized_stream');
      const lines = pending.split(/\r?\n/);
      pending = lines.pop();
      for (const value of lines) line(value, controller);
    },
    flush(controller) {
      pending += decoder.decode();
      if (pending.trim()) line(pending, controller);
      // Never invent completion after a dropped stream.
      if (!done) controller.enqueue(encoder.encode('data: {"error":{"code":"stream_interrupted"}}\n\n'));
    },
  }));
}
