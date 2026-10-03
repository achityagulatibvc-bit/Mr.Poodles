"""Run a one-request live smoke test without displaying credentials or private data."""
import json
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError

root = Path(__file__).resolve().parents[1]
settings = root / '.poodles.properties'
if not settings.exists():
    raise SystemExit('Run scripts/configure-cloud.py with your Worker URL first.')
values = dict(line.split('=', 1) for line in settings.read_text().splitlines() if '=' in line)
body = dict(task='text', instructions='Reply with one short friendly greeting.', input='Hello', history=[], maxTokens=80, stream=False)
request = Request(values['backend.url'] + '/v1/help', data=json.dumps(body).encode(),
                  headers={'Authorization': 'Bearer ' + values['app.token'], 'Content-Type': 'application/json',
                           'User-Agent': 'MrPoodles-Selftest/0.2'})
try:
    with urlopen(request, timeout=90) as reply:
        parsed = json.load(reply)
        assert isinstance(parsed.get('text'), str) and parsed['text'].strip()
        print('Live request passed. A nonempty reply arrived. No credentials were printed.')
except HTTPError as error:
    hints = {401: 'App token does not match the Worker hash.',
             403: 'Cloudflare blocked this client before the Worker ran (error 1010, browser-signature check).',
             429: 'Free quota is exhausted.',
             503: 'Check the AI binding, app credential and Workers AI availability.'}
    reasons = {
        'not_ready': 'Worker is missing the AI binding, app credential or quota binding.',
        'service_unavailable': 'Workers AI could not complete the request.',
        'free_limit': 'Free request allowance is exhausted.',
    }
    try:
        code = json.load(error).get('error', {}).get('code')
    except (ValueError, AttributeError):
        code = None
    scope = error.headers.get('X-Poodles-Limit', 'unknown')
    raise SystemExit(f'HTTP {error.code}; allowance={scope}. ' + reasons.get(code, hints.get(error.code, 'Review backend configuration.')))
