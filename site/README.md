# Site vitrine Mate

Page statique, sans build. Déposer le contenu de `site/` à la racine du bucket S3 (site statique).

Les deux installeurs sont à déposer à la main dans `downloads/` du bucket (ignorés par git) :

- `downloads/Compagnon-Unowhy-Mac.zip`
- `downloads/Compagnon-Unowhy-Windows.exe`

Ce sont les fichiers de la release (`npm run package`, paquet `companion-updates/latest`).

Essai en local : `python3 -m http.server -d site 8765`.

DA : Cadran (`docs/ui-ux/da/README.md`). Logo : variante « visage ». Captures : `docs/ui-ux/da`. Police Outfit (SIL OFL), copiée de `app/src/main/res/font/`.
