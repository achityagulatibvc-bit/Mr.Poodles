import test from 'node:test';
import assert from 'node:assert/strict';
import { ResearchError } from '../src/bounded-http.js';
import { ProviderBudget, FREE_CAPS } from '../src/provider-budget.js';
import { PoodlesQuota } from '../src/quota.js';
import { retrieve, EvidenceCache, videoLookup } from '../src/retrieval.js';

const first = 'https://www.bbcgoodfood.com/recipes/unreadable';
const second = 'https://www.bbcgoodfood.com/recipes/readable';
const text = 'Ingredients: 100 g oats and 200 ml water. Mix the oats with water and refrigerate overnight. This recipe has two servings.';
const env = { FREE_PROVIDERS_VERIFIED: 'exa,tavily,youtube', EXA_API_KEY: 'fixture', TAVILY_API_KEY: 'fixture', YOUTUBE_API_KEY: 'fixture' };
const request = extra => ({ requestId: 'fallback-regression', task: 'recipe', subject: 'oats', ...extra });
function memoryBudget() {
  const values = new Map(), calls = [];
  const storage = { get: async key => structuredClone(values.get(key)),
    put: async (key, value) => values.set(key, structuredClone(value)), transaction: callback => callback(storage) };
  const durable = new PoodlesQuota({ storage });
  const quota = { fetch: async req => {
    const result = await durable.fetch(req);
    calls.push({ path: new URL(req.url).pathname, status: result.status, retry: Number(result.headers.get('Retry-After')) });
    return result;
  } };
  return { budget: new ProviderBudget(quota), quota, values, calls };
}
const page = (endpoint, url, content = text) => Response.json({ results: [{ url,
  ...(endpoint.endsWith('/contents') ? { text: content } : { raw_content: content }) }] });
const lookup = (budget, fetcher, extra = {}, signal) => retrieve(request(extra), env, budget, signal, fetcher, new EvidenceCache());
const sourceError = error => error.status === 422 && error.retry === 0 && ['source_incomplete', 'source_unavailable'].includes(error.code);
const noCooldowns = values => {
  for (const state of Object.values(values.get('provider-budgets-v1'))) assert.equal(state.until, undefined);
};

test('unreadable first candidate does not cool either provider; second candidate supplies real text', async () => {
  const { budget, values, calls } = memoryBudget(), urls = [];
  const result = await lookup(budget, async (endpoint, options) => {
    if (endpoint.endsWith('/search')) return Response.json({ results: [{ url: first }, { url: second }] });
    const body = JSON.parse(options.body), url = (body.ids || body.urls)[0];
    urls.push(url);
    return page(endpoint, url, url === first ? 'unreadable' : text);
  });
  assert.deepEqual(urls, [first, first, second]);
  assert.equal(result.sources.length, 1);
  assert.equal(result.sources[0].url, second);
  assert.equal(result.sources[0].excerpt, text);
  assert.equal(result.sources[0].completeness, 'unverified');
  assert.equal(values.get('provider-budgets-v1').exa.month.credits, 6000);
  assert.equal(values.get('provider-budgets-v1').tavily.month.credits, 1);
  assert.ok(calls.every(call => call.path === '/v2/reserve'));
  noCooldowns(values);
});

test('all unreadable candidates return actionable 422 and retain the three-candidate cap', async () => {
  const { budget, values } = memoryBudget(); let extracts = 0;
  await assert.rejects(lookup(budget, async (endpoint, options) => {
    if (endpoint.endsWith('/search')) return Response.json({ results: Array.from({ length: 5 }, (_, i) => ({ url: first + i })) });
    extracts++;
    const body = JSON.parse(options.body);
    return page(endpoint, (body.ids || body.urls)[0], 'short');
  }), sourceError);
  assert.equal(extracts, 6);
  noCooldowns(values);
});

test('no-result search does not block a different query or refund reservations', async () => {
  const { budget, values } = memoryBudget(); let calls = 0;
  const fetcher = async (endpoint, options) => {
    calls++;
    if (endpoint.endsWith('/search')) return Response.json({ results: JSON.parse(options.body).query.startsWith('missing') ? [] : [{ url: second }] });
    return page(endpoint, second);
  };
  await assert.rejects(lookup(budget, fetcher, { subject: 'missing' }), error => sourceError(error) && error.code === 'source_unavailable');
  const result = await lookup(budget, fetcher, { subject: 'oats' });
  assert.equal(result.sources[0].excerpt, text);
  assert.equal(calls, 4);
  assert.equal(values.get('provider-budgets-v1').exa.month.credits, 9000);
  assert.equal(values.get('provider-budgets-v1').tavily.month.credits, 1);
  noCooldowns(values);
});

for (const limited of ['exa', 'tavily']) {
  test(`source failure wins over ${limited} upstream quota, preserving only the true cooldown`, async () => {
    const { budget, values } = memoryBudget(); let calls = 0;
    await assert.rejects(lookup(budget, async endpoint => {
      calls++;
      return endpoint.includes(`api.${limited}.`) ? new Response(null, { status: 429, headers: { 'Retry-After': '180' } }) : page(endpoint, first, 'short');
    }, { url: first }), sourceError);
    const state = values.get('provider-budgets-v1');
    assert.ok(state[limited].until > Date.now());
    assert.equal(state[limited === 'exa' ? 'tavily' : 'exa'].until, undefined);
    const result = await lookup(budget, async endpoint => {
      calls++;
      assert.ok(!endpoint.includes(`api.${limited}.`));
      return page(endpoint, second);
    }, { url: second });
    assert.equal(result.sources[0].excerpt, text);
    assert.equal(calls, 3);
    assert.equal(values.get('provider-budgets-v1')[limited].until, state[limited].until);
  });
}

test('empty search wins over unrelated blocked provider with no retry hint', async () => {
  const { budget, values } = memoryBudget();
  await assert.rejects(lookup(budget, async endpoint => endpoint.includes('api.exa.') ? Response.json({ results: [] }) :
    new Response(null, { status: 429, headers: { 'Retry-After': '90' } })), sourceError);
  assert.equal(values.get('provider-budgets-v1').exa.until, undefined);
  assert.ok(values.get('provider-budgets-v1').tavily.until > Date.now());
});

test('earlier source failure survives later candidates that hit real quotas', async () => {
  const { budget } = memoryBudget();
  await assert.rejects(lookup(budget, async (endpoint, options) => {
    if (endpoint.endsWith('/search')) return Response.json({ results: [{ url: first }, { url: second }] });
    const body = JSON.parse(options.body), url = (body.ids || body.urls)[0];
    return endpoint.includes('api.exa.') && url === first ? page(endpoint, url, 'short') :
      new Response(null, { status: 429, headers: { 'Retry-After': '120' } });
  }), sourceError);
});

test('true upstream quotas stay 429 with Retry-After and blocked retries do not extend cooldowns', async () => {
  const { budget, values, calls } = memoryBudget(); let fetches = 0;
  await assert.rejects(lookup(budget, async endpoint => {
    fetches++;
    return new Response(null, { status: 429, headers: { 'Retry-After': endpoint.includes('api.exa.') ? '120' : '240' } });
  }), error => error.code === 'free_limit' && error.status === 429 && error.retry === 120);
  const state = structuredClone(values.get('provider-budgets-v1'));
  calls.length = 0;
  await assert.rejects(lookup(budget, () => { fetches++; assert.fail('blocked provider must not be called'); }),
    error => error.status === 429 && error.retry === Math.min(...calls.map(call => call.retry)));
  assert.equal(fetches, 2);
  assert.ok(calls.every(call => call.path === '/v2/reserve'));
  assert.deepEqual(values.get('provider-budgets-v1'), state);
});

test('exhausted monthly free caps stay 429 with persisted-window Retry-After and no writes or calls', async () => {
  const { budget, values, calls } = memoryBudget(), now = new Date();
  const start = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1);
  const state = Object.fromEntries(['exa', 'tavily'].map(provider => [provider, { month: { start, credits: FREE_CAPS[provider].month.credits } }]));
  values.set('provider-budgets-v1', structuredClone(state));
  await assert.rejects(lookup(budget, () => assert.fail('quota must prevent external calls')),
    error => error.status === 429 && error.retry > 0 && error.retry === Math.min(...calls.map(call => call.retry)));
  assert.deepEqual(values.get('provider-budgets-v1'), state);
  assert.equal(calls.length, 2);
  assert.ok(calls.every(call => call.path === '/v2/reserve'));
});

for (const status of [401, 403, 503]) {
  test(`upstream ${status} retains provider cooldown and Retry-After`, async () => {
    const { budget, values } = memoryBudget();
    const retry = status === 503 ? 150 : 86400;
    await assert.rejects(lookup(budget, async () => new Response(null, { status, headers: { 'Retry-After': '150' } })),
      error => error.status === 503 && error.retry === retry);
    for (const value of Object.values(values.get('provider-budgets-v1'))) assert.ok(value.until >= Date.now() + (retry - 2) * 1000);
  });
}

for (const mode of ['throw', '400', '503']) {
  test(`reservation storage failure (${mode}) is fatal, not a quota or provider fallback`, async () => {
    let reservations = 0;
    const budget = new ProviderBudget({ fetch: async () => {
      reservations++;
      if (mode === 'throw') throw new Error('storage unavailable');
      return new Response(null, { status: Number(mode) });
    } });
    await assert.rejects(lookup(budget, () => assert.fail('must not fetch')), error => error.code === 'budget_unavailable' && error.status === 503 && error.retry === 0);
    assert.equal(reservations, 1);
  });
}

for (const mode of ['throw', '503']) {
  test(`failure persistence (${mode}) cannot be hidden by already retrieved evidence or later candidates`, async () => {
    const { quota } = memoryBudget(); let extracts = 0, writes = 0;
    const budget = new ProviderBudget({ fetch: async req => {
      if (req.url.endsWith('/failure')) {
        writes++;
        if (mode === 'throw') throw new Error('write failed');
        return new Response(null, { status: 503 });
      }
      return quota.fetch(req);
    } });
    await assert.rejects(lookup(budget, async (endpoint, options) => {
      if (endpoint.endsWith('/search')) return Response.json({ results: [{ url: second }, { url: first }, { url: first + '3' }] });
      extracts++;
      const body = JSON.parse(options.body), url = (body.ids || body.urls)[0];
      return url === second ? page(endpoint, url) : new Response(null, { status: 503 });
    }), error => error.code === 'budget_unavailable');
    assert.equal(extracts, 2);
    assert.equal(writes, 1);
  });
}

test('invalid budget is fatal during candidate extraction rather than aggregated or skipped', async () => {
  let reservations = 0;
  const budget = { reserve: async () => { if (++reservations === 2) throw new ResearchError('invalid_budget', 400); },
    failed: () => assert.fail('invalid budget must not cool a provider') };
  await assert.rejects(lookup(budget, async () => Response.json({ results: [{ url: first }, { url: second }] })),
    error => error.code === 'invalid_budget' && error.status === 400);
  assert.equal(reservations, 2);
});

test('cancellation during extraction stops candidate/provider fallback without cooldown writes', async () => {
  const { budget, calls } = memoryBudget(), controller = new AbortController(); let fetches = 0;
  await assert.rejects(lookup(budget, async endpoint => {
    fetches++;
    if (endpoint.endsWith('/search')) return Response.json({ results: [{ url: first }, { url: second }] });
    controller.abort();
    return new Promise(() => {});
  }, {}, controller.signal), error => error.status === 499);
  assert.equal(fetches, 2);
  assert.ok(calls.every(call => call.path === '/v2/reserve'));
});

test('cancellation during budget persistence remains cancellation and stops fallback', async () => {
  const controller = new AbortController(); let fetches = 0;
  const budget = new ProviderBudget({ fetch: async req => {
    if (req.url.endsWith('/failure')) { controller.abort(); throw new Error('aborted storage'); }
    return new Response(null, { status: 204 });
  } }, controller.signal);
  await assert.rejects(lookup(budget, async () => { fetches++; return new Response(null, { status: 503 }); }, { url: first }, controller.signal),
    error => error.status === 499);
  assert.equal(fetches, 1);
});

test('optional video lookup cannot swallow a budget-integrity failure', async () => {
  const budget = new ProviderBudget({ fetch: async () => { throw new Error('storage failed'); } });
  await assert.rejects(videoLookup(request({ task: 'workout' }), env, budget, undefined, () => assert.fail('must not fetch')),
     error => error.code === 'budget_unavailable');
});

test('network failures remain provider outages rather than source-specific client errors', async () => {
  const { budget, values } = memoryBudget();
  await assert.rejects(lookup(budget, async () => { throw new TypeError('network unavailable'); }),
    error => error.code === 'provider_unavailable' && error.status === 503 && error.retry === 30);
  for (const value of Object.values(values.get('provider-budgets-v1'))) assert.ok(value.until > Date.now());
});
