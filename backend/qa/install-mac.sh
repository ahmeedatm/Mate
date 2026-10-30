#!/bin/bash
# Test de l'appli portable sur Mac, sur le Mac d'un développeur où tourne son vrai compagnon. À la main : plus de job CI.
# Instance de test à part : port, données, runtime, journal et dossier de l'appli propres au test, rien d'affiché à
# l'écran (COMPANION_HEADLESS). Le vrai compagnon (agent app.mate, port 3000, données) est relevé avant et
# après : la moindre différence fait échouer le test. Rien ne doit être inscrit dans LaunchAgents.
# Jamais de lecture dans ~/Desktop, ~/Documents… : macOS (TCC) y bloque le runner en attendant une autorisation.
#
#   backend/qa/install-mac.sh run dist/Mate-Mac.zip [sanity.xml]
#                                     lance l'appli, vérifie, sanity (backend/qa/sanity.ts), panneau, quitter
#   backend/qa/install-mac.sh cleanup                             retire l'instance de test après un échec
set -euo pipefail

REAL_LABEL=app.mate
REAL_PORT=3000
ID=${CI_JOB_ID:-local}
WORK=/tmp/companion-ci-$ID
PORT=$((41000 + ${ID//[!0-9]/0} % 1000))
SANITY="$(cd "$(dirname "$0")" && pwd)/sanity.ts"
APP="$WORK/install/Mate/Mate.app"
RUNTIME=$WORK/runtime
NODE="$RUNTIME/node/darwin-$([[ $(uname -m) == arm64 ]] && echo arm64 || echo x64)/node"

export PORT HOST=127.0.0.1 DATA_DIR=$WORK/data DOCS_DIR=$WORK/shared \
  COMPANION_LOG_DIR=$WORK/logs COMPANION_RUNTIME_DIR=$RUNTIME COMPANION_HEADLESS=1

START=$(date +%s)

step() { echo; echo "== $*"; }
fail() { echo "ÉCHEC : $*" >&2; exit 1; }
sha() { [[ -f $1 ]] && shasum -a 256 "$1" | cut -c1-16 || echo absent; }
listener() { lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null | head -1 || true; }
health() { curl -fsS --max-time 3 "http://127.0.0.1:$1/health" 2>/dev/null || true; }
agent() { launchctl print "gui/$UID/$1" 2>/dev/null | awk -F' = ' '/^\tstate = /{s=$2} /^\tpid = /{p=$2} END{print (s ? s " pid " p : "absent")}'; }
# Corps par défaut {} : écrit "${2:-{\}}", le \ restait entre guillemets doubles (JSON invalide, 400).
post() { local body=${2:-'{}'}; curl -fsS --max-time 5 -X POST -H 'content-type: application/json' -d "$body" "http://127.0.0.1:$PORT$1"; }

# État du vrai compagnon, comparé avant / après. Sans PID : une tablette connectée peut le relancer pendant le test
# (essai de backend d'une MR, mise à jour à distance) ; ce qui compte, c'est qu'il tourne, sur le même port et la même identité.
real_state() {
  echo "agent : $(agent $REAL_LABEL | sed 's/ pid .*//')"
  echo "plist : $(sha ~/Library/LaunchAgents/$REAL_LABEL.plist)"
  echo "LaunchAgents : $(ls ~/Library/LaunchAgents 2>/dev/null | shasum | cut -c1-16)"
  echo "port $REAL_PORT : $([[ -n $(listener $REAL_PORT) ]] && echo écoute || echo muet), $(health $REAL_PORT | grep -o '"id":"[^"]*"' || echo 'pas de réponse')"
  echo "données : $(sha ~/Library/Application\ Support/Mate/identity.json)"
}

check_real() {
  real_state > "$WORK/real-after.txt"
  diff -u "$WORK/real-before.txt" "$WORK/real-after.txt" || fail "le vrai compagnon a changé pendant le test"
  echo "Vrai compagnon intact :"; cat "$WORK/real-after.txt"
}

# Aucune autorisation macOS (TCC) demandée par le node du job : TCC suit un programme en ligne de commande par son
# chemin, propre à chaque job, et chaque demande ajoute un « node » de plus dans Réglages Système (ou une invite).
# Seules les entrées créées pendant ce test comptent (les restes d'anciens essais portent le même préfixe).
# Base illisible sans accès complet au disque : contrôle sauté.
check_tcc() {
  local db rows read=0
  for db in "/Library/Application Support/com.apple.TCC/TCC.db" ~/"Library/Application Support/com.apple.TCC/TCC.db"; do
    rows=$(sqlite3 "$db" "select service from access where (client like '$WORK/%' or client like '/private$WORK/%') and last_modified >= $START" 2>/dev/null) ||
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
  [[ $PORT != "$REAL_PORT" ]] || fail "instance de test confondue avec le vrai compagnon"
  rm -rf "$WORK"; mkdir -p "$WORK"
  [[ -z $(listener "$PORT") ]] || fail "port $PORT déjà pris"

  step "Vrai compagnon avant le test"
  real_state | tee "$WORK/real-before.txt"

  step "Lancement de l'appli ($WORK, port $PORT)"
  unzip -q "$zip" -d "$WORK/install"
  # Exécutable de l'appli, comme au double-clic : runtime recopié, serveur lancé, rend la main quand il répond.
  "$APP/Contents/MacOS/Mate" < /dev/null || { cat "$WORK/logs/backend.log" 2>/dev/null; fail "lancement"; }
  [[ -x $NODE ]] || fail "runtime absent : $NODE"
  [[ ! -e $RUNTIME/node/darwin-$([[ $(uname -m) == arm64 ]] && echo x64 || echo arm64) ]] || fail "node de l'autre architecture recopié"
  local pid; pid=$(listener "$PORT")
  ps -o command= -p "$pid" | grep -qF "$RUNTIME/" || fail "le port $PORT n'est pas servi par le runtime du test"
  local h; h=$(health "$PORT"); echo "$h"
  grep -q '"os":"mac"' <<< "$h" || fail "/health : os"
  # Accessibilité non accordée au node du test : attendu.
  grep -q '"accessibility":false' <<< "$h" || fail "/health : accessibility"
  [[ -f $DATA_DIR/identity.json ]] || fail "identité absente de $DATA_DIR"

  step "Rien d'inscrit, panneau"
  local panel; panel=$(curl -fsS "http://127.0.0.1:$PORT/local/panel"); echo "$panel"
  grep -q '"portable":true' <<< "$panel" || fail "/local/panel : portable"
  grep -q '"autostart":false' <<< "$panel" || fail "/local/panel : lancement au démarrage inscrit sans demande"
  [[ -z $(ls ~/Library/LaunchAgents 2>/dev/null | grep -F "$ID" || true) ]] || fail "agent inscrit dans LaunchAgents"

  step "Appli relancée : rouvre seulement la page"
  "$APP/Contents/MacOS/Mate" < /dev/null || fail "relance de l'appli"
  [[ $(listener "$PORT") == "$pid" ]] || fail "second serveur lancé"

  step "Relance par la boucle après un arrêt (mise à jour à distance)"
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

  step "Quitter depuis le panneau"
  post /local/quit > /dev/null || fail "/local/quit"
  wait_for 10 bash -c "[[ -z \$(lsof -nP -iTCP:$PORT -sTCP:LISTEN -t) ]]" || fail "port $PORT toujours ouvert"
  sleep 12
  [[ -z $(listener "$PORT") ]] || fail "relancé après « Quitter »"
  pgrep -f "$WORK/" > /dev/null && fail "processus restants du test"

  step "Vrai compagnon après le test"
  check_real
  ((sanity == 0)) || fail "sanity en échec (voir plus haut)"

  step "Autorisations macOS"
  check_tcc
  echo; echo "OK : lancement, relance, sanity, panneau et sortie"
}

# Après un échec ou une annulation : ne touche qu'à l'instance de test (boucle du lanceur et serveur lancés depuis WORK).
cleanup() {
  pkill -f "$WORK/" 2>/dev/null || true
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
