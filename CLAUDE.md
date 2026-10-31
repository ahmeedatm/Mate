# Mate : compagnon de bureau sur tablette 4.2

## Le projet
Mate donne une seconde vie à des tablettes **4.2** (Android 8 à 11 selon les lots) : posées à côté d'un Mac ou d'un PC Windows, elles servent d'écran de contrôle. **On cible Android 10** (celle d'Eliot, et l'image sans surcouche demandée). Elles sont peu puissantes : pas d'applis lourdes. Pas d'IA : l'Assistant IA a été abandonné le 26/10/2026.

## L'idée de départ (Eliot)
Un **compagnon de bureau** : la tablette posée sur le bureau, avec plusieurs modes :
- **Mode "Steam Deck" / sons** : interface média et ambiance (sons d'ambiance, contrôle audio, retours sonores, Bluetooth vers une enceinte). Interprétation à confirmer avec Eliot.
- **Autres usages de bureau** : timer / Pomodoro, agenda, météo, notifications, etc.

## Contraintes techniques connues
- Cible : **Android 10 (API 29)**, tests sur l'émulateur `companion_api29` puis la vraie tablette. `minSdk 24` est gardé (ne coûte rien, laisse tourner les lots en Android 8). Les `<queries>` du manifeste restent pour Android 11+.
- **Seul matériel cible** : spec relevée par adb dans `docs/tablette-4.2.md` (MT8167 4 × Cortex-A35 1,3 GHz, 2 Go de RAM, tas de 128 Mo, GPU PowerVR GE8300, écran 1280×800 à 55 Hz soit 18,2 ms par image, WebView 81, pas de vibreur). Toute décision technique se vérifie contre elle. Numéro de série commençant par 7733 = V4.2.
- Pas d'IA, ni locale ni cloud (Assistant IA retiré le 26/10/2026). La tablette est une interface : micro (commande vocale par la reconnaissance Android), écran, haut-parleur.
- Aucun secret (clé API) ne doit être stocké sur la tablette. Seule exception (27/10/2026) : le jeton de lecture des releases GitHub du projet (fine-grained PAT, Contents: read-only), injecté au build de l'APK release (`-PupdateToken=`, secret Actions `UPDATE_TOKEN`) pour lire les mises à jour.

## Architecture "client léger"
La tablette est l'interface ; le backend sur l'ordinateur pilote l'ordinateur. Aucune IA (Assistant IA retiré le 26/10/2026).

## Points à vérifier avant de coder (bloquants possibles)
1. **MDM** : vérifié par adb sur la tablette d'Eliot (23/10/2026, détails dans `docs/tablette-4.2.md`). Le système est un Android 10 du constructeur quasi standard (services Google, Play Store, Google TTS). Le MDM du constructeur est une appli installée dans `/data`, **profile owner** (pas device owner) ; seule restriction : `no_add_user`. adb et l'installation d'APK par adb fonctionnent déjà. Bloquant pour le kiosque seulement : `dpm set-device-owner` échoue tant qu'il y a un profile owner. Fait le 23/10/2026 (pas de reflash : bootloader verrouillé) : réinitialisation d'usine **hors ligne**, Mate device owner avant le WiFi ; il masque l'agent de provisionnement du constructeur (qui réinstalle le MDM dès qu'il a du réseau) et bloque toute installation (Réglages > code technicien > Autoriser les installations : la carte Kiosque est cachée derrière un appui long sur le titre + code `BuildConfig.TECH_CODE`, 4242 par défaut ; micro et caméra accordés d'office). Procédure et noms de paquets : `docs/kiosque.md`. L'appli n'a pas besoin des services Google ; elle utilise le WebView (« Page de l'ordi »), qu'elle met à jour elle-même (carte Mises à jour).
2. **Google Play Services** et WebView à jour.
3. **Qualité micro / haut-parleur** intégrés : sinon dock avec micro/enceinte externe (USB ou Bluetooth).
4. **Débogage USB (adb)** activable ?
5. **Réseau** : WiFi sur le lieu d'usage, accès sortant aux API cloud autorisé.
6. **Alimentation** : usage branché en permanence (batterie vieillissante).

## Autres pistes si l'idée principale bloque
Panneau domotique (Home Assistant), companion focus / Pomodoro, cadre photo / affichage ambiant, borne d'accueil / salle de réunion, CAA pour élèves, console rétro-gaming légère, second écran de notifications.

## Décisions techniques proposées (à valider)
- **Appli Android native en Kotlin**, `minSdk 24`, une seule Activity plein écran (mode kiosque / lanceur), architecture simple par "modes" (écrans).
- Dépendances minimales (APK léger, compat API 24).
- Test sur **tablette réelle** via adb dès le premier jour (l'émulateur ne reflète pas les perfs).

## Décidé
- Nom (29/10/2026) : **Mate**, partout. Le 30/10, tout lien avec le constructeur d'origine est retiré du code : compatibilité cassée avec les installations d'avant (plus d'adoption de données ni de nettoyage d'ancienne installation). Package Kotlin et `applicationId` `app.mate` (essai `app.mate.essai`), `app.mate.kiosk.KioskAdminReceiver`, alias `app.mate.HomeAlias`, permission `app.mate.permission.COMPUTERS` ; lancer à la main : `am start -n app.mate/app.mate.MainActivity` ; dossiers `Mate` (données, journaux), exe/zip `Mate-*`, `MateHelper.exe`, `Mate.exe`, `MateMenu`, protocole d'appairage `mate-pair-1`, sonde UDP `MATE?`, pointeur `MATE-POINTER`, en-tête `X-Companion-Hook`, service `mate`. Dépôt : GitHub privé `ahmeedatm/Mate`.
- Public : des gens qui ont un Mac ou un PC Windows et une tablette 4.2 posée à côté, au bureau ou à la maison, joueurs et domotique casual comprise ; la config de base vise cet usage. Pas de prix ni de positionnement commercial dans le dépôt. Le cadrage « régie de poche » pour streamers et créateurs de contenu (28/10) est écarté ; ses briques (soundboard, OBS, Twitch, SimHub) sont mises de côté.

## Pas encore décidé
- Sens exact du mode "Steam Deck" (média/ambiance ? gaming rétro ?).

## Règles de travail
- Une question à la fois si une info manque, plutôt que deviner.
- Expliquer les choix en 1-2 phrases.
- Ne rien commit ni pousser sans demande explicite. Demander une feature par la skill `nouvelle-feature` vaut accord pour sa branche, ses commits et sa PR brouillon.
- `master` protégée : tout passe par une branche et une PR, fusionnée seulement avec la CI GitHub Actions verte (format prettier + typecheck + tests d'intégration backend sous seuil de couverture (`backend/.c8rc.json`), tests Playwright des pages web du backend, ktlint + tests JVM + APK Android ; rien de lourd en PR : ni émulateur, ni installation réelle, ni compilation Windows ; `npm run format` et `./gradlew ktlintFormat` avant de pousser ; voir `docs/developpement.md`). Nouveau comportement backend = un test dans `backend/test/` ; page web servie par le backend = un test dans `backend/e2e/` ; logique pure Android = un test dans `app/src/test/`. Release : tag `vX.Y.Z` sur `master` → paquets, APK signé (clé en secrets Actions), release GitHub `latest` ; `versionName`/`versionCode` suivent le tag (`-PappVersion`) ; `npm run updates:add -- release` alimente le serveur de mises à jour de l'ordinateur (docs/developpement.md, section Release).
- Essais de PR en parallèle, sans fusion ni tag : APK `X.Y.Z-essai.N` (code entre deux tags : versionCode = (X × 10000 + Y × 100 + Z) × 1000) et, si `backend/` change par rapport à `master`, archives du backend Mac et Windows (sans node), publiés par `scripts/updates-publish.mjs --essai <pr>` (`UPDATES_REPO`, CLI `gh`, une publication à la fois) dans la release GitHub `essais` (pré-release, jamais `latest`) : une entrée par PR dans son `manifest.json` (clé `mr` = numéro de la PR, titre = variable `PR_TITLE`, version, APK, backends) ; fusion ou abandon : `--retire <pr>` ou `--purge-fermees` (docs/developpement.md, section Release). L'essai est une autre appli, `app.mate.essai` « Mate essai » (`-essai.N` → `applicationIdSuffix`, `BuildConfig.TRIAL`), une seule installée à côté de la stable, qui reste device owner : Réglages > Mises à jour liste les essais par PR lus dans la release `essais` (jeton de lecture déjà injecté, sans passer par l'ordinateur), choisir une PR installe son essai à la place du précédent (désinstallé d'abord si sa `versionCode` est plus basse), l'autorise en lock task et l'ouvre (« Ouvrir l'essai ») ; l'essai reprend ses ordinateurs par `trial/ComputersProvider` (permission signature, jamais de jeton dans un intent ou un fichier) et rend la main (« Revenir à la stable »). Backend d'une PR : la tablette télécharge l'archive de la plateforme de l'ordinateur actif (taille et SHA-256 vérifiés) et l'envoie à son backend (`POST /companion/backend/essai`, jeton d'appairage ; `backend/src/essai-backend.ts` garde une seule copie d'origine, remplace sans node ni lanceur Windows, redémarre ; `POST /companion/backend/origin` restaure) ; installé par paquet seulement, lancé depuis le dépôt il refuse (409) ; restauré par « Revenir à la stable », au retour de la stable, et par une PR sans backend ; package `trial/` (`EssaiBackend`, `EssaiPlan`).
- Installation, `backend/src/mac/`, `backend/src/win/` ou `service.ts` touchés : tests d'installation à la main (`backend/qa/install-mac.sh`, `install-windows.ps1`, `npm run build:helper` ; docs/developpement.md, section Branches, PR et CI) ; paquets et `helper-windows` ne tournent que sur un tag.
- CI ciblée par zone (en PR, seule la zone modifiée tourne ; tout sur master et les tags ; pas de nightly) ; `backend/qa/sanity.ts` = un contrôle par feature contre un compagnon réel, lancé par les scripts d'installation ; nouvelle feature backend = un contrôle dans la sanity ; voir docs/developpement.md, section Branches, PR et CI.

## Conventions du code
Règles transverses, une ligne chacune ; le détail de chaque fonction est dans `docs/architecture.md`, section « Détail par fonction ».
- **Android** (`app/`) : Kotlin, vues XML (pas de Compose), une Activity (`MainActivity`) + un Fragment par mode.
- **Couleurs et polices** : attributs de thème `?attr/c_…` (`themeColor(context, R.attr.c_…)` dans le code) et polices par rôle `c_font_*`, jamais en dur ; la liste des thèmes est dans `app/src/main/kotlin/app/mate/ui/Themes.kt`.
- Textes de l'UI en français, dans `res/values/strings.xml`.
- **Erreurs affichées** : par `errorText(e)` (`ui/Errors.kt`), jamais de `e.message` brut à l'écran.
- **Dépendances** limitées (appcompat, lifecycle-runtime-ktx, coroutines) : en ajouter une seulement si c'est indispensable et compatible API 24.
- **HTTP** par `HttpURLConnection` + `org.json`, un client par domaine dans `net/` (point d'accès `net/BackendClient.kt`).
- AGP 9 avec Kotlin intégré : pas de plugin `kotlin-android` à appliquer. Sources dans `src/main/kotlin`.
- **Backend** (`backend/`) : Node 22, TypeScript exécuté par `tsx`, Fastify, un plugin par domaine dans `src/routes/` ; `src/os.ts` est le seul point d'entrée vers le système (Mac ou Windows).
- **Réseau** : WiFi en HTTPS seulement, HTTP en clair seulement sur 127.0.0.1 ; le jeton n'est jamais envoyé en clair hors de 127.0.0.1.
- **Nouvel ordre vers la tablette** : type dans `tablet.ts` + schéma dans `routes/tablet.ts` + outil dans `mcp-tools.ts` + branche dans `RemoteChannel.execute`.
- **Noms** : `Computer*` = l'ordinateur piloté, Mac ou Windows ; `Mac` seulement pour ce qui est propre au Mac (`backend/src/mac/`).
- Tester sur l'émulateur `companion_api29` (Android 10, 1280×800, sans services Google) avant la vraie tablette : voir README. Captures avec `adb exec-out screencap -p`.

## Environnement local (Mac, Apple Silicon)
- Projet isolé, séparé des repos du boulot.
- SDK Android : `~/Library/Android/sdk` (à référencer via `local.properties`, car `ANDROID_HOME` dans `~/.zshrc` pointe vers le cask platform-tools).
- JDK 21, Node 22, adb 1.0.41.

## Où trouver le reste
- `docs/architecture.md` (fonctionnement), section « Détail par fonction » : le détail de chaque fonction (Deck V2, Agents Claude, Jira, Caméra, Verrou, Commande vocale, Plugins, MCP…).
- `docs/developpement.md` : travailler dessus, sauvegarde, branches, PR et CI, release.
- `docs/claude.md` : le circuit Claude (skills, agents, contrôle bloquant).
- `DESIGN.md` et `docs/ui-ux/da/` : direction artistique et maquettes.
- `docs/etat-du-projet.md` : fait, pas testé, à faire ; à tenir à jour en fin de session.
