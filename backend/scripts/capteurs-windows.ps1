# Active la temperature du processeur (widget Performances) : installe le pilote PawnIO (namazso/PawnIO.Setup, version
# et SHA-256 figes) s'il manque, copie MateCapteurs.exe dans %LOCALAPPDATA%\Mate\capteurs\bin et cree la tache planifiee
# « MateCapteurs » (droits maximum, a l'ouverture de session) qui le lance. Le pilote noyau ne se lit qu'en
# administrateur : l'accord est demande ici, une seule fois, puis la tache demarre sans accord.
# Lance par le panneau (backend/src/portable.ts). -Retirer supprime la tache (le pilote reste). Code de sortie 0 = fait.
param([switch] $Retirer)
$task = 'MateCapteurs'
$pawnVersion = '2.2.0'
$pawnSha256 = '1F519A22E47187F70A1379A48CA604981C4FCF694F4E65B734AAA74A9FBA3032'
$source = Join-Path (Split-Path -Parent $PSScriptRoot) 'src\win\MateCapteurs'
$dest = Join-Path $env:LOCALAPPDATA 'Mate\capteurs\bin'

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) {
  if ($Retirer -and -not (Get-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue)) { exit 0 }
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
  Stop-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue
  Get-Process -Name MateCapteurs -ErrorAction SilentlyContinue | Stop-Process -Force
  if ($Retirer) {
    Unregister-ScheduledTask -TaskName $task -Confirm:$false -ErrorAction SilentlyContinue
    exit 0
  }

  if (-not (Test-Path (Join-Path $env:ProgramFiles 'PawnIO\PawnIOLib.dll'))) {
    $setup = Join-Path $env:TEMP 'PawnIO_setup.exe'
    Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/namazso/PawnIO.Setup/releases/download/$pawnVersion/PawnIO_setup.exe" -OutFile $setup
    if ((Get-FileHash $setup -Algorithm SHA256).Hash -ne $pawnSha256) { Remove-Item $setup; exit 2 }
    $install = Start-Process $setup -ArgumentList '-install', '-silent' -Wait -PassThru
    Remove-Item $setup -ErrorAction SilentlyContinue
    if ($install.ExitCode -ne 0 -and $install.ExitCode -ne 3010) { exit 3 }
  }

  New-Item -ItemType Directory -Force -Path $dest | Out-Null
  Copy-Item -Path (Join-Path $source '*') -Destination $dest -Recurse -Force
  $user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
  $action = New-ScheduledTaskAction -Execute (Join-Path $dest 'MateCapteurs.exe')
  $trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
  $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Highest
  $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -StartWhenAvailable
  Register-ScheduledTask -TaskName $task -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
  Start-ScheduledTask -TaskName $task
  exit 0
} catch {
  exit 1
}
