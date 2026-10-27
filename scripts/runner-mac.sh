#!/bin/bash
# Runner GitLab Mac (job install-mac) isolé dans un compte macOS dédié et standard : les jobs lancés
# par les autres membres du projet ne voient ni le dossier personnel du développeur, ni ses clés, ni ses jetons.
# Même runner (config.toml copié tel quel : même id, même jeton), lancé en LaunchAgent dans la session
# graphique du compte (seul mode pris en charge par GitLab sur macOS, et install-mac.sh a besoin de gui/$UID).
#
#   sudo scripts/runner-mac.sh installer               crée le compte, copie la config, pose l'agent, vérifie
#   sudo scripts/runner-mac.sh verifier                refait les vérifications
#   sudo scripts/runner-mac.sh retour [--supprimer]    arrête l'agent du compte, retire l'ACL (et le compte)
#
# Puis, sans sudo : brew services stop gitlab-runner (installer) ou brew services start gitlab-runner (retour).
# Le jeton du runner n'est jamais affiché. Rapport de vérification : /tmp/runner-mac-verif.txt.
set -euo pipefail

RUNNER=ci-runner
DEV=${SUDO_USER:?lancer avec sudo depuis le compte du développeur}
DEV_HOME=$(dscl . -read "/Users/$DEV" NFSHomeDirectory | awk '{print $2}')
HOME_CI=/Users/$RUNNER
PLIST=$HOME_CI/Library/LaunchAgents/gitlab-runner.plist
BIN=/opt/homebrew/opt/gitlab-runner/bin/gitlab-runner
REPORT=/tmp/runner-mac-verif.txt
ACL="user:$RUNNER deny list,search,readattr,readextattr,readsecurity,add_file,add_subdirectory,delete_child"

fail() { echo "ÉCHEC : $*" >&2; exit 1; }
[[ $EUID == 0 ]] || fail "lancer avec sudo"
exists() { dscl . -read "/Users/$RUNNER" UniqueID > /dev/null 2>&1; }
uid_ci() { id -u "$RUNNER"; }

installer() {
  [[ -x $BIN ]] || fail "$BIN absent (brew install gitlab-runner)"
  [[ -f $DEV_HOME/.gitlab-runner/config.toml ]] || fail "$DEV_HOME/.gitlab-runner/config.toml absent"

  if ! exists; then
    echo "== Compte $RUNNER (standard) : son mot de passe, puis celui de $DEV"
    # Secure token (accordé par $DEV) : avec FileVault, seuls les comptes qui en ont un sont proposés à la connexion.
    sysadminctl -addUser "$RUNNER" -fullName "CI runner" -home "$HOME_CI" -password - -adminUser "$DEV" -adminPassword -
  fi
  dseditgroup -o checkmember -m "$RUNNER" admin > /dev/null 2>&1 && fail "$RUNNER est administrateur"
  # Liste des comptes de la connexion (FileVault) recopiée dans le volume Preboot.
  diskutil apfs updatePreboot / > /dev/null
  [[ -d $HOME_CI ]] || createhomedir -c -u "$RUNNER" > /dev/null
  chmod 700 "$HOME_CI"

  echo "== Dossier de $DEV fermé à $RUNNER (ACL)"
  [[ $(ls -led "$DEV_HOME") == *"user:$RUNNER deny"* ]] || chmod +a "$ACL" "$DEV_HOME"

  echo "== Config du runner copiée (même runner, jeton non affiché)"
  install -d -o "$RUNNER" -g staff -m 700 "$HOME_CI/.gitlab-runner" "$HOME_CI/Library/LaunchAgents" "$HOME_CI/Library/Logs"
  install -o "$RUNNER" -g staff -m 600 "$DEV_HOME/.gitlab-runner/config.toml" "$HOME_CI/.gitlab-runner/config.toml"

  echo "== Agent $PLIST"
  cat > "$PLIST" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>gitlab-runner</string>
  <key>ProgramArguments</key>
  <array>
    <string>$BIN</string>
    <string>run</string>
    <string>--working-directory</string><string>$HOME_CI</string>
    <string>--config</string><string>$HOME_CI/.gitlab-runner/config.toml</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict><key>PATH</key><string>/opt/homebrew/bin:/opt/homebrew/sbin:/usr/bin:/bin:/usr/sbin:/sbin</string></dict>
  <key>WorkingDirectory</key><string>$HOME_CI</string>
  <key>LimitLoadToSessionType</key><string>Aqua</string>
  <key>ProcessType</key><string>Interactive</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$HOME_CI/Library/Logs/gitlab-runner.log</string>
  <key>StandardErrorPath</key><string>$HOME_CI/Library/Logs/gitlab-runner.log</string>
</dict>
</plist>
EOF
  chown "$RUNNER":staff "$PLIST"; chmod 644 "$PLIST"
  plutil -lint "$PLIST" > /dev/null
  # Session déjà ouverte : charger tout de suite, sinon l'agent part à l'ouverture de la session.
  launchctl print "gui/$(uid_ci)" > /dev/null 2>&1 && launchctl bootstrap "gui/$(uid_ci)" "$PLIST" 2>/dev/null || true
  verifier
}

verifier() {
  exists || fail "compte $RUNNER absent"
  {
    as() { (cd / && sudo -u "$RUNNER" -H "$@"); }
    ok() { echo "OK   $*"; }
    ko() { echo "KO   $*"; bad=1; }
    bad=0
    echo "Vérification du $(date '+%d/%m/%Y %H:%M')"
    [[ $(as id -un) == "$RUNNER" ]] && ok "compte $RUNNER" || ko "compte"
    dseditgroup -o checkmember -m "$RUNNER" admin > /dev/null 2>&1 && ko "$RUNNER administrateur" || ok "pas administrateur"
    v=$(as /opt/homebrew/bin/node --version 2>&1 || true)
    [[ $v =~ ^v([0-9]+) ]] && ((BASH_REMATCH[1] >= 22)) && ok "node $v" || ko "node : $v"
    v=$(as /opt/homebrew/bin/npm --version 2>&1 || true)
    [[ $v =~ ^[0-9] ]] && ok "npm $v" || ko "npm : $v"
    as touch /opt/homebrew/bin/.ci-runner-test 2> /dev/null && { rm -f /opt/homebrew/bin/.ci-runner-test; ko "écriture dans /opt/homebrew/bin"; } || ok "/opt/homebrew en lecture seule"
    for p in "$DEV_HOME" "$DEV_HOME/.ssh" "$DEV_HOME/Developer" "$DEV_HOME/orca" "$DEV_HOME/.gitlab-runner/config.toml"; do
      as ls "$p" > /dev/null 2>&1 && ko "lecture de $p" || ok "pas d'accès à $p"
    done
    as test -r "$HOME_CI/.gitlab-runner/config.toml" && ok "config du runner lisible par $RUNNER" || ko "config illisible"
    [[ $(stat -f %Lp "$HOME_CI/.gitlab-runner/config.toml") == 600 ]] && ok "config en 600" || ko "droits de la config"
    if launchctl print "gui/$(uid_ci)/gitlab-runner" > /dev/null 2>&1; then
      ok "agent : $(launchctl print "gui/$(uid_ci)/gitlab-runner" | awk -F' = ' '/^\tstate = /{print $2}')"
    else
      echo "...  agent pas encore chargé : ouvrir la session $RUNNER (changement rapide d'utilisateur)"
    fi
    ((bad == 0)) && echo "Résultat : OK" || echo "Résultat : KO"
  } 2>&1 | tee "$REPORT"
  chmod 644 "$REPORT"
  grep -q '^Résultat : OK' "$REPORT"
}

retour() {
  if exists; then
    launchctl bootout "gui/$(uid_ci)/gitlab-runner" 2> /dev/null || true
    rm -f "$PLIST" "$HOME_CI/.gitlab-runner/config.toml"
  fi
  chmod -a "$ACL" "$DEV_HOME" 2> /dev/null || true
  if [[ ${1:-} == --supprimer ]] && exists; then
    sysadminctl -deleteUser "$RUNNER"
  fi
  echo "Fait. Relancer le runner de $DEV : brew services start gitlab-runner (sans sudo)"
}

case ${1:-} in
  installer) installer ;;
  verifier) verifier ;;
  retour) shift; retour "$@" ;;
  *) echo "Usage : sudo $0 installer | verifier | retour [--supprimer]" >&2; exit 2 ;;
esac
