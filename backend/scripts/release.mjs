// Authorized release operations only. Secrets stay in memory/stdin, never command arguments or output.
import { readFile, lstat, mkdir, writeFile } from 'node:fs/promises';
import { parseEnv } from 'node:util';
import { createHash, randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { sourceRegistry } from '../src/retrieval.js';
import { validateResearchAnswer } from '../src/research.js';
import { readJsonLimited } from '../src/bounded-http.js';

const root = new URL('../', import.meta.url);
const cwd = fileURLToPath(root);
const mode = process.argv[2] || 'check';
const sensitive = [];
const redact = text => sensitive.reduce((value, secret) => value.replaceAll(secret, '[redacted]'), String(text));
const requireValue = (value, code) => { if (!value) throw new Error(code); return value; };
// One fixed public case per invocation; no user profile, history or automatic retries.
const liveCases = {
  recipe: { task: 'recipe', subject: 'Garden tomato salad recipe',
    url: 'https://www.bbcgoodfood.com/recipes/garden-tomato-salad' },
  tiramisu: { task: 'recipe', subject: 'tiramisu recipe' },
  oats: { task: 'recipe', subject: 'overnight oats recipe' },
  workout: { task: 'workout', subject: 'NHS beginner warm-up instructions',
    url: 'https://www.nhs.uk/live-well/exercise/how-to-warm-up-before-exercising/' },
  'workout-strength': { task: 'workout', subject: 'NHS wall strength instructions',
    url: 'https://www.nhs.uk/live-well/exercise/strength-exercises/' },
  'workout-cooldown': { task: 'workout', subject: 'NHS cooldown instructions',
    url: 'https://www.nhs.uk/live-well/exercise/how-to-stretch-after-exercising/' },
  food_check: { task: 'food_check', subject: 'MAGGI 2-Minute Masala Noodles', brand: 'MAGGI', variant: 'Masala', country: 'India' },
  nutrition: { task: 'food_log', subject: 'banana nutrition per 100 grams',
    url: 'https://tools.myfooddata.com/nutrition-facts/173944/100g' },
};
const captureCases = { recipe: 'phase7-tomato', tiramisu: 'tiramisu', oats: 'oats', workout: 'workout-nhs-warmup',
  'workout-strength': 'workout-nhs-strength', 'workout-cooldown': 'workout-nhs-cooldown',
  food_check: 'product', nutrition: 'nutrition' };
const publicJson = value => JSON.stringify(value, (_key, item) => typeof item === 'string' ? redact(item) : item, 2);
function sourceSummary(source) {
  let url = 'invalid_public_url';
  try {
    const parsed = new URL(source.url);
    if (parsed.protocol === 'https:') url = redact(`${parsed.origin}${parsed.pathname}`);
  } catch { /* Never log malformed source URLs verbatim. */ }
  return { url, title: redact(String(source.title || '')).replace(/[\u0000-\u001f\u007f]/g, ' ').slice(0, 240),
    chars: typeof source.excerpt === 'string' ? source.excerpt.length : 0 };
}
async function requireDirectory(directory) {
  const info = await lstat(directory);
  requireValue(info.isDirectory() && !info.isSymbolicLink(), 'capture_directory_invalid');
}
const cliEnvironment = { ...process.env, WRANGLER_SEND_METRICS: 'false' };
async function wrangler(args, input) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [fileURLToPath(new URL('node_modules/wrangler/bin/wrangler.js', root)), ...args],
      { cwd, env: cliEnvironment, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
    let output = '', errors = '';
    const timer = setTimeout(() => { child.kill(); reject(new Error('wrangler_timeout')); }, 180000);
    child.stdout.on('data', chunk => { output += chunk; });
    child.stderr.on('data', chunk => { errors += chunk; });
    child.on('error', () => { clearTimeout(timer); reject(new Error('wrangler_start_failed')); });
    child.stdin.on('error', () => { clearTimeout(timer); reject(new Error('wrangler_stdin_failed')); });
    child.on('close', code => {
      clearTimeout(timer);
      if (code !== 0) reject(new Error(`wrangler_${args[0]}_failed: ${redact(errors || output).slice(-1200)}`));
      else resolve(output);
    });
    child.stdin.end(input);
  });
}
async function fetchJson(url, headers = {}, options = {}) {
  const response = await fetch(url, { ...options, headers, redirect: 'error', signal: AbortSignal.timeout(60000) });
  const body = await readJsonLimited(response);
  return { response, body };
}
try {
  requireValue(['check', 'deploy', 'verify'].includes(mode), 'unknown_release_mode');
  const args = process.argv.slice(3);
  requireValue(args.every(arg => ['--live', '--capture', '--with-groq', '--search'].includes(arg) || arg.startsWith('--case=')), 'unknown_release_option');
  const selections = args.filter(arg => arg.startsWith('--case='));
  requireValue(selections.length <= 1, 'multiple_live_cases');
  const caseName = selections.length ? selections[0].slice('--case='.length) : 'nutrition';
  requireValue(Object.hasOwn(liveCases, caseName), 'unknown_live_case');
  requireValue(!args.length || mode === 'verify', 'live_options_require_verify');
  requireValue(!args.length || args.includes('--live'), 'live_options_require_live');
  const captureDirectory = new URL('.wrangler/live-evidence/production/', root);
  if (args.includes('--capture')) {
    // Reuse the ignored evidence parent; keep historical local captures intact.
    await requireDirectory(new URL('.wrangler/', root));
    await requireDirectory(new URL('.wrangler/live-evidence/', root));
  }
  const settings = Object.fromEntries((await readFile(new URL('../../.poodles.properties', import.meta.url), 'utf8'))
    .split(/\r?\n/).filter(line => line.includes('=') && !line.trim().startsWith('#')).map(line => [line.slice(0, line.indexOf('=')).trim(), line.slice(line.indexOf('=') + 1).trim()]));
  const origin = new URL(settings['backend.url']);
  requireValue(origin.protocol === 'https:' && /^mr-poodles\.[a-z0-9-]+\.workers\.dev$/.test(origin.hostname) &&
    !origin.username && !origin.password && !origin.port && origin.pathname === '/' && !origin.search && !origin.hash, 'unexpected_client_origin');
  const appToken = requireValue(settings['app.token']?.length >= 32 && settings['app.token'].length <= 256 && settings['app.token'], 'missing_app_credential');
  sensitive.push(appToken);
  const env = parseEnv(await readFile(new URL('.dev.vars', root), 'utf8'));
  const keys = ['EXA_API_KEY', 'TAVILY_API_KEY', 'GROQ_API_KEY', 'YOUTUBE_API_KEY'];
  for (const key of keys) sensitive.push(requireValue(env[key]?.trim(), `missing_${key}`));
  const verified = String(env.FREE_PROVIDERS_VERIFIED || '').split(',').map(x => x.trim());
  requireValue(['exa', 'tavily', 'groq', 'youtube'].every(x => verified.includes(x)), 'provider_eligibility_not_attested');
  const registry = sourceRegistry(env);
  requireValue(registry.some(x => x.host === 'tools.myfooddata.com' && x.kind === 'nutrition') &&
    registry.some(x => x.kind === 'manufacturer'), 'required_source_policy_missing');
  const appHeaders = { Authorization: `Bearer ${appToken}`, 'Content-Type': 'application/json' };
  if (mode === 'verify') {
    const health = await fetchJson(new URL('/health', origin));
    requireValue(health.response.ok && health.body.version === '0.4.1', 'deployed_version_mismatch');
    const before = await fetchJson(new URL('/v1/status', origin), appHeaders);
    requireValue(before.response.ok && before.body.chat && before.body.assistance, 'app_credential_or_quota_status_failed');
    const invalid = await fetchJson(new URL('/v2/research', origin), appHeaders, { method: 'POST', body: '{}' });
    requireValue(invalid.response.status === 400 && invalid.body.error?.code === 'invalid_request', 'research_route_not_ready');
    const retired = await fetchJson(new URL('/v1/help', origin), appHeaders, { method: 'POST', body: JSON.stringify({
      task: 'vision', input: 'Synthetic retired-route probe', instructions: '', history: [], maxTokens: 20, stream: false,
      image: 'data:image/jpeg;base64,YWJj',
    }) });
    requireValue(retired.response.status === 400, 'retired_route_still_enabled');
    const after = await fetchJson(new URL('/v1/status', origin), appHeaders);
    requireValue(after.response.ok && JSON.stringify(before.body) === JSON.stringify(after.body), 'invalid_requests_changed_quota');
    console.log(JSON.stringify({ check: 'deployed_contract', origin: origin.origin, version: health.body.version,
      existingAppCredentialAccepted: true, researchRoute: true, retiredImagesRejected: true, invalidRequestsConsumeNoQuota: true }));
    if (process.argv.includes('--live')) {
      const requestId = randomUUID();
      const started = Date.now();
      const live = await fetchJson(new URL('/v2/research', origin), appHeaders, { method: 'POST', body: JSON.stringify({
        ...liveCases[caseName], ...(args.includes('--search') ? { url: undefined } : {}), requestId, profileRevision: 0,
        allowExternalModel: process.argv.includes('--with-groq'),
      }) });
      if (!live.response.ok) {
        console.log(JSON.stringify({ check: 'deployed_research', case: caseName, status: live.response.status,
          code: /^[a-z_]+$/.test(live.body.error?.code || '') ? live.body.error.code : 'unknown',
          retryAfter: /^\d{1,10}$/.test(live.response.headers.get('Retry-After') || '') ? live.response.headers.get('Retry-After') : null,
          latencyMs: Date.now() - started }));
        process.exitCode = 2;
      } else {
        requireValue(live.body.apiVersion === 2 && live.body.profileRevision === 0 &&
          live.body.requestId === requestId && live.body.snapshot?.requestId === requestId, 'deployed_request_identity_failed');
        validateResearchAnswer(live.body.answer, live.body.snapshot);
        requireValue(Array.isArray(live.body.snapshot.sources) && live.body.snapshot.sources.length > 0, 'deployed_sources_missing');
        console.log(JSON.stringify({ check: 'deployed_research', case: caseName, status: live.response.status,
          provider: ['cloudflare', 'groq', 'retrieval_only'].includes(live.body.provider) ? live.body.provider : 'unknown',
          sources: live.body.snapshot.sources.map(sourceSummary), validatedCitations: true, latencyMs: Date.now() - started }));
        if (args.includes('--capture')) {
          await mkdir(captureDirectory, { recursive: false, mode: 0o700 }).catch(error => {
            if (error.code !== 'EEXIST') throw error;
          });
          await requireDirectory(captureDirectory);
          // Android replay reads response, including full public excerpts and citation identities.
          // No request, headers, credentials, local configuration or private profile is captured.
          const response = Object.fromEntries(['apiVersion', 'requestId', 'profileRevision', 'snapshot', 'video',
            'answer', 'provider', 'limitations'].map(key => [key, live.body[key]]));
          const captureCase = captureCases[caseName];
          const destination = new URL(`${captureCase}.json`, captureDirectory);
          const existing = await lstat(destination).catch(error => { if (error.code !== 'ENOENT') throw error; });
          requireValue(!existing || (existing.isFile() && !existing.isSymbolicLink() && existing.nlink === 1), 'capture_file_invalid');
          await writeFile(destination, publicJson({ case: captureCase, capturedAt: new Date().toISOString(), response }),
            { encoding: 'utf8', mode: 0o600 });
          console.log(JSON.stringify({ check: 'capture', case: caseName,
            path: `backend/.wrangler/live-evidence/production/${captureCase}.json`, synthetic: true }));
        }
      }
    }
  } else {
    // Read-only target verification precedes any secret upload or deployment.
    const accountInfo = JSON.parse(await wrangler(['whoami', '--json']));
    requireValue(accountInfo.loggedIn && accountInfo.accounts?.length === 1, 'cloudflare_account_ambiguous');
    const account = accountInfo.accounts[0].id;
    requireValue(/^[a-f0-9]{32}$/.test(account), 'invalid_cloudflare_account');
    const auth = JSON.parse(await wrangler(['auth', 'token', '--json']));
    sensitive.push(requireValue(auth.token, 'unsupported_cloudflare_authentication'));
    const headers = { Authorization: `Bearer ${auth.token}` };
    const domain = await fetchJson(`https://api.cloudflare.com/client/v4/accounts/${account}/workers/subdomain`, headers);
    requireValue(domain.response.ok && domain.body.success && origin.hostname === `mr-poodles.${domain.body.result?.subdomain}.workers.dev`, 'cloudflare_target_mismatch');
    const worker = await fetchJson(`https://api.cloudflare.com/client/v4/accounts/${account}/workers/scripts/mr-poodles/settings`, headers);
    requireValue(worker.response.ok && worker.body.success, 'existing_worker_not_found');
    const existing = await fetchJson(new URL('/v1/status', origin), appHeaders);
    requireValue(existing.response.ok, 'existing_app_credential_rejected');
    cliEnvironment.CLOUDFLARE_ACCOUNT_ID = account;
    console.log(JSON.stringify({ check: 'release_preflight', origin: origin.origin, existingWorkerVerified: true,
      existingAppCredentialAccepted: true, providerKeysPresent: keys.length, userAttestedProviders: verified,
      sourceHosts: registry.length, accountBilling: 'previous user attestation; not re-inferred from API calls' }));
    if (mode === 'deploy') {
      const secrets = Object.fromEntries(keys.map(key => [key, env[key]]));
      secrets.APP_TOKEN_SHA256 = createHash('sha256').update(appToken).digest('hex');
      secrets.FREE_PROVIDERS_VERIFIED = verified.join(',');
      secrets.SOURCE_REGISTRY_JSON = env.SOURCE_REGISTRY_JSON;
      await wrangler(['secret', 'bulk', '--config', 'wrangler.jsonc'], JSON.stringify(secrets));
      const output = await wrangler(['deploy', '--config', 'wrangler.jsonc']);
      console.log(JSON.stringify({ check: 'production_deployment', origin: origin.origin,
        versionId: output.match(/Current Version ID:\s*([a-f0-9-]+)/i)?.[1] || 'see Cloudflare deployment history',
        secretNames: Object.keys(secrets), appCredentialRotated: false }));
    }
  }
} catch (error) {
  console.error(JSON.stringify({ check: `release_${mode}`, error: redact(error.message) }));
  process.exitCode = 1;
}
