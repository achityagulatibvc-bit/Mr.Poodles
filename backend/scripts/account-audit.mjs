// Read-only account inspection. No searches, inference, billing changes or raw account output.
import { readFile } from 'node:fs/promises';
import { parseEnv } from 'node:util';
import { pathToFileURL } from 'node:url';
import { bounded, readJsonLimited, ResearchError, retrySeconds } from '../src/bounded-http.js';

function amount(value, nullable = false) {
  if (nullable && value === null) return null;
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) throw new ResearchError('invalid_response', 502);
  return value;
}

export function tavilyAccountSummary(value) {
  const account = value?.account, key = value?.key;
  const plan = ['Researcher', 'Free', 'Project', 'Bootstrap', 'Startup', 'Growth', 'Enterprise'].find(name =>
    name.toLowerCase() === String(account?.current_plan).toLowerCase()) || 'unrecognized';
  const planLimit = amount(account?.plan_limit), planUsage = amount(account?.plan_usage);
  const paygoLimit = amount(account?.paygo_limit, true), paygoUsage = amount(account?.paygo_usage);
  const keyLimit = amount(key?.limit, true), keyUsage = amount(key?.usage);
  return { provider: 'tavily', plan, planLimit, planUsage, paygoLimit, paygoUsage, keyLimit, keyUsage,
    remainingPlanCredits: Math.max(0, planLimit - planUsage),
    observedOtherKeyCredits: Math.max(0, planUsage - keyUsage),
    freePlanWithPaygoDisabled: ['Researcher', 'Free'].includes(plan) && paygoLimit === 0 && paygoUsage === 0,
    unverified: ['billing_cycle_reset', 'other_applications_using_this_key', 'future_account_changes'] };
}

export async function auditTavily(env, fetcher = fetch) {
  if (!env.TAVILY_API_KEY?.trim() || !String(env.FREE_PROVIDERS_VERIFIED).split(',').map(p => p.trim()).includes('tavily'))
    throw new ResearchError('provider_not_configured');
  return bounded(async signal => {
    const response = await fetcher('https://api.tavily.com/usage', { method: 'GET', redirect: 'manual', signal,
      headers: { Authorization: `Bearer ${env.TAVILY_API_KEY}` } });
    if (!response.ok) {
      await response.body?.cancel();
      throw new ResearchError(response.status === 429 ? 'free_limit' : response.status >= 300 && response.status < 400 ?
        'provider_redirect' : 'provider_configuration', response.status, response.headers.has('Retry-After') ?
        retrySeconds(response.headers.get('Retry-After')) : 0);
    }
    return tavilyAccountSummary(await readJsonLimited(response, 32000));
  }, undefined, 10000);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const env = parseEnv(await readFile(new URL('../.dev.vars', import.meta.url), 'utf8'));
    const started = Date.now();
    console.log(JSON.stringify({ check: 'account_usage', ...await auditTavily(env), latencyMs: Date.now() - started }));
  } catch (error) {
    const allowed = ['provider_not_configured', 'free_limit', 'provider_redirect', 'provider_configuration', 'invalid_response', 'response_too_large', 'provider_timeout'];
    console.error(JSON.stringify({ check: 'account_usage', code: allowed.includes(error.code) ? error.code : 'audit_unavailable',
      retryAfter: Number.isFinite(error.retry) ? error.retry : undefined }));
    process.exitCode = 1;
  }
}
