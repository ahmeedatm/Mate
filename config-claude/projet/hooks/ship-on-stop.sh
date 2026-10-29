#!/bin/sh
# Hook Stop : du travail non livré (modifs non commitées ou commits non poussés) relance Claude sur /ship.
# Une seule relance par tour (stop_hook_active), rien pendant un merge ou un rebase.

input=$(cat)
[ "$(printf '%s' "$input" | jq -r '.stop_hook_active // false')" = true ] && exit 0

cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null || exit 0
gd=$(git rev-parse --git-dir 2>/dev/null) || exit 0
if [ -e "$gd/MERGE_HEAD" ] || [ -d "$gd/rebase-merge" ] || [ -d "$gd/rebase-apply" ]; then exit 0; fi

pending=""
[ -n "$(git status --porcelain)" ] && pending="modifications non commitées"
if git rev-parse --abbrev-ref '@{u}' >/dev/null 2>&1; then
  [ "$(git rev-list --count '@{u}..HEAD')" -gt 0 ] && pending="${pending:+$pending, }commits non poussés"
elif [ "$(git branch --show-current)" != main ] && [ "$(git rev-list --count origin/main..HEAD 2>/dev/null || echo 0)" -gt 0 ]; then
  pending="${pending:+$pending, }branche jamais poussée"
fi
[ -z "$pending" ] && exit 0

jq -n --arg p "$pending" '{decision: "block", reason: ("Travail non livré (" + $p + "). Si l’étape est terminée et vérifiée, lance /ship maintenant. Sinon, termine en disant en une ligne ce qui reste à faire.")}'
