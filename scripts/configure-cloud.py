"""Set the public Worker URL and generate a revocable personal-app token, never a provider key."""
import argparse
import hashlib
from pathlib import Path
import secrets
from urllib.parse import urlparse

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--url', required=True, help='HTTPS URL of your deployed Cloudflare Worker')
parser.add_argument('--rotate', action='store_true', help='Replace the app credential; rebuild all personal APK copies afterwards')
args = parser.parse_args()
url = args.url.rstrip('/')
parsed = urlparse(url)
if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('', '/'):
    parser.error('Use only the HTTPS origin, such as https://mr-poodles.your-name.workers.dev')
settings = root / '.poodles.properties'
existing = dict(line.split('=', 1) for line in settings.read_text().splitlines() if '=' in line) if settings.exists() else {}
token = secrets.token_hex(32) if args.rotate or not existing.get('app.token') else existing['app.token']
settings.write_text(f'backend.url={url}\napp.token={token}\n', encoding='ascii')
folder = root / 'backend/.secrets'
folder.mkdir(parents=True, exist_ok=True)
(folder / 'app-token-sha256.txt').write_text(hashlib.sha256(token.encode()).hexdigest(), encoding='ascii')
print('Saved Worker URL and restricted app token in ignored .poodles.properties.')
print('Saved only the matching hash in backend/.secrets/app-token-sha256.txt.')
print('No provider key was requested or placed in the APK.')
