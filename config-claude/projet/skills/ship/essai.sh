#!/bin/sh
# Publie la MR de la branche courante en mise à jour d'essai pour la tablette (docs/developpement.md, « Déployer un
# essai depuis une MR ») : branche protégée essai/mr-<iid>, job manuel essai-android, attente d'essai-updates, puis
# l'essai annoncé par le compagnon du Mac (la tablette le voit dans Réglages > Mises à jour). Si backend/ a changé,
# le compagnon du Mac passe aussi sur la branche. Un seul essai à la fois : le dernier publié remplace le précédent.
# Sortie 0 = essai annoncé, dernière ligne = sa version.
set -eu
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

root=$(git rev-parse --show-toplevel)
here=$(cd "$(dirname "$0")" && pwd)
wait="$HOME/.claude/scripts/wait-until.sh"
proj=efreifer%2Funowhy-companion
branch=$(git -C "$root" branch --show-current)

iid=$(glab api "projects/$proj/merge_requests?source_branch=$branch&state=opened" | jq -r '.[0].iid // empty')
[ -n "$iid" ] || { echo "❌ aucune MR ouverte pour $branch"; exit 1; }
essai=essai/mr-$iid
ref=$(printf %s "$essai" | sed 's#/#%2F#g')
sha=$(git -C "$root" rev-parse HEAD)

# Pipeline le plus récent de la branche d'essai avant publication : le nouveau a un id plus grand.
last=$(glab api "projects/$proj/pipelines?ref=$ref&per_page=1" | jq -r '.[0].id // 0')

echo "essai de la MR !$iid ($branch, $(git -C "$root" rev-parse --short HEAD))"
if [ "$(git -C "$root" ls-remote origin "refs/heads/$essai" | cut -f1)" = "$sha" ]; then
  # Déjà poussé : un push ne relancerait rien, nouveau pipeline par l'API (autre essai a pu passer entre-temps).
  glab api -X POST "projects/$proj/pipeline?ref=$ref" >/dev/null
elif ! git -C "$root" push -q origin "HEAD:refs/heads/$essai" 2>/dev/null; then
  # Refusé (branche de MR rebasée, essai/* sans force push) : la branche d'essai de cette MR est recréée.
  glab api -X DELETE "projects/$proj/repository/branches/$ref" >/dev/null
  git -C "$root" push -q origin "HEAD:refs/heads/$essai"
fi

# shellcheck disable=SC2016
"$wait" --timeout 300 --interval 5 -- sh -c '
  out=$(glab api "projects/$1/pipelines?ref=$2&per_page=1") || exit 2
  id=$(printf %s "$out" | jq -r ".[0].id // 0") || exit 2
  [ "$id" -gt "$3" ] && echo "pipeline $id" && exit 0
  exit 1' sh "$proj" "$ref" "$last"
pid=$(glab api "projects/$proj/pipelines?ref=$ref&per_page=1" | jq -r '.[0].id')
jobs=$(glab api "projects/$proj/pipelines/$pid/jobs")
android=$(printf %s "$jobs" | jq -r '.[] | select(.name=="essai-android") | .id')
updates=$(printf %s "$jobs" | jq -r '.[] | select(.name=="essai-updates") | .id')
[ -n "$android" ] && [ -n "$updates" ] || { echo "❌ pipeline $pid sans jobs d'essai (branche essai/* protégée ?)"; exit 1; }

# Le job manuel n'est jouable qu'une fois les étapes d'avant finies (`created` → `manual`) : sinon 400 « Unplayable Job ».
# shellcheck disable=SC2016
"$wait" --timeout 600 --interval 10 -- sh -c '
  s=$(glab api "projects/$1/jobs/$2" | jq -r .status) || exit 2
  case $s in manual) exit 0 ;; created|pending|running|waiting_for_resource|preparing) exit 1 ;; *) echo "essai-android : $s"; exit 2 ;; esac' sh "$proj" "$android"
glab api -X POST "projects/$proj/jobs/$android/play" >/dev/null
echo "pipeline $pid : essai-android lancé"
"$wait" --timeout 1500 --interval 20 -- "$HOME/.claude/scripts/gitlab-status.sh" job "$updates" efreifer/unowhy-companion
status=$(glab api "projects/$proj/jobs/$updates" | jq -r .status)
if [ "$status" != success ]; then
  failed=$(glab api "projects/$proj/pipelines/$pid/jobs" | jq -r '[.[] | select(.status=="failed")][0].id // empty')
  echo "❌ essai-updates : $status${failed:+ (job $failed en échec : glab ci trace $failed)}"
  exit 1
fi

# Annonce par le compagnon du Mac (installé par paquet), lue par la tablette.
cd "$root/backend"
[ -d node_modules ] || npm ci --silent
DATA_DIR=paquet npm run -s updates:essai

APK=0 "$here/deploy-tablette.sh"
