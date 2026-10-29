#!/bin/bash
# Exécutable de « Compagnon Unowhy.app » (Contents/MacOS), copié par scripts/package.ts. Rien n'est installé : l'appli
# reste où l'utilisateur l'a mise. Au lancement, elle recopie son runtime (Resources/backend) dans
# ~/Library/Application Support/UnowhyCompanion/runtime (chemin stable : l'Accessibilité accordée à node reste valable
# d'une version à l'autre), lance le serveur dans une boucle qui le relance s'il s'arrête, puis ouvre le panneau (page
# locale). Relancée alors que son compagnon tourne : rouvre seulement la page.
# Sortie du serveur : 75 = quitter (panneau), 76 = quitter et effacer le runtime (« tout oublier »), autre = relancer.
#   --demarrage  ouverture de session (agent posé par le panneau) : la boucle tourne au premier plan, sans ouvrir la page
#   --boucle     la boucle seule, lancée par l'appli elle-même
# COMPANION_HEADLESS=1 (qa/install-mac.sh) : ni page ni boîte. COMPANION_RUNTIME_DIR, COMPANION_LOG_DIR, PORT : instance de test.
set -u
export PATH="/usr/bin:/bin:/usr/sbin:/sbin"

app="$(cd "$(dirname "$0")/../.." && pwd -P)"
source_dir="$app/Contents/Resources/backend"
arch="$([ "$(uname -m)" = arm64 ] && echo arm64 || echo x64)"
other="$([ "$arch" = arm64 ] && echo x64 || echo arm64)"
runtime="${COMPANION_RUNTIME_DIR:-$HOME/Library/Application Support/UnowhyCompanion/runtime}"
logs="${COMPANION_LOG_DIR:-$HOME/Library/Logs/UnowhyCompanion}"
node="$runtime/node/darwin-$arch/node"
port="${PORT:-3000}"
headless="${COMPANION_HEADLESS:-}"
mode="${1:-}"

alert() {
  [ -n "$headless" ] && { echo "$1" >&2; return; }
  osascript - "$1" <<'OSA' >/dev/null 2>&1
on run argv
  display dialog (item 1 of argv) with title "Compagnon Unowhy" buttons {"OK"} default button 1 with icon caution
end run
OSA
}

healthy() { curl -fsS --max-time 2 "http://127.0.0.1:$port/health" >/dev/null 2>&1; }
open_page() { [ -z "$headless" ] && [ "$mode" != --demarrage ] && open "http://127.0.0.1:$port/"; return 0; }
same_build() { cmp -s "$source_dir/runtime.stamp" "$runtime/runtime.stamp"; }
wait_down() { # secondes
  local n=$(($1 * 2))
  while ((n-- > 0)); do healthy || return 0; sleep 0.5; done
  ! healthy
}

boucle() {
  mkdir -p "$logs"
  cd "$runtime" || exit 1
  export COMPANION_APP="$app"
  local code
  while true; do
    "$node" --env-file-if-exists=.env dist/server.mjs >>"$logs/backend.log" 2>&1
    code=$?
    case $code in
      75) exit 0 ;;
      76) cd / && rm -rf "$runtime"; exit 0 ;;
    esac
    sleep 10
  done
}

[ "$mode" = --boucle ] && boucle

# Reste d'une mise à jour faite par le panneau.
rm -rf "$app.old" 2>/dev/null

if healthy; then
  if same_build; then open_page; exit 0; fi
  # Autre build, ou ancienne installation : on lui demande de quitter ; sinon son agent launchd, s'il vient de l'ancien
  # installeur (appli dans Contents/Resources/backend), est arrêté pour la session. Un compagnon lancé depuis le dépôt n'est jamais touché.
  curl -fsS --max-time 3 -X POST -H 'content-type: application/json' -d '{}' "http://127.0.0.1:$port/local/quit" >/dev/null 2>&1
  if ! wait_down 15; then
    if grep -qF "Contents/Resources/backend" "$HOME/Library/LaunchAgents/com.unowhy.companion.plist" 2>/dev/null; then
      launchctl bootout "gui/$(id -u)/com.unowhy.companion" 2>/dev/null
    fi
    wait_down 10 || { alert "Un compagnon tourne déjà sur le port $port (autre version, ou lancé depuis le dépôt) : arrête-le, puis rouvre l'appli."; exit 1; }
  fi
fi

if ! same_build || [ ! -x "$node" ]; then
  mkdir -p "$runtime" || exit 1
  # Sans l'autre architecture de node ; .env et .cache gardés ; le repère est posé en dernier.
  if ! out="$(rsync -a --delete --exclude /node/darwin-"$other" --exclude /.env --exclude /.cache --exclude /runtime.stamp "$source_dir/" "$runtime/" 2>&1 &&
    cp "$source_dir/runtime.stamp" "$runtime/runtime.stamp" 2>&1)"; then
    alert "Le compagnon n'a pas pu être préparé dans $runtime.

$(printf '%s' "$out" | tail -n 6)"
    exit 1
  fi
  # Fichiers sortis d'un zip téléchargé : sans ça, macOS demande une confirmation pour node.
  xattr -dr com.apple.quarantine "$app" "$runtime" 2>/dev/null
fi

if [ "$mode" = --demarrage ]; then boucle; fi
nohup "$app/Contents/MacOS/Compagnon Unowhy" --boucle >/dev/null 2>&1 &
disown 2>/dev/null

for _ in $(seq 60); do healthy && break; sleep 0.5; done
if ! healthy; then
  alert "Le compagnon ne répond pas. Journal : $logs/backend.log"
  exit 1
fi
open_page
exit 0
