#!/bin/bash
# Exécutable de « Compagnon Unowhy.app » (Contents/MacOS), copié par scripts/package.ts.
# Premier lancement : l'appli se copie dans Applications (chemin de node stable : l'Accessibilité reste accordée
# d'une version à l'autre), installe le lancement automatique (dist/service.mjs install), puis ouvre la page locale.
# Relancée alors que son compagnon tourne : rouvre seulement la page. Nouvelle version : remplace l'ancienne.
# COMPANION_HEADLESS=1 (CI, job install-mac) : sur place, ni copie, ni boîte, ni page ; le code de sortie dit si
# l'installation a marché.
set -u
export PATH="/usr/bin:/bin:/usr/sbin:/sbin"

app="$(cd "$(dirname "$0")/../.." && pwd -P)"
backend="$app/Contents/Resources/backend"
node="$backend/node/darwin-$([ "$(uname -m)" = arm64 ] && echo arm64 || echo x64)/node"
label="${COMPANION_LABEL:-com.unowhy.companion}"
port="${PORT:-3000}"
headless="${COMPANION_HEADLESS:-}"

alert() {
  [ -n "$headless" ] && { echo "$1" >&2; return; }
  osascript - "$1" <<'EOF' >/dev/null 2>&1
on run argv
  display dialog (item 1 of argv) with title "Compagnon Unowhy" buttons {"OK"} default button 1 with icon caution
end run
EOF
}

# Hors d'un dossier Applications (Téléchargements, ou chemin aléatoire de macOS pour une appli téléchargée) :
# copie dans /Applications, sinon ~/Applications (compte standard), puis lancement de la copie.
if [ -z "$headless" ]; then
  case "$app" in
    /Applications/* | "$HOME/Applications/"*) ;;
    *)
      for dir in /Applications "$HOME/Applications"; do
        dest="$dir/Compagnon Unowhy.app"
        mkdir -p "$dir" 2>/dev/null && [ -w "$dir" ] || continue
        rm -rf "$dest" 2>/dev/null
        if ditto "$app" "$dest" 2>/dev/null; then
          xattr -dr com.apple.quarantine "$dest" 2>/dev/null
          exec "$dest/Contents/MacOS/Compagnon Unowhy"
        fi
      done
      alert "Impossible de copier l'appli dans Applications. Glisse « Compagnon Unowhy » dans le dossier Applications, puis ouvre-la depuis là."
      exit 1
      ;;
  esac
fi

# Même build déjà installé (Info.plist recopié à l'installation, il porte la date du paquet) et en marche : la page seulement.
page="http://127.0.0.1:$port/"
plist="$HOME/Library/LaunchAgents/$label.plist"
stamp="$HOME/Library/Application Support/UnowhyCompanion/app-Info.plist"
if [ -z "$headless" ] && cmp -s "$app/Contents/Info.plist" "$stamp" && grep -qF "$backend/" "$plist" 2>/dev/null &&
  curl -fsS --max-time 2 "${page}health" >/dev/null 2>&1; then
  open "$page"
  exit 0
fi

cd "$backend" || exit 1
# Fichiers sortis d'un zip téléchargé : sans ça, macOS demande une confirmation pour node.
xattr -dr com.apple.quarantine "$app" 2>/dev/null
if [ -n "$headless" ]; then
  "$node" --env-file-if-exists=.env dist/service.mjs install
  exit $?
fi
out="$("$node" --env-file-if-exists=.env dist/service.mjs install 2>&1)"
code=$?
if [ $code -eq 0 ]; then
  mkdir -p "$(dirname "$stamp")" && cp "$app/Contents/Info.plist" "$stamp"
else
  alert "L'installation a échoué.

$(printf '%s' "$out" | tail -n 8)"
fi
exit $code
