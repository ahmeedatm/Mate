# Site vitrine Mate

Page statique, sans build.

**Cluster dev** (https://mate.integration.unowhy.dev) : image nginx `harbor.unowhy.dev/squads/mate-site` construite par la CI (jobs `site-downloads` et `site-image`), installeurs de la dernière release compris ; déploiement : `deploy/site-dev/README.md`. Image en local : `docker build -t mate-site site && docker run --rm -p 8080:8080 mate-site`.

**Bucket S3** (statique) : déposer le contenu de `site/` à la racine du bucket.

Pour le bucket, les deux installeurs sont à déposer à la main dans `downloads/` (ignorés par git ; l'image les récupère seule) :

- `downloads/Mate-Mac.zip`
- `downloads/Mate-Windows.exe`

Ce sont les fichiers de la release (`npm run package`, paquet `companion-updates/latest`).

Essai en local : `python3 -m http.server -d site 8765`.

DA : Cadran (`docs/ui-ux/da/README.md`), Outfit seule. Composition « la page est l'objet » (refonte du 30/10, journal des passes de skills dans `docs/ui-ux/site-vitrine.md`) :

- héros = la vraie coque de la tablette 4.2 (`assets/img/tablette.webp`, tirée de `video/demo/assets/brand/tablette-4.2.png`) avec ses vrais écrans, pilotée par la ligne du jour : six moments (boutons radio, l'écran change en CSS `:has()` sans script), point rouge à l'heure du visiteur ; zone d'écran reprise de `video/demo/SPEC.md` ;
- feuilles empilées des fonctions, chacune aux couleurs du thème de démo de sa capture (`app/src/main/res/values/palettes.xml`) ; boucle `assets/video/absence.*` découpée dans le film de démo (`video/demo`, 28 à 40 s, zone de la tablette, sans son) ;
- captures des personas : `video/demo/assets/screens/` (émulateur, données fictives) ; ni persona, ni métier, ni thème nommés.

Polices (SIL OFL) copiées de `app/src/main/res/font/`.
