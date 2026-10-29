# Site vitrine Mate : image nginx sans root (port 8080), construite par le job site-image de la CI (kaniko).
# Contexte de construction : le dossier site/, avec downloads/ rempli par scripts/site-downloads.mjs (installeurs de la
# dernière release ; vide en local, les boutons de téléchargement répondent alors 404).
FROM nginxinc/nginx-unprivileged:1.27.3-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --chown=nginx:nginx index.html style.css script.js favicon.ico apple-touch-icon.png /usr/share/nginx/html/
COPY --chown=nginx:nginx assets/ /usr/share/nginx/html/assets/
COPY --chown=nginx:nginx downloads/ /usr/share/nginx/html/downloads/

EXPOSE 8080
