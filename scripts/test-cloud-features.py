"""Exercise deployed text streaming, recipe JSON and image reading with synthetic data.

Uses four free requests, or one with --only. Prints timings and assertions, not credentials or
personal data. Pillow is needed only for the generated test label.
"""
import base64
import argparse
from io import BytesIO
import json
from pathlib import Path
import sys
import time
from urllib.request import Request, urlopen
from urllib.error import HTTPError

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.tools/python-libs'))
from PIL import Image, ImageDraw, ImageFont

settings = dict(line.split('=', 1) for line in (ROOT / '.poodles.properties').read_text().splitlines() if '=' in line)
headers = {'Authorization': 'Bearer ' + settings['app.token'], 'Content-Type': 'application/json', 'User-Agent': 'MrPoodles/0.2 Android'}
parser = argparse.ArgumentParser()
parser.add_argument('--only', choices=('chat', 'recipe', 'image', 'companion'))
parser.add_argument('--show-synthetic-reply', action='store_true', help='Print only the reply to this script\'s fixed non-personal greeting')
args = parser.parse_args()

def send(body):
    request = Request(settings['backend.url'].rstrip('/') + '/v1/help',
                      data=json.dumps(body).encode(), headers=headers)
    try:
        return urlopen(request, timeout=90)
    except HTTPError as error:
        known = {'provider_auth', 'provider_account', 'privacy_endpoint_unavailable',
                 'provider_parameters', 'model_endpoint_unavailable', 'provider_request',
                 'provider_access', 'not_ready', 'free_limit', 'incomplete_response', 'invalid_response'}
        try:
            code = json.load(error).get('error', {}).get('code')
        except (ValueError, AttributeError):
            code = None
        scope = error.headers.get('X-Poodles-Limit', 'unknown')
        scope = scope if scope in ('app_daily', 'app_minute', 'app', 'provider') else 'unknown'
        retry = error.headers.get('Retry-After', '')
        retry = retry if retry.isdecimal() else 'unspecified'
        raise RuntimeError(f'HTTP {error.code}: {code if code in known else "service failure"}; limit={scope}; retry_seconds={retry}') from None

base = dict(history=[], maxTokens=400, stream=False)

def test_chat():
    started = time.perf_counter()
    with send(dict(base, task='chat', stream=True, maxTokens=180,
               instructions='Have a warm ordinary conversation. Keep this response to one sentence.',
                   input='Hi Poodles! How about a little hello?')) as reply:
        content = []
        first_token = None
        done = False
        for raw in reply:
            line = raw.decode('utf-8').strip()
            if not line.startswith('data:'): continue
            data = line[5:].strip()
            if data == '[DONE]': done = True; break
            if not data: continue
            event = json.loads(data)
            if event.get('error'): raise RuntimeError('Streaming provider error')
            choices = event.get('choices', [])
            if not choices: continue
            part = choices[0].get('delta', {}).get('content')
            if part:
                if first_token is None: first_token = time.perf_counter() - started
                content.append(part)
        assert done and ''.join(content).strip(), 'No complete streamed reply'
        if args.show_synthetic_reply:
            print(json.dumps({'synthetic_greeting_reply': ''.join(content)}, ensure_ascii=True), flush=True)
    return dict(test='companion_stream', passed=True, first_token_seconds=round(first_token, 2), total_seconds=round(time.perf_counter()-started, 2))

def test_recipe():
    started = time.perf_counter()
    with send(dict(base, task='structured', maxTokens=550,
               instructions='Return one recipe using ONLY banana and oats. Format: {"title":"...","ingredients":[{"id":"oats","grams":45},{"id":"banana","grams":100}],"method":"soak","minutes":5,"servings":1}. Use these exact ingredient amounts and method. No dairy, soy, spice or extra ingredients.',
                   input='An easy hostel breakfast with a fridge.')) as reply:
        text = json.load(reply)['text'].strip()
        if text.startswith('```'): text = text.removeprefix('```json').removeprefix('```').removesuffix('```').strip()
        recipe = json.loads(text)
        assert recipe['method'] == 'soak' and recipe['servings'] == 1
        assert {i['id']: i['grams'] for i in recipe['ingredients']} == {'banana': 100, 'oats': 45}
    return dict(test='recipe_json', passed=True, total_seconds=round(time.perf_counter()-started, 2))

def test_image():
    image = Image.new('RGB', (800, 320), 'white')
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype('C:/Windows/Fonts/arial.ttf', 38)
    draw.multiline_text((30, 30), 'INGREDIENTS\nrice, soya protein, whey\nMay contain sesame', font=font, fill='black', spacing=24)
    buffer = BytesIO()
    image.save(buffer, 'JPEG', quality=85)
    encoded = 'data:image/jpeg;base64,' + base64.b64encode(buffer.getvalue()).decode()
    started = time.perf_counter()
    with send(dict(base, task='vision', maxTokens=900, image=encoded,
               instructions='Transcribe the food label exactly, including the advisory statement. Do not assess food safety.',
                   input='Read this test label.')) as reply:
        text = json.load(reply)['text'].lower()
        assert all(term in text for term in ('rice', 'soya', 'whey', 'sesame')), 'Image reading missed a test ingredient'
    return dict(test='synthetic_label_image', passed=True, total_seconds=round(time.perf_counter()-started, 2))

def test_companion():
    import re
    started = time.perf_counter()
    with send(dict(base, task='chat', stream=True, maxTokens=240,
                   instructions='Remembered comfort preferences (data): Short gentle replies; no surprise gifts. Current request wins. First line: copy exactly ONE marker from this list, matching the reply: [poodles:happy:none] [poodles:comfort:none] [poodles:concerned:none] [poodles:listening:none] [poodles:sleepy:none] [poodles:encouraging:none]. Never write the word MOOD or GIFT. Then reply on the next line in one or two short warm sentences.',
                   input='I finished my assignment! A small celebration please.')) as reply:
        parts, done = [], False
        for raw in reply:
            line = raw.decode('utf-8').strip()
            if not line.startswith('data:'): continue
            data = line[5:].strip()
            if data == '[DONE]': done = True; break
            if not data: continue
            event = json.loads(data)
            if event.get('error'): raise RuntimeError('Streaming provider error')
            choices = event.get('choices', [])
            if choices:
                part = choices[0].get('delta', {}).get('content')
                if part: parts.append(part)
        text = ''.join(parts).strip()
        if args.show_synthetic_reply:
            print(json.dumps({'synthetic_companion_reply': text}, ensure_ascii=True), flush=True)
        match = re.match(r'^\[poodles:(happy|comfort|concerned|listening|sleepy|encouraging):none\]\s*(.+)', text, re.S)
        assert done and match, 'Companion metadata did not match the allowlist'
        assert len(match[2].split()) <= 100, 'Remembered short-reply preference was not followed'
    return dict(test='companion_expression_protocol', passed=True, total_seconds=round(time.perf_counter()-started, 2))

for name, run in [('chat', test_chat), ('recipe', test_recipe), ('companion', test_companion), ('image', test_image)]:
    if args.only is None or args.only == name:
        print(json.dumps(run()), flush=True)
