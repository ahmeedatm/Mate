# Site vitrine Mate

Page statique, sans build.

**Cluster dev** (https://mate.development.unowhy.dev) : image nginx `harbor.unowhy.dev/squads/mate-site` construite par la CI (jobs `site-downloads` et `site-image`), installeurs de la dernière release compris ; déploiement : `deploy/site-dev/README.md`. Image en local : `docker build -t mate-site site && docker run --rm -p 8080:8080 mate-site`.

**Bucket S3** (statique) : déposer le contenu de `site/` à la racine du bucket.

Pour le bucket, les deux installeurs sont à déposer à la main dans `downloads/` (ignorés par git ; l'image les récupère seule) :

- `downloads/Compagnon-Unowhy-Mac.zip`
- `downloads/Compagnon-Unowhy-Windows.exe`

Ce sont les fichiers de la release (`npm run package`, paquet `companion-updates/latest`).

Essai en local : `python3 -m http.server -d site 8765`.

DA : Cadran (`docs/ui-ux/da/README.md`). Logo : variante « visage ». Captures : `docs/ui-ux/da`. Police Outfit (SIL OFL), copiée de `app/src/main/res/font/`.
