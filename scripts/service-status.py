"""Read the separate service allowances without spending an inference request."""
import json
from pathlib import Path
from urllib.request import Request, urlopen

root = Path(__file__).resolve().parents[1]
values = dict(line.split('=', 1) for line in (root / '.poodles.properties').read_text().splitlines() if '=' in line)
request = Request(values['backend.url'].rstrip('/') + '/v1/status',
                  headers={'Authorization': 'Bearer ' + values['app.token'], 'User-Agent': 'MrPoodles/0.3 Android'})
with urlopen(request, timeout=30) as response:
    print(json.dumps(json.load(response), indent=2))
