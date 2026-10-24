#!/bin/sh
# Sauvegarde d'Unowhy Companion dans un dossier (par défaut le Bureau) :
#  - unowhy-companion-AAAA-MM-JJ.bundle : tout l'historique git (commits seulement), à partager sans dépôt distant ;
#  - unowhy-companion-donnees-perso-AAAA-MM-JJ.tgz : secrets et données de CET ordinateur, à garder pour soi.
# Usage : scripts/sauvegarde.sh [dossier]
set -e
cd "$(dirname "$0")/.."
dest="${1:-$HOME/Desktop}"
day=$(date +%Y-%m-%d)

if [ -n "$(git status --porcelain)" ]; then
  echo "Attention : des changements ne sont pas commités, ils ne seront pas dans le bundle."
fi

bundle="$dest/unowhy-companion-$day.bundle"
git bundle create "$bundle" --all
git bundle verify "$bundle" >/dev/null
echo "Code : $bundle"

# Ce que git ignore volontairement : .env (secrets), .data (jeton, agenda, tâches, touches…), deck et tâches perso.
# Les mises à jour servies à la tablette (APK lourds) restent dehors.
perso=""
for f in backend/.env backend/.data backend/config/deck.json backend/config/tasks.json local.properties; do
  [ -e "$f" ] && perso="$perso $f"
done
if [ -n "$perso" ]; then
  archive="$dest/unowhy-companion-donnees-perso-$day.tgz"
  # shellcheck disable=SC2086
  tar czf "$archive" --exclude backend/.data/updates $perso
  echo "Données perso (secrets, ne pas partager) : $archive"
fi
