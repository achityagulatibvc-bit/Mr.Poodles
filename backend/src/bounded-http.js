export class ResearchError extends Error {
  constructor(code, status = 503, retry = 0, scope = 'research_provider') {
    super(code); Object.assign(this, { code, status, retry, scope });
  }
}
export function retrySeconds(value, now = Date.now()) {
  if (!value) return 60;
  const seconds = /^\d+(\.\d+)?$/.test(value) ? Math.ceil(Number(value)) : Math.ceil((Date.parse(value) - now) / 1000);
  return Number.isSafeInteger(seconds) ? Math.max(1, seconds) : 60;
}
export function checkAbort(signal) { if (signal?.aborted) throw new ResearchError('request_cancelled', 499); }

/** Race even an uncooperative binding against a deadline; consume late rejections safely. */
export async function bounded(operation, signal, timeout = 10000) {
  checkAbort(signal);
  const controller = new AbortController();
  let timer, abort;
  const stopped = new Promise((_, reject) => {
    abort = () => { controller.abort(); reject(new ResearchError('request_cancelled', 499)); };
    signal?.addEventListener('abort', abort, { once: true });
    timer = setTimeout(() => { controller.abort(); reject(new ResearchError('provider_timeout', 503, 30)); }, timeout);
  });
  try { return await Promise.race([Promise.resolve().then(() => operation(controller.signal)), stopped]); }
  finally { clearTimeout(timer); signal?.removeEventListener('abort', abort); }
}

export async function readJsonLimited(response, limit = 256000) {
  if (Number(response.headers.get('content-length')) > limit) { await response.body?.cancel(); throw new ResearchError('response_too_large', 502); }
  const reader = response.body?.getReader();
  if (!reader) throw new ResearchError('invalid_response', 502);
  const chunks = []; let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > limit) { await reader.cancel(); throw new ResearchError('response_too_large', 502); }
      chunks.push(value);
    }
    const bytes = new Uint8Array(size); let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
  } catch (error) {
    if (error instanceof ResearchError) throw error;
    throw new ResearchError('invalid_response', 502);
  } finally { reader.releaseLock(); }
}

const ENDPOINTS = new Set(['https://api.exa.ai/search', 'https://api.exa.ai/contents',
  'https://api.tavily.com/search', 'https://api.tavily.com/extract',
  'https://api.groq.com/openai/v1/chat/completions', 'https://www.googleapis.com/youtube/v3/search']);

/** Credential-bearing requests go only to fixed API endpoints. No redirect is ever followed. */
export async function apiJson(url, options = {}, signal, fetcher = fetch) {
  const parsed = new URL(url);
  if (!ENDPOINTS.has(parsed.origin + parsed.pathname) || parsed.username || parsed.password) throw new ResearchError('invalid_endpoint', 400);
  return bounded(async inner => {
    const result = await fetcher(parsed.href, { ...options, redirect: 'manual', signal: inner });
    if (result.status >= 300 && result.status < 400) { await result.body?.cancel(); throw new ResearchError('provider_redirect', 502, 300); }
    if (!result.ok) {
      await result.body?.cancel();
      const quota = [402, 429, 432, 433].includes(result.status);
      const now = new Date();
      const monthly = Math.ceil((Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 1) - now.getTime()) / 1000);
      const retry = result.headers.has('Retry-After') ? retrySeconds(result.headers.get('Retry-After')) :
        [402, 432, 433].includes(result.status) ? monthly : 60;
      const failure = new ResearchError(quota ? 'free_limit' : [401, 403].includes(result.status) ? 'provider_configuration' : 'provider_unavailable',
        quota ? 429 : 503, quota ? retry : [401, 403].includes(result.status) ? Math.max(86400, retry) : result.headers.has('Retry-After') ? retry : 30);
      failure.upstreamStatus = result.status;
      throw failure;
    }
    return readJsonLimited(result);
  }, signal);
}

/** All IP literals, local names, credentials and nonstandard ports are excluded, including normalized numeric IPs. */
export function publicUrl(value) {
  if (typeof value !== 'string' || value.length > 2000 || /[\s\\\u0000-\u001f]/.test(value)) throw new ResearchError('invalid_source_url', 400);
  let url; try { url = new URL(value); } catch { throw new ResearchError('invalid_source_url', 400); }
  if (url.protocol !== 'https:' || url.port || url.username || url.password ||
      !/^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,63}$/.test(url.hostname) ||
      /(?:^|\.)(?:localhost|local|internal|lan|home|test|invalid|example|onion)$/.test(url.hostname) ||
      [...url.searchParams.keys()].some(key => /token|secret|password|api.?key|signature|credential/i.test(key)))
    throw new ResearchError('invalid_source_url', 400);
  url.hash = ''; return url;
}
