"""Build a compact, attributable nutrition catalog from USDA SR Legacy JSON."""
import json
import pathlib
import zipfile
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
with zipfile.ZipFile(ROOT / '.deps/nutrition.zip') as archive:
    name = next(n for n in archive.namelist() if n.endswith('.json'))
    dataset = json.loads(archive.read(name))
foods = dataset['SRLegacyFoods']
if '--find' in sys.argv:
    index = sys.argv.index('--find')
    for term in sys.argv[index + 1:]:
        matches = [f for f in foods if term.lower() in f['description'].lower()]
        print(json.dumps({'query': term, 'matches': [{'fdcId': f['fdcId'], 'description': f['description']} for f in matches[:12]]}))
    sys.exit()
if '--inspect' in sys.argv:
    terms = ['oats', 'bananas, raw', 'apples, raw, with skin', 'peanut butter, smooth',
             'almonds', 'chia', 'cucumber, with peel', 'tomatoes, red, ripe, raw',
             'chickpeas', 'lentils, mature seeds, cooked', 'rice, white, long-grain',
             'oil, olive', 'bread, whole-wheat', 'egg, whole, cooked, hard-boiled',
             'chicken, broilers or fryers, breast, meat only, cooked, roasted',
             'spinach, raw', 'lemon juice, raw', 'yogurt, plain', 'tofu, raw, firm']
    for term in terms:
        matches = [f for f in foods if term in f['description'].lower()]
        print(term, [(f['fdcId'], f['description']) for f in matches[:6]])
    sys.exit()

# The exact FDC IDs below are selected after inspecting the source, not guessed.
selection = json.loads((ROOT / 'scripts/nutrition-selection.json').read_text())
by_id = {f['fdcId']: f for f in foods}
result = []
for entry in selection:
    source = by_id[entry['fdcId']]
    nutrients = {int(n['nutrient']['id']): n['amount'] for n in source['foodNutrients'] if 'amount' in n}
    result.append(dict(entry, description=source['description'],
                       kcal=nutrients.get(1008), protein=nutrients.get(1003),
                       carbs=nutrients.get(1005), fat=nutrients.get(1004),
                       fiber=nutrients.get(1079),
                       source=f"https://fdc.nal.usda.gov/food-details/{entry['fdcId']}/nutrients"))
assert all(f['kcal'] is not None for f in result)
out = ROOT / 'app/src/main/assets/nutrition.json'
out.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
print(f'Exported {len(result)} source-backed foods to {out}')
