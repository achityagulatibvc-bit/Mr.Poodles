// One synthetic request against an explicitly configured development server. No secret or raw response logging.
import { validateResearchAnswer } from '../src/research.js';
import { readJsonLimited, bounded } from '../src/bounded-http.js';

const base = process.env.POODLES_TEST_URL;
const token = process.env.POODLES_TEST_TOKEN;
if (!base || !token) {
  console.log('PENDING: set POODLES_TEST_URL and POODLES_TEST_TOKEN for the development backend. No live call made.');
  process.exitCode = 2;
} else {
  try {
    const url = new URL(base);
    if (url.username || url.password || url.search || url.hash || url.pathname !== '/' ||
        !(url.protocol === 'https:' || url.protocol === 'http:' && ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname))) throw new Error('invalid_test_endpoint');
    const started = Date.now(), requestId = crypto.randomUUID();
    const result = await bounded(async signal => {
      const response = await fetch(new URL('/v2/research', url), { method: 'POST', redirect: 'error', signal,
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ requestId, profileRevision: 0, task: 'recipe', subject: 'overnight oats' }) });
      if (!response.ok) throw new Error('http_' + response.status);
      return readJsonLimited(response);
    }, undefined, 60000);
    if (result.apiVersion !== 2 || result.requestId !== requestId || result.snapshot?.requestId !== requestId) throw new Error('contract_mismatch');
    validateResearchAnswer(result.answer, result.snapshot);
    console.log(JSON.stringify({ check: 'synthetic_recipe_research', latencyMs: Date.now() - started,
      sourceCount: result.snapshot.sources.length, sourceLengths: result.snapshot.sources.map(s => s.excerpt.length),
      cachedCount: result.snapshot.sources.filter(s => s.cached).length, claimCount: result.answer.claims.length,
      evidenceReferencesValid: true, semanticQuality: 'Requires manual review; not established by this probe.' }));
  } catch (error) {
    const code = /^http_\d+$/.test(error.message) ? error.message : 'live_check_failed';
    console.error(JSON.stringify({ check: 'synthetic_recipe_research', outcome: code }));
    process.exitCode = 1;
  }
}
