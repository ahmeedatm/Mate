# Travailler sur le projet

Pour reprendre le code sur un autre ordinateur ou le confier à un collègue. Pour comprendre comment le tout fonctionne : [architecture.md](architecture.md). Pour savoir où on en est : [etat-du-projet.md](etat-du-projet.md).

## Récupérer le projet

Le dépôt est un GitHub privé, `ahmeedatm/Mate`. La branche par défaut est `master`.

```bash
git clone git@github.com:ahmeedatm/Mate.git
cd Mate
```

**Données de cet ordinateur** (secrets, jeton d'appairage, agendas…) : elles ne sont pas dans git. `scripts/sauvegarde.sh` (dans `~/Desktop`, ou `scripts/sauvegarde.sh /chemin/du/dossier`) crée :

| Fichier | Contenu | À qui |
|---|---|---|
| `mate-AAAA-MM-JJ.bundle` | tout le code et l'historique (commits seulement) | copie de secours du code |
| `mate-donnees-perso-AAAA-MM-JJ.tgz` | `backend/.env`, `backend/.data/` (jeton d'appairage, agendas reliés, tâches, touches…), deck et tâches perso | **à garder pour soi** : il contient des secrets |

Pour retrouver SES données sur un autre ordinateur : `tar xzf ../mate-donnees-perso-AAAA-MM-JJ.tgz` dans le clone. Pour restaurer le code depuis un bundle : `git clone mate-AAAA-MM-JJ.bundle Mate`.

## Ce qui n'est pas dans git (et comment le recréer)

| Fichier | Rôle | Sans lui |
|---|---|---|
| `local.properties` | `sdk.dir=/Users/<toi>/Library/Android/sdk` | le build Android ne trouve pas le SDK |
| `backend/.env` | réglages et secrets (liste dans `backend/.env.example` : `PORT`, `HOST`, `COMPUTER_NAME`, `DOCS_DIR`, `CALENDAR_ICS_URLS`, `JIRA_*`…) | valeurs par défaut, sans Jira |
| `backend/.data/` | données de cet ordinateur : identité et jeton, agendas, tâches, touches, applis, sites | recréé vide : il faut appairer la tablette à nouveau |
| `backend/config/deck.json` | deck de cet ordinateur | créé depuis `deck.example*.json` |
| `backend/config/tasks.json` | tâches quotidiennes | pas de tâche quotidienne |
| `backend/node_modules/` | dépendances | `cd backend && npm install` |
| `shared/` | dossier Partage par défaut | vide |

Ne jamais mettre de clé ou de jeton dans un fichier versionné, ni sur la tablette.

## Installer l'environnement

- **Android** : JDK 17+ (testé avec 21), Android SDK (`compileSdk 36`), adb. AGP 9 avec Kotlin intégré.
- **Ordinateur** : Node 22+.
- macOS : si `ANDROID_HOME` pointe ailleurs (cask platform-tools), le préciser dans les commandes de l'émulateur (voir README).

Puis :

```bash
cd backend && npm install && npm run lint && npm test
cd .. && ./gradlew assembleDebug
```

## Boucle de développement

**Compagnon** (sur l'ordinateur) : arrêter le service, puis `cd backend && npm run dev` (rechargement à chaque sauvegarde). Pour le relancer en service : `npm run service:start`.

Pour un **compagnon de test** à côté du vrai (autres données, autre port) :

```bash
HOST=127.0.0.1 PORT=3021 DATA_DIR=/tmp/companion-test npx tsx --env-file-if-exists=.env src/server.ts
```

**Appli** :

```bash
./gradlew assembleDebug
adb -s <série> install -r app/build/outputs/apk/debug/app-debug.apk   # un appareil précis
```

`./gradlew installDebug` installe sur **tous** les appareils branchés, tablette comprise. Une tablette en kiosque relance l'appli toute seule après l'installation (`UpdatedReceiver`). Pour installer, il faut que « Autoriser les installations » soit activé (Réglages > appui long sur le titre > code technicien).

**Émulateur** `companion_api29` (Android 10, 1280×800, sans services Google) : on y joint l'ordinateur comme en USB : `adb reverse tcp:3000 tcp:3000`, puis « Saisir l'adresse » `127.0.0.1` (l'appli n'envoie pas son jeton en clair à `10.0.2.2`, voir [securite-appairage.md](securite-appairage.md)). La recherche WiFi ne traverse pas l'émulateur, son DNS peut être en panne (météo) et sa caméra plante. On teste sur la vraie tablette dès que possible : l'émulateur ne reflète pas les performances.

**Captures de la tablette** : `adb exec-out screencap -p > capture.png`. Une capture noire veut dire que l'écran est éteint : l'appli est alors en pause et n'écoute plus l'ordinateur.

## Branches, PR et CI

`master` est protégée : on n'y pousse plus directement. Une branche par changement (`feat/…`, `fix/…`, `docs/…`, `ci/…`), puis une PR vers `master`. Elle se fusionne quand la CI GitHub Actions (`.github/workflows/`) est verte.

La CI reste courte : seulement des jobs rapides. Rien de lourd en PR (ni émulateur, ni installation sur un vrai ordinateur, ni compilation Windows) ; ce qui sert à la release tourne sur un tag.

| Contrôle | Vérifie |
|---|---|
| `backend` | `npm run lint` (format prettier, puis typecheck), puis `npm run coverage` : tests unitaires et d'intégration (`backend/test/`) sous seuil de couverture (voir plus bas), le vrai serveur démarre avec un dossier de données vide et répond à de vraies requêtes HTTP ; le pilotage de l'ordinateur y est simulé (voir plus bas). Comprend la sanity (`test/sanity.test.ts`) |
| `e2e` | pages web du backend dans Chromium (Playwright, `backend/e2e/`) : page locale (adresse, saisie du code d'appairage, lien ICS), visualiseur `/camera`, retours d'erreur de `/oauth/:provider/callback`. Même serveur que les tests d'intégration. Artefacts : `e2e-results/junit.xml` et traces des tests en échec |
| `android` | style Kotlin (`ktlintCheck`), tests JVM (`app/src/test/` : `Computers`, `Prefs`, `Connection` contre de petits serveurs HTTP locaux, `FocusTimer`, `Water`, ordre `focus`, modes, widgets, commande vocale ; contexte simulé `FakeContext`), APK debug (téléchargeable dans les artefacts, à installer sur la tablette) |

**Ciblage par zone.** En PR, un contrôle ne tourne que si sa zone a bougé. Sur `master` et sur un tag, tout tourne. Toucher les workflows relance tout.

| Zone | Fichiers | Contrôles |
|---|---|---|
| backend | `backend/**` | `backend`, `e2e` |
| android | `app/**`, `*.gradle*`, `gradle/**`, `gradlew*` | `android` |

Sur un tag `vX.Y.Z` seulement : `.github/workflows/release.yml` (jobs `android`, `backend`, `publier` ; section Release). PR étiquetée `essai` : `.github/workflows/essai.yml` puis `essai-publication.yml` (section Déployer un essai depuis une PR). `helper-windows` n'a pas encore de job GitHub : `npm run build:helper` à la main.

**Tests d'installation, à la main.** Plus en CI. À lancer avant une release ou quand l'installation, `backend/src/mac/`, `backend/src/win/`, `os.ts` ou `service.ts` changent :

- Mac : `cd backend && npm run package -- mac && cd .. && backend/qa/install-mac.sh run dist/Mate-Mac.zip` (instance de test isolée du vrai compagnon : port, données et runtime à part, rien dans LaunchAgents ; `install-mac.sh cleanup` après un échec).
- Windows : `powershell -ExecutionPolicy Bypass -File backend\qa\install-windows.ps1 -Exe <exe> -Root <dossier> -Port <port>` (exe lancé seul, rien d'inscrit, runtime extrait, lancement au démarrage coché puis décoché, sanity, pilote, relance de l'exe, quitter ; `-Nettoyage` ensuite). Sur un PC qui fait tourner un vrai compagnon, toujours un autre `-Root` et un autre `-Port` que les valeurs par défaut.
- Pilote Windows : `cd backend && npm run build:helper` après un changement de `src/win/helper/` ou `src/win/launcher/`, puis commiter les exe. Le contrôle `helper-windows` (tag seulement) échoue si les exe du dépôt ne sont plus ceux des sources.

**Sanity** (`backend/qa/sanity.ts`) : un contrôle par feature contre un compagnon réel déjà démarré : santé et identité, appairage par code (sans boîte avec `COMPANION_HEADLESS=1` ou `NO_ALERT=1`), deck et deck de départ filtré aux applis installées, son, fenêtres ouvertes, absence gardée (lecture seule : jamais de start ni de resume), refus d'un `.exe` par `/docs/open`, presse-papiers (écrit un texte accentué, le relit à l'identique et remet l'ancien texte ; passe son tour seulement sur un 503 « pas de session de bureau » ; image, contenu protégé, autre format et texte coupé sont en lecture seule : le contrôle n'écrit jamais dessus), tâches, agenda personnel, agenda ICS (fichier servi en local, seulement si aucun agenda n'est relié), GitLab (lecture seule : 503 sans jeton, sinon `mine` et `review` en tableaux ; jamais de fusion ni d'ouverture), `/camera`, page locale. Réponse attendue selon le système : hors Mac et Windows, le deck se lit et le reste du pilotage répond 501. Ce qu'il crée est retiré à la fin. Sans dépendance : le node du paquet le lance tel quel.

```sh
node qa/sanity.ts --url http://127.0.0.1:3000 --token <jeton> --os mac --junit sanity.xml
```

Avant de pousser : `cd backend && npm run lint && npm run coverage`, et `./gradlew ktlintCheck testDebugUnitTest`. Lint Android à la main si besoin : `./gradlew lintDebug` (erreurs déjà présentes figées dans `app/lint-baseline.xml` ; après en avoir corrigé, `./gradlew updateLintBaseline`).

**Format et style** (bloquants en CI) :

| | Corriger | Vérifier (CI) | Règles |
|---|---|---|---|
| Backend | `cd backend && npm run format` | `npm run lint` = `format:check` + `typecheck` | `backend/.prettierrc.json` (lignes de 140), `.prettierignore` ; `tsconfig.json` refuse variables, imports et paramètres inutilisés |
| Android | `./gradlew ktlintFormat` | `./gradlew ktlintCheck` | `.editorconfig` : ktlint (style IntelliJ) sans les règles qui coupent les lignes ; tâches dans `build.gradle.kts` (sans plugin) |

Pas d'eslint : typescript-eslint ne prend pas encore TypeScript 7. Un paramètre inutilisé mais imposé par une signature se préfixe par `_`. Si `ktlintFormat` échoue sur `MaxPermSize`, c'est `~/.gradle/gradle.properties` : retirer l'option (Java 8) ou lancer avec `-Dorg.gradle.jvmargs=-Xmx2048m`.

**Couverture du backend** (bloquante en CI) : `npm run coverage` lance les tests sous c8, serveur de test compris (processus enfant ; `test/fake-os-hook.ts` le fait sortir proprement sur `SIGTERM`, sinon V8 n'écrit pas sa couverture). Le job échoue si les lignes, branches ou fonctions de `src/` passent sous les seuils de `backend/.c8rc.json` ; la MR affiche l'écart de couverture et les lignes non couvertes du diff. Hors mesure : vrais pilotes (`src/mac/`, `src/win/`, `src/os.ts`, vérifiés à la main par `backend/qa/install-mac.sh` et `install-windows.ps1`) et `src/mcp.ts`. Seuils = valeur du job arrondie à l'entier inférieur : les remonter dans la MR qui fait monter la couverture, jamais les baisser pour faire passer un pipeline. La valeur locale (Mac, Node récent) peut dépasser celle de la CI de quelques dixièmes.

Tests Playwright (page web touchée : `src/local-page.ts`, `src/camera.ts`, routes OAuth) : `cd backend && npm run test:e2e`, après un `npx playwright install chromium` la première fois. Sans navigateur installé, dans l'image du job :

```sh
docker run --rm -e CI=true -v "$PWD":/w -v /w/backend/node_modules -w /w/backend \
  mcr.microsoft.com/playwright:v1.64.1-noble bash -c 'npm ci && npm run test:e2e'
```

Le tag de l'image du job `e2e` suit la version exacte de `@playwright/test` (`backend/package.json`) : changer les deux ensemble (et le digest). Trace d'un échec : `npx playwright show-trace e2e-results/traces/<test>/trace.zip`.

**Pilotage simulé dans les tests** : `backend/test/fake-os.ts` remplace `src/os.ts` (hook de résolution `test/fake-os-hook.ts`) ; aucun test ne lance d'appli, n'envoie de touches ni n'ouvre de fenêtre. Il note chaque action (`calls`) et répond selon un scénario (`state` : applis installées, fenêtres, boutons, Ne pas déranger, échecs). Serveur de `test/server.ts` : toujours simulé (`startServer({ os: "windows" })` pour Windows), scénario par `server.setOs(...)`, actions par `server.osCalls()`. Test unitaire d'un module qui pilote l'ordinateur : `import "./fake-os-hook.ts"` en tête, puis le module en import dynamique (`await import("../src/deck.ts")`). Un export ajouté à `os.ts` doit l'être aussi au faux, sinon les tests échouent au chargement.

Ce que la CI ne voit pas : l'appli Android lancée (ni émulateur ni tablette en CI : écrans et parcours se vérifient par l'essai sur la tablette), l'installation et le pilotage réels du Mac et de Windows (simulés dans les tests), la vraie tablette (perfs, kiosque, micro, caméra). Ils restent testés à la main (case « Testé sur » de la PR).

## Release (tag vX.Y.Z)

**Mentions de licences** : l'appli affiche `app/src/main/assets/licences.txt` (Réglages > À propos > Licences des composants), à régénérer après tout ajout ou changement de dépendance Android : `node scripts/licences-android.mjs` (licences lues dans les POM du cache Gradle ; le script s'arrête sur une licence autre qu'Apache 2.0, à traiter à la main ; `LicencesTest` échoue si une dépendance de `app/build.gradle.kts` manque). Côté ordinateur, rien à faire : `npm run package` écrit `LICENCES-TIERS.txt` (paquets npm réellement regroupés par esbuild, texte de leur licence, licence de node) dans le dossier installé.

Une release enchaîne ces étapes, lancées par `.github/workflows/release.yml` sur un tag `vX.Y.Z` (ce sont ses commandes) :

| Étape | Produit |
|---|---|
| `npm run package -- windows` puis `mac` (dans `backend/`) | `Mate-Windows.exe` (exe portable : lanceur + runtime zippé) et `Mate-Mac.zip` (appli portable), version = le tag |
| `./gradlew assembleRelease -PappVersion=X.Y.Z -PupdateToken=…` | `companion-X.Y.Z.apk` signé (clé de signature ci-dessous, jeton de lecture ci-dessous) |
| entrée de l'APK | `companion-X.Y.Z.json` : `{package, label, versionCode, versionName, file}`, lus dans l'APK (`aapt2 dump badging`) |
| `npm run package -- essai` (dans `backend/`) | `backend-mac.tar.gz` et `backend-windows.tar.gz` : backend sans node ni lanceur, pour la mise à jour de l'ordinateur depuis la tablette |
| publication | la release GitHub `latest` (commande ci-dessous) |

Tous les fichiers sont dans `dist/`. Commande de publication, `gh` connecté ou `GH_TOKEN` (jeton avec écriture sur les releases) dans l'environnement :

```bash
UPDATES_REPO=ahmeedatm/Mate node scripts/updates-publish.mjs dist updates
```

Le numéro vient d'un tag `vX.Y.Z` posé sur `master` : `git tag v1.2.0 origin/master && git push origin v1.2.0`.

Version de l'appli : `versionName` = `X.Y.Z` du tag, `versionCode` = (X × 10000 + Y × 100 + Z) × 1000 (Y et Z sous 100), donnés à Gradle par `-PappVersion=X.Y.Z`. Les 999 codes entre deux tags servent aux essais d'une PR (`-PappVersion=X.Y.Z-essai.N` : code du dernier tag + 1 + N mod 999). Sans ce paramètre : `0.0.0` (code 1, Android refuse 0). La tablette n'installe une mise à jour que si son `versionCode` est plus grand : chaque tag doit être supérieur au précédent.

Numérotation : sur GitHub, la série repart de `v0.0.1` (code 1000), posé le 01/11/2026 ; aucune tablette n'avait encore d'APK `app.mate` signé (renommage du 30/10, nouvelle clé). Les tags `v0.x` de l'ancienne CI GitLab n'existent plus. `v0.1.0` = 100000, `v1.0.0` = 10000000 (au-dessus de toute la série 0.x). Vérifié en local (manifeste fusionné) : `0.0.3` → 3000, `0.1.0` → 100000, `0.0.2-essai.169` → 2170, sans paramètre → 1. Attention : un APK construit sans `-PappVersion` porte le code par défaut de `app/build.gradle.kts`, pas celui d'un tag : la tablette refuse toute mise à jour dont le code n'est pas strictement plus grand que celui de l'APK installé.

**Clé de signature**, jamais dans le dépôt. Secrets de l'environnement Actions `signature` : `RELEASE_KEYSTORE_BASE64` (fichier PKCS12 en base64, sur une ligne) et `RELEASE_KEYSTORE_PASSWORD` (mot de passe du fichier et de la clé, alias `companion`). L'environnement n'est ouvert qu'à `master` et aux tags `v*` (Settings > Environments) : un workflow lancé par une PR ne le voit pas, même modifié. Seuls les admins posent un tag `v*` (règle « tags de version », Settings > Rules) ; `release.yml` vérifie en plus que le tag est sur `master`. Clé créée le 01/11/2026 : fichier `~/.config/mate/companion-release.p12` (droits 600) sur le Mac d'Ahmed, mot de passe dans son Trousseau (`security find-generic-password -s mate-release-keystore -a companion -w`). Empreinte SHA-256 du certificat : `2c9cd9872f2ee1875b55f16e2da1f38c439480b10ef8389143d644f76b6b18f1`, figée dans les workflows (`SIGNING_CERT_SHA256`) : un APK signé par une autre clé est refusé. Perdue = plus aucune mise à jour possible des tablettes : garder une copie du fichier et du mot de passe hors du Mac. Commande de création (une seule fois) :

```bash
keytool -genkeypair -storetype PKCS12 -keystore companion-release.p12 -alias companion -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Mate"
base64 -i companion-release.p12 | tr -d '\n'   # valeur de RELEASE_KEYSTORE_BASE64
```

Sur un poste de dev, `./gradlew assembleRelease` sans `RELEASE_KEYSTORE` donne un APK release non signé. Avec : `RELEASE_KEYSTORE=chemin.p12 RELEASE_KEYSTORE_PASSWORD=… ./gradlew assembleRelease -PappVersion=1.2.0`.

**Source distante des mises à jour** : les releases GitHub du dépôt privé `ahmeedatm/Mate`. Deux tags : `latest` (la dernière stable) et `essais` (pré-release, un essai par PR). Chaque fichier est un asset de la release ; `manifest.json` en est la table des matières.

- `manifest.json` → `{"updates":[{"package":"app.mate","label":"Mate","versionCode":N,"versionName":"X.Y.Z","file":"companion-X.Y.Z.apk","size":octets,"sha256":"…"}]}`, même forme que `GET /updates` du backend ;
- `companion-X.Y.Z.apk` → l'APK signé.

La tablette lit par l'API GitHub (`net/GitHubReleases.kt`, point d'accès unique `UpdatesApi.server()` et `UpdatesApi.essais()`) : `GET /repos/<repo>/releases/tags/<tag>` donne les assets (nom, id), puis `GET /repos/<repo>/releases/assets/<id>` avec `Accept: application/octet-stream` donne le fichier. GitHub répond 302 vers un stockage signé : la tablette suit la redirection à la main et n'envoie le jeton qu'à `api.github.com` (jamais à un autre hôte). 401, 404 ou release absente : « aucune mise à jour », sans erreur.

Le jeton est `BuildConfig.UPDATE_TOKEN`, donné au build par `-PupdateToken=` (vide en debug : aucune mise à jour vue). Le dépôt est privé : jeton fine-grained limité à `ahmeedatm/Mate`, permission **Contents: read-only** (la plus basse qui lit les releases d'un dépôt privé). La CI le lit dans un secret Actions masqué (`UPDATE_TOKEN`) et le donne à Gradle (`-PupdateToken="$UPDATE_TOKEN"`). Il est dans l'APK, donc lisible par qui a l'APK, et donne la lecture du dépôt. Le révoquer ou le laisser expirer = plus aucune mise à jour pour les tablettes déjà installées : noter sa date d'expiration. État au 01/11/2026 : secret `UPDATE_TOKEN` pas encore posé (jeton à créer par Ahmed sur github.com, l'API ne sait pas le faire) ; les workflows avertissent et construisent sans. Tant que le dépôt est public (vu le 01/11/2026, alors que ce document le dit privé), les tablettes lisent quand même les releases sans jeton (limite de l'API : 60 requêtes par heure et par adresse IP). Expiration du jeton : à noter ici à sa création. `-PupdateRepo=` change le dépôt (vide : source coupée). `-PupdateUrl=http://10.0.2.2:8099` remplace GitHub par un serveur HTTP simple (essai local, essais sous `/essais`).

`scripts/updates-publish.mjs` (à la racine) lit l'entrée `companion-X.Y.Z.json`, calcule taille et SHA-256, écrit dans `<sortie>/` l'APK, les installeurs trouvés dans `dist/` (`Mate-Mac.zip`, `Mate-Windows.exe`) et les archives du backend, puis `manifest.json`. Avec `UPDATES_REPO`, il publie par la CLI `gh` : crée la release `latest` si elle manque, envoie les fichiers (`--clobber`) puis `manifest.json` (le manifest ne désigne jamais un fichier absent), et supprime les assets que le manifest ne nomme plus : seule la dernière version reste, installeurs compris. Le manifest décrit les installeurs dans une clé `installers` à côté de `updates` : `[{"os":"mac"|"windows","file":…,"size":…,"sha256":…,"version":"X.Y.Z"}]` (absente d'un essai). Test : `backend/test/updates-publish.test.ts`, contre une fausse CLI `gh`. Essai local sans dépôt : `node scripts/updates-publish.mjs dist updates` (sans `UPDATES_REPO`, n'écrit que `updates/`).

**Compagnon mis à jour depuis la tablette** : les archives de `package-backend` sont publiées sous `backend-X.Y.Z-<os>.tar.gz`, clé `backends` du manifest (même forme que `installers`). Le serveur de mises à jour de l'ordinateur peut aussi en servir (`npm run updates:add -- backend-X.Y.Z-mac.tar.gz`, clé `backends` de `GET /updates`) ; la tablette garde la plus récente des deux sources. Elle compare à la version de l'ordinateur actif (`/health` : `version`, `backendUpdate` = sait se mettre à jour ainsi), télécharge l'archive de son système, vérifie taille et SHA-256 et l'envoie par `POST /companion/backend/update?version=&sha256=` (jeton d'appairage). L'ordinateur refuse lancé depuis le dépôt ou si la version n'est pas plus récente (409), pose l'archive (`applyUpdate` de `src/essai-backend.ts` : mêmes dossiers gérés que l'essai, copie temporaire remise si une copie échoue, essai de PR posé oublié) et redémarre ; la tablette attend qu'il revienne dans la nouvelle version (2 min). Proposé par Réglages > Mises à jour (ligne du compagnon et bouton) et par le bandeau « Nouvelle version du compagnon » ; rien n'est proposé par la stable tant qu'un essai de PR est posé. Un compagnon sans `backendUpdate` (installé avant) reçoit l'installeur une dernière fois (étapes ci-dessous). Node et le lanceur ne sont pas dans l'archive : un changement de l'un d'eux repasse par l'installeur. Logique dans `installers/BackendPlan` (testée) et `installers/CompanionUpdater`.

**Applis servies par la tablette** : au démarrage puis tous les quarts d'heure (WiFi seulement), la tablette lit la clé `installers` du manifest de la release `latest`. Un installeur absent ou dont le SHA-256 a changé est téléchargé dans `.part`, vérifié, puis renommé : seule la dernière version est gardée (`filesDir/installers`, état dans `installed.json`). Un petit serveur HTTP (`installers/InstallerHttpServer`, sans dépendance, port 8080, démarré par `MainActivity`, pas dans l'appli d'essai) répond sur le WiFi : `GET /` = page qui détecte l'OS du visiteur et propose son appli en premier, `GET /installers.json` = la liste (version, taille, SHA-256) ; le panneau de l'ordinateur la lit, lui, dans l'état du poll (HTTPS, jeton : clé `installers`), jamais en clair, `GET /<fichier>` = le fichier. Avant le premier téléchargement, la page dit qu'ils arrivent. Écran Bienvenue et Réglages > Ordinateurs affichent `http://<ip de la tablette>:8080`. Le compagnon donne sa version dans `/health` (`version` : tag du paquet, `dev` depuis le dépôt, absente avant cette version) ; tous les quarts d'heure, si l'installeur gardé pour son OS est plus récent (ou sa version absente), un bandeau le dit, une fois par ordinateur et par version ; le toucher ouvre les étapes de mise à jour (Mac ou Windows : relancer l'appli, « Chercher une mise à jour » dans le panneau ; page 8080 pour une ancienne version sans panneau) avec un bouton qui ouvre la page 8080 sur l'ordinateur, puis la tablette vérifie chaque minute et dit « Compagnon à jour ». La page 8080 a une ligne « Déjà lancé ? » par appli. Essai sans release : `node scripts/updates-publish.mjs dist updates` avec les deux installeurs dans `dist/`, `python3 -m http.server 8099 -d updates` (accessible de l'émulateur en `10.0.2.2`), build avec `-PupdateUrl=http://10.0.2.2:8099` (sans jeton), puis `adb forward tcp:8080 tcp:8080` et `http://localhost:8080` sur le Mac.

**Alimenter le serveur de mises à jour d'un ordinateur** (mise à jour de la tablette depuis l'ordinateur, sans GitHub) : dans `backend/`, avec la CLI `gh` connectée :

```bash
npm run updates:add -- release                                   # release `latest` (APK et archives backend), données du dépôt (backend/.data)
DATA_DIR=paquet npm run updates:add -- release                   # compagnon installé par paquet
npm run updates:add -- ~/Téléchargements/companion-1.2.0.json    # ou une entrée et son APK téléchargés à côté
```

`scripts/updates.ts` copie l'APK (et les archives du backend) dans `<données>/updates/` et remplace la version précédente du Mate dans `manifest.json` ; les autres entrées (WebView) restent. `UPDATES_REPO` change le dépôt lu. La tablette la voit dans Réglages > Mises à jour. Test : `backend/test/updates-add.test.ts`.

Changement de signature : Android refuse une mise à jour signée par une autre clé. Une tablette qui a reçu l'APK debug (adb, artefact du job `android`) n'acceptera l'APK de release qu'après désinstallation du Mate, donc, en kiosque, après avoir refait la procédure de [kiosque.md](kiosque.md) avec l'APK de release.

### Déployer un essai depuis une PR

Pour essayer l'appli d'une PR (et son backend) sur une tablette en kiosque, sans fusionner ni tagger. Plusieurs PR peuvent être en essai en même temps ; on choisit celle à installer sur la tablette. L'essai est une **autre appli**, `app.mate.essai` « Mate essai », installée à côté de la stable (une seule appli d'essai : choisir une PR remplace la précédente) : la stable reste device owner et kiosque, et on revient à elle sans rien réinstaller. Il doit être signé par la clé de release (même clé que la stable : sinon pas d'ordinateurs repris).

**Déclenchement** : poser l'étiquette `essai` sur la PR (`gh pr edit <n> --add-label essai`). Tant qu'elle est là, chaque nouveau commit republie l'essai ; une PR en brouillon ne se publie pas (il part au passage en prête). Les étapes 1, 2 et 4 sont faites par la CI ; les commandes restent pour un essai à la main.

**Signature à part** : le code de la PR ne voit jamais la clé. `.github/workflows/essai.yml` (« Essai (construction) », déclenché par la PR, lecture seule) construit l'APK release **non signé** et, si `backend/` change, les archives du backend, sans secret autre que le jeton de lecture `UPDATE_TOKEN`. Puis `.github/workflows/essai-publication.yml` (`workflow_run`, donc toujours la version de `master`, sans exécuter un seul fichier de la PR) vérifie que la PR est ouverte, hors brouillon, étiquetée, du dépôt même (pas d'une fourche) et que sa tête est le commit construit ; contrôle le SHA-256 de chaque fichier reçu ; lit l'APK (`aapt2 dump badging`) et refuse tout autre paquet que `app.mate.essai`, libellé « Mate essai » et version `X.Y.Z-essai.N` ; l'aligne (`zipalign`), le signe (`apksigner`, environnement `signature`), vérifie l'empreinte du certificat, puis publie avec `scripts/updates-publish.mjs` de `master`. À la fermeture d'une PR étiquetée, `pull_request_target` (code de `master`, sans checkout de la PR) lance `--retire` ; à chaque push sur `master`, `--purge-fermees` rattrape le reste. Les trois jobs partagent `concurrency: essais` ; limite de GitHub : d'une file de plusieurs publications en attente, seule la dernière reste (les autres sont annulées) : un essai sauté se republie au commit suivant, un retrait sauté est rattrapé par la purge.

1. Construire : `./gradlew assembleRelease -PappVersion=X.Y.Z-essai.N -PupdateToken=…` (X.Y.Z = dernier tag de la branche, N = numéro d'exécution du workflow), entrée `companion-X.Y.Z-essai.N.json` comme pour une release, et `npm run package -- essai` dans `backend/` si `git diff origin/master...HEAD -- backend/` n'est pas vide (`dist/backend-mac.tar.gz`, `dist/backend-windows.tar.gz`).
2. Publier, un workflow à la fois (`concurrency: essais` : la commande lit le manifest puis le réécrit) :

```bash
UPDATES_REPO=ahmeedatm/Mate PR_TITLE="<titre de la PR>" node scripts/updates-publish.mjs --essai <numéro de la PR> dist updates
```

   Le titre est du texte libre, affiché tel quel sur la tablette, jamais exécuté. Une PR en brouillon ne se publie pas.
3. Sur la tablette, dans la stable : Réglages > Mises à jour > Rechercher. La liste « Essais de PR » montre une ligne par PR (« #109 titre », version, backend de l'ordinateur remplacé ou non). La PR installée dit « Installée », sans bouton ; les autres disent quelle PR elles remplacent (« Remplace l'essai de la PR #109 ») ; la liste se met à jour à la fin de l'installation. Choisir une PR installe son essai à la place du précédent (silencieux, device owner), puis, si la PR touche le backend, envoie son archive à l'ordinateur. « Ouvrir l'essai » apparaît à côté de Rechercher. L'essai démarre avec les ordinateurs de la stable, verrouillé à l'écran comme elle ; dans l'essai, Réglages > Mises à jour > « Revenir à la stable » remet le backend d'origine de l'ordinateur, ferme l'essai et rouvre la stable.
4. Retirer, une fois la PR fusionnée ou abandonnée (pas en même temps qu'une publication) :

```bash
UPDATES_REPO=ahmeedatm/Mate node scripts/updates-publish.mjs --retire <numéro de la PR> updates
UPDATES_REPO=ahmeedatm/Mate node scripts/updates-publish.mjs --purge-fermees updates   # toutes les PR qui ne sont plus ouvertes (gh pr view)
```

Limites : l'essai n'est pas device owner. Mises à jour, WiFi et carte Kiosque ne s'y testent pas (ils se règlent dans la stable) ; il garde ses propres réglages (thème, Accueil) et, une fois ses ordinateurs repris, sa propre liste. Kiosque quitté dans la stable (carte Kiosque) : l'essai s'ouvre sans verrouillage. Un seul essai installé à la fois. La tablette et ses ordinateurs doivent avoir ces fonctions (liste des essais, bascule du backend) en version stable avant de pouvoir choisir une PR : la mise à jour d'un ordinateur est à faire avant, comme les autres (section suivante).

Version : `X.Y.Z-essai.N` ; code = celui du tag + 1 + N mod 999 (`app/build.gradle.kts`). Le suffixe `-essai.N` fait l'appli d'essai (`applicationIdSuffix ".essai"`, libellé, `BuildConfig.TRIAL`) ; la CI lit paquet et libellé dans l'APK (`aapt2 dump badging`) et refuse un essai qui porterait le paquet de la stable (`updates-publish.mjs` le vérifie aussi). Une PR à la version plus ancienne que l'essai installé a un code plus bas, qu'Android refuse : la stable désinstalle d'abord l'essai (silencieux, device owner ; ses données partent, il reprend les ordinateurs de la stable), puis installe. Tous les 999 exécutions, le code repart du bas : même cas. Essai local sur l'émulateur : `./gradlew assembleDebug -PappVersion=0.0.3-essai.1`, à côté d'une stable debug (même clé debug).

**Release `essais`** (pré-release, aucune tablette ne la lit seule : elle ne sert qu'aux essais choisis). Manifest → `{"essais":[{"mr":109,"title":"…","package":"app.mate.essai","label":"Mate essai","versionCode":N,"versionName":"X.Y.Z-essai.N","file":"companion-X.Y.Z-essai.N.apk","size":octets,"sha256":"…","backend":{"mac":{"file":"backend-X.Y.Z-essai.N-mac.tar.gz","size":…,"sha256":"…"},"windows":{…}}}]}`, une entrée par PR (la clé `mr` porte le numéro de la PR : le format n'a pas changé) ; `backend` seulement si `backend/` diffère de `master`. La tablette le lit avec le jeton de lecture injecté au build (`UpdatesApi.essais()`, même dépôt, tag `essais`), sans passer par l'ordinateur. Testé contre une fausse CLI `gh` : `backend/test/updates-publish.test.ts`.

**Backend de la PR sur l'ordinateur** (`backend/src/essai-backend.ts`, routes de `routes/updates.ts`). La tablette télécharge l'archive de la plateforme de l'ordinateur actif (`os` de l'ordinateur), vérifie taille et SHA-256, puis l'envoie à l'ordinateur : `POST /companion/backend/essai?mr=<numéro de la PR>&sha256=<empreinte>` (corps `application/gzip`, jeton d'appairage ; aucun secret ne va à l'ordinateur, qui ne télécharge rien). L'ordinateur vérifie l'empreinte et le contenu (`dist/server.mjs` et le fichier propre à sa plateforme), extrait à part (`tar`, présent sur Mac et sous Windows 10), prend une copie de son backend installé dans `<données>/backend-origin` (une seule, celle d'origine : un essai suivant ne l'écrase pas), remplace `dist/`, `src/`, `scripts/`, `assets/` et les fichiers de `config/` livrés, écrit `<données>/essai-backend.json` (PR en cours et empreinte du serveur posé) et redémarre (relancé par le lancement automatique). Jamais touchés : `node/` et le lanceur `Mate.exe` (verrouillés), `.env`, `config/deck.json`, `config/tasks.json`. Un échec en cours de remplacement remet l'origine. `GET /companion/backend` → `{packaged, mode: "origin"|"essai", mr}` ; `POST /companion/backend/origin` restaure la copie, supprime la marque et la copie, redémarre (`{ok, changed}` : sans essai en cours, rien ne se passe). Compagnon lancé depuis le dépôt (`npm run dev`, non installé par paquet) : l'envoi est refusé en 409 avec une phrase en français.

La tablette met l'ordinateur sur le bon backend à trois moments : au choix d'une PR ; à « Ouvrir l'essai » (si l'ordinateur n'est pas déjà sur le backend de la PR installée) ; au retour à la stable, qui restaure l'origine si l'ordinateur est encore sur un backend d'essai (filet si l'essai a planté). Une PR sans backend alors que l'ordinateur est sur celui d'une autre PR : l'origine est restaurée. « Revenir à la stable » dans l'essai restaure avant de rendre la main. Compagnon réinstallé pendant un essai (nouvel installeur) : la marque garde l'empreinte du `dist/server.mjs` posé, l'ordinateur voit que le serveur n'est plus celui de l'essai et oublie l'essai et sa copie d'origine périmée.

Sécurité : l'APK d'essai porte la signature de la clé de release et le jeton de lecture des tablettes, comme une release. La clé n'est jamais affichée ; seuls les jobs de l'environnement `signature` (code de `master` ou d'un tag `v*`) la voient, jamais le code d'une PR (signature à part, ci-dessus). `PR_TITLE` est du texte libre : affiché tel quel sur la tablette, jamais exécuté. L'archive du backend est du code qui tourne sur l'ordinateur : elle vient de la release `essais` de ce dépôt (lecture par jeton), vérifiée par SHA-256 avant l'envoi puis par l'ordinateur ; la route exige le jeton d'appairage.

## Mettre à jour les ordinateurs de test (installés par paquet)

Le PC d'Eliot et le Mac tournent depuis le paquet (pas depuis le dépôt). Le plus simple : « Chercher une mise à jour » dans le panneau (l'appli vient de la tablette), ou lancer la nouvelle appli (elle demande à l'ancienne de quitter, données gardées). Sur Mac, l'Accessibilité reste accordée d'une version à l'autre (chemin de node stable). Sans l'appli, pour aller vite : `cd backend && npm run package`, le dossier `dist/windows/runtime/` (Windows) ou `dist/mac/Mate/Mate.app/Contents/Resources/backend/` (Mac) est le contenu préparé, puis rsync comme ci-dessous.

**PC Windows**, par le partage SMB `Compagnon` (= dossier `Documents\Companion-PC\Companion` du PC) monté sur le Mac :

```bash
rsync -rt --exclude node --exclude .data --exclude .DS_Store "dist/windows/runtime/" <partage monté>/
curl -X POST -H "Authorization: Bearer <jeton du PC>" -H "Content-Type: application/json" -d '{}' http://<ip-du-pc>:3000/companion/restart
```

Le compagnon redémarre seul en ~9 s. Vérifier ensuite une route Windows (`/system/media`, `/windows/open`) : le C# est compilé sur le Mac (`npm run build:helper`, avant `npm run package`), mais rien ne remplace un essai sur Windows.

**Mac** (dossier `~/Documents/Mate-Mac/Mate`, service launchd `app.mate`) :

```bash
rsync -rt --exclude node --exclude .DS_Store "dist/mac/Mate/Mate.app/Contents/Resources/backend/" ~/Documents/Mate-Mac/"Mate"/backend/
launchctl kickstart -k gui/$(id -u)/app.mate
```

Le paquet range le serveur dans `Mate.app/Contents/Resources/backend` ; ce Mac tourne encore sur l'ancien dossier `backend/` (service launchd), d'où ce chemin. Garder le même dossier : l'Accessibilité est accordée au chemin du `node` du paquet. `config/tasks.json` (worklog) est dans le dossier du paquet, rsync ne l'efface pas. Journal : `~/Library/Logs/Mate/backend.log`. `npm run dev` ne peut pas tourner en même temps (port 3000, le service redémarre seul) : pour revenir au dev, `node/darwin-arm64/node dist/service.mjs uninstall` dans le paquet, puis appairer de nouveau (identité du dev dans `backend/.data`). Le serveur MCP `tablette` lit le jeton du paquet (`DATA_DIR` dans `.mcp.json`).

## Agents Claude (hooks)

`cd backend && npm run agents:hooks` affiche le bloc de hooks HTTP à fusionner dans `~/.claude/settings.json` ; `-- --install` le fusionne (sauvegarde `settings.json.bak-agents-<date>` avant) et `-- --remove` le retire, sans toucher aux autres hooks (Orca, scripts perso). `--file <chemin>` vise un autre fichier, `--port` et `--hold <s>` règlent l'adresse et l'attente. `--install --statusline` enveloppe aussi la statusline (seule à recevoir le quota 5 h / 7 j) : sa sortie ne change pas, son entrée part en plus au compagnon (`POST /agents/statusline`, en arrière-plan, `curl`) ; `--remove` rend la statusline d'origine. Le secret et le port viennent des données de CET ordinateur : pour le compagnon installé par paquet, `DATA_DIR="$HOME/Library/Application Support/Mate" npm run agents:hooks`. Chaque dossier de config de Claude a ses propres sessions et son propre `settings.json` : les sessions d'Orca tournent avec le profil `~/Library/Application Support/orca/claude-profiles/<id>/home`, pas `~/.claude`. Le compagnon lit les deux (et `AGENTS_CLAUDE_CONFIG_DIRS`, dossiers séparés par « : ») ; les hooks se posent dans le `settings.json` des sessions à suivre (`npm run agents:hooks` vise `$CLAUDE_CONFIG_DIR`, donc le profil Orca lancé depuis un terminal Orca ; sinon `--file`). Les sessions dont le processus n'existe plus (arrière-plan abandonné) sont ignorées. Sans hook récent, l'état vu par Orca prime pour ses sessions (au prompt mais une surveillance tourne : travaille ; tour fini : fini), et un tour qui s'arrête passe à « fini » ; « fini » reste jusqu'au prompt suivant. Le titre affiché est celui que Claude donne à la session (`ai-title`, lu à la fin de son journal `<config>/projects/<dossier>/<session>.jsonl`, format interne de Claude Code : à revérifier après une mise à jour), avec le ticket et la MR liés (Orca, journal, branche). Sans hooks, l'état se lit quand même (`claude agents --json`) ; répondre depuis la tablette demande les hooks, posés à la main par l'utilisateur. Les sessions déjà ouvertes les prennent au prochain démarrage ; compagnon éteint, chaque hook affiche une erreur de connexion dans le terminal, sans rien bloquer.

Essai sans toucher à son `settings.json` : `npm run agents:hooks -- --install --file /tmp/essai-settings.json --port 3921`, puis `claude --settings /tmp/essai-settings.json` contre un compagnon de test (`HOST=127.0.0.1 PORT=3921 COMPANION_TOKEN= DATA_DIR=/tmp/companion-agents AGENTS_ALWAYS_ALERT=1 npx tsx src/server.ts`). `AGENTS_ALWAYS_ALERT=1` alerte même si un terminal est devant (sinon le compagnon se tait : l'utilisateur est sur Claude) ; `AGENTS_HOLD_S` (90) est l'attente d'une réponse ; `AGENTS_POLL_MS` (4000) le rythme du filet. Pas de tablette : un script qui fait son long-polling sur `/tablet/poll` et répond sur `/agents/answer` suffit. `CLAUDE_BIN` et `ORCA_BIN` forcent les chemins de `claude` et d'`orca` (tests). Mac seulement : `agents` est absent de `/deck/features` ailleurs et `/agents/*` répondent 501.

## Extension de navigateur (onglets et page Média sur la tablette)

`backend/extension/` (Manifest V3, Chromium : Opera, Chrome, Edge, Brave) envoie les onglets du navigateur à `POST http://127.0.0.1:3000/browser/extension` (sans jeton, origine `chrome-extension://` exigée) à chaque changement et chaque minute ; le compagnon les garde en mémoire 3 min (`src/browser-extension.ts`). Le paquet la copie dans « Extension navigateur ». Installer : `opera://extensions` (ou `chrome://extensions`), Mode développeur, « Charger l'extension non empaquetée », choisir ce dossier. Après une mise à jour de l'extension : bouton Recharger sur la même page. Après un redémarrage du compagnon, la liste revient au prochain changement d'onglet (au plus 1 min). Depuis la 1.1.0 (27/10), elle est dans les deux paquets et pilote aussi la lecture (page Média) : elle attend les ordres en long-polling (`POST /browser/extension/commands`, local, origine d'extension exigée, ordre suivant envoyé avec le résultat du précédent, lu dans le journal : `extension : ordre de lecture`), joue ou met en pause le lecteur de l'onglet qui joue, et Suivant/Précédent passent par les actions Media Session du site, gardées par `media-session.js` (script de page, `document_start` : recharger l'onglet après avoir rechargé l'extension).

## Lecture en cours sur Mac (MediaRemote)

`src/mac/NowPlaying.dylib` (arm64 + x86_64) est compilée par `npm run build:nowplaying` (clang des Command Line Tools) depuis `src/mac/nowplaying/NowPlaying.m` et gardée dans le dépôt ; recompiler après toute modification. Elle est chargée par `/usr/bin/perl` (`DynaLoader`), qui fait partie des programmes d'Apple encore admis par MediaRemote depuis macOS 15.4 : une action par lancement (`NP_ACTION` = `get`, `art`, `play-pause`, `next`, `previous`, `seek`). Infos par `MRNowPlayingRequest` (immédiat) ; `MRMediaRemoteGetNowPlayingInfo` ne rappelle pas toujours (Opera en lecture), il ne sert qu'à la pochette, 1,5 s au plus. Essai à la main : `/usr/bin/perl -e '$ENV{NP_ACTION}="get"; require DynaLoader; DynaLoader::dl_load_file($ARGV[0])' "$PWD/src/mac/NowPlaying.dylib"` (chemin absolu obligatoire). En production, signer la bibliothèque (Developer ID) pour ne pas alerter un antivirus d'entreprise.

Icône de la barre des menus du Mac : `src/mac/MateMenu` (arm64 + x86_64, signature ad hoc) est compilée par `npm run build:menubar` (swiftc des Command Line Tools) depuis `src/mac/menubar/MenuBar.swift` et gardée dans le dépôt ; recompiler après toute modification. Le compagnon la lance au démarrage (`src/mac/menubar.ts`, absente des instances `COMPANION_HEADLESS` et `NO_ALERT`) ; elle s'arrête quand son parent disparaît. Windows : même rôle pour le lanceur (`src/win/launcher/Tray.cs`), recompilé par `npm run build:helper`.

## Fabriquer les applis portables (sans installation ni Node à installer)

Sur le Mac (ou sous Linux, comme le job `package` d'une release ; `zip`, `unzip` et `tar` requis), dans `backend/` : `npm run package` (`scripts/package.ts`). Résultat dans `dist/` à la racine (ignoré par git). Rien ne s'installe : ni copie dans Applications, ni Programmes et fonctionnalités, ni clé Run ou LaunchAgent tant que la case « Lancer au démarrage » du panneau n'est pas cochée.

- `Mate-Windows.exe` (~34 Mo) : un seul fichier = le lanceur C# (`src/win/Mate.exe`, `src/win/launcher/Program.cs`) suivi du zip du runtime (node, `dist/server.mjs`, `MateHelper.exe`, config, `firewall-windows.ps1`, extension du navigateur, `runtime.stamp`) et d'un pied de 16 octets (taille du zip, `MATEZIP1`). Au lancement, le lanceur extrait le zip dans `%LOCALAPPDATA%\Mate\runtime` si son `runtime.stamp` diffère de celui en place (`.env` et `.cache` gardés), lance `node.exe` depuis là et le relance s'il s'arrête, puis ouvre le panneau. L'exe lui-même n'est donc jamais verrouillé par node. Déjà lancé avec le même build : il rouvre seulement la page. Autre build en marche : il lui demande de quitter (`POST /local/quit`). `--demarrage` (clé Run) : pas de page ouverte. Les arguments `--node --backend --logs --data` gardent le mode dépôt de `service.ts` ;
- `Mate-Mac.zip` (~77 Mo) : dossier « Mate » avec « Mate.app » (exécutable `backend/portable/mac/launcher.sh`, `Info.plist` écrit par `package.ts`, icône `backend/assets/compagnon.icns`), qui reste où l'utilisateur la met. Au lancement, `launcher.sh` recopie `Contents/Resources/backend` (sans le node de l'autre architecture) dans `~/Library/Application Support/Mate/runtime` quand `runtime.stamp` diffère : chemin de node stable, l'Accessibilité reste accordée d'une version à l'autre et à l'endroit où l'appli est rangée. Il lance une boucle (`--boucle`, détachée) qui relance le serveur ; `--demarrage` (agent posé par le panneau) la garde au premier plan ;
- dans chacune : `backend/dist/server.mjs` (sources regroupées par esbuild, plus de `service.mjs`), `backend/config` (sans `deck.json` ni `tasks.json`), `MateHelper.exe` pour Windows, et le node officiel de nodejs.org (ligne 22, téléchargé une fois dans `backend/.cache/node`, vérifié par SHA-256).

**Panneau** (page locale `http://127.0.0.1:3000/`, `src/local-page.ts`, rouverte en relançant l'appli) : carte « Cette appli » visible seulement lancé par l'appli portable (`COMPANION_APP`, posé par le lanceur ; `routes/portable.ts`, `src/portable.ts`, `src/app-update.ts`) : « Lancer au démarrage » (clé Run `"<exe>" --demarrage` ou LaunchAgent `app.mate.demarrage`, décochée par défaut), pare-feu Windows (`firewall-windows.ps1` : demande d'administrateur à ce moment-là, `-Retirer` pour la règle), « Chercher une mise à jour » puis « Installer » (voir plus bas), « Quitter » (sortie 75) et « Tout oublier » (lancement au démarrage, règle de pare-feu, données et runtime effacés, sortie 76 ; le dossier Partage tablette reste). Les actions sont des `POST` JSON : un autre site ne peut pas les envoyer à `127.0.0.1` sans l'accord du navigateur.

**Mise à jour depuis la tablette** : la tablette sert sur le WiFi, port 8080 (HTTP en clair), les fichiers de chaque appli qu'elle garde ; leur version, taille et SHA-256 partent dans l'état de son poll (clé `installers`, HTTPS et jeton), seule source de l'empreinte attendue. Le panneau lit l'adresse de la tablette (dernier `POST /tablet/poll`, WiFi seulement), prend l'appli de son système, la télécharge, vérifie taille et SHA-256, puis remplace l'exe (renommé `.old`) ou l'appli (zip extrait à côté, ancienne appli en `.old`) et la relance ; le nouveau lanceur demande à l'ancien de quitter, extrait son runtime et efface le `.old`. La page se recharge quand la version change. L'empreinte vient de la tablette, sur le réseau local : elle protège d'un fichier abîmé, pas d'un réseau hostile.

Depuis le dépôt (sans l'appli) : `npm run service:install` dans `backend/`, avec le Node du système (`scripts/service.ts`).

Données d'un ordinateur lancé par l'appli : hors du runtime, dans `%LOCALAPPDATA%\Mate\data` (Windows) ou `~/Library/Application Support/Mate` (Mac) (`src/paths.ts`). Mettre à jour : l'identité est gardée, la tablette retrouve l'ordinateur. Le dossier Partage est `Documents\Partage tablette` (Documents lu dans le registre sous Windows, OneDrive compris). Sur Mac, l'autorisation Accessibilité est donnée au node du runtime. Sur Windows, le pare-feu s'ouvre depuis le panneau au `node.exe` du runtime (règle « Mate », réseaux privés).

Vérifié le 29/10 : build local des deux paquets (structure du zip ajouté à l'exe lue à la main) ; appli Mac lancée depuis un dossier temporaire (extraction du runtime, relance, build différent remplacé, `--demarrage`, lancement au démarrage coché puis décoché, quitter, tout oublier). Pas encore vu : l'exe Windows (à lancer sur le PC de QA avec `backend\qa\install-windows.ps1 -Exe <exe>`), le pare-feu, la mise à jour depuis une vraie tablette. Non signés : la signature Authenticode des exe n'existe pas encore dans le dépôt (SmartScreen : « Informations complémentaires » > « Exécuter quand même ») ; l'appli Mac n'est ni signée ni notarisée (compte Apple Developer requis ; Gatekeeper : Réglages Système > Confidentialité et sécurité > « Ouvrir quand même »).

## Pièges connus

- **Pilote Windows** : C# 7.3, .NET Framework 4.8 (`src/win/helper/`, lanceur dans `src/win/launcher/`), compilé par `npm run build:helper` avec le SDK .NET 8 figé par `backend/global.json` (sur le Mac : `~/.dotnet`, installé par le script officiel `dotnet-install.sh --version <version de global.json>`). `helper-windows` vérifie en CI que les exe du dépôt correspondent aux sources. `npm run package` embarque la version de node fixée dans `scripts/package.ts` (`NODE_VERSION`) : la changer à la main pour mettre node à jour. Les exe compilés sont gardés dans le dépôt : recompiler après toute modification du C#. Jamais de PowerShell lancé par le compagnon (antivirus).
- **Mac** : le compagnon a besoin de l'Accessibilité (pour `node`, ou pour le terminal qui lance `npm run dev`). Sans elle : raccourcis, fenêtres et macros échouent (bandeau sur le Deck).
- Redémarrer le compagnon actif fait basculer la tablette sur un autre ordinateur qui répond. Il faut ensuite revenir à la main (nom en bas de la barre de gauche).
- Le compagnon lancé depuis une session (terminal, éditeur) meurt avec elle. La tablette affiche alors l'ordinateur en rouge et « Aucun événement ».
- Un champ de texte construit dans le code doit être gonflé depuis `item_input.xml` : un `EditText` construit avec un style perd le focus et le curseur.
- Toute saisie dans une boîte de dialogue passe par `showForTyping` : sinon le clavier cache la boîte.

## Avec Claude Code

`CLAUDE.md` à la racine donne à Claude le contexte et les conventions. Il est versionné : il voyage avec le bundle. Les notes de session de Claude, elles, restent sur l'ordinateur où il tourne : ce qui compte est recopié dans [etat-du-projet.md](etat-du-projet.md). Le serveur MCP `tablette` (`.mcp.json`) permet à Claude de piloter la tablette.

**Passe qualité d'un écran** : skill Claude `ecran-design` (versionnée dans `.claude/skills/`) (`critique`, `etats`, `polish`, `distill`, `harden`, `animate`), pour l'appli et les pages du backend ; première passe dans `docs/team/design-companion/premiere-passe.md`.

Le circuit commun (skills, agents, contrôle des couleurs, textes et polices en dur) est décrit dans [claude.md](claude.md).

**Changer d'ordinateur de développement** (par exemple du Mac au PC Windows, avec un autre compte Claude) : commiter, `scripts/sauvegarde.sh`, et joindre les notes de Claude (`~/.claude/projects/<projet>/memory/`, zippées) au bundle et aux données perso. Sur l'autre ordinateur, on clone le bundle, puis on demande à Claude de recopier les notes dans sa propre mémoire. Pour revenir : commiter, `git bundle create retour.bundle --all`, rezipper les notes, puis `git pull retour.bundle master` et fusionner les notes sur l'ordinateur d'origine. Le serveur MCP `tablette` lit les données du paquet installé sur Mac comme sur Windows (`DATA_DIR=paquet` dans `.mcp.json`). Sur Windows, sa commande `backend/node_modules/.bin/tsx` (script shell) peut ne pas démarrer : s'il n'apparaît pas, le déclarer en local avec `claude mcp add`, sans modifier `.mcp.json`.
