#!/bin/sh
# Sauvegarde d'UnoMate dans un dossier (par défaut le Bureau) :
#  - unomate-AAAA-MM-JJ.bundle : tout l'historique git (commits seulement), à partager sans dépôt distant ;
#  - unomate-donnees-perso-AAAA-MM-JJ.tgz : secrets et données de CET ordinateur, à garder pour soi.
# Usage : scripts/sauvegarde.sh [dossier]
set -e
cd "$(dirname "$0")/.."
dest="${1:-$HOME/Desktop}"
day=$(date +%Y-%m-%d)

if [ -n "$(git status --porcelain)" ]; then
  echo "Attention : des changements ne sont pas commités, ils ne seront pas dans le bundle."
fi

bundle="$dest/unomate-$day.bundle"
git bundle create "$bundle" --all
git bundle verify "$bundle" >/dev/null
echo "Code : $bundle"

# Ce que git ignore volontairement : .env (secrets), .data (jeton, agenda, tâches, touches…), deck et tâches perso.
# Les mises à jour servies à la tablette (APK lourds) restent dehors.
perso=""
for f in backend/.env backend/.data backend/config/deck.json backend/config/tasks.json local.properties; do
  [ -e "$f" ] && perso="$perso $f"
done
# Compagnon installé par paquet sur ce Mac : ses données sont hors du dépôt (src/paths.ts).
app="$HOME/Library/Application Support/UnoMate"
if [ -n "$perso" ] || [ -d "$app" ]; then
  archive="$dest/unomate-donnees-perso-$day.tgz"
  # shellcheck disable=SC2086
  if [ -d "$app" ]; then
    tar czf "$archive" --exclude backend/.data/updates $perso -C "$(dirname "$app")" UnoMate
  else
    tar czf "$archive" --exclude backend/.data/updates $perso
  fi
  echo "Données perso (secrets, ne pas partager) : $archive"
fi
