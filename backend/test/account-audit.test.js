import test from 'node:test';
import assert from 'node:assert/strict';
import { auditTavily, tavilyAccountSummary } from '../scripts/account-audit.mjs';
import { groqLimitHeaders } from '../scripts/probe-worker.js';

const response = () => ({ account: { current_plan: 'Researcher', plan_limit: 1000, plan_usage: 41.4,
  paygo_usage: 0, paygo_limit: 0 }, key: { usage: 41.4, limit: null } });

test('account audit exposes only allowed numeric usage and known plan names, never raw account fields', () => {
  const value = response();
  value.account.email = 'private@example.org'; value.key.api_key = 'sensitive-key';
  value.account.current_plan = 'sensitive-unexpected-value';
  const summary = tavilyAccountSummary(value);
  assert.equal(summary.plan, 'unrecognized');
  assert.equal(summary.freePlanWithPaygoDisabled, false);
  assert.equal(summary.planUsage, 41.4);
  assert.equal(summary.keyLimit, null);
  assert.ok(!JSON.stringify(summary).includes('sensitive'));
  assert.ok(!JSON.stringify(summary).includes('private@example.org'));
  assert.ok(summary.unverified.includes('billing_cycle_reset'));
});

test('missing and unlimited paygo data never become a confirmed disabled paid fallback', () => {
  assert.equal(tavilyAccountSummary(response()).freePlanWithPaygoDisabled, true);
  for (const limit of [null, 100]) {
    const value = response(); value.account.paygo_limit = limit;
    assert.equal(tavilyAccountSummary(value).freePlanWithPaygoDisabled, false);
  }
  const value = response(); delete value.account.paygo_usage;
  assert.throws(() => tavilyAccountSummary(value), error => error.code === 'invalid_response');
});

test('read-only audit never follows credential-bearing redirects or retries quota refusals', async () => {
  const env = { TAVILY_API_KEY: 'test-secret', FREE_PROVIDERS_VERIFIED: 'tavily' };
  for (const status of [302, 429]) {
    let calls = 0;
    await assert.rejects(auditTavily(env, async (url, options) => {
      calls++;
      assert.equal(url, 'https://api.tavily.com/usage');
      assert.equal(options.method, 'GET'); assert.equal(options.redirect, 'manual');
      assert.equal(options.body, undefined);
      return new Response('private response', { status, headers: { Location: 'https://untrusted.example', 'Retry-After': '3600' } });
    }), error => error.code === (status === 429 ? 'free_limit' : 'provider_redirect') && error.retry === 3600);
    assert.equal(calls, 1);
  }
});

test('Groq audit labels daily requests and minute tokens correctly and discards unrelated or malformed headers', () => {
  const headers = new Headers({ 'x-ratelimit-limit-requests': '1000', 'x-ratelimit-limit-tokens': '8000',
    'x-ratelimit-reset-requests': '2m59.56s', 'x-ratelimit-reset-tokens': 'private-value',
    'x-ratelimit-remaining-tokens': '-1', 'authorization': 'private-token' });
  const summary = groqLimitHeaders(headers);
  assert.equal(summary.dailyRequestLimit, 1000); assert.equal(summary.tokenLimitPerMinute, 8000);
  assert.equal(summary.requestResetAfter, '2m59.56s'); assert.equal(summary.tokenResetAfter, null);
  assert.equal(summary.remainingTokensPerMinute, null); assert.equal(summary.remainingDailyRequests, null);
  assert.ok(!JSON.stringify(summary).includes('private'));
});
