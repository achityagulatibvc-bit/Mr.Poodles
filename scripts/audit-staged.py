"""Release-only audit of Git's index; never print configured credential values."""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[1]
def git(*arguments):
    return subprocess.check_output(['git', *arguments], cwd=root)

settings = dict(line.split('=', 1) for line in (root / '.poodles.properties').read_text().splitlines()
                if '=' in line and not line.lstrip().startswith('#'))
secrets = [settings['app.token'].strip().encode()]
secrets += [match.group(1).strip().strip('\"\'').encode() for match in re.finditer(
    r'^(?:EXA|TAVILY|GROQ|YOUTUBE)_API_KEY\s*=\s*(.+)$', (root / 'backend/.dev.vars').read_text(), re.M)]
assert len(secrets) == 5 and all(len(value) >= 12 for value in secrets), 'Secret audit configuration is incomplete'
files = [name.decode() for name in git('diff', '--cached', '--name-only', '--diff-filter=ACMR', '-z').split(b'\0') if name]
assert files, 'No intended changes are staged'
for name in files:
    path = Path(name)
    assert path.suffix.lower() not in ('.apk', '.aab', '.keystore', '.jks'), 'A release binary or signing file is staged'
    assert path.name not in ('.poodles.properties', '.dev.vars', 'local.properties'), 'Local configuration is staged'
    content = git('show', ':' + name)
    assert not any(secret in content for secret in secrets), 'A configured credential is present in staged content'
    assert not re.search(rb'^-----BEGIN (?:RSA )?PRIVATE KEY-----\r?$', content, re.M), 'A private key is staged'
print(f'Staged-source audit passed: {len(files)} files; five configured credentials absent; no APK/AAB/signing/local-config files.')
