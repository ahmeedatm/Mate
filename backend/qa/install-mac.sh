#!/bin/bash
# Test de l'installation sur Mac (job install-mac), sur le Mac d'un développeur où tourne son vrai compagnon.
# Instance de test à part : agent launchd, port, données, journal et dossier d'installation propres au job,
# rien d'affiché à l'écran (COMPANION_HEADLESS). Le vrai compagnon (agent com.unowhy.companion, port 3000,
# données) est relevé avant et après : la moindre différence fait échouer le job.
# Jamais de lecture dans ~/Desktop, ~/Documents… : macOS (TCC) y bloque le runner en attendant une autorisation.
#
#   backend/qa/install-mac.sh run dist/Compagnon-Unowhy-Mac.zip [sanity.xml]
#                                     installe le paquet, vérifie, sanity (backend/qa/sanity.ts), désinstalle
#   backend/qa/install-mac.sh cleanup                             retire l'instance de test (after_script)
set -euo pipefail

REAL_LABEL=com.unowhy.companion
REAL_PORT=3000
ID=${CI_JOB_ID:-local}
WORK=/tmp/companion-ci-$ID
LABEL=$REAL_LABEL.ci-$ID
PORT=$((41000 + ${ID//[!0-9]/0} % 1000))
PLIST=~/Library/LaunchAgents/$LABEL.plist
SANITY="$(cd "$(dirname "$0")" && pwd)/sanity.ts"
APP="$WORK/install/Compagnon Unowhy/Compagnon Unowhy.app"
BACKEND="$APP/Contents/Resources/backend"
NODE="$BACKEND/node/darwin-$([[ $(uname -m) == arm64 ]] && echo arm64 || echo x64)/node"

export COMPANION_LABEL=$LABEL PORT HOST=127.0.0.1 DATA_DIR=$WORK/data DOCS_DIR=$WORK/shared \
  COMPANION_LOG_DIR=$WORK/logs COMPANION_HEADLESS=1

step() { echo; echo "== $*"; }
fail() { echo "ÉCHEC : $*" >&2; exit 1; }
sha() { [[ -f $1 ]] && shasum -a 256 "$1" | cut -c1-16 || echo absent; }
listener() { lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null | head -1 || true; }
health() { curl -fsS --max-time 3 "http://127.0.0.1:$1/health" 2>/dev/null || true; }
agent() { launchctl print "gui/$UID/$1" 2>/dev/null | awk -F' = ' '/^\tstate = /{s=$2} /^\tpid = /{p=$2} END{print (s ? s " pid " p : "absent")}'; }

# État du vrai compagnon, comparé avant / après.
real_state() {
  echo "agent : $(agent $REAL_LABEL)"
  echo "plist : $(sha ~/Library/LaunchAgents/$REAL_LABEL.plist)"
  echo "port $REAL_PORT : pid $(listener $REAL_PORT), $(health $REAL_PORT | grep -o '"id":"[^"]*"' || echo 'pas de réponse')"
  echo "données : $(sha ~/Library/Application\ Support/UnowhyCompanion/identity.json)"
}

check_real() {
  real_state > "$WORK/real-after.txt"
  diff -u "$WORK/real-before.txt" "$WORK/real-after.txt" || fail "le vrai compagnon a changé pendant le test"
  echo "Vrai compagnon intact :"; cat "$WORK/real-after.txt"
}

# Aucune autorisation macOS (TCC) demandée par le node du job : TCC suit un programme en ligne de commande par son
# chemin, propre à chaque job, et chaque demande ajoute un « node » de plus dans Réglages Système (ou une invite).
# Base illisible sans accès complet au disque : contrôle sauté.
check_tcc() {
  local db rows read=0
  for db in "/Library/Application Support/com.apple.TCC/TCC.db" ~/"Library/Application Support/com.apple.TCC/TCC.db"; do
    rows=$(sqlite3 "$db" "select service from access where client like '$WORK/%' or client like '/private$WORK/%'" 2>/dev/null) ||
      { echo "Base TCC illisible ($db) : contrôle sauté"; continue; }
    [[ -z $rows ]] || fail "autorisations macOS demandées par l'instance de test : $(tr '\n' ' ' <<< "$rows")"
    read=$((read + 1))
  done
  ((read == 0)) || echo "Aucune autorisation macOS demandée"
}

wait_for() { # secondes, commande
  local n=$(($1 * 2)); shift
  while ((n-- > 0)); do "$@" && return 0; sleep 0.5; done
  return 1
}

run() {
  local zip=${1:?chemin du zip Mac} junit=${2:-sanity-mac.xml}
  [[ $LABEL != "$REAL_LABEL" && $PORT != "$REAL_PORT" ]] || fail "instance de test confondue avec le vrai compagnon"
  rm -rf "$WORK"; mkdir -p "$WORK"
  [[ -z $(listener "$PORT") ]] || fail "port $PORT déjà pris"

  step "Vrai compagnon avant le test"
  real_state | tee "$WORK/real-before.txt"

  step "Installation du paquet ($LABEL, port $PORT, $WORK)"
  unzip -q "$zip" -d "$WORK/install"
  # Exécutable de l'appli, comme au double-clic ; COMPANION_HEADLESS : sur place, sans copie dans Applications.
  "$APP/Contents/MacOS/Compagnon Unowhy" < /dev/null || { cat "$WORK/logs/backend.log" 2>/dev/null; fail "installeur"; }

  step "Agent launchd et serveur"
  grep -q "<string>$DATA_DIR</string>" "$PLIST" || fail "DATA_DIR absent de $PLIST"
  [[ $(agent "$LABEL") == running* ]] || fail "agent $LABEL : $(agent "$LABEL")"
  local pid; pid=$(listener "$PORT")
  ps -o command= -p "$pid" | grep -qF "$BACKEND/" || fail "le port $PORT n'est pas servi par le paquet de test"
  local h; h=$(health "$PORT"); echo "$h"
  grep -q '"os":"mac"' <<< "$h" || fail "/health : os"
  # Accessibilité non accordée au node du job : attendu.
  grep -q '"accessibility":false' <<< "$h" || fail "/health : accessibility"
  [[ -f $DATA_DIR/identity.json ]] || fail "identité absente de $DATA_DIR"

  step "Relance par launchd après un arrêt (mise à jour à distance)"
  kill "$pid"
  wait_for 30 bash -c "p=\$(lsof -nP -iTCP:$PORT -sTCP:LISTEN -t | head -1); [[ -n \$p && \$p != $pid ]]" || fail "pas relancé"
  wait_for 20 bash -c "curl -fsS --max-time 2 http://127.0.0.1:$PORT/health > /dev/null" || fail "ne répond plus après relance"
  echo "Relancé : pid $(listener "$PORT")"

  step "Sanity"
  local token secret sanity=0
  token=$("$NODE" -p "JSON.parse(require('fs').readFileSync(process.argv[1], 'utf8')).token" "$DATA_DIR/identity.json")
  # Secret des hooks des agents Claude (créé au démarrage) : la sanity envoie de faux événements.
  secret=$("$NODE" -p "JSON.parse(require('fs').readFileSync(process.argv[1], 'utf8')).secret" "$DATA_DIR/agents-hook.json")
  "$NODE" "$SANITY" --url "http://127.0.0.1:$PORT" --token "$token" --os mac --hook-secret "$secret" --junit "$junit" || sanity=$?

  step "Désinstallation"
  (cd "$BACKEND" && "$NODE" dist/service.mjs uninstall)
  [[ ! -e $PLIST ]] || fail "$PLIST toujours là"
  [[ $(agent "$LABEL") == absent ]] || fail "agent $LABEL toujours chargé"
  wait_for 10 bash -c "[[ -z \$(lsof -nP -iTCP:$PORT -sTCP:LISTEN -t) ]]" || fail "port $PORT toujours ouvert"

  step "Vrai compagnon après le test"
  check_real
  ((sanity == 0)) || fail "sanity en échec (voir plus haut)"

  step "Autorisations macOS"
  check_tcc
  echo; echo "OK : installation, démarrage, relance, sanity et désinstallation"
}

# Toujours lancé (after_script), même après un échec ou une annulation : ne touche qu'à l'instance de test.
cleanup() {
  launchctl bootout "gui/$UID/$LABEL" 2>/dev/null || true
  rm -f "$PLIST"
  local pid; pid=$(listener "$PORT")
  if [[ -n $pid ]] && ps -o command= -p "$pid" | grep -qF "$WORK/"; then kill "$pid" || true; fi
  [[ -f $WORK/real-before.txt ]] && { (check_real) || true; }
  # Journal du serveur de test, en artefact du job.
  [[ -n ${CI_PROJECT_DIR:-} && -f $WORK/logs/backend.log ]] && cp "$WORK/logs/backend.log" "$CI_PROJECT_DIR/install-mac-backend.log"
  rm -rf "$WORK"
}

case ${1:-} in
  run) shift; run "$@" ;;
  cleanup) cleanup ;;
  *) echo "Usage : $0 run <zip> | cleanup" >&2; exit 2 ;;
esac
