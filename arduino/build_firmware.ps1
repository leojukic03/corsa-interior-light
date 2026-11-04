# Kompajlira Arduino kod i stavlja ga u aplikaciju (android/app/src/main/assets/firmware/).
# Aplikacija ga onda sama snimi na Arduino kad je njegova verzija starija.
#
#   powershell -ExecutionPolicy Bypass -File arduino\build_firmware.ps1
#
# Treba arduino-cli s jezgrom arduino:avr i bibliotekom FastLED.
param(
    [string]$ArduinoCli = "$env:LOCALAPPDATA\CorsaLightTools\arduino-cli\arduino-cli.exe"
)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$sketch = Join-Path $PSScriptRoot "CorsaLight"
$out = Join-Path $env:TEMP "corsalight_fw_build"
$assets = Join-Path $root "android\app\src\main\assets\firmware"

if (-not (Test-Path $ArduinoCli)) { $ArduinoCli = "arduino-cli" }

$m = Select-String -Path (Join-Path $sketch "CorsaLight.ino") -Pattern '^#define FW_VERSION (\d+)'
if (-not $m) { throw "FW_VERSION nije pronaden u CorsaLight.ino" }
$version = $m.Matches[0].Groups[1].Value

& $ArduinoCli compile --fqbn arduino:avr:uno --output-dir $out $sketch
if ($LASTEXITCODE -ne 0) { throw "Kompajliranje nije uspjelo" }

New-Item -ItemType Directory -Force $assets | Out-Null
Copy-Item (Join-Path $out "CorsaLight.ino.hex") (Join-Path $assets "corsalight.hex") -Force
Set-Content (Join-Path $assets "version.txt") $version -Encoding ascii -NoNewline
Write-Output "Firmware v$version spremljen u $assets"
