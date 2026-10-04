import { ResearchError, checkAbort, retrySeconds } from './bounded-http.js';

// Conservative app ceilings, not promises of throughput. Requests, tokens, compute and credits are independent.
export const FREE_CAPS = Object.freeze({
  exa: { minute: { requests: 10 }, month: { requests: 2000, credits: 8000000 } }, // micro-USD; $8
  tavily: { minute: { requests: 10 }, month: { requests: 800, credits: 800 } },
  groq: { minute: { requests: 25, tokens: 7500 }, day: { requests: 900, tokens: 180000 } },
  youtube: { minute: { requests: 5 }, day: { requests: 80, credits: 80 } },
  cloudflare: { minute: { requests: 15 }, day: { requests: 100, neurons: 1800 } },
});
const MS = { minute: 60000, day: 86400000 };
function windowFor(now, period) {
  if (period === 'month') {
    const d = new Date(now);
    return [Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1), Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1)];
  }
  return [Math.floor(now / MS[period]) * MS[period], (Math.floor(now / MS[period]) + 1) * MS[period]];
}
export function reserveProvider(previous, now, provider, cost) {
  if (!Object.hasOwn(FREE_CAPS, provider) || !cost || Object.keys(cost).some(k => !['requests', 'tokens', 'neurons', 'credits'].includes(k)) ||
      Object.values(cost).some(v => !Number.isSafeInteger(v) || v < 0) || cost.requests !== 1) throw new ResearchError('invalid_budget', 400);
  const requiredUnit = provider === 'groq' ? 'tokens' : provider === 'cloudflare' ? 'neurons' : 'credits';
  if (!(cost[requiredUnit] >= 1)) throw new ResearchError('invalid_budget', 400);
  const state = structuredClone(previous || {});
  const current = state[provider] || {};
  if (current.until > now) return { ok: false, retry: Math.ceil((current.until - now) / 1000), scope: 'research_provider' };
  let blockedUntil = 0;
  for (const [period, caps] of Object.entries(FREE_CAPS[provider])) {
    const [start, end] = windowFor(now, period);
    const used = current[period]?.start === start ? current[period] : { start };
    for (const [unit, cap] of Object.entries(caps)) if ((used[unit] || 0) + (cost[unit] || 0) > cap) blockedUntil = Math.max(blockedUntil, end);
    current[period] = { start, ...Object.fromEntries(Object.keys(caps).map(unit => [unit, (used[unit] || 0) + (cost[unit] || 0)])) };
  }
  if (blockedUntil) return { ok: false, retry: Math.ceil((blockedUntil - now) / 1000), scope: 'research_provider' };
  state[provider] = current; return { ok: true, state };
}
export function recordFailure(previous, now, provider, seconds) {
  if (!Object.hasOwn(FREE_CAPS, provider) || !Number.isFinite(seconds) || seconds < 0 || !Number.isSafeInteger(now + Math.ceil(seconds) * 1000)) throw new ResearchError('invalid_budget', 400);
  const state = structuredClone(previous || {});
  const value = state[provider] ||= {};
  value.until = Math.max(value.until || 0, now + Math.ceil(seconds) * 1000);
  return state;
}
export function eligible(env, provider) {
  // Workers AI is the existing first-party binding, not an optional external account.
  // Its free compute reservations still apply before every inference call.
  if (provider === 'cloudflare') return typeof env.AI?.run === 'function';
  const verified = String(env.FREE_PROVIDERS_VERIFIED || '').split(',').map(x => x.trim());
  const key = { exa: 'EXA_API_KEY', tavily: 'TAVILY_API_KEY', groq: 'GROQ_API_KEY', youtube: 'YOUTUBE_API_KEY', cloudflare: 'AI' }[provider];
  return verified.includes(provider) && Boolean(key && env[key]);
}
export class ProviderBudget {
  constructor(quota, signal) { this.quota = quota; this.signal = signal; }
  async send(path, body) {
    checkAbort(this.signal);
    try {
      const result = await this.quota.fetch(new Request('https://quota/v2/' + path, { method: 'POST', body: JSON.stringify(body) }));
      checkAbort(this.signal);
      return result;
    } catch (error) {
      checkAbort(this.signal);
      if (error.status === 499 || error.code === 'invalid_budget') throw error;
      throw new ResearchError('budget_unavailable');
    }
  }
  async reserve(provider, cost) {
    const result = await this.send('reserve', { provider, cost });
    if (result.status === 429) {
      const error = new ResearchError('free_limit', 429, retrySeconds(result.headers.get('Retry-After')), 'research_provider');
      // This denial already has a persisted quota/cooldown; do not extend it on reads.
      error.reservationDenied = true;
      throw error;
    }
    if (!result.ok) throw new ResearchError('budget_unavailable');
  }
  async failed(provider, error) {
    if (error.status === 499 || error.reservationDenied) return;
    const result = await this.send('failure', { provider, seconds: error.retry || 30 });
    if (!result.ok) throw new ResearchError('budget_unavailable');
  }
}
