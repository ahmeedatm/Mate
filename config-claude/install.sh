#!/bin/sh
# Installe la config Claude Code du projet : projet/ → <dépôt>/.claude, global/ → ~/.claude.
# N'écrase rien d'existant (fichier gardé, signalé) ; --force écrase.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
force=0
[ "${1:-}" = --force ] && force=1

# Checkout principal, même lancé depuis un worktree.
common=$(git -C "$here" rev-parse --path-format=absolute --git-common-dir)
repo=$(dirname "$common")
claude_home=${CLAUDE_CONFIG_DIR:-$HOME/.claude}

# copy <dossier source> <dossier cible>
copy() {
  (cd "$1" && find . -type f ! -name .DS_Store) | while read -r f; do
    dest="$2/${f#./}"
    if [ -e "$dest" ] && [ "$force" = 0 ]; then
      echo "gardé   $dest (existe déjà, --force pour écraser)"
      continue
    fi
    mkdir -p "$(dirname "$dest")"
    sed "s#@REPO@#$repo#g" "$1/$f" >"$dest"
    case $dest in *.sh) chmod +x "$dest" ;; esac
    echo "installé $dest"
  done
}

copy "$here/projet" "$repo/.claude"
# Les scripts sont appelés par ~/.claude/scripts/ dans /ship, quel que soit CLAUDE_CONFIG_DIR.
copy "$here/global/scripts" "$HOME/.claude/scripts"
copy "$here/global/agents" "$claude_home/agents"
copy "$here/global/output-styles" "$claude_home/output-styles"

missing=""
for cmd in glab jq orca node; do
  command -v "$cmd" >/dev/null 2>&1 || missing="$missing $cmd"
done
[ -x "$HOME/Library/Android/sdk/platform-tools/adb" ] || missing="$missing adb(~/Library/Android/sdk)"
[ -z "$missing" ] || echo "⚠️ absent du PATH :$missing (voir README, Prérequis)"
glab auth status --hostname gitlab.unowhy.dev >/dev/null 2>&1 || echo "⚠️ glab non connecté à gitlab.unowhy.dev : glab auth login --hostname gitlab.unowhy.dev"
echo "fini : relancer Claude Code dans $repo"
