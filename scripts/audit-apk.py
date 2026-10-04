"""Check packaged connection settings, required assets and absence of retired runtimes."""
import hashlib
import json
import re
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[1]
apk = root / 'app/build/outputs/apk/debug/app-debug.apk'
with zipfile.ZipFile(apk) as archive:
    assert not any(name.endswith('.gguf') for name in archive.namelist()), 'Old model was accidentally packaged'
    assert not any(name.endswith('libpoodles.so') for name in archive.namelist()), 'Old inference runtime was accidentally packaged'
    assert not any(name.endswith('.traineddata') or 'tesseract' in name.lower() or 'leptonica' in name.lower() for name in archive.namelist())
    assert archive.getinfo('assets/NOTICES.txt').file_size > 1000
    assert archive.getinfo('res/raw/poodles_chime.wav').file_size > 1000
    foods = json.loads(archive.read('assets/nutrition.json'))
    assert len(foods) == 34
    assert all(f['fdcId'] and f['source'].startswith('https://fdc.nal.usda.gov/') for f in foods)
    settings_file = root / '.poodles.properties'
    configured = False
    if settings_file.exists():
        values = dict(line.split('=', 1) for line in settings_file.read_text().splitlines() if '=' in line)
        dex = b''.join(archive.read(name) for name in archive.namelist() if name.endswith('.dex'))
        configured = bool(values.get('app.token')) and values['app.token'].encode() in dex and values.get('backend.url', '').encode() in dex
        assert configured, 'APK connection settings do not match the local configuration; rebuild it'
    assert 'res/xml/file_paths.xml' not in archive.namelist(), 'Retired photo provider paths were packaged'
    provider_keys_checked = 0
    local_secrets = root / 'backend/.dev.vars'
    if local_secrets.exists():
        # Only configured provider-key values are scanned; never print them or include them in a report.
        provider_keys = [match.group(1).strip().strip('\"\'').encode() for match in
                         re.finditer(r'^(?:EXA|TAVILY|GROQ|YOUTUBE)_API_KEY\s*=\s*(.+)$', local_secrets.read_text(), re.M)]
        assert len(provider_keys) == 4 and all(len(key) >= 12 for key in provider_keys), 'Provider-key audit configuration is incomplete'
        for name in archive.namelist():
            contents = archive.read(name)
            assert not any(key in contents for key in provider_keys), 'A backend provider credential was included in the APK'
        provider_keys_checked = len(provider_keys)
apk_hash = hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest()
print(json.dumps({'apk': str(apk), 'bytes': apk.stat().st_size,
                  'decimal_MB': round(apk.stat().st_size / 1e6, 2),
                  'sha256': apk_hash, 'bundled_language_model': False, 'cloud_configured': configured,
                   'label_processing': 'text_only', 'provider_keys_absent': provider_keys_checked,
                  'nutrition_records': len(foods)}, indent=2))
