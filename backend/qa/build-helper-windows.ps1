# Pilote Windows et lanceur recompilés sur Windows (job helper-windows ou à la main) :
# échoue si le C# ne compile pas, ou si src/win/UnowhyHelper.exe et src/win/UnowhyCompanion.exe ne sont plus ceux
# que donnent les sources (C# modifié sans `npm run build:helper`, ou compilé avec un autre SDK que global.json).
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File backend\qa\build-helper-windows.ps1
#
# SDK .NET : celui du PATH s'il a la version de global.json (image du job), sinon installé dans -Work. Paquets NuGet
# et réglages du SDK restent aussi dans -Work : rien n'est installé sur le poste ni ajouté au PATH.
# Windows PowerShell 5.1 ou PowerShell 7.
param(
  [string] $Work = (Join-Path (Get-Location) ".dotnet-ci")
)
$ErrorActionPreference = "Stop"
$backend = Split-Path -Parent $PSScriptRoot
$Work = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Work)
$sdkDir = Join-Path $Work "sdk"
$dotnet = Join-Path $sdkDir "dotnet.exe"

$env:DOTNET_CLI_TELEMETRY_OPTOUT = "1"
$env:DOTNET_NOLOGO = "1"
$env:DOTNET_SKIP_FIRST_TIME_EXPERIENCE = "1"
$env:DOTNET_CLI_HOME = Join-Path $Work "home"
$env:DOTNET_MULTILEVEL_LOOKUP = "0"
$env:NUGET_PACKAGES = Join-Path $Work "nuget\packages"
$env:NUGET_HTTP_CACHE_PATH = Join-Path $Work "nuget\http-cache"
$env:NUGET_PLUGINS_CACHE_PATH = Join-Path $Work "nuget\plugins-cache"

function Step([string] $title) { Write-Host "`n== $title" }
function Fail([string] $message) { Write-Host "ÉCHEC : $message" -ForegroundColor Red; exit 1 }

$version = (Get-Content (Join-Path $backend "global.json") -Raw | ConvertFrom-Json).sdk.version
Step "SDK .NET $version"
function HasSdk([string] $exe) {
  return [bool]((Get-Command $exe -ErrorAction SilentlyContinue) -and (& $exe --list-sdks | Select-String -SimpleMatch "$version "))
}
if (HasSdk "dotnet") {
  $dotnet = "dotnet"   # image du job ou SDK déjà installé
} elseif (-not (HasSdk $dotnet)) {
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
  New-Item -ItemType Directory -Force $Work | Out-Null
  $installer = Join-Path $Work "dotnet-install.ps1"
  Invoke-WebRequest -UseBasicParsing "https://dot.net/v1/dotnet-install.ps1" -OutFile $installer
  & $installer -Version $version -InstallDir $sdkDir -NoPath
}
Push-Location $backend   # global.json choisit le SDK
try {
  & $dotnet --version
  if ($LASTEXITCODE) { Fail "SDK $version introuvable" }

  foreach ($p in @(@("helper", "UnowhyHelper.exe"), @("launcher", "UnowhyCompanion.exe"))) {
    $dir, $exe = $p
    Step "Compilation de src\win\$dir"
    $out = Join-Path $Work "out\$dir"
    & $dotnet build "src\win\$dir" -c Release -o $out --nologo
    if ($LASTEXITCODE) { Fail "src\win\$dir ne compile pas" }
    Copy-Item -Force (Join-Path $out $exe) "src\win\$exe"
  }

  Step "Exe du dépôt comparés aux sources"
  git -c safe.directory=* diff --stat --exit-code -- src/win/UnowhyHelper.exe src/win/UnowhyCompanion.exe
  if ($LASTEXITCODE) {
    Fail "exe du dépôt différents des sources : lancer npm run build:helper (dans backend/, SDK $version) et commiter les exe"
  }
  Write-Host "`nOK : le pilote et le lanceur compilent, exe du dépôt à jour"
} finally {
  Pop-Location
}
