# Lance le backend du compagnon sous Windows, fenetre cachee, et le relance s'il s'arrete.
# Installe par `npm run service:install` (entree "Executer" de la session, voir scripts/service.ts).
param(
  [Parameter(Mandatory = $true)][string]$Node,
  [Parameter(Mandatory = $true)][string]$LogDir
)

$backend = Split-Path -Parent $PSScriptRoot
Set-Location $backend
New-Item -ItemType Directory -Force -Path $LogDir, (Join-Path $backend '.data') | Out-Null
# PID de cette boucle : `npm run service:stop` l'arrete avec node (taskkill /T).
Set-Content -Path (Join-Path $backend '.data\service.pid') -Value $PID

while ($true) {
  $out = Join-Path $LogDir 'backend.log'
  $err = Join-Path $LogDir 'backend-erreurs.log'
  Start-Process -FilePath $Node -ArgumentList '--env-file-if-exists=.env', '--import', 'tsx', 'src/server.ts' `
    -WorkingDirectory $backend -NoNewWindow -Wait -RedirectStandardOutput $out -RedirectStandardError $err
  Start-Sleep -Seconds 10
}
