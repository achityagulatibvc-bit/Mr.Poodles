import { ResearchError, apiJson, checkAbort } from './bounded-http.js';
import { ProviderBudget, eligible } from './provider-budget.js';

/** Legacy clients keep their text/SSE shapes. No fallback after a stream has started. */
export async function legacyFallback(task, input, env, quota, signal, fetcher = fetch) {
  if (!eligible(env, 'groq')) throw new ResearchError('providers_not_configured');
  const budget = new ProviderBudget(quota, signal);
  const body = { ...input, stream: false, model: 'openai/gpt-oss-20b' };
  if (input.response_format?.type === 'json_schema') body.response_format = {
    type: 'json_schema', json_schema: { name: 'legacy_' + task, strict: false, schema: input.response_format.json_schema },
  };
  await budget.reserve('groq', { requests: 1, tokens: new TextEncoder().encode(JSON.stringify(body)).length + input.max_tokens + 512 });
  try {
    const result = await apiJson('https://api.groq.com/openai/v1/chat/completions', { method: 'POST',
      headers: { Authorization: `Bearer ${env.GROQ_API_KEY}`, 'Content-Type': 'application/json' }, body: JSON.stringify(body) }, signal, fetcher);
    checkAbort(signal);
    const choice = result?.choices?.[0];
    if (!choice || ['length', 'error'].includes(choice.finish_reason) || typeof choice.message?.content !== 'string' ||
        !choice.message.content.trim() || choice.message.content.length > 16000) throw new ResearchError('invalid_response', 502);
    if (input.response_format) JSON.parse(choice.message.content);
    if (!input.stream) return result;
    return new ReadableStream({ start(controller) {
      controller.enqueue(new TextEncoder().encode('data: ' + JSON.stringify({ choices: [{ delta: { content: choice.message.content } }] }) + '\n\ndata: [DONE]\n\n'));
      controller.close();
    } });
  } catch (error) { await budget.failed('groq', error); throw error; }
}
