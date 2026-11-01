#!/usr/bin/env bash
# Sortie 0 si la PR <numéro> est à publier en essai : ouverte, hors brouillon, étiquetée « essai », du dépôt même (pas
# d'une fourche), et sa tête est <sha> (le commit construit). Sinon 1, avec la raison. UPDATES_REPO et GH_TOKEN requis.
# Usage : .github/essai-pr.sh <numéro> <sha>
set -euo pipefail
pr=$1 sha=$2
gh pr view "$pr" -R "$UPDATES_REPO" --json state,isDraft,isCrossRepository,headRefOid,labels \
  | jq -e --arg sha "$sha" '.state == "OPEN" and (.isDraft | not) and (.isCrossRepository | not)
      and .headRefOid == $sha and any(.labels[]; .name == "essai")' > /dev/null && exit 0
echo "PR #$pr : pas (ou plus) à publier (fermée, brouillon, sans étiquette essai, fourche ou commit dépassé)."
exit 1
