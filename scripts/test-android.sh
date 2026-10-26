#!/bin/bash
# Tests instrumentés (app/src/androidTest/) sur un émulateur calqué sur la tablette : Android 10 (API 29) sans
# services Google, 1280×800 à 160 dpi, 2 Go, 4 cœurs. Démarre l'émulateur, compile, installe, lance les tests, l'arrête.
#
# Usage : scripts/test-android.sh [--window]   (--window : voir l'émulateur pendant les tests)
#
# Environnement jetable : chaque lancement a ses ports libres (émulateur, backend), son AVD dans un dossier temporaire
# supprimé à la fin, et son backend aux données temporaires. Plusieurs lancements (worktrees) tournent en parallèle ;
# companion_api29 (dev) n'est pas touché.
# - Mac Apple Silicon : émulateur et image arm64 téléchargés au premier lancement (~800 Mo) dans le SDK.
#   Linux (CI) : image x86_64 par sdkmanager, /dev/kvm obligatoire.
# - Avec un JDK : ./gradlew connectedDebugAndroidTest (rapport JUnit). Sans : APK compilés dans l'image Docker
#   de la CI, tests lancés par am instrument, avec un cache Gradle propre au worktree (volume Docker, amorcé depuis
#   companion-gradle s'il existe).
# - Backend réel pour les tests du Deck (backend/test/deck-emulateur.ts, joint par 10.0.2.2, ports passés aux tests) :
#   pilotage simulé sans contrôle (toucher une touche répond 501), rien n'agit sur la machine.
#   node 22 du système, sinon (image Android de la CI) celui de nodejs.org (version de backend/scripts/package.ts).
# Résultats, journal du backend et capture de l'écran de bienvenue dans app/build/test-android/.
set -euo pipefail
cd "$(dirname "$0")/.."

SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
export ANDROID_HOME=$SDK ANDROID_SDK_ROOT=$SDK
AVD=companion_test_api29
OUT=app/build/test-android
CI_IMAGE=ghcr.io/cirruslabs/android-sdk:36
# Cache Gradle du build Docker : un par worktree (deux builds sur un même volume se bloquent sur ses verrous).
GRADLE_VOLUME=companion-gradle-$(printf %s "$PWD" | shasum | cut -c1-8)
BACKEND_PID=
EMULATOR_PID=
PORT= SERIAL= ADB= DECK_PORT= DECK_CONTROL_PORT=
WINDOW=-no-window
[ "${1:-}" = --window ] && WINDOW=

case $(uname -m) in arm64|aarch64) ABI=arm64-v8a ;; *) ABI=x86_64 ;; esac
IMAGE="system-images;android-29;default;$ABI"
SYSDIR="$SDK/system-images/android-29/default/$ABI"

# Téléchargement vérifié (sha1 publié dans le dépôt Google) puis extraction dans le SDK.
fetch() { # url sha1 dossier-parent
  local zip; zip=$(mktemp -t android).zip
  echo "Téléchargement de $1"
  curl -fL --progress-bar -o "$zip" "https://dl.google.com/android/repository/$1"
  [ "$(shasum -a 1 "$zip" | cut -d' ' -f1)" = "$2" ] || { echo "Empreinte invalide pour $1"; exit 1; }
  mkdir -p "$3" && unzip -q -o "$zip" -d "$3" && rm "$zip"
}

# Sur Mac, /usr/bin/java (et le sdkmanager de Homebrew) existent même sans JDK : on teste qu'il répond.
has_jdk() { java -version >/dev/null 2>&1; }

install_sdk() {
  if has_jdk && command -v sdkmanager >/dev/null; then
    sdkmanager --install emulator "$IMAGE" < /dev/null > /dev/null
    return
  fi
  [ "$(uname -s)/$ABI" = Darwin/arm64-v8a ] || { echo "Sans sdkmanager, seul un Mac Apple Silicon est géré."; exit 1; }
  [ -x "$SDK/emulator/emulator" ] ||
    fetch emulator-darwin_aarch64-16428233.zip 3af4fe44ce82b3d88ae5678a53735f27ad729c15 "$SDK"
  [ -f "$SYSDIR/system.img" ] || {
    fetch sys-img/android/arm64-v8a-29_r08.zip fa0d67d7430fcc84b2fe2508ea81e92ac644e264 "$SDK/system-images/android-29/default"
  }
}

# Ports libres : console de l'émulateur (paire paire/impaire entre 5554 et 5680, tirée au hasard), backend et son pilotage.
pick_ports() {
  read -r PORT DECK_PORT DECK_CONTROL_PORT < <(node -e '
    const net = require("node:net");
    const free = (p) => new Promise((ok) => {
      const s = net.createServer().once("error", () => ok(0)).listen(p, "127.0.0.1", () => { const a = s.address().port; s.close(() => ok(a)); });
    });
    (async () => {
      const pairs = [];
      for (let p = 5554; p <= 5680; p += 2) pairs.push(p);
      pairs.sort(() => Math.random() - 0.5);
      let emu = 0;
      for (const p of pairs) if ((await free(p)) && (await free(p + 1))) { emu = p; break; }
      if (!emu) process.exit(1);
      console.log(emu, await free(0), await free(0));
    })();')
  [ -n "$PORT" ] || { echo "Aucun port libre pour l'émulateur entre 5554 et 5681"; exit 1; }
  SERIAL=emulator-$PORT
  ADB="$SDK/platform-tools/adb -s $SERIAL"
  export DECK_PORT DECK_CONTROL_PORT
}

# AVD dans un dossier temporaire, supprimé à la fin.
create_avd() {
  export ANDROID_AVD_HOME
  ANDROID_AVD_HOME=$(mktemp -d "${TMPDIR:-/tmp}/companion-avd.XXXXXX")
  local home=$ANDROID_AVD_HOME
  mkdir -p "$home/$AVD.avd"
  printf 'avd.ini.encoding=UTF-8\npath=%s\ntarget=android-29\n' "$home/$AVD.avd" > "$home/$AVD.ini"
  cat > "$home/$AVD.avd/config.ini" <<EOF
abi.type=$ABI
hw.cpu.arch=$([ $ABI = x86_64 ] && echo x86_64 || echo arm64)
image.sysdir.1=system-images/android-29/default/$ABI/
tag.id=default
hw.lcd.width=1280
hw.lcd.height=800
hw.lcd.density=160
hw.ramSize=2048
hw.cpu.ncore=4
hw.keyboard=yes
disk.dataPartition.size=2G
EOF
}

# Sans node 22 (image Android de la CI, Linux) : node officiel, vérifié par sa somme SHA-256.
ensure_node() {
  node -e 'process.exit(Number(+process.versions.node.split(".")[0] < 22))' 2>/dev/null && return
  [ "$(uname -s)" = Linux ] || { echo "node 22 ou plus requis pour le backend de test"; exit 1; }
  local version arch name dir=$HOME/.cache/companion-node tmp
  version=$(sed -n 's/^const NODE_VERSION = "\(.*\)";/\1/p' backend/scripts/package.ts)
  case $(uname -m) in aarch64) arch=arm64 ;; *) arch=x64 ;; esac
  name=node-$version-linux-$arch.tar.xz
  if [ ! -x "$dir/$version/bin/node" ]; then
    tmp=$(mktemp -d)
    curl -fsSL -o "$tmp/$name" "https://nodejs.org/dist/$version/$name"
    curl -fsSL "https://nodejs.org/dist/$version/SHASUMS256.txt" | grep " $name\$" | (cd "$tmp" && sha256sum -c --quiet -)
    mkdir -p "$dir/$version" && tar -xJf "$tmp/$name" -C "$dir/$version" --strip-components=1 && rm -rf "$tmp"
  fi
  export PATH="$dir/$version/bin:$PATH"
}

start_backend() {
  (cd backend && npm ci --prefer-offline --no-audit --no-fund > /dev/null && exec node --import tsx test/deck-emulateur.ts) \
    > "$OUT/backend.log" 2>&1 &
  BACKEND_PID=$!
}

wait_backend() {
  local start=$SECONDS
  until curl -sf "http://127.0.0.1:$DECK_CONTROL_PORT/health" > /dev/null; do
    [ $((SECONDS - start)) -lt 120 ] || { echo "Le backend de test n'a pas démarré en 2 min (voir $OUT/backend.log)"; exit 1; }
    sleep 1
  done
  echo "Backend de test prêt"
}

cleanup() {
  [ -z "$BACKEND_PID" ] || kill "$BACKEND_PID" 2> /dev/null || true
  if [ -n "$EMULATOR_PID" ]; then
    $ADB emu kill > /dev/null 2>&1 || kill "$EMULATOR_PID" 2> /dev/null || true
    for _ in $(seq 1 20); do kill -0 "$EMULATOR_PID" 2> /dev/null || break; sleep 0.5; done
  fi
  [ -z "${ANDROID_AVD_HOME:-}" ] || rm -rf "$ANDROID_AVD_HOME"
}

# Premier build Docker du worktree : volume amorcé depuis le cache partagé (dépendances et Gradle déjà téléchargés).
gradle_volume() {
  docker volume inspect "$GRADLE_VOLUME" > /dev/null 2>&1 && return
  docker volume create "$GRADLE_VOLUME" > /dev/null
  docker volume inspect companion-gradle > /dev/null 2>&1 &&
    docker run --rm -v companion-gradle:/from:ro -v "$GRADLE_VOLUME":/to $CI_IMAGE cp -a /from/. /to/ || true
}

boot() {
  if [ "$(uname -s)" = Linux ] && [ ! -w /dev/kvm ]; then
    echo "/dev/kvm absent ou inaccessible : l'émulateur x86_64 ne démarre pas sans KVM (runner non privilégié ?)"; exit 1
  fi
  "$SDK/emulator/emulator" -avd $AVD -port $PORT $WINDOW -no-audio -no-boot-anim -no-snapshot -wipe-data \
    -gpu swiftshader_indirect > "$OUT/emulator.log" 2>&1 &
  EMULATOR_PID=$!
  local start=$SECONDS
  $ADB wait-for-device
  until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
    [ $((SECONDS - start)) -lt 300 ] || { echo "L'émulateur n'a pas démarré en 5 min (voir $OUT/emulator.log)"; exit 1; }
    sleep 2
  done
  echo "Émulateur prêt en $((SECONDS - start)) s"
  for a in window_animation_scale transition_animation_scale animator_duration_scale; do
    $ADB shell settings put global $a 0
  done
}

mkdir -p "$OUT"
trap cleanup EXIT
ensure_node
pick_ports
echo "Émulateur $SERIAL, backend sur les ports $DECK_PORT (pilotage $DECK_CONTROL_PORT)"
install_sdk
create_avd
# Backend lancé pendant le démarrage de l'émulateur.
start_backend
boot
wait_backend

status=0
if has_jdk; then
  ANDROID_SERIAL=$SERIAL ./gradlew --console=plain connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.backendPort="$DECK_PORT" \
    -Pandroid.testInstrumentationRunnerArguments.controlPort="$DECK_CONTROL_PORT" || status=$?
else
  gradle_volume
  docker run --rm --platform linux/amd64 -v "$PWD":/w -v $GRADLE_VOLUME:/gradle -e GRADLE_USER_HOME=/gradle -w /w \
    $CI_IMAGE ./gradlew --no-daemon --no-watch-fs --console=plain assembleDebug assembleDebugAndroidTest
  $ADB install -r -t app/build/outputs/apk/debug/app-debug.apk > /dev/null
  $ADB install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk > /dev/null
  $ADB shell am instrument -w -r --no-window-animation -e backendPort "$DECK_PORT" -e controlPort "$DECK_CONTROL_PORT" \
    com.unowhy.companion.test/androidx.test.runner.AndroidJUnitRunner | tr -d '\r' > "$OUT/instrument.txt"
  grep -E '^(OK|FAILURES|Tests run)' "$OUT/instrument.txt" || true
  grep -q '^OK (' "$OUT/instrument.txt" || { status=1; grep -A15 'INSTRUMENTATION_STATUS: stack=' "$OUT/instrument.txt" | head -40; }
fi
# Lancement à froid, données effacées : temps de démarrage et capture de l'écran de bienvenue. Ne change pas le verdict.
# L'APK est réinstallé : connectedDebugAndroidTest le désinstalle à la fin.
{
  $ADB install -r -t app/build/outputs/apk/debug/app-debug.apk > /dev/null &&
  $ADB shell pm clear com.unowhy.companion > /dev/null &&
  $ADB shell am start -W -n com.unowhy.companion/.MainActivity | grep TotalTime &&
  sleep 3 && $ADB exec-out screencap -p > "$OUT/bienvenue.png"
} || echo "Capture de l'écran de bienvenue impossible"
[ $status = 0 ] && echo "Tests instrumentés : OK" || echo "Tests instrumentés : ÉCHEC (détails dans $OUT/)"
exit $status
