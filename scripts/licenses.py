"""Generate the dependency notices shipped with Mr. Poodles."""
from pathlib import Path
from urllib.request import Request, urlopen

root = Path(__file__).resolve().parents[1]
request = Request('https://www.apache.org/licenses/LICENSE-2.0.txt', headers={'User-Agent': 'MrPoodles-Build/0.3'})
with urlopen(request, timeout=40) as response:
    apache = response.read().decode('utf-8')
assert 'Apache License' in apache and 'END OF TERMS AND CONDITIONS' in apache
notice = '''Mr. Poodles — third-party notices

Original application code, penguin illustration, decorative artwork and synthesized chime: MIT.
AndroidX, Jetpack Compose, Kotlin, kotlinx and OkHttp: Apache License 2.0.
Nutrition: USDA FoodData Central SR Legacy (April 2018), public-domain source data.
Record IDs and source URLs are retained in nutrition.json.
https://fdc.nal.usda.gov/download-datasets

Messages and selected label photos are processed through Cloudflare Workers and OpenRouter.
Provider models are selected by the service. Model weights are not distributed with this application.
The UI is inspired by cozy illustrated games. All bundled artwork is original.

'''
(root / 'app/src/main/assets/NOTICES.txt').write_text(notice + apache, encoding='utf-8')
print('Dependency notices prepared.')
