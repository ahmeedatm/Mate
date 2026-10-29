#!/bin/sh
# Publie la MR de la branche courante en essai pour la tablette (docs/developpement.md, « Déployer un essai depuis
# une MR ») : branche protégée essai/mr-<iid> (titre de la MR passé en variable de pipeline MR_TITLE), job manuel
# essai-android, attente d'essai-updates, qui dépose l'essai dans le paquet commun companion-updates/essais (une
# entrée par MR ; le backend de la MR y est aussi si backend/ diffère de main). La tablette le lit dans le registre :
# l'ordinateur n'est pas touché ici.
# Sortie 0 = essai publié, dernière ligne = sa version.
#
# essai.sh --retire <iid> : fusion ou abandon de la MR, retire son essai (branche essai/mr-<iid> et entrée du manifest).
# Jeton GITLAB_TOKEN (scope api) dans l'environnement ; à lancer quand aucun essai n'est en cours de publication.
set -eu
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

root=$(git rev-parse --show-toplevel)
here=$(cd "$(dirname "$0")" && pwd)
wait="$HOME/.claude/scripts/wait-until.sh"
proj=efreifer%2Funowhy-companion

if [ "${1:-}" = --retire ]; then
  iid=${2:?numéro de la MR}
  : "${GITLAB_TOKEN:?GITLAB_TOKEN absent (jeton personnel, scope api)}"
  # Checkout principal si ce worktree n'a pas encore le script (branche plus ancienne).
  script="$root/scripts/updates-publish.mjs"
  [ -f "$script" ] || script="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")/scripts/updates-publish.mjs"
  glab api -X DELETE "projects/$proj/repository/branches/essai%2Fmr-$iid" >/dev/null 2>&1 || true
  out=$(mktemp -d)
  UPDATES_URL="https://gitlab.unowhy.dev/api/v4/projects/891/packages/generic/companion-updates/essais" \
    node "$script" --retire "$iid" "$out/essais"
  rm -rf "$out"
  exit 0
fi

branch=$(git -C "$root" branch --show-current)

mrs=$(glab api "projects/$proj/merge_requests?source_branch=$branch&state=opened")
iid=$(printf %s "$mrs" | jq -r '.[0].iid // empty')
[ -n "$iid" ] || { echo "❌ aucune MR ouverte pour $branch"; exit 1; }
title=$(printf %s "$mrs" | jq -r '.[0].title')
essai=essai/mr-$iid
ref=$(printf %s "$essai" | sed 's#/#%2F#g')
sha=$(git -C "$root" rev-parse HEAD)

# Pipeline le plus récent de la branche d'essai avant publication : le nouveau a un id plus grand.
last=$(glab api "projects/$proj/pipelines?ref=$ref&per_page=1" | jq -r '.[0].id // 0')

echo "essai de la MR !$iid ($branch, $(git -C "$root" rev-parse --short HEAD))"
if [ "$(git -C "$root" ls-remote origin "refs/heads/$essai" | cut -f1)" = "$sha" ]; then
  # Déjà poussé : un push ne relancerait rien, nouveau pipeline par l'API (autre essai a pu passer entre-temps).
  enc=$(jq -rn --arg t "$title" '$t|@uri')
  glab api -X POST "projects/$proj/pipeline?ref=$ref&variables[][key]=MR_TITLE&variables[][value]=$enc" >/dev/null
elif ! git -C "$root" push -q -o "ci.variable=MR_TITLE=$title" origin "HEAD:refs/heads/$essai" 2>/dev/null; then
  # Refusé (branche de MR rebasée, essai/* sans force push) : la branche d'essai de cette MR est recréée.
  glab api -X DELETE "projects/$proj/repository/branches/$ref" >/dev/null
  git -C "$root" push -q -o "ci.variable=MR_TITLE=$title" origin "HEAD:refs/heads/$essai"
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

# Entrée de la MR dans le manifest du registre : version et backend.
entry=$(glab api "projects/$proj/packages/generic/companion-updates/essais/manifest.json" 2>/dev/null |
  jq -c --argjson iid "$iid" '.essais[]? | select(.mr == $iid)' || true)

# TRANSITION (à retirer quand la stable de la tablette lit le registre des essais, version après !115) :
# la stable d'avant ne voit que l'annonce du Mac, et le Mac garde le backend de la branche comme avant.
cd "$root/backend"
[ -d node_modules ] || npm ci --silent
if [ -z "$entry" ]; then
  # Branche d'avant !115 : sa CI publie encore dans l'ancien paquet companion-updates/essai.
  DATA_DIR=paquet npm run -s updates:essai
  APK=0 "$here/deploy-tablette.sh"
  exit 0
fi
tmp=$(mktemp -d)
file=$(printf %s "$entry" | jq -r .file)
glab api "projects/$proj/packages/generic/companion-updates/essais/$file" > "$tmp/$file"
[ "$(shasum -a 256 "$tmp/$file" | cut -d' ' -f1)" = "$(printf %s "$entry" | jq -r .sha256)" ] ||
  { rm -rf "$tmp"; echo "❌ $file : SHA-256 différent du manifest"; exit 1; }
printf %s "$entry" | jq '{package,label,versionCode,versionName,file}' > "$tmp/$file.json"
DATA_DIR=paquet npm run -s updates:add -- "$tmp/$file.json"
rm -rf "$tmp"
APK=0 "$here/deploy-tablette.sh"

printf %s "$entry" | jq -r '"essai \(.versionName) de la MR !\(.mr) publié (backend : \(if .backend then "oui" else "non" end))"'
