# Ouvre le pare-feu de Windows au node du paquet (serveur du compagnon + recherche UDP de la tablette),
# reseaux prives seulement. Lance par "Installer (Windows).cmd" ; demande l'accord administrateur si la
# regle manque ou vise un autre dossier (paquet extrait ailleurs). Code de sortie 0 = regle en place.
$name = 'Compagnon Unowhy'
$node = Join-Path (Split-Path -Parent $PSScriptRoot) 'node\node.exe'

$existing = Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue
if ($existing -and (@($existing | Get-NetFirewallApplicationFilter | Where-Object { $_.Program -eq $node }).Count -gt 0)) { exit 0 }

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) {
  try {
    $p = Start-Process powershell.exe -Verb RunAs -Wait -PassThru -WindowStyle Hidden `
      -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"")
    exit $p.ExitCode
  } catch {
    # Accord refuse.
    exit 1
  }
}

try {
  Remove-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue
  New-NetFirewallRule -DisplayName $name -Direction Inbound -Action Allow -Program $node -Profile Private, Domain -ErrorAction Stop | Out-Null
  exit 0
} catch {
  exit 1
}
