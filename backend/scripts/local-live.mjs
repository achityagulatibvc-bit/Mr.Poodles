// Local Worker + real external providers, using ignored keys without printing them.
// Each invocation runs one bounded synthetic case; local Durable Object budgets persist across invocations.
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { parseEnv } from 'node:util';
import { randomBytes, createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { validateResearchAnswer, researchInput, researchTokenBound } from '../src/research.js';
import { bounded, readJsonLimited } from '../src/bounded-http.js';
import { sourceRegistry } from '../src/retrieval.js';

const root = new URL('../', import.meta.url);
const names = { exa: 'EXA_API_KEY', tavily: 'TAVILY_API_KEY', groq: 'GROQ_API_KEY', youtube: 'YOUTUBE_API_KEY' };
const cases = {
  'recipe-exa': { task: 'recipe', subject: 'overnight oats', providers: ['exa', 'groq'] },
  'recipe-tavily': { task: 'recipe', subject: 'overnight oats', providers: ['tavily', 'groq'] },
  'recipe-cloudflare': { task: 'recipe', subject: 'overnight oats', providers: ['exa', 'tavily', 'cloudflare'] },
  'phase7-tiramisu': { task: 'recipe', subject: 'tiramisu recipe', providers: ['exa', 'tavily', 'groq'] },
  'phase7-salad': { task: 'recipe', subject: 'chickpea salad recipe', providers: ['exa', 'tavily', 'cloudflare'] },
  'phase7-tomato': { task: 'recipe', subject: 'tomato salad recipe', providers: ['exa', 'tavily', 'cloudflare'] },
  'phase7-kachori': { task: 'food_log', subject: 'pyaaz kachori nutrition per piece', providers: ['exa', 'tavily', 'groq'] },
  workout: { task: 'workout', subject: 'beginner calisthenics no equipment', providers: ['exa', 'tavily', 'groq', 'youtube'] },
  'workout-nhs-warmup': { task: 'workout', subject: 'NHS beginner warm-up instructions', url: 'https://www.nhs.uk/live-well/exercise/how-to-warm-up-before-exercising/', providers: ['exa', 'tavily', 'cloudflare'] },
  'workout-nhs-strength': { task: 'workout', subject: 'NHS wall strength instructions', url: 'https://www.nhs.uk/live-well/exercise/strength-exercises/', providers: ['exa', 'tavily', 'cloudflare'] },
  'workout-nhs-cooldown': { task: 'workout', subject: 'NHS cooldown instructions', url: 'https://www.nhs.uk/live-well/exercise/how-to-stretch-after-exercising/', providers: ['exa', 'tavily', 'cloudflare'] },
  product: { task: 'food_check', subject: 'MAGGI 2-Minute Masala Noodles', brand: 'MAGGI', country: 'India', providers: ['exa', 'tavily', 'groq'] },
  nutrition: { task: 'food_log', subject: 'banana raw nutrition per 100 grams', url: 'https://tools.myfooddata.com/nutrition-facts/173944/100g', providers: ['exa', 'tavily', 'groq'] },
  plan: { task: 'plan', subject: 'chickpea salad', providers: ['exa', 'tavily', 'groq'] },
  'voice-cloudflare': { providers: ['cloudflare'] },
  'voice-groq': { providers: ['groq'] },
  'injection-cloudflare': { providers: ['cloudflare'] },
  'injection-groq': { providers: ['groq'] },
  'fallback-groq': { providers: ['groq'] },
  'fallback-tavily': { providers: ['exa', 'tavily', 'groq'] },
  'limits-groq': { providers: ['groq'] },
};
let server;
try {
  const env = parseEnv(await readFile(new URL('.dev.vars', root), 'utf8'));
  const present = Object.fromEntries(Object.entries(names).map(([provider, key]) => [provider,
    Boolean(env[key]?.trim() && !/yahan|paste|replace|your[_-]?key/i.test(env[key]))]));
  const mode = process.argv[2] || 'inventory';
  if (mode === 'estimate') {
    const input = researchInput({ task: 'recipe', subject: 'overnight oats' }, { id: 'sample',
      sources: Array.from({ length: 3 }, (_, i) => ({ id: String(i), title: 'Example recipe', excerpt: 'x'.repeat(600) })) });
    console.log(JSON.stringify({ check: 'three_source_reservation', reservedTokens: researchTokenBound(input) }));
  } else if (mode === 'inventory') {
    console.log(JSON.stringify({ check: 'local_configuration', keysPresent: present,
      eligibleProviders: String(env.FREE_PROVIDERS_VERIFIED || '').split(',').filter(p => Object.hasOwn(names, p)),
      sourcePolicy: sourceRegistry(env).map(({ host, kind, cacheSeconds }) => ({ host, kind, cacheSeconds })),
      localTokenHashConfigured: /^[a-f0-9]{64}$/i.test(env.APP_TOKEN_SHA256 || '') }));
  } else {
    if (!Object.hasOwn(cases, mode)) throw new Error('unknown_case');
    const { providers: defaults, ...input } = cases[mode];
    if (process.argv.includes('--cloudflare') && !input.task) throw new Error('unknown_case');
    const providers = process.argv.includes('--cloudflare') ? [...new Set([...defaults.filter(p => p !== 'groq'), 'cloudflare',
      ...(process.argv.includes('--with-groq') ? ['groq'] : [])])] : defaults;
    const verified = String(env.FREE_PROVIDERS_VERIFIED || '').split(',').map(p => p.trim());
    // Cloudflare is the pre-existing binding; this explicit probe uses the existing Wrangler login only.
    if (providers.some(p => p !== 'cloudflare' && (!present[p] || !verified.includes(p)))) throw new Error('provider_not_configured');
    if (process.argv.includes('--next-minute')) {
      // Let the previous bounded model reservation expire; never reset counters or shorten a provider delay.
      await new Promise(resolve => setTimeout(resolve, 60000 - Date.now() % 60000 + 250));
    }
    const token = randomBytes(32).toString('hex');
    const secrets = [token, ...Object.values(names).map(key => env[key]).filter(Boolean)];
    const redact = value => secrets.reduce((text, secret) => text.replaceAll(secret, '[redacted]'), String(value));
    for (const method of ['log', 'warn', 'error', 'info', 'debug']) {
      const original = console[method].bind(console);
      console[method] = (...args) => original(...args.map(a => typeof a === 'string' ? redact(a) : '[redacted structured diagnostic]'));
    }
    process.env.WRANGLER_SEND_METRICS = 'false';
    const { unstable_dev } = await import('wrangler');
    const modelProbe = /^(voice|injection|fallback|limits)-/.test(mode);
    const entrypoint = fileURLToPath(new URL(modelProbe ? 'scripts/probe-worker.js' : 'src/worker.js', root));
    const vars = { ...env, FREE_PROVIDERS_VERIFIED: providers.join(','), APP_TOKEN_SHA256: createHash('sha256').update(token).digest('hex') };
    server = await unstable_dev(entrypoint, {
      config: fileURLToPath(new URL(providers.includes('cloudflare') ? 'wrangler.live-ai.jsonc' : 'wrangler.live.jsonc', root)),
      // Omit local for AI: default execution is local, but explicit local:true disables remote bindings.
      // local:false would upload the application to remote dev and lose the shared local budget boundary.
      ...(providers.includes('cloudflare') ? {} : { local: true }), ip: '127.0.0.1', port: 0,
      logLevel: 'none', persist: true, persistTo: fileURLToPath(new URL('.wrangler/live-state', root)),
      vars,
      experimental: { disableExperimentalWarning: true, showInteractiveDevSession: false, watch: false, disableDevRegistry: true },
    });
    const requestId = randomBytes(16).toString('hex');
    const started = Date.now();
    const response = await bounded(signal => fetch(`http://${server.address}:${server.port}/${modelProbe ? '__live/model' : 'v2/research'}`, {
      method: 'POST', redirect: 'error', signal,
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(modelProbe ? { mode } : { ...input, requestId, profileRevision: 0, allowExternalModel: true }),
    }), undefined, 60000);
    const result = await readJsonLimited(response);
    if (modelProbe) {
      if (result.snapshot) validateResearchAnswer(result.answer, result.snapshot);
      console.log(JSON.stringify({ check: mode, status: response.status, latencyMs: Date.now() - started, result }));
      if (!response.ok || result.followedInjection === true) process.exitCode = 1;
      if (mode.startsWith('fallback-') && (result.provider !== 'groq' || result.injectedFailures !== 1)) process.exitCode = 1;
      if (mode === 'limits-groq' && !(result.headers?.dailyRequestLimit > 0 && result.headers?.tokenLimitPerMinute > 0)) process.exitCode = 1;
    } else
    if (!response.ok) {
      const code = /^[a-z_]+$/.test(result.error?.code || '') ? result.error.code : 'unknown_error';
      const causes = Array.isArray(result.error?.causes) ? result.error.causes.map(c => ({
        provider: Object.hasOwn(names, c.provider) || c.provider === 'cloudflare' ? c.provider : 'unknown',
        code: /^[a-z_]+$/.test(c.code || '') ? c.code : 'unknown', upstreamStatus: Number.isInteger(c.upstreamStatus) ? c.upstreamStatus : undefined,
        issue: /^[a-z_]+$/.test(c.issue || '') ? c.issue : undefined,
      })) : undefined;
      console.log(JSON.stringify({ check: mode, status: response.status, code, causes, scope: response.headers.get('X-Poodles-Limit'),
        retryAfter: response.headers.get('Retry-After'), latencyMs: Date.now() - started }));
      process.exitCode = 1;
    } else {
      if (result.requestId !== requestId || result.snapshot?.requestId !== requestId) throw new Error('contract_mismatch');
      validateResearchAnswer(result.answer, result.snapshot);
      if (process.argv.includes('--capture')) {
        // Opt-in test artifact only: fixed synthetic cases, public evidence, no request headers,
        // credentials or provider configuration. Never used as the application's evidence cache.
        const directory = new URL('.wrangler/live-evidence/', root);
        await mkdir(directory, { recursive: true });
        await writeFile(new URL(`${mode}.json`, directory), redact(JSON.stringify({
          case: mode, capturedAt: new Date().toISOString(), response: result,
        }, null, 2)), { encoding: 'utf8', mode: 0o600 });
        console.log(JSON.stringify({ check: 'capture', path: `.wrangler/live-evidence/${mode}.json`, synthetic: true }));
      }
      // Public URLs/short excerpts and synthetic replies are intentionally shown for quality review; keys are redacted.
      console.log(JSON.stringify({ check: mode, status: response.status, latencyMs: Date.now() - started, modelProvider: result.provider,
        sources: result.snapshot.sources.map(s => ({ url: s.url, title: s.title, chars: s.excerpt.length, cached: s.cached,
          preview: process.argv.includes('--full-sources') ? s.excerpt : s.excerpt.slice(0, 1600), completeness: s.completeness })),
        answer: result.answer, video: result.video, limitations: result.limitations }));
    }
  }
} catch (error) {
  const allowed = ['unknown_case', 'provider_not_configured', 'contract_mismatch'];
  console.error(JSON.stringify({ check: 'local_live', outcome: allowed.includes(error.message) ? error.message : 'setup_or_probe_failed',
    ...(error.code === 'ENOENT' ? { reason: 'local_configuration_missing' } : {}) }));
  process.exitCode = 1;
} finally { if (server) await server.stop(); }
