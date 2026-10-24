# Processus résident du backend sous Windows (lancé par src/win/helper.ts) : compile native.cs une fois,
# puis exécute une requête JSON par ligne lue sur stdin. Chaque réponse est une ligne "@@{...}" sur stdout
# (le préfixe permet d'ignorer toute autre sortie). Windows PowerShell 5.1, lancé avec -STA (presse-papiers).

$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding $false
$stdin = New-Object System.IO.StreamReader -ArgumentList ([Console]::OpenStandardInput()), $utf8
$stdout = New-Object System.IO.StreamWriter -ArgumentList ([Console]::OpenStandardOutput()), $utf8
$stdout.AutoFlush = $true

try {
  # Lance par helper.ts en memoire (pas de $PSScriptRoot) : dossier donne par COMPANION_HELPER_DIR.
  $root = if ($PSScriptRoot) { $PSScriptRoot } else { $env:COMPANION_HELPER_DIR }
  $source = [System.IO.File]::ReadAllText((Join-Path $root 'native.cs'), $utf8)
  Add-Type -TypeDefinition $source -Language CSharp -ReferencedAssemblies @(
    'System.Core', 'System.Drawing', 'System.Windows.Forms', 'System.Web.Extensions', 'System.Management'
  )
  [Companion.Native]::Init()
} catch {
  $stdout.WriteLine('@@{"ok":false,"fatal":true,"error":' + (ConvertTo-Json -Compress -InputObject ([string]$_)) + '}')
  exit 1
}

# --- Lecture en cours : contrôles multimédias de Windows (ceux du panneau de volume), où le navigateur publie
# YouTube, YouTube Music, Twitch, et Spotify aussi. API WinRT : PowerShell sait l'appeler, pas le C# d'Add-Type.

$script:asTask = $null
$script:mediaManager = $null

function Await($operation, [Type]$type) {
  if (-not $script:asTask) {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $script:asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
      $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
    } | Select-Object -First 1
  }
  $task = $script:asTask.MakeGenericMethod($type).Invoke($null, @($operation))
  if (-not $task.Wait(5000)) { throw 'Windows ne repond pas (lecture en cours)' }
  $task.Result
}

function Get-MediaManager {
  if (-not $script:mediaManager) {
    $type = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
    $script:mediaManager = Await ($type::RequestAsync()) $type
  }
  $script:mediaManager
}

function Get-MediaProperties($session) {
  $type = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime]
  Await ($session.TryGetMediaPropertiesAsync()) $type
}

# Session de l'appli $app (identifiant Windows de l'appli), sinon celle que Windows met en avant.
function Find-MediaSession($app) {
  $manager = Get-MediaManager
  if ($app) {
    foreach ($s in $manager.GetSessions()) { if ($s.SourceAppUserModelId -eq $app) { return $s } }
  }
  $current = $manager.GetCurrentSession()
  if (-not $current) { throw "Rien en lecture sur l'ordinateur" }
  $current
}

function Get-MediaList {
  $manager = Get-MediaManager
  $current = $manager.GetCurrentSession()
  $list = New-Object System.Collections.ArrayList
  foreach ($s in $manager.GetSessions()) {
    $props = Get-MediaProperties $s
    $playback = $s.GetPlaybackInfo()
    $controls = $playback.Controls
    $timeline = $s.GetTimelineProperties()
    $status = [string]$playback.PlaybackStatus
    # Position donnée à l'instant LastUpdatedTime : on ajoute le temps écoulé depuis si la lecture continue.
    $position = $timeline.Position
    if ($status -eq 'Playing' -and $timeline.LastUpdatedTime.Year -gt 2000) { $position += [DateTimeOffset]::Now - $timeline.LastUpdatedTime }
    $duration = ($timeline.EndTime - $timeline.StartTime).TotalMilliseconds
    $positionMs = $null
    $durationMs = $null
    if ($duration -gt 0) {
      $durationMs = [long]$duration
      $positionMs = [long][Math]::Max(0, [Math]::Min($duration, ($position - $timeline.StartTime).TotalMilliseconds))
    }
    [void]$list.Add([ordered]@{
      app = [string]$s.SourceAppUserModelId
      current = [bool]($current -and $current.SourceAppUserModelId -eq $s.SourceAppUserModelId)
      title = [string]$props.Title
      artist = [string]$props.Artist
      album = [string]$props.AlbumTitle
      hasThumb = [bool]($null -ne $props.Thumbnail)
      status = $status.ToLowerInvariant()
      canPlayPause = [bool]$controls.IsPlayPauseToggleEnabled
      canNext = [bool]$controls.IsNextEnabled
      canPrevious = [bool]$controls.IsPreviousEnabled
      canSeek = [bool]$controls.IsPlaybackPositionEnabled
      positionMs = $positionMs
      durationMs = $durationMs
    })
  }
  , $list
}

# Écrit la vignette (pochette, image de la vidéo) dans $path ; $false si la session n'en a pas.
function Save-MediaThumb($session, $path) {
  $thumb = (Get-MediaProperties $session).Thumbnail
  if (-not $thumb) { return $false }
  $type = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime]
  $winStream = (Await ($thumb.OpenReadAsync()) $type).psobject.BaseObject
  # Par réflexion : PowerShell voit le flux comme un __ComObject sans méthodes et ne trouve pas la surcharge ;
  # .NET, lui, reconnaît l'interface IInputStream.
  $inputType = [Windows.Storage.Streams.IInputStream, Windows.Storage.Streams, ContentType = WindowsRuntime]
  $asStream = [System.IO.WindowsRuntimeStreamExtensions].GetMethod('AsStreamForRead', [Type[]]@($inputType))
  $stream = $asStream.Invoke($null, @(, $winStream))
  $file = [System.IO.File]::Create($path)
  try { $stream.CopyTo($file) } finally { $file.Dispose(); $stream.Dispose() }
  $true
}

function Invoke-Media($req) {
  switch ($req.action) {
    # Virgule : la liste reste une liste (vide ou d'un seul élément) au lieu d'être déroulée.
    'list' { return , (Get-MediaList) }
    'thumb' { return Save-MediaThumb (Find-MediaSession $req.app) $req.out }
    'play-pause' { return Await ((Find-MediaSession $req.app).TryTogglePlayPauseAsync()) ([bool]) }
    'next' { return Await ((Find-MediaSession $req.app).TrySkipNextAsync()) ([bool]) }
    'previous' { return Await ((Find-MediaSession $req.app).TrySkipPreviousAsync()) ([bool]) }
    'seek' { return Await ((Find-MediaSession $req.app).TryChangePlaybackPositionAsync([long]$req.position * 10000)) ([bool]) }
    default { throw "Action inconnue : $($req.action)" }
  }
}

# --- Page ouverte dans le navigateur (bouton "Page de l'ordi" de la tablette) : barre d'adresse lue par
# UI Automation, chargee au premier appel. Fenetres parcourues de la plus en avant a la plus en arriere :
# le navigateur utilise en dernier gagne, meme derriere un jeu en plein ecran.

$script:browsers = @{ chrome = 'Chrome'; msedge = 'Edge'; opera = 'Opera'; brave = 'Brave'; vivaldi = 'Vivaldi'; firefox = 'Firefox' }

function Get-BrowserPage {
  if (-not ('System.Windows.Automation.AutomationElement' -as [type])) {
    Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes
  }
  $auto = [System.Windows.Automation.AutomationElement]
  $scope = [System.Windows.Automation.TreeScope]
  $isEdit = New-Object System.Windows.Automation.PropertyCondition -ArgumentList $auto::ControlTypeProperty, ([System.Windows.Automation.ControlType]::Edit)
  $firefoxBar = New-Object System.Windows.Automation.PropertyCondition -ArgumentList $auto::AutomationIdProperty, 'urlbar-input'
  foreach ($window in $auto::RootElement.FindAll($scope::Children, [System.Windows.Automation.Condition]::TrueCondition)) {
    $process = Get-Process -Id $window.Current.ProcessId -ErrorAction SilentlyContinue
    if (-not $process) { continue }
    $browser = $script:browsers[$process.ProcessName.ToLowerInvariant()]
    if (-not $browser) { continue }
    # Premier champ dont la valeur ressemble a une adresse : Chrome n'en a qu'un, Opera GX en a deux (le premier
    # vide), Firefox nomme le sien urlbar-input.
    foreach ($field in $window.FindAll($scope::Descendants, $(if ($browser -eq 'Firefox') { $firefoxBar } else { $isEdit }))) {
      $pattern = $null
      if (-not $field.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$pattern)) { continue }
      $value = ([string]$pattern.Current.Value).Trim()
      if ($value -match '^\S+\.\S+$' -or $value -match '^[a-z]+://\S+$') {
        return @{ found = $true; url = $value; title = [string]$window.Current.Name; browser = $browser }
      }
    }
  }
  # Secours : Opera et Chrome coupent parfois leur accessibilite (fenetre vide pour UI Automation). Le lien copie
  # dans le navigateur (Ctrl+L puis Ctrl+C) fait alors l'affaire.
  # Jamais le contenu dans la reponse (ce peut etre un mot de passe) : seulement son etat.
  $copied = ''
  $clipboard = 'empty'
  try {
    Add-Type -AssemblyName System.Windows.Forms
    $copied = ([string][System.Windows.Forms.Clipboard]::GetText()).Trim()
    if ($copied) { $clipboard = 'not-url' }
  } catch { $clipboard = 'error: ' + $_.Exception.Message }
  if ($copied -match '^https?://\S+$') { return @{ found = $true; url = $copied; title = ''; browser = 'clipboard' } }
  @{ found = $false; clipboard = $clipboard }
}

# --- Boutons d'une fenetre (UI Automation) : le bouton Jouer d'un launcher de jeu, par son nom.
# 'list' : noms des elements cliquables (lecture seule) ; 'invoke' : actionne le premier dont le nom est dans $req.names,
# par son motif Invoke, sinon par un clic de souris au centre (les launchers Electron n'exposent pas toujours Invoke).

function Initialize-Mouse {
  if ('Companion.Mouse' -as [type]) { return }
  Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
namespace Companion {
  public static class Mouse {
    [DllImport("user32.dll")] static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extra);
    public static void Click(int x, int y) {
      SetCursorPos(x, y);
      mouse_event(0x0002, 0, 0, 0, UIntPtr.Zero);
      mouse_event(0x0004, 0, 0, 0, UIntPtr.Zero);
    }
  }
}
'@
}

function Invoke-Press($req) {
  if (-not ('System.Windows.Automation.AutomationElement' -as [type])) {
    Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes
  }
  $auto = [System.Windows.Automation.AutomationElement]
  $scope = [System.Windows.Automation.TreeScope]
  $wanted = @($req.names | ForEach-Object { ([string]$_).Trim().ToLowerInvariant() })
  $seen = New-Object System.Collections.ArrayList
  $total = 0
  foreach ($h in $req.windows) {
    $root = $auto::FromHandle([IntPtr][long]$h)
    if (-not $root) { continue }
    foreach ($e in $root.FindAll($scope::Descendants, [System.Windows.Automation.Condition]::TrueCondition)) {
      $total++
      $name = ([string]$e.Current.Name).Trim()
      # Diagnostic : tous les elements avec leur classe (Chromium y met la classe HTML).
      if ($req.detail) {
        $cls = [string]$e.Current.ClassName
        if (($name -or $cls) -and $seen.Count -lt 3000) {
          $type = $e.Current.ControlType.ProgrammaticName -replace '^ControlType\.', ''
          [void]$seen.Add("$type : $name : $cls")
        }
        continue
      }
      if (-not $name -or $name.Length -gt 40) { continue }
      $type = $e.Current.ControlType.ProgrammaticName -replace '^ControlType\.', ''
      if ($type -notin @('Button', 'Hyperlink', 'MenuItem', 'ListItem', 'Text', 'Group', 'Custom')) { continue }
      if ($req.action -eq 'list') {
        if ($seen.Count -lt 1500) { [void]$seen.Add("$type : $name") }
        continue
      }
      if ($wanted -notcontains $name.ToLowerInvariant()) { continue }
      if ($e.Current.IsOffscreen -or -not $e.Current.IsEnabled) { continue }
      $pattern = $null
      if ($e.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$pattern)) {
        $pattern.Invoke()
        return @{ pressed = $name; how = 'invoke' }
      }
      $r = $e.Current.BoundingRectangle
      if ($r.Width -le 0 -or $r.Height -le 0) { continue }
      Initialize-Mouse
      [Companion.Mouse]::Click([int]($r.X + $r.Width / 2), [int]($r.Y + $r.Height / 2))
      return @{ pressed = $name; how = 'click' }
    }
  }
  if ($req.action -eq 'list') { return @{ items = $seen; total = $total } }
  @{ pressed = $null }
}

# --- Etat vocal de Discord (UI Automation : Chromium expose son interface une fois l'accessibilite demandee).
# Panneau du bas a gauche : "Voix connectee" + lien du salon/appel, boutons micro et sourdine ; vue de l'appel :
# "Deconnecter" puis un bouton par participant. Noms en francais ou en anglais (pas d'accents ici : fichier lu en ANSI).

function Get-ToggleState($e) {
  $pattern = $null
  if ($e.TryGetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern, [ref]$pattern)) {
    return [string]$pattern.Current.ToggleState -eq 'On'
  }
  $null
}

function Get-Voice($req) {
  if (-not ('System.Windows.Automation.AutomationElement' -as [type])) {
    Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes
  }
  $auto = [System.Windows.Automation.AutomationElement]
  $scope = [System.Windows.Automation.TreeScope]
  $state = @{ found = $false; connected = $false; channel = $null; people = @(); muted = $null; deafened = $null; mic = $null; deaf = $null }
  foreach ($h in $req.windows) {
    $root = $auto::FromHandle([IntPtr][long]$h)
    if (-not $root) { continue }
    $phase = 'start'
    $self = @()
    foreach ($e in $root.FindAll($scope::Descendants, [System.Windows.Automation.Condition]::TrueCondition)) {
      $name = ([string]$e.Current.Name).Trim()
      if (-not $name) { continue }
      $type = $e.Current.ControlType.ProgrammaticName -replace '^ControlType\.', ''
      switch ($phase) {
        'start' {
          if ($type -eq 'Group' -and $name -match '^(Statut et param|User (status|area|settings))') { $phase = 'panel'; $state.found = $true }
        }
        'panel' {
          if ($name -match 'Voix connect|Voice connected') { $state.connected = $true; $phase = 'channel' }
          elseif ($type -eq 'Text') { $self += $name }
          elseif ($type -eq 'Button' -and $name -match '^(Rendre muet|R.activer|Mute|Unmute)') { $state.mic = $name; $state.muted = Get-ToggleState $e }
          elseif ($type -eq 'Button' -and $name -match 'sourdine|deafen') { $state.deaf = $name; $state.deafened = Get-ToggleState $e; $phase = 'call' }
        }
        'channel' {
          if ($type -eq 'Hyperlink') { $state.channel = $name; $phase = 'panel' }
        }
        'call' {
          if ($type -eq 'Button' -and $name -match '^(D.connecter|Disconnect)$') { $phase = 'people' }
        }
        'people' {
          if ($type -eq 'Group') { $phase = 'done' }
          elseif ($type -eq 'Button' -and $self -notcontains $name) { $state.people += $name }
        }
      }
      if ($phase -eq 'done') { break }
    }
    if ($state.found) { break }
  }
  $state
}

$stdout.WriteLine('@@{"ok":true,"result":"ready"}')
while ($true) {
  $line = $stdin.ReadLine()
  if ($null -eq $line) { break }
  if ($line.Trim().Length -eq 0) { continue }
  try {
    $req = ConvertFrom-Json $line
    if ($req.op -eq 'browser') {
      $result = Get-BrowserPage
      $stdout.WriteLine('@@{"ok":true,"result":' + (ConvertTo-Json -Compress -InputObject $result) + '}')
      continue
    }
    if ($req.op -eq 'press') {
      $result = Invoke-Press $req
      $stdout.WriteLine('@@{"ok":true,"result":' + (ConvertTo-Json -Compress -Depth 4 -InputObject $result) + '}')
      continue
    }
    if ($req.op -eq 'voice') {
      $result = Get-Voice $req
      $stdout.WriteLine('@@{"ok":true,"result":' + (ConvertTo-Json -Compress -Depth 4 -InputObject $result) + '}')
      continue
    }
    if ($req.op -eq 'media') {
      $result = Invoke-Media $req
      $stdout.WriteLine('@@{"ok":true,"result":' + (ConvertTo-Json -Compress -Depth 4 -InputObject $result) + '}')
      continue
    }
    $stdout.WriteLine('@@' + [Companion.Native]::Handle($line))
  } catch {
    $stdout.WriteLine('@@{"ok":false,"error":' + (ConvertTo-Json -Compress -InputObject ([string]$_)) + '}')
  }
}
