# Paquet Windows testé de bout en bout sur un vrai PC (job install-windows, runner local-runner, ou à la main) :
# installeur en mode silencieux, lancement automatique, backend qui répond, sanity, pilote, désinstallation propre.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File backend\qa\install-windows.ps1 -Zip dist\Compagnon-Unowhy-Windows.zip
#   powershell -NoProfile -ExecutionPolicy Bypass -File backend\qa\install-windows.ps1 -Nettoyage   (after_script)
#
# Le PC sert à d'autres tests : tout est propre au test (port, DATA_DIR, journaux, LOCALAPPDATA, clé Run
# « UnowhyCompanionCI »), sous un seul dossier supprimé à la fin, et rien ne s'affiche (COMPANION_HEADLESS).
# Un compagnon déjà installé n'est pas touché.
# Windows PowerShell 5.1 ou PowerShell 7. Script de CI : le compagnon, lui, ne lance jamais de PowerShell (antivirus).
param(
  [string] $Zip,
  [switch] $Nettoyage,
  [string] $Root = (Join-Path $env:TEMP "compagnon-ci"),
  [int] $Port = 3917,
  [string] $Junit = "sanity-windows.xml",
  [string] $Logs = "install-logs"
)
$ErrorActionPreference = "Stop"
$Name = "UnowhyCompanionCI"
$Root = [IO.Path]::GetFullPath($Root)
$pkg = Join-Path $Root "Compagnon Unowhy"
$backend = Join-Path $pkg "backend"
$node = Join-Path $backend "node\node.exe"
$base = "http://127.0.0.1:$Port"
$runKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"

# Hérité par l'installeur, le lanceur, le serveur et le pilote.
$env:PORT = "$Port"
$env:HOST = "127.0.0.1"            # pas d'écoute sur le réseau : pas de demande du pare-feu
$env:DATA_DIR = Join-Path $Root "data"
$env:DOCS_DIR = Join-Path $Root "partage"
$env:LOCALAPPDATA = Join-Path $Root "localappdata"   # copies du pilote
$env:COMPANION_LOG_DIR = Join-Path $Root "logs"
$env:COMPANION_LABEL = $Name       # valeur de la clé Run propre au test
# Ni boîte du code d'appairage (lu sur la page locale par la sanity), ni page, ni raccourci, ni document ouvert.
$env:COMPANION_HEADLESS = "1"
New-Item -ItemType Directory -Force $env:LOCALAPPDATA | Out-Null

function Step([string] $title) { Write-Host "`n== $title" }
function Health { try { Invoke-RestMethod "$base/health" -TimeoutSec 3 } catch { $null } }
function RunValue { (Get-ItemProperty $runKey -Name $Name -ErrorAction SilentlyContinue).$Name }
# Processus du test seulement : ceux qui tournent depuis le dossier du test.
function Leftovers {
  @(Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path -and $_.Path.StartsWith($Root, "OrdinalIgnoreCase") })
}
function Uninstall {
  if (Test-Path (Join-Path $backend "dist\service.mjs")) {
    Push-Location $backend
    try { & $node dist\service.mjs uninstall } finally { Pop-Location }
  }
}

if ($Nettoyage) {
  # Même après un échec : rien ne doit rester sur le PC.
  Uninstall
  Leftovers | Stop-Process -Force -ErrorAction SilentlyContinue
  Start-Sleep -Seconds 1
  if (RunValue) { Remove-ItemProperty $runKey -Name $Name }
  if (Test-Path $Root) { Remove-Item -Recurse -Force $Root }
  Write-Host "Nettoyage : $Root et clé Run $Name retirés"
  exit 0
}

if (-not $Zip) { throw "-Zip <paquet Windows> attendu (ou -Nettoyage)" }
$Zip = [IO.Path]::GetFullPath($Zip)
$Junit = [IO.Path]::GetFullPath($Junit)
$Logs = [IO.Path]::GetFullPath($Logs)
$sanity = Join-Path $PSScriptRoot "sanity.ts"
$sanityCode = 0
try {
  Step "Paquet"
  if (Health) { throw "le port $Port est déjà pris" }
  if (Test-Path $pkg) { Remove-Item -Recurse -Force $pkg }
  Expand-Archive $Zip $Root
  $installer = Join-Path $pkg "Installer (Windows).cmd"
  foreach ($f in @($installer, $node, (Join-Path $backend "dist\server.mjs"), (Join-Path $backend "src\win\UnowhyHelper.exe"))) {
    if (-not (Test-Path $f)) { throw "absent du paquet : $f" }
  }
  "node $(& $node --version), PowerShell $($PSVersionTable.PSVersion)"

  Step "Installation silencieuse"
  & $installer /silencieux
  if ($LASTEXITCODE -ne 0) { throw "l'installeur a échoué (code $LASTEXITCODE)" }

  Step "Lancement automatique"
  $run = RunValue
  if ($run -notlike "*UnowhyCompanion.exe*--backend*") { throw "clé Run $Name absente ou inattendue : $run" }
  "clé Run : $run"

  Step "Backend"
  $health = Health
  if (-not $health) { throw "pas de réponse sur $base" }
  if ($health.os -ne "windows") { throw "système annoncé : $($health.os)" }
  "$($health.name), $($health.os), port $($health.port)"
  $token = (Get-Content -Raw (Join-Path $env:DATA_DIR "identity.json") | ConvertFrom-Json).token

  Step "Sanity"
  & $node $sanity --url $base --token $token --os windows --junit $Junit
  $sanityCode = $LASTEXITCODE

  Step "Pilote Windows"
  $helper = @(Leftovers | Where-Object { $_.Name -eq "UnowhyHelper" })
  if ($helper.Count -eq 0) { throw "UnowhyHelper.exe du test ne tourne pas" }
  $auth = @{ Authorization = "Bearer $token" }
  $open = Invoke-RestMethod "$base/windows/open" -Headers $auth -TimeoutSec 45
  $audio = Invoke-RestMethod "$base/system/audio" -Headers $auth -TimeoutSec 45
  "pilote $($helper[0].Id) : $(@($open.apps).Count) applis ouvertes sur $(@($open.displays).Count) écrans, volume $($audio.output)"

  Step "Désinstallation"
  Uninstall
  if ($LASTEXITCODE -ne 0) { throw "désinstallation : code $LASTEXITCODE" }
  $deadline = (Get-Date).AddSeconds(30)
  while (((Health) -or @(Leftovers).Count) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
  if (Health) { throw "le compagnon répond encore après la désinstallation" }
  $left = @(Leftovers)
  if ($left.Count) { throw "processus restants : $(($left | ForEach-Object { "$($_.Name) ($($_.Id))" }) -join ', ')" }
  if (RunValue) { throw "clé Run $Name encore présente" }
  "lancement automatique et processus retirés"

  if ($sanityCode -ne 0) { throw "sanity en échec (voir plus haut)" }
  Write-Host "`nInstallation Windows : OK"
} finally {
  # Journaux du lanceur et du serveur, en artefacts du job.
  New-Item -ItemType Directory -Force $Logs | Out-Null
  Get-ChildItem $env:COMPANION_LOG_DIR -Filter *.log -ErrorAction SilentlyContinue | Copy-Item -Destination $Logs
}
