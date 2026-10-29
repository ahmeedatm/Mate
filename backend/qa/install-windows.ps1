# Appli portable Windows testée de bout en bout sur un vrai PC (à la main, ou par le runner local-runner) : l'exe seul
# est lancé de n'importe quel dossier, rien ne s'inscrit dans Windows, le runtime est extrait, le backend répond, sanity,
# pilote, panneau (lancement au démarrage coché puis décoché), relance de l'exe, « Quitter ».
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File backend\qa\install-windows.ps1 -Exe dist\Compagnon-Unowhy-Windows.exe
#   powershell -NoProfile -ExecutionPolicy Bypass -File backend\qa\install-windows.ps1 -Nettoyage   (après un échec)
#
# Le PC sert à d'autres tests : tout est propre au test (port, DATA_DIR, journaux, LOCALAPPDATA, valeur de la clé Run
# « UnowhyCompanionCI »), sous un seul dossier supprimé à la fin, et rien ne s'affiche (COMPANION_HEADLESS).
# Un compagnon déjà lancé n'est pas touché.
# Windows PowerShell 5.1 ou PowerShell 7. Script de test : le compagnon, lui, ne lance PowerShell que pour le pare-feu, à la demande.
param(
  [string] $Exe,
  [switch] $Nettoyage,
  [string] $Root = (Join-Path $env:TEMP "compagnon-ci"),
  [int] $Port = 3917,
  [string] $Junit = "sanity-windows.xml",
  [string] $Logs = "install-logs"
)
$ErrorActionPreference = "Stop"
# Chemin absolu depuis le dossier courant de PowerShell ([IO.Path]::GetFullPath part de celui du processus en 5.1).
function Full([string] $p) { $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($p) }
$Name = "UnowhyCompanionCI"
$Root = Full $Root
$appDir = Join-Path $Root "app"
$app = Join-Path $appDir "Compagnon-Unowhy-Windows.exe"
$base = "http://127.0.0.1:$Port"
$runKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"

# Hérité par l'exe, le serveur et le pilote.
$env:PORT = "$Port"
$env:HOST = "127.0.0.1"            # pas d'écoute sur le réseau : pas de demande du pare-feu
$env:DATA_DIR = Join-Path $Root "data"
$env:DOCS_DIR = Join-Path $Root "partage"
$env:LOCALAPPDATA = Join-Path $Root "localappdata"   # runtime extrait, copies du pilote
$env:COMPANION_LOG_DIR = Join-Path $Root "logs"
$env:COMPANION_LABEL = $Name       # valeur de la clé Run propre au test (panneau)
# Ni page ouverte ni boîte de message ; le code d'appairage est lu sur la page locale par la sanity.
$env:COMPANION_HEADLESS = "1"
$env:NO_ALERT = "1"
$runtime = Join-Path $env:LOCALAPPDATA "UnowhyCompanion\runtime"
$node = Join-Path $runtime "node\node.exe"

function Step([string] $title) { Write-Host "`n== $title" }
function Health { try { Invoke-RestMethod "$base/health" -TimeoutSec 3 } catch { $null } }
function Post([string] $route, $body) {
  Invoke-RestMethod "$base$route" -Method Post -ContentType "application/json" -Body ($body | ConvertTo-Json -Compress) -TimeoutSec 10
}
function RunValue { (Get-ItemProperty $runKey -Name $Name -ErrorAction SilentlyContinue).$Name }
# Processus du test seulement : ceux qui tournent depuis le dossier du test.
function Leftovers {
  @(Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path -and $_.Path.StartsWith($Root, "OrdinalIgnoreCase") })
}
function WaitUntil([scriptblock] $condition, [int] $seconds) {
  $deadline = (Get-Date).AddSeconds($seconds)
  while (-not (& $condition) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
  [bool] (& $condition)
}

if ($Nettoyage) {
  # Même après un échec : rien ne doit rester sur le PC.
  Leftovers | Stop-Process -Force -ErrorAction SilentlyContinue
  Start-Sleep -Seconds 1
  if (RunValue) { Remove-ItemProperty $runKey -Name $Name }
  if (Test-Path $Root) { Remove-Item -Recurse -Force $Root }
  Write-Host "Nettoyage : $Root et valeur Run $Name retirés"
  exit 0
}

if (-not $Exe) { throw "-Exe <Compagnon-Unowhy-Windows.exe> attendu (ou -Nettoyage)" }
$Exe = Full $Exe
$Junit = Full $Junit
$Logs = Full $Logs
$sanity = Join-Path $PSScriptRoot "sanity.ts"
$sanityCode = 0
New-Item -ItemType Directory -Force $env:LOCALAPPDATA | Out-Null
try {
  Step "Lancement de l'exe seul"
  if (Health) { throw "le port $Port est déjà pris" }
  if (RunValue) { throw "la valeur Run $Name existe déjà" }
  if (Test-Path $appDir) { Remove-Item -Recurse -Force $appDir }
  New-Item -ItemType Directory -Force $appDir | Out-Null
  Copy-Item $Exe $app
  # Le lanceur reste en marche (il relance le serveur) : pas d'attente de sa fin.
  $launcher = Start-Process -FilePath $app -PassThru
  if (-not (WaitUntil { Health } 60)) { throw "pas de réponse sur $base" }
  foreach ($f in @($node, (Join-Path $runtime "dist\server.mjs"), (Join-Path $runtime "src\win\UnowhyHelper.exe"), (Join-Path $runtime "runtime.stamp"))) {
    if (-not (Test-Path $f)) { throw "absent après le lancement : $f" }
  }
  "node $(& $node --version), PowerShell $($PSVersionTable.PSVersion)"

  Step "Rien d'inscrit"
  if (RunValue) { throw "clé Run $Name inscrite sans demande" }
  $panel = Invoke-RestMethod "$base/local/panel"
  if (-not $panel.portable -or $panel.autostart) { throw "/local/panel : portable=$($panel.portable) autostart=$($panel.autostart)" }
  "panneau : version $($panel.version), pare-feu $($panel.firewall), ancienne installation : $(@($panel.legacy).Count)"

  Step "Lancement au démarrage (panneau)"
  $null = Post "/local/autostart" @{ on = $true }
  $run = RunValue
  if ($run -ne "`"$app`" --demarrage") { throw "clé Run $Name inattendue : $run" }
  "clé Run : $run"
  $null = Post "/local/autostart" @{ on = $false }
  if (RunValue) { throw "clé Run $Name encore présente" }

  Step "Exe relancé : rouvre seulement la page"
  $nodes = @(Leftovers | Where-Object { $_.Name -eq "node" }).Count
  $second = Start-Process -FilePath $app -PassThru -Wait
  if (@(Leftovers | Where-Object { $_.Name -eq "node" }).Count -ne $nodes) { throw "second serveur lancé" }

  Step "Backend"
  $health = Health
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

  Step "Quitter depuis le panneau"
  $null = Post "/local/quit" @{}
  if (-not (WaitUntil { -not (Health) -and @(Leftovers).Count -eq 0 } 30)) {
    $left = @(Leftovers)
    throw "processus restants : $(($left | ForEach-Object { "$($_.Name) ($($_.Id))" }) -join ', ')"
  }
  "serveur, pilote et lanceur arrêtés"

  if ($sanityCode -ne 0) { throw "sanity en échec (voir plus haut)" }
  Write-Host "`nAppli portable Windows : OK"
} finally {
  # Journaux du lanceur et du serveur, en artefacts.
  New-Item -ItemType Directory -Force $Logs | Out-Null
  Get-ChildItem $env:COMPANION_LOG_DIR -Filter *.log -ErrorAction SilentlyContinue | Copy-Item -Destination $Logs
}
