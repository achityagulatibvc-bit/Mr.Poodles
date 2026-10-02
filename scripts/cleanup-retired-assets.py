"""Remove only the specifically retired processing assets from this project's source tree."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
assets = root / 'app/src/main/assets'
retired = [assets / 'models/gemma-3-1b-it-Q4_K_M.gguf', assets / 'models/.gitkeep', assets / 'tessdata/.gitkeep']
retired += [assets / f'tessdata/{language}.traineddata' for language in ('eng', 'fra', 'deu', 'spa', 'ita')]
for path in retired:
    path.unlink(missing_ok=True)
for folder in ('models', 'tessdata'):
    path = assets / folder
    if path.exists() and not any(path.iterdir()):
        path.rmdir()
print('Retired processing assets removed; user data is not touched.')
