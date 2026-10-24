#!/bin/zsh
# Double-clic dans le Finder : installe le compagnon sur ce Mac. Il démarrera ensuite tout seul
# à chaque ouverture de session (voir backend/scripts/service.ts). Relancer ce fichier pour mettre à jour.
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
cd "${0:A:h}/backend" || exit 1

pause() { echo; read -k1 "?Appuie sur une touche pour fermer."; echo; }

if ! command -v node >/dev/null 2>&1 || ! node -e 'process.exit(+process.versions.node.split(".")[0] >= 22 ? 0 : 1)'; then
  echo "Node.js 22 (ou plus récent) est nécessaire : installe la version LTS depuis nodejs.org, puis relance ce fichier."
  open "https://nodejs.org/fr/download"
  pause; exit 1
fi

echo "Installation des dépendances…"
npm install --no-audit --no-fund || { pause; exit 1; }
npm run service:install
pause
