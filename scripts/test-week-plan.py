"""Verify a full week is selected in one service request from a synthetic recipe shelf."""
import json
from pathlib import Path
from urllib.request import Request, urlopen

root = Path(__file__).resolve().parents[1]
values = dict(line.split('=', 1) for line in (root / '.poodles.properties').read_text().splitlines() if '=' in line)
body = dict(task='plan', history=[], stream=False, maxTokens=1100,
    instructions='Plan exactly seven days. Available recipe indexes: 0=chickpea tomato pasta salad; 1=potato sandwich; 2=cucumber chickpea salad. Return JSON with a days array of seven objects, each containing breakfast, lunch, dinner as integer indexes. Use each of the three recipes once in each day. Vary the order across days. No invented indexes.',
    input='Plan starting 2026-10-03 for seven days.')
request = Request(values['backend.url'].rstrip('/') + '/v1/help', data=json.dumps(body).encode(),
    headers={'Authorization': 'Bearer ' + values['app.token'], 'Content-Type': 'application/json', 'User-Agent': 'MrPoodles/0.3 Android'})
with urlopen(request, timeout=90) as response:
    menu = json.loads(json.load(response)['text'])
assert len(menu['days']) == 7
for day in menu['days']:
    assert {day['breakfast'], day['lunch'], day['dinner']} == {0, 1, 2}
print(json.dumps({'test': 'seven_day_plan', 'passed': True, 'inference_requests': 1, 'meals': 21}))
