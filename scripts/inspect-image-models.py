"""Inspect public free vision routes without accessing credentials or making inference requests."""
import json
from urllib.request import Request, urlopen

def get(path):
    with urlopen(Request('https://openrouter.ai/api/v1/' + path,
                         headers={'User-Agent': 'MrPoodles-Build/0.3'}), timeout=40) as response:
        return json.load(response)['data']

zdr = get('endpoints/zdr')
for model in get('models'):
    if model['id'].endswith(':free') and 'image' in model.get('architecture', {}).get('input_modalities', []):
        details = get('models/' + model['id'] + '/endpoints')
        matches = [e for e in zdr if e.get('model_id') == model['id']]
        print(json.dumps({'id': model['id'], 'zdr_providers': [e.get('provider_name') for e in matches], 'endpoints': [
            {'provider': e.get('provider_name'), 'status': e.get('status'), 'uptime': e.get('uptime_last_30m'),
             'parameters': e.get('supported_parameters'), 'pricing': e.get('pricing')}
            for e in details.get('endpoints', [])]}), flush=True)
