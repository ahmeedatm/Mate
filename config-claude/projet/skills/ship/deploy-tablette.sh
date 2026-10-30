#!/bin/sh
# Déploie la branche courante pour le test de l'utilisateur avant fusion :
# APK debug sur la tablette (adb, émulateurs ignorés), et compagnon du Mac si backend/ a changé.
# BACKEND=1 redéploie le Mac même sans changement (retour à main). APK=0 : Mac seul (essai.sh). ANDROID_SERIAL choisit la tablette s'il y en a plusieurs. Sortie 2 = rien à quoi déployer.
set -eu

root=$(git rev-parse --show-toplevel)
adb="$HOME/Library/Android/sdk/platform-tools/adb"
pkg_app=app.mate

if [ "${APK:-1}" != 0 ]; then
  serial=${ANDROID_SERIAL:-$("$adb" devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1; exit}')}
  if [ -z "$serial" ]; then
    echo "❌ aucune tablette en adb : la brancher en USB (ou adb connect <ip>:5555)"
    exit 2
  fi

  echo "APK debug de $(git -C "$root" branch --show-current)"
  (cd "$root" && GRADLE_USER_HOME=/tmp/gh-companion ./gradlew -q assembleDebug)
  "$adb" -s "$serial" install -r -d "$root/app/build/outputs/apk/debug/app-debug.apk"
  "$adb" -s "$serial" shell monkey -p "$pkg_app" -c android.intent.category.LAUNCHER 1 >/dev/null
  echo "✅ tablette $serial : appli installée et relancée"
fi

if [ "${BACKEND:-}" != 1 ] && [ -z "$(git -C "$root" diff --name-only origin/main...HEAD -- backend/)" ]; then
  echo "backend inchangé : compagnon du Mac laissé tel quel"
  exit 0
fi

# Dossier backend installé, lu dans le LaunchAgent (WorkingDirectory) : celui de l'appli
# (Mate.app/Contents/Resources/backend) ou d'un ancien paquet zip (<paquet>/backend).
plist="$HOME/Library/LaunchAgents/app.mate.plist"
backend_dir=$(plutil -extract WorkingDirectory raw "$plist")
(cd "$root/backend" && npm run -s package -- mac)
rsync -rt --exclude node --exclude .DS_Store \
  "$root/dist/mac/Mate/Mate.app/Contents/Resources/backend/" "$backend_dir/"
launchctl kickstart -k "gui/$(id -u)/app.mate"
for _ in $(seq 30); do
  if curl -sf http://127.0.0.1:3000/health >/dev/null; then
    echo "✅ compagnon du Mac relancé sur la branche ($backend_dir)"
    exit 0
  fi
  sleep 1
done
echo "❌ le compagnon du Mac ne répond pas : ~/Library/Logs/Mate/backend.log"
exit 1
