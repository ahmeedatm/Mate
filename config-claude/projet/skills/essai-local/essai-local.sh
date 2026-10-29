#!/bin/sh
# Essai d'une MR sur la tablette, tout en local : APK d'essai (com.unowhy.unomate.essai) construit sur ce Mac, signé
# par la clé de release (trousseau macOS), posé par adb à côté de la stable, puis compagnon du Mac passé sur le backend.
# Sans argument : le worktree courant tel quel (modifs non commitées comprises). Avec <iid> : la tête de la MR, dans le
# worktree détaché essai-mr. ANDROID_SERIAL choisit la tablette s'il y en a plusieurs.
# Sorties : 0 essai posé, 1 échec, 2 tablette absente ou installations bloquées.
set -eu
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

here=$(cd "$(dirname "$0")" && pwd)
main=$(dirname "$(git -C "$here" rev-parse --path-format=absolute --git-common-dir)")
proj=efreifer%2Funowhy-companion
adb="$HOME/Library/Android/sdk/platform-tools/adb"
tools=$(ls -d "$HOME/Library/Android/sdk/build-tools/"* | tail -1)
stable=com.unowhy.unomate
essai=com.unowhy.unomate.essai
kc_store=unowhy-companion-release-keystore
kc_pass=unowhy-companion-release-password

# 1. Tablette (émulateurs ignorés), installations permises.
serial=${ANDROID_SERIAL:-$("$adb" devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1; exit}')}
[ -n "$serial" ] || { echo "❌ aucune tablette en adb : la brancher en USB (ou adb connect <ip>:5555)"; exit 2; }
if "$adb" -s "$serial" shell dumpsys user | sed -n '/Effective restrictions/,/Guest/p' | grep -q no_install_apps; then
  echo "❌ tablette $serial : installations bloquées. Dans la stable : Réglages > appui long sur le titre > code technicien > Autoriser les installations"
  exit 2
fi

# 2. Sources : worktree courant, ou tête de la MR dans le worktree détaché essai-mr.
if [ $# -gt 0 ]; then
  iid=$1
  root="$HOME/orca/workspaces/unowhy-companion/essai-mr"
  git -C "$main" fetch -q --tags origin "+refs/merge-requests/$iid/head:refs/remotes/origin/mr/$iid"
  [ -d "$root" ] || git -C "$main" worktree add -q --detach "$root" "origin/mr/$iid"
  git -C "$root" checkout -q -f --detach "origin/mr/$iid"
  git -C "$root" clean -q -fd
  [ -f "$root/local.properties" ] || cp "$main/local.properties" "$root/"
  label="MR !$iid ($(glab api "projects/$proj/merge_requests/$iid" | jq -r .source_branch), $(git -C "$root" rev-parse --short HEAD))"
else
  root=$(git rev-parse --show-toplevel)
  git -C "$root" fetch -q --tags origin
  [ -f "$root/local.properties" ] || cp "$main/local.properties" "$root/"
  label="$(git -C "$root" branch --show-current) ($(git -C "$root" rev-parse --short HEAD), worktree tel quel)"
fi
echo "essai local de $label"

# 3. Version : dernier tag de la branche, N au-dessus de l'essai installé (Android refuse une version plus basse).
tag=$(git -C "$root" describe --tags --abbrev=0 --match "v[0-9]*.[0-9]*.[0-9]*")
base=$(echo "${tag#v}" | awk -F. '{print ($1 * 10000 + $2 * 100 + $3) * 1000}')
installed=$("$adb" -s "$serial" shell dumpsys package "$essai" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
installed=${installed:-0}
n=$((installed > base ? installed - base : 0))
if [ "$n" -ge 999 ]; then
  echo "❌ essai installé ($installed) au-dessus de ce que $tag permet : « Revenir à la stable », puis adb uninstall $essai"
  exit 1
fi
version=${tag#v}-essai.$n

# 4. Clé de release : copiée une fois des variables CI vers le trousseau, posée sur disque le temps du build seulement.
if ! security find-generic-password -s "$kc_store" >/dev/null 2>&1; then
  echo "clé de release copiée de la CI vers le trousseau"
  store=$(glab api "projects/$proj/variables/RELEASE_KEYSTORE_BASE64" 2>/dev/null | jq -er .value) ||
    { echo "❌ clé de release illisible dans les variables CI (rôle Maintainer requis)"; exit 1; }
  pass=$(glab api "projects/$proj/variables/RELEASE_KEYSTORE_PASSWORD" 2>/dev/null | jq -er .value) ||
    { echo "❌ mot de passe de la clé illisible dans les variables CI (rôle Maintainer requis)"; exit 1; }
  security add-generic-password -U -a companion -s "$kc_store" -w "$store"
  security add-generic-password -U -a companion -s "$kc_pass" -w "$pass"
  unset store pass
fi
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
security find-generic-password -s "$kc_store" -w | base64 -d > "$tmp/release.p12"
RELEASE_KEYSTORE_PASSWORD=$(security find-generic-password -s "$kc_pass" -w)
export RELEASE_KEYSTORE_PASSWORD

echo "APK $version (assembleRelease, 2 à 5 min)"
(cd "$root" && RELEASE_KEYSTORE="$tmp/release.p12" GRADLE_USER_HOME=/tmp/gh-companion \
  ./gradlew -q assembleRelease -PappVersion="$version" >"$tmp/gradle.log" 2>&1) || { tail -30 "$tmp/gradle.log"; exit 1; }
rm -f "$tmp/release.p12"
apk="$root/app/build/outputs/apk/release/app-release.apk"

# 5. Garde-fous : paquet de l'essai (jamais celui de la stable), même clé que la stable de la tablette.
pkg=$("$tools/aapt2" dump badging "$apk" | sed -n "1s/^package: name='\([^']*\)'.*/\1/p")
[ "$pkg" = "$essai" ] || { echo "❌ paquet « $pkg », attendu $essai : rien installé"; exit 1; }
cert() { "$tools/apksigner" verify --print-certs "$1" | sed -n 's/.*SHA-256 digest: //p' | head -1; }
"$adb" -s "$serial" pull "$("$adb" -s "$serial" shell pm path "$stable" | sed 's/^package://' | tr -d '\r')" "$tmp/stable.apk" >/dev/null 2>&1
[ "$(cert "$apk")" = "$(cert "$tmp/stable.apk")" ] || { echo "❌ clé de l'APK différente de celle de la stable : rien installé"; exit 1; }

"$adb" -s "$serial" install -r "$apk" >/dev/null
"$adb" -s "$serial" shell monkey -p "$essai" -c android.intent.category.LAUNCHER 1 >/dev/null
echo "✅ tablette $serial : UnoMate essai $version installé et ouvert"

# 6. Compagnon du Mac sur le backend des mêmes sources.
cd "$root/backend"
[ -d node_modules ] && [ node_modules/.package-lock.json -nt package-lock.json ] || npm ci --silent
cd "$root"
BACKEND=1 APK=0 "$here/../ship/deploy-tablette.sh"
echo "$essai $version ($label) posé"
