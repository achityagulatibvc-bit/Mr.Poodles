"""Exercise three real recipe requests with synthetic preferences, not personal data."""
import json
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError

root = Path(__file__).resolve().parents[1]
values = dict(line.split('=', 1) for line in (root / '.poodles.properties').read_text().splitlines() if '=' in line)
catalog = [food for food in json.loads((root / 'app/src/main/assets/nutrition.json').read_text()) if food['id'] not in ('yogurt', 'tofu')]
ids = {food['id'] for food in catalog}
recent = []
for query, required, excluded in [
    ('Pasta without spinach, banana or oats', {'pasta'}, {'spinach', 'banana', 'oats'}),
    ('A potato sandwich without spinach', {'potato', 'bread'}, {'spinach'}),
    ('A different savory lunch without spinach, banana or oats', set(), {'spinach', 'banana', 'oats'}),
]:
    instructions = '''Create TWO genuinely different recipes for this exact request. No dairy, soy or hot spices.
Hostel equipment: kettle and fridge only. No stove or microwave. Active preparation at most 20 minutes.
Use only the ingredient IDs below, in their stated already-cooked/ready-to-eat form.
Return ONE JSON object with a recipes array. Each recipe: title, ingredients (2-7 objects with id and grams),
method (assemble or soak), minutes, servings (1 or 2), preparation (2-6 ordered objects with action and ingredients).
Preparation action is cut, mash, mix, layer, spread or soak. Step ingredients is an array of IDs used in that recipe.
Soak oats/chia in drinking water, refrigerated for at least 4 hours. Do not use uncooked pasta, rice, beans or potatoes.
Honor required/excluded IDs. Do not copy earlier suggestions under new names or with different portions.
''' + '\n'.join(f"{food['id']}: {food['name']}" for food in catalog if food['id'] not in excluded)
    instructions += '\nRequired IDs: ' + ', '.join(sorted(required)) + '\nExcluded IDs: ' + ', '.join(sorted(excluded))
    instructions += '\nRecent suggestions: ' + json.dumps(recent)
    body = dict(task='recipe', input=query, instructions=instructions, maxTokens=1500, stream=False, history=[])
    request = Request(values['backend.url'].rstrip('/') + '/v1/help', data=json.dumps(body).encode(),
        headers={'Authorization': 'Bearer ' + values['app.token'], 'Content-Type': 'application/json', 'User-Agent': 'MrPoodles/0.3 Android'})
    try:
        with urlopen(request, timeout=90) as response:
            payload = json.loads(json.load(response)['text'])
    except HTTPError as error:
        try: code = json.load(error).get('error', {}).get('code', 'unknown')
        except ValueError: code = 'unknown'
        raise SystemExit(f'Recipe test HTTP {error.code}; code={code}; scope={error.headers.get("X-Poodles-Limit", "unknown")}') from None
    candidates = payload.get('recipes', [])
    good = []
    for recipe in candidates:
        ingredients = {part['id'] for part in recipe['ingredients']}
        signature = '|'.join(sorted(ingredients - {'oil', 'lemon'}))
        if not (ingredients <= ids and required <= ingredients and not ingredients & excluded): continue
        if any(previous['signature'] == signature for previous in recent): continue
        if recipe['method'] not in ('assemble', 'soak') or not 1 <= recipe['minutes'] <= 20: continue
        if not recipe.get('preparation'): continue
        if any(step.get('action') not in ('cut', 'mash', 'mix', 'layer', 'spread', 'soak') or not step.get('ingredients') or not set(step['ingredients']) <= ingredients for step in recipe['preparation']): continue
        if not all(0 < part['grams'] <= 2000 for part in recipe['ingredients']): continue
        good.append((recipe, signature))
    assert good, f'No valid non-repeating result for {query}; candidates={json.dumps(candidates)}'
    selected, signature = good[0]
    recent.append({'title': selected['title'], 'signature': signature})
    print(json.dumps({'query': query, 'passed': True, 'recipe': selected['title'], 'ingredients': signature}), flush=True)
