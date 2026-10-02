$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dependencies = Join-Path $root '.deps'
$assets = Join-Path $root 'app\src\main\assets'
New-Item -ItemType Directory -Force -Path $dependencies, $assets | Out-Null
if (!(Test-Path -LiteralPath "$dependencies\nutrition.zip")) {
    curl.exe -fL --retry 2 -o "$dependencies\nutrition.zip" 'https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_json_2018-04.zip'
    if ($LASTEXITCODE -ne 0) { throw 'Nutrition data download failed' }
}
python "$PSScriptRoot\nutrition.py"
if ($LASTEXITCODE -ne 0) { throw 'Nutrition export failed' }
python "$PSScriptRoot\licenses.py"
if ($LASTEXITCODE -ne 0) { throw 'License preparation failed' }
python "$PSScriptRoot\make-sounds.py"
if ($LASTEXITCODE -ne 0) { throw 'Sound generation failed' }
