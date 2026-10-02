param([string[]]$Tasks = @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug'))
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$localJava = Join-Path $root '.tools\jdk\jdk-17.0.20.1+1'
if (!$env:JAVA_HOME -and (Test-Path -LiteralPath $localJava)) { $env:JAVA_HOME = $localJava }
$env:GRADLE_USER_HOME = Join-Path $root '.tools\gradle-cache'
$localGradle = Join-Path $root '.tools\gradle-8.11.1\bin\gradle.bat'
if (!(Test-Path -LiteralPath "$root\.poodles.properties")) {
    Write-Warning 'Building a UI preview: cloud connection is not configured. See docs/CLOUD_SETUP.md before sharing for AI use.'
}
if (Test-Path -LiteralPath $localGradle) {
    & $localGradle -p $root @Tasks --console=plain
} else {
    & "$root\gradlew.bat" -p $root @Tasks --console=plain
}
exit $LASTEXITCODE
