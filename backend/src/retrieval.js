import { ResearchError, apiJson, publicUrl, checkAbort } from './bounded-http.js';
import { eligible } from './provider-budget.js';

const DEFAULT_SOURCES = [
  ['www.bbcgoodfood.com', 'recipe'], ['www.eatingwell.com', 'recipe'], ['www.seriouseats.com', 'recipe'],
  ['www.nhs.uk', 'workout'], ['www.acefitness.org', 'workout'], ['www.mayoclinic.org', 'workout'],
  ['fdc.nal.usda.gov', 'nutrition'],
].map(([host, kind]) => ({ host, kind, cacheSeconds: 0 }));

/** Reviewed source hosts are an egress boundary, not proof that a page supports a claim. */
export function sourceRegistry(env) {
  let configured;
  try { configured = JSON.parse(env.SOURCE_REGISTRY_JSON || '[]'); } catch { throw new ResearchError('source_configuration'); }
  if (!Array.isArray(configured) || configured.length > 40) throw new ResearchError('source_configuration');
  const entries = [...DEFAULT_SOURCES, ...configured];
  for (const item of entries) {
    if (!item || typeof item.host !== 'string' || publicUrl('https://' + item.host).host !== item.host ||
        !['recipe', 'workout', 'nutrition', 'manufacturer'].includes(item.kind) ||
        !Number.isInteger(item.cacheSeconds) || item.cacheSeconds < 0 || item.cacheSeconds > (item.kind === 'manufacturer' ? 900 : 86400))
      throw new ResearchError('source_configuration');
  }
  return [...new Map(entries.map(item => [item.host + ':' + item.kind, item])).values()];
}
export function approvedUrl(value, registry) {
  const url = publicUrl(value);
  if (!registry.some(item => item.host === url.hostname)) throw new ResearchError('source_not_approved', 422);
  return url.href;
}
export function relevantSources(task, registry) {
  const kinds = task === 'workout' ? ['workout'] : task === 'food_check' ? ['manufacturer'] :
    task === 'food_log' ? ['nutrition', 'manufacturer'] : ['recipe'];
  return registry.filter(item => kinds.includes(item.kind));
}
export function researchQuery(request) {
  const suffix = { recipe: 'recipe ingredients quantities method', plan: 'recipe ingredients quantities method',
    workout: 'beginner exercises technique instructions', food_check: 'ingredients allergen statements manufacturer', food_log: 'nutrition calories serving size' }[request.task];
  return [request.subject, request.brand, request.variant, request.country, suffix].filter(Boolean).join(' ');
}
const post = (key, body) => ({ method: 'POST', headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
const clean = (value, max) => typeof value === 'string' ? value.trim().slice(0, max) : '';
export async function hashText(value) {
  return [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value)))].map(b => b.toString(16).padStart(2, '0')).join('');
}

/** Per-isolate bounded cache contains public extracted pages only, never prompts, profiles or answers. */
export class EvidenceCache {
  constructor() { this.pages = new Map(); }
  get(key, now) {
    const item = this.pages.get(key);
    if (!item || item.until <= now) { this.pages.delete(key); return null; }
    return structuredClone(item.value);
  }
  put(key, value, until) {
    if (this.pages.size >= 32) this.pages.delete(this.pages.keys().next().value);
    this.pages.set(key, { value: structuredClone(value), until });
  }
}
const cache = new EvidenceCache();

export async function providerSearch(provider, query, hosts, env, budget, signal, fetcher) {
  await budget.reserve(provider, { requests: 1, credits: provider === 'exa' ? 4000 : 1 });
  const result = provider === 'exa' ? await apiJson('https://api.exa.ai/search', post(env.EXA_API_KEY,
    { query, type: 'instant', numResults: 5, includeDomains: hosts }), signal, fetcher) :
    await apiJson('https://api.tavily.com/search', post(env.TAVILY_API_KEY,
      { query, search_depth: 'basic', auto_parameters: false, max_results: 5, include_domains: hosts,
        include_answer: false, include_raw_content: false, include_images: false }), signal, fetcher);
  if (!Array.isArray(result.results)) throw new ResearchError('invalid_search_response', 502);
  return result.results.slice(0, 5).map(item => ({ url: item.url, title: clean(item.title, 200) }));
}
export async function providerExtract(provider, url, env, budget, signal, fetcher) {
  await budget.reserve(provider, { requests: 1, credits: provider === 'exa' ? 1000 : 1 });
  const result = provider === 'exa' ? await apiJson('https://api.exa.ai/contents', post(env.EXA_API_KEY,
    { ids: [url], text: { maxCharacters: 12000 }, maxAgeHours: 0, livecrawlTimeout: 6000 }), signal, fetcher) :
    await apiJson('https://api.tavily.com/extract', post(env.TAVILY_API_KEY,
      { urls: [url], extract_depth: 'basic', format: 'text', timeout: 6 }), signal, fetcher);
  if (!Array.isArray(result.results)) throw new ResearchError('invalid_extract_response', 502);
  const item = result.results.find(item => { try { return publicUrl(item.url).href === url; } catch { return false; } });
  // Search snippets and provider-generated answers/summaries are deliberately never used as page evidence.
  const text = provider === 'exa' ? item?.text : item?.raw_content;
  if (typeof text !== 'string' || text.trim().length < 100 || text.length > 12000) throw new ResearchError('source_incomplete', 422);
  return { excerpt: text.trim(), title: clean(item.title, 200), author: clean(item.author, 120) || null,
    publishedAt: clean(item.publishedDate, 80) || null };
}
async function fallback(providers, env, budget, signal, operation) {
  const failures = [];
  for (const provider of providers.filter(p => eligible(env, p))) {
    checkAbort(signal);
    try { return await operation(provider); }
    catch (error) {
      if (error.status === 499 || signal?.aborted) throw new ResearchError('request_cancelled', 499);
      failures.push(error);
      await budget.failed(provider, error);
    }
  }
  if (!failures.length) throw new ResearchError('providers_not_configured', 503);
  const last = failures.at(-1);
  const retry = Math.min(...failures.map(e => e.retry || 30));
  throw new ResearchError(last.code || 'source_unavailable', failures.every(e => e.status === 429) ? 429 : 503, retry);
}

export async function retrieve(request, env, budget, signal, fetcher = fetch, pageCache = cache, now = Date.now()) {
  const registry = relevantSources(request.task, sourceRegistry(env));
  if (!registry.length) throw new ResearchError('source_configuration', 503);
  const providers = ['exa', 'tavily'];
  let candidates;
  if (request.url) candidates = [{ url: approvedUrl(request.url, registry), title: '' }];
  else candidates = await fallback(providers, env, budget, signal, async provider => {
    const results = await providerSearch(provider, researchQuery(request), registry.map(x => x.host), env, budget, signal, fetcher);
    const seen = new Set();
    const allowed = results.filter(item => {
      try { item.url = approvedUrl(item.url, registry); if (seen.has(item.url)) return false; seen.add(item.url); return true; }
      catch { return false; }
    });
    if (!allowed.length) throw new ResearchError('source_unavailable', 503, 30);
    return allowed;
  });
  const sources = [], failures = [];
  for (const candidate of candidates.slice(0, 3)) {
    checkAbort(signal);
    const policy = registry.find(item => item.host === new URL(candidate.url).hostname);
    const key = await hashText(candidate.url + ':' + policy.kind + ':' + policy.cacheSeconds);
    let cached = false, page = policy.cacheSeconds > 0 ? pageCache.get(key, now) : null;
    try {
      if (page) cached = true;
      else {
        const extracted = await fallback(providers, env, budget, signal, provider => providerExtract(provider, candidate.url, env, budget, signal, fetcher));
        page = { ...extracted, retrievedAt: new Date(now).toISOString(), contentHash: await hashText(extracted.excerpt) };
        if (policy.cacheSeconds > 0) pageCache.put(key, page, now + policy.cacheSeconds * 1000);
      }
      sources.push({ id: `source-${sources.length + 1}`, url: candidate.url, ...page, title: page.title || candidate.title || new URL(candidate.url).hostname,
        publisher: policy.host, kind: policy.kind, cached, completeness: 'unverified' });
    } catch (error) {
      if (error.status === 499) throw error;
      failures.push(error);
    }
  }
  if (!sources.length) throw failures.at(-1) || new ResearchError('source_unavailable', 503, 30);
  return { id: crypto.randomUUID(), requestId: request.requestId, sources,
    limitations: ['Extracted page text may be incomplete. Citation matching does not prove factual accuracy.',
      ...(failures.length ? ['Some candidate pages could not be retrieved.'] : [])] };
}

export async function videoLookup(request, env, budget, signal, fetcher = fetch) {
  if (!eligible(env, 'youtube')) return { video: null, limitation: 'Video lookup is not configured.' };
  try {
    await budget.reserve('youtube', { requests: 1, credits: 1 });
    const url = new URL('https://www.googleapis.com/youtube/v3/search');
    for (const [key, value] of Object.entries({ part: 'snippet', type: 'video', maxResults: '3', safeSearch: 'strict', q: request.subject })) url.searchParams.set(key, value);
    const result = await apiJson(url.href, { headers: { 'X-Goog-Api-Key': env.YOUTUBE_API_KEY } }, signal, fetcher);
    if (!Array.isArray(result.items)) throw new ResearchError('invalid_video_response', 502);
    const terms = request.subject.toLowerCase().split(/\W+/).filter(word => word.length > 3);
    const item = result.items.find(item => item.id?.kind === 'youtube#video' && /^[A-Za-z0-9_-]{11}$/.test(item.id.videoId) &&
      clean(item.snippet?.title, 200) && clean(item.snippet?.channelTitle, 200) &&
      terms.some(term => item.snippet.title.toLowerCase().includes(term)));
    if (!item) return { video: null, limitation: 'No usable video metadata was retrieved.' };
    return { video: { url: 'https://www.youtube.com/watch?v=' + item.id.videoId, title: clean(item.snippet.title, 200),
      channel: clean(item.snippet.channelTitle, 200), match: 'TECHNIQUE_REFERENCE', verificationNote: 'Search metadata only; not watched or verified as an exact session.' } };
  } catch (error) {
    if (error.status === 499) throw error;
    await budget.failed('youtube', error);
    return { video: null, limitation: 'Video lookup is temporarily unavailable; article evidence is kept.' };
  }
}
