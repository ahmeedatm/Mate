# Ouvre le pare-feu de Windows au node du runtime (serveur du compagnon + recherche UDP de la tablette),
# reseaux prives seulement. Lance par le panneau (backend/src/portable.ts) : demande l'accord administrateur si la
# regle manque ou vise un autre dossier. -Retirer supprime la regle. Code de sortie 0 = fait.
param([switch] $Retirer)
$name = 'UnoMate'
$node = Join-Path (Split-Path -Parent $PSScriptRoot) 'node\node.exe'

$existing = Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue
if ($Retirer) {
  if (-not $existing) { exit 0 }
} elseif ($existing -and (@($existing | Get-NetFirewallApplicationFilter | Where-Object { $_.Program -eq $node }).Count -gt 0)) {
  exit 0
}

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) {
  try {
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"")
    if ($Retirer) { $arguments += '-Retirer' }
    $p = Start-Process powershell.exe -Verb RunAs -Wait -PassThru -WindowStyle Hidden -ArgumentList $arguments
    exit $p.ExitCode
  } catch {
    # Accord refuse.
    exit 1
  }
}

try {
  Remove-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue
  if (-not $Retirer) {
    New-NetFirewallRule -DisplayName $name -Direction Inbound -Action Allow -Program $node -Profile Private, Domain -ErrorAction Stop | Out-Null
  }
  exit 0
} catch {
  exit 1
}
