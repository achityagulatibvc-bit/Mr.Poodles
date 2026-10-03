// Independent allowances reserve conversational capacity; recipes/photos cannot consume chat slots.
// Neuron reservations use conservative byte-based input bounds, not raw private content.
export const LIMITS = Object.freeze({
  chat: { daily: 120, minute: 30, neurons: 7000 },
  assistance: { daily: 40, minute: 15, neurons: 1800 },
  vision: { daily: 6, minute: 6, neurons: 1000 },
});
export function bucketFor(task) { return task === 'chat' ? 'chat' : task === 'vision' ? 'vision' : 'assistance'; }

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
}
