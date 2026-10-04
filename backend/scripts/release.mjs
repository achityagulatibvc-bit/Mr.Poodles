// Authorized release operations only. Secrets stay in memory/stdin, never command arguments or output.
import { readFile } from 'node:fs/promises';
import { parseEnv } from 'node:util';
import { createHash, randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { sourceRegistry } from '../src/retrieval.js';
import { validateResearchAnswer } from '../src/research.js';

const root = new URL('../', import.meta.url);
const cwd = fileURLToPath(root);
const mode = process.argv[2] || 'check';
const sensitive = [];
const redact = text => sensitive.reduce((value, secret) => value.replaceAll(secret, '[redacted]'), String(text));
const requireValue = (value, code) => { if (!value) throw new Error(code); return value; };
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
  const body = await response.json();
  return { response, body };
}
try {
  requireValue(['check', 'deploy', 'verify'].includes(mode), 'unknown_release_mode');
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
    requireValue(health.response.ok && health.body.version === '0.4.0', 'deployed_version_mismatch');
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
        requestId, profileRevision: 0, task: 'food_log', subject: 'banana nutrition per 100 grams',
        url: 'https://tools.myfooddata.com/nutrition-facts/173944/100g', allowExternalModel: true,
      }) });
      if (!live.response.ok) {
        console.log(JSON.stringify({ check: 'deployed_research', status: live.response.status,
          code: /^[a-z_]+$/.test(live.body.error?.code || '') ? live.body.error.code : 'unknown',
          retryAfter: live.response.headers.get('Retry-After'), latencyMs: Date.now() - started }));
        process.exitCode = 2;
      } else {
        requireValue(live.body.requestId === requestId && live.body.snapshot?.requestId === requestId, 'deployed_request_identity_failed');
        validateResearchAnswer(live.body.answer, live.body.snapshot);
        console.log(JSON.stringify({ check: 'deployed_research', status: 200, provider: live.body.provider,
          sources: live.body.snapshot.sources.length, validatedCitations: true, latencyMs: Date.now() - started }));
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
