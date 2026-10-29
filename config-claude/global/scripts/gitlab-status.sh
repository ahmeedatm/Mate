#!/usr/bin/env bash
# Vérification pour wait-until.sh : statut d'un pipeline ou d'un job GitLab.
#
#   gitlab-status.sh pipeline|job <id> [projet, défaut efreifer/unowhy-companion]
#
# Affiche le statut. Sortie 0 = statut final, 1 = en cours, 2 = erreur (API, auth, réponse illisible).
# Exemple : wait-until.sh --timeout 3600 -- gitlab-status.sh pipeline 374943

set -u
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

kind=${1:?pipeline ou job} id=${2:?id} project=${3:-efreifer/unowhy-companion}
case $kind in pipeline) path=pipelines ;; job) path=jobs ;; *) echo "type inconnu: $kind" >&2; exit 2 ;; esac

json=$(glab api --hostname gitlab.unowhy.dev "projects/${project//\//%2F}/$path/$id" 2>&1) || { echo "glab: $json" >&2; exit 2; }
status=$(printf '%s' "$json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])' 2>/dev/null) \
  || { echo "réponse illisible: ${json:0:200}" >&2; exit 2; }

echo "$kind $id : $status"
case $status in
  success|failed|canceled|skipped|manual) exit 0 ;;
  *) exit 1 ;;
esac
