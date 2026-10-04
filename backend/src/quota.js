import { reserveProvider, recordFailure } from './provider-budget.js';
// Independent allowances reserve conversational capacity; recipes cannot consume chat slots.
// Neuron reservations use conservative byte-based input bounds, not raw private content.
export const LIMITS = Object.freeze({
  chat: { daily: 120, minute: 30, neurons: 7000 },
  assistance: { daily: 40, minute: 15, neurons: 1800 },
});
export function bucketFor(task) { return task === 'chat' ? 'chat' : 'assistance'; }

export function reserveQuota(previous, now, bucket, neurons) {
  if (!Object.hasOwn(LIMITS, bucket) || !Number.isFinite(neurons) || neurons < 1 || neurons > 1000) throw new Error('invalid');
  const date = new Date(now).toISOString().slice(0, 10);
  const minute = Math.floor(now / 60000);
  const state = previous?.date === date ? structuredClone(previous) : { date, buckets: {} };
  state.buckets ||= {};
  const current = state.buckets[bucket] || { daily: 0, neurons: 0, minute, perMinute: 0 };
  const perMinute = current.minute === minute ? current.perMinute : 0;
  const limit = LIMITS[bucket];
  const daily = current.daily >= limit.daily || current.neurons + neurons > limit.neurons;
  if (daily || perMinute >= limit.minute) {
    const interval = daily ? 86400000 : 60000;
    return { ok: false, scope: daily ? `${bucket}_daily` : `${bucket}_minute`, retry: Math.ceil((interval - now % interval) / 1000) };
  }
  state.buckets[bucket] = { daily: current.daily + 1, neurons: current.neurons + neurons, minute, perMinute: perMinute + 1 };
  return { ok: true, state, remaining: limit.daily - current.daily - 1 };
}

export function quotaStatus(state, now) {
  const date = new Date(now).toISOString().slice(0, 10);
  return Object.fromEntries(Object.entries(LIMITS).map(([bucket, limits]) => {
    const used = state?.date === date ? state.buckets?.[bucket] : null;
    return [bucket, { daily_limit: limits.daily, used: used?.daily || 0,
      remaining: Math.max(0, limits.daily - (used?.daily || 0)),
      compute_remaining: Math.max(0, limits.neurons - (used?.neurons || 0)) }];
  }));
}

export class PoodlesQuota {
  constructor(ctx) { this.storage = ctx.storage; }
  async fetch(request) {
    const path = new URL(request.url).pathname;
    if (path.startsWith('/v2/')) return this.research(request, path);
    if (request.method === 'GET') return Response.json(quotaStatus(await this.storage.get('quota-v2'), Date.now()));
    if (request.method !== 'POST') return new Response(null, { status: 405 });
    let body;
    try { body = await request.json(); } catch { return new Response(null, { status: 400 }); }
    const result = await this.storage.transaction(async tx => {
      const next = reserveQuota(await tx.get('quota-v2'), Date.now(), body.bucket, body.neurons);
      if (next.ok) await tx.put('quota-v2', next.state);
      return next;
    });
    return new Response(null, { status: result.ok ? 204 : 429, headers: result.ok ?
      { 'X-Poodles-Remaining': String(result.remaining) } :
      { 'X-Poodles-Limit': result.scope, 'Retry-After': String(result.retry) } });
  }
  async research(request, path) {
    if (request.method !== 'POST') return new Response(null, { status: 405 });
    try {
      const body = await request.json();
      const result = await this.storage.transaction(async tx => {
        const now = Date.now();
        if (path === '/v2/failure') {
          await tx.put('provider-budgets-v1', recordFailure(await tx.get('provider-budgets-v1'), now, body.provider, body.seconds));
          return { ok: true };
        }
        if (path === '/v2/feature') {
          if (!['recipe', 'food_check', 'workout', 'food_log', 'plan'].includes(body.task)) throw new Error('invalid');
          const date = new Date(now).toISOString().slice(0, 10), minute = Math.floor(now / 60000);
          const key = 'research-feature-' + body.task;
          const previous = await tx.get(key);
          const value = previous?.date === date ? previous : { date, daily: 0 };
          const rate = value.minute === minute ? value.rate : 0;
          if (value.daily >= 100 || rate >= 10) return { ok: false, scope: 'research_' + body.task,
            retry: Math.ceil(((value.daily >= 100 ? 86400000 : 60000) - now % (value.daily >= 100 ? 86400000 : 60000)) / 1000) };
          await tx.put(key, { date, minute, daily: value.daily + 1, rate: rate + 1 });
          return { ok: true };
        }
        if (path !== '/v2/reserve') throw new Error('invalid');
        const next = reserveProvider(await tx.get('provider-budgets-v1'), now, body.provider, body.cost);
        if (!next.ok) return next;
        if (body.provider === 'cloudflare') {
          // Share the existing compute pool with v1: v2 cannot spend the companion reserve.
          const legacy = reserveQuota(await tx.get('quota-v2'), now, 'assistance', body.cost.neurons);
          if (!legacy.ok) return { ...legacy, scope: 'research_provider' };
          await tx.put('quota-v2', legacy.state);
        }
        await tx.put('provider-budgets-v1', next.state);
        return next;
      });
      return new Response(null, { status: result.ok ? 204 : 429, headers: result.ok ? {} :
        { 'X-Poodles-Limit': result.scope, 'Retry-After': String(result.retry) } });
    } catch { return new Response(null, { status: 400 }); }
  }
}
