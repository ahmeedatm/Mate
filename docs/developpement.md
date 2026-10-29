# Travailler sur le projet

Pour reprendre le code sur un autre ordinateur ou le confier à un collègue. Pour comprendre comment le tout fonctionne : [architecture.md](architecture.md). Pour savoir où on en est : [etat-du-projet.md](etat-du-projet.md).

## Récupérer le projet (sans dépôt distant)

Le projet n'est publié sur aucun serveur git. On le transporte avec un **bundle git**, un fichier unique qui contient tout l'historique.

**Créer la sauvegarde** (sur l'ordinateur qui a le projet) :

```bash
scripts/sauvegarde.sh            # dans ~/Desktop ; ou scripts/sauvegarde.sh /chemin/du/dossier
```

Le script crée deux fichiers :

| Fichier | Contenu | À qui |
|---|---|---|
| `unowhy-companion-AAAA-MM-JJ.bundle` | tout le code et l'historique (commits seulement) | à partager avec les collègues |
| `unowhy-companion-donnees-perso-AAAA-MM-JJ.tgz` | `backend/.env`, `backend/.data/` (jeton d'appairage, agendas reliés, tâches, touches…), deck et tâches perso | **à garder pour soi** : il contient des secrets |

Seul ce qui est commité part dans le bundle : il faut commiter avant.

**Récupérer sur un autre ordinateur** :

```bash
git clone unowhy-companion-2026-10-24.bundle unowhy-companion
cd unowhy-companion
tar xzf ../unowhy-companion-donnees-perso-2026-10-24.tgz   # seulement pour retrouver SES données
```

**Mettre à jour une copie existante** avec un bundle plus récent : `git pull ../unowhy-companion-AAAA-MM-JJ.bundle main`.

Plus tard, pour publier : `git remote add origin <url>` puis `git push -u origin main`. L'historique est conservé.

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

## Branches, MR et CI

`main` est protégée : on n'y pousse plus directement. Une branche par changement (`feat/…`, `fix/…`, `docs/…`, `ci/…`), puis une MR vers `main`. Elle se fusionne quand le pipeline est vert et les discussions résolues.

Le pipeline (`.gitlab-ci.yml`) reste court : seulement des jobs rapides, sur le runner Linux. Rien de lourd en MR (ni émulateur, ni installation sur un vrai ordinateur, ni compilation Windows) ; ce qui sert à la release tourne sur un tag.

| Job | Vérifie |
|---|---|
| `backend` | `npm run lint` (format prettier, puis typecheck), puis `npm run coverage` : tests unitaires et d'intégration (`backend/test/`) sous seuil de couverture (voir plus bas), le vrai serveur démarre avec un dossier de données vide et répond à de vraies requêtes HTTP ; le pilotage de l'ordinateur y est simulé (voir plus bas). Comprend la sanity (`test/sanity.test.ts`) |
| `e2e` | pages web du backend dans Chromium (Playwright, `backend/e2e/`) : page locale (adresse, saisie du code d'appairage, lien ICS), visualiseur `/camera`, retours d'erreur de `/oauth/:provider/callback`. Même serveur que les tests d'intégration. Artefacts : `e2e-results/junit.xml` et traces des tests en échec |
| `android` | style Kotlin (`ktlintCheck`), tests JVM (`app/src/test/` : `Computers`, `Prefs`, `Connection` contre de petits serveurs HTTP locaux, `FocusTimer`, `Water`, ordre `focus`, modes, widgets, commande vocale ; contexte simulé `FakeContext`), APK debug (téléchargeable dans les artefacts du job, à installer sur la tablette) |
| `zones` | rien : garde un pipeline aux MR qui ne touchent aucune zone (sans pipeline, la MR ne fusionne pas) |

**Ciblage par zone.** En MR, un job ne tourne que si sa zone a bougé (`rules:changes`, ancres `.files-*` en tête du fichier). Sur `main` et sur un tag, tout tourne. Toucher `.gitlab-ci.yml` relance tout.

| Zone | Fichiers | Jobs |
|---|---|---|
| backend | `backend/**` | `backend`, `e2e` |
| android | `app/**`, `*.gradle*`, `gradle/**`, `gradlew*` | `android` |

Sur un tag `vX.Y.Z` seulement : `package-windows`, `package-mac`, `helper-windows`, `android-release`, `updates` et `release` (section Release).

**Tests d'installation, à la main.** Plus en CI. À lancer avant une release ou quand l'installation, `backend/src/mac/`, `backend/src/win/`, `os.ts` ou `service.ts` changent :

- Mac : `cd backend && npm run package -- mac && cd .. && backend/qa/install-mac.sh run dist/Compagnon-Unowhy-Mac.zip` (instance de test isolée du vrai compagnon : agent, port, données à part ; `install-mac.sh cleanup` après un échec).
- Windows : `powershell -ExecutionPolicy Bypass -File backend\qa\install-windows.ps1 -Installeur <exe> -Root <dossier> -Port <port>` (installation silencieuse, lancement automatique, sanity, pilote, désinstalleur ; `-Nettoyage` ensuite). Sur le PC du runner 976, toujours un autre `-Root` et un autre `-Port` que les valeurs par défaut.
- Pilote Windows : `cd backend && npm run build:helper` après un changement de `src/win/helper/` ou `src/win/launcher/`, puis commiter les exe. `helper-windows` (tag seulement) échoue si les exe du dépôt ne sont plus ceux des sources.

Le runner Mac 1584 (`scripts/runner-mac.sh`) n'a plus de job : `sudo scripts/runner-mac.sh retour --supprimer` le retire du Mac.

**Sanity** (`backend/qa/sanity.ts`) : un contrôle par feature contre un compagnon réel déjà démarré : santé et identité, appairage par code (sans boîte avec `COMPANION_HEADLESS=1` ou `NO_ALERT=1`), deck et deck de départ filtré aux applis installées, son, fenêtres ouvertes, refus d'un `.exe` par `/docs/open`, presse-papiers (écrit un texte accentué, le relit à l'identique et remet l'ancien texte ; passe son tour seulement sur un 503 « pas de session de bureau » ; image, contenu protégé, autre format et texte coupé sont en lecture seule : le contrôle n'écrit jamais dessus), tâches, agenda personnel, agenda ICS (fichier servi en local, seulement si aucun agenda n'est relié), `/camera`, page locale. Réponse attendue selon le système : hors Mac et Windows, le deck se lit et le reste du pilotage répond 501. Ce qu'il crée est retiré à la fin. Sans dépendance : le node du paquet le lance tel quel.

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

Ce que la CI ne voit pas : l'appli Android lancée (ni émulateur ni tablette en CI : écrans et parcours se vérifient par l'essai sur la tablette), l'installation et le pilotage réels du Mac et de Windows (simulés dans les tests), la vraie tablette (perfs, kiosque, micro, caméra). Ils restent testés à la main (case « Testé sur » de la MR).

## Release (tag vX.Y.Z)

**Mentions de licences** (obligatoires pour vendre, carte COMP-51) : l'appli affiche `app/src/main/assets/licences.txt` (Réglages > À propos > Licences des composants), à régénérer après tout ajout ou changement de dépendance Android : `node scripts/licences-android.mjs` (licences lues dans les POM du cache Gradle ; le script s'arrête sur une licence autre qu'Apache 2.0, à traiter à la main ; `LicencesTest` échoue si une dépendance de `app/build.gradle.kts` manque). Côté ordinateur, rien à faire : `npm run package` écrit `LICENCES-TIERS.txt` (paquets npm réellement regroupés par esbuild, texte de leur licence, licence de node) dans le dossier installé.

Un tag `vX.Y.Z` posé sur `main` lance les mêmes tests, puis :

| Job | Produit |
|---|---|
| `package-windows`, `package-mac` | `Compagnon-Unowhy-Windows.exe` (installeur NSIS) et `Compagnon-Unowhy-Mac.zip` (appli) (`npm run package -- windows` ou `mac` sur le runner Linux, un job par plateforme : artefacts sous 100 Mo ; version = le tag) |
| `android-release` | `companion-X.Y.Z.apk` signé, et `companion-X.Y.Z.json` : son entrée pour le « serveur Unowhy » |
| `package-backend` | `backend-mac.tar.gz` et `backend-windows.tar.gz` (`npm run package -- essai` sur le tag : backend sans node ni lanceur, version = le tag), pour la mise à jour de l'ordinateur depuis la tablette |
| `updates` | serveur central des mises à jour de la tablette (registre de paquets du projet) : `manifest.json`, l'APK de la dernière version, les deux installeurs de l'ordinateur et les deux archives du backend |
| `release` | release GitLab du tag, liens vers les fichiers des jobs (gardés : dernière pipeline réussie du tag) |

```bash
git tag v1.2.0 origin/main && git push origin v1.2.0
```

Version de l'appli : `versionName` = `X.Y.Z` du tag, `versionCode` = (X × 10000 + Y × 100 + Z) × 1000 (Y et Z sous 100), donnés à Gradle par `-PappVersion=X.Y.Z`. Les 999 codes entre deux tags servent aux essais depuis une MR (`-PappVersion=X.Y.Z-essai.N` : code du dernier tag + 1 + N mod 999). Sans ce paramètre : `0.0.0` (code 1, Android refuse 0). La tablette n'installe une mise à jour que si son `versionCode` est plus grand : chaque tag doit être supérieur au précédent.

Numérotation : `v0.0.1` (code 1) et `v0.0.2` (2) ont été publiés avant le × 1000 ; la suite part de `v0.0.3` (3000), toujours au-dessus. `v0.1.0` = 100000. Pas de `v1.0.0` tant qu'il n'y a pas de MVP concret. Vérifié en local (manifeste fusionné) : `0.0.3` → 3000, `0.1.0` → 100000, `0.0.2-essai.169` → 2170, sans paramètre → 1. Attention : un APK construit sans `-PappVersion` porte le code par défaut de `app/build.gradle.kts`, pas celui d'un tag : la tablette refuse toute mise à jour dont le code n'est pas strictement plus grand que celui de l'APK installé.

**Clé de signature**, jamais dans le dépôt. Variables CI du projet, protégées et masquées : `RELEASE_KEYSTORE_BASE64` (fichier PKCS12 en base64, sur une ligne) et `RELEASE_KEYSTORE_PASSWORD` (mot de passe du fichier et de la clé, alias `companion`). Une variable protégée n'existe que pour une branche ou un tag protégé : protéger les tags `v*` (Settings > Repository > Protected tags), sinon `android-release` échoue (« Clé de signature absente »). Créer la clé une fois, puis la garder hors du dépôt (perdue = plus aucune mise à jour possible des tablettes) :

```bash
keytool -genkeypair -storetype PKCS12 -keystore companion-release.p12 -alias companion -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Unowhy Companion"
base64 -i companion-release.p12 | tr -d '\n'   # valeur de RELEASE_KEYSTORE_BASE64
```

Sur un poste de dev, `./gradlew assembleRelease` sans `RELEASE_KEYSTORE` donne un APK release non signé. Avec : `RELEASE_KEYSTORE=chemin.p12 RELEASE_KEYSTORE_PASSWORD=… ./gradlew assembleRelease -PappVersion=1.2.0`.

**Serveur central des mises à jour** : le projet reste privé. Le job `updates` dépose les fichiers dans le paquet générique `companion-updates`, version `latest`, du registre de paquets du projet (jeton du job). Base : `https://gitlab.unowhy.dev/api/v4/projects/891/packages/generic/companion-updates/latest` (`UPDATES_URL` dans `.gitlab-ci.yml`).

- `GET <base>/manifest.json` → `{"updates":[{"package":"com.unowhy.companion","label":"Companion","versionCode":N,"versionName":"X.Y.Z","file":"companion-X.Y.Z.apk","size":octets,"sha256":"…"}]}`, même forme que `GET /updates` du backend ;
- `GET <base>/companion-X.Y.Z.apk` → l'APK signé.

La tablette lit avec l'en-tête `Deploy-Token: <jeton>` : deploy token « tablettes-mises-a-jour », lecture seule du registre (`read_package_registry`), variable CI `UPDATE_DEPLOY_TOKEN` protégée et masquée. `android-release` le donne à Gradle (`-PupdateUrl`, `-PupdateHeader`, jamais affiché) : il est dans l'APK, donc lisible par qui a l'APK, et ne permet que de télécharger les paquets du projet. Le révoquer = plus aucune mise à jour pour les tablettes déjà installées.

`scripts/updates-publish.mjs` (à la racine) lit l'entrée `companion-X.Y.Z.json` d'`android-release`, calcule taille et SHA-256, dépose l'APK, les installeurs trouvés dans `dist/` (`Compagnon-Unowhy-Mac.zip`, `Compagnon-Unowhy-Windows.exe`, fournis par `package-mac` et `package-windows`) puis `manifest.json` (le manifest ne désigne jamais un fichier absent), puis supprime les anciens fichiers du paquet : seule la dernière version reste, installeurs compris. Le manifest décrit les installeurs dans une clé `installers` à côté de `updates` : `[{"os":"mac"|"windows","file":…,"size":…,"sha256":…,"version":"X.Y.Z"}]` (absente d'un essai). Test : `backend/test/updates-publish.test.ts`. Un même nom déposé deux fois se télécharge dans sa dernière version (vérifié le 27/10 sur un paquet d'essai). Essai local sans dépôt : `node scripts/updates-publish.mjs dist updates` ; avec dépôt, `UPDATES_URL=<base d'un paquet d'essai>` et `GITLAB_TOKEN` (jeton personnel).

**Compagnon mis à jour depuis la tablette** : les archives de `package-backend` sont publiées sous `backend-X.Y.Z-<os>.tar.gz`, clé `backends` du manifest (même forme que `installers`). Le « serveur Unowhy » de l'ordinateur peut aussi en servir (`npm run updates:add -- backend-X.Y.Z-mac.tar.gz`, clé `backends` de `GET /updates`) ; la tablette garde la plus récente des deux sources. Elle compare à la version de l'ordinateur actif (`/health` : `version`, `backendUpdate` = sait se mettre à jour ainsi), télécharge l'archive de son système, vérifie taille et SHA-256 et l'envoie par `POST /companion/backend/update?version=&sha256=` (jeton d'appairage). L'ordinateur refuse lancé depuis le dépôt ou si la version n'est pas plus récente (409), pose l'archive (`applyUpdate` de `src/essai-backend.ts` : mêmes dossiers gérés que l'essai, copie temporaire remise si une copie échoue, essai de MR posé oublié) et redémarre ; la tablette attend qu'il revienne dans la nouvelle version (2 min). Proposé par Réglages > Mises à jour (ligne du compagnon et bouton) et par le bandeau « Nouvelle version du compagnon » ; rien n'est proposé par la stable tant qu'un essai de MR est posé. Un compagnon sans `backendUpdate` (installé avant) reçoit l'installeur une dernière fois (étapes ci-dessous). Node et le lanceur ne sont pas dans l'archive : un changement de l'un d'eux repasse par l'installeur. Logique dans `installers/BackendPlan` (testée) et `installers/CompanionUpdater`.

**Installeurs servis par la tablette** : au démarrage puis tous les quarts d'heure (WiFi seulement), la tablette lit la clé `installers` du manifest central. Un installeur absent ou dont le SHA-256 a changé est téléchargé dans `.part`, vérifié, puis renommé : seule la dernière version est gardée (`filesDir/installers`, état dans `installed.json`). Un petit serveur HTTP (`installers/InstallerHttpServer`, sans dépendance, port 8080, démarré par `MainActivity`, pas dans l'appli d'essai) répond sur le WiFi : `GET /` = page qui détecte l'OS du visiteur et propose son installeur en premier, `GET /<fichier>` = le fichier. Avant le premier téléchargement, la page dit qu'ils arrivent. Écran Bienvenue et Réglages > Ordinateurs affichent `http://<ip de la tablette>:8080`. Le compagnon donne sa version dans `/health` (`version` : tag du paquet, `dev` depuis le dépôt, absente avant cette version) ; tous les quarts d'heure, si l'installeur gardé pour son OS est plus récent (ou sa version absente), un bandeau le dit, une fois par ordinateur et par version ; le toucher ouvre les étapes de mise à jour (Mac ou Windows) avec un bouton qui ouvre la page 8080 sur l'ordinateur, puis la tablette vérifie chaque minute et dit « Compagnon à jour ». La page 8080 a une ligne « Déjà installé ? » par installeur. Essai sans release : `node scripts/updates-publish.mjs dist updates` avec les deux installeurs dans `dist/`, `python3 -m http.server 8099 -d updates` (accessible de l'émulateur en `10.0.2.2`), build avec `-PupdateUrl=http://10.0.2.2:8099`, puis `adb forward tcp:8080 tcp:8080` et `http://localhost:8080` sur le Mac.

**Alimenter le « serveur Unowhy » d'un ordinateur** (mise à jour de la tablette depuis l'ordinateur, sans le serveur central) : télécharger de la release `companion-X.Y.Z.apk` et `companion-X.Y.Z.json` dans un même dossier, puis sur l'ordinateur, dans `backend/` :

```bash
npm run updates:add -- ~/Téléchargements/companion-1.2.0.json                  # données du dépôt (backend/.data)
DATA_DIR=paquet npm run updates:add -- ~/Téléchargements/companion-1.2.0.json  # compagnon installé par paquet
```

`scripts/updates.ts` copie l'APK dans `<données>/updates/` et remplace la version précédente du Companion dans `manifest.json` ; les autres entrées (WebView) restent. La tablette la voit dans Réglages > Mises à jour.

Changement de signature : Android refuse une mise à jour signée par une autre clé. Une tablette qui a reçu l'APK debug (adb, artefact du job `android`) n'acceptera l'APK de release qu'après désinstallation du Companion, donc, en kiosque, après avoir refait la procédure de [kiosque.md](kiosque.md) avec l'APK de release.

### Déployer un essai depuis une MR

Pour essayer l'appli d'une MR (et son backend) sur une tablette en kiosque, sans fusionner ni tagger. Plusieurs MR peuvent être en essai en même temps ; on choisit celle à installer sur la tablette. L'essai est une **autre appli**, `com.unowhy.companion.essai` « Companion essai », installée à côté de la stable (une seule appli d'essai : choisir une MR remplace la précédente) : la stable reste device owner et kiosque, et on revient à elle sans rien réinstaller. Il doit être signé par la clé de release (même clé que la stable : sinon pas d'ordinateurs repris) : seul un pipeline de ref protégée la voit.

1. Une fois : protéger les branches `essai/*` (Settings > Repository > Protected branches, Allowed to merge et Allowed to push : Maintainers).
2. Publier : `.claude/skills/ship/essai.sh` depuis la branche de la MR (étape 6 de `/ship`). Il pousse la branche sur `essai/mr-<iid>` avec le titre de la MR en variable de pipeline `MR_TITLE` (push option `ci.variable`, ou `variables` de l'API si la branche y est déjà), lance le job manuel `essai-android`, attend `essai-updates` et lit l'entrée de la MR dans le manifest. À la main : `git push -o ci.variable="MR_TITLE=<titre>" origin HEAD:essai/mr-<iid>`, puis jouer `essai-android` (Build > Pipelines) ; `essai-backend` et `essai-updates` suivent seuls. Push refusé (MR rebasée, branche protégée sans force push) : supprimer la branche dans Code > Branches, puis repousser.
3. Sur la tablette, dans la stable : Réglages > Mises à jour > Rechercher. La liste « Essais de MR » montre une ligne par MR (« !109 titre », version, backend de l'ordinateur remplacé ou non) ; choisir une MR installe son essai à la place du précédent (silencieux, device owner), puis, si la MR touche le backend, envoie son archive à l'ordinateur. « Ouvrir l'essai » apparaît à côté de Rechercher. L'essai démarre avec les ordinateurs de la stable, verrouillé à l'écran comme elle ; dans l'essai, Réglages > Mises à jour > « Revenir à la stable » remet le backend d'origine de l'ordinateur, ferme l'essai et rouvre la stable.
4. Fusion ou abandon : `.claude/skills/ship/essai.sh --retire <iid>` (supprime la branche `essai/mr-<iid>` et l'entrée du manifest ; `GITLAB_TOKEN`, scope `api`, dans l'environnement).

Limites : l'essai n'est pas device owner. Mises à jour, WiFi et carte Kiosque ne s'y testent pas (ils se règlent dans la stable) ; il garde ses propres réglages (thème, Accueil) et, une fois ses ordinateurs repris, sa propre liste. Kiosque quitté dans la stable (carte Kiosque) : l'essai s'ouvre sans verrouillage. Un seul essai installé à la fois. La tablette et ses ordinateurs doivent avoir ces fonctions (liste des essais, bascule du backend) en version stable avant de pouvoir choisir une MR : la mise à jour d'un ordinateur est à faire avant, comme les autres (section suivante).

Version : `X.Y.Z-essai.N`, X.Y.Z = dernier tag de la branche, N = numéro du pipeline ; code = celui du tag + 1 + N mod 999 (`app/build.gradle.kts`). Le suffixe `-essai.N` fait l'appli d'essai (`applicationIdSuffix ".essai"`, libellé, `BuildConfig.TRIAL`) ; la CI lit paquet et libellé dans l'APK (`aapt2 dump badging`) et refuse un essai qui porterait le paquet de la stable. Une MR au pipeline plus ancien que l'essai installé a un code plus bas, qu'Android refuse : la stable désinstalle d'abord l'essai (silencieux, device owner ; ses données partent, il reprend les ordinateurs de la stable), puis installe. Tous les 999 pipelines, le code repart du bas : même cas. Essai local sur l'émulateur : `./gradlew assembleDebug -PappVersion=0.0.3-essai.1`, à côté d'une stable debug (même clé debug).

**Paquet `companion-updates/essais`** (paquet générique du registre, jamais `latest` : aucune tablette ne le lit seule). `GET <base>/manifest.json` → `{"essais":[{"mr":109,"title":"…","package":"com.unowhy.companion.essai","label":"Companion essai","versionCode":N,"versionName":"X.Y.Z-essai.N","file":"companion-X.Y.Z-essai.N.apk","size":octets,"sha256":"…","backend":{"mac":{"file":"backend-X.Y.Z-essai.N-mac.tar.gz","size":…,"sha256":"…"},"windows":{…}}}]}`, une entrée par MR ; `backend` seulement si `backend/` diffère de `main`. La tablette le lit avec le jeton de lecture déjà injecté au build (`BuildConfig.UPDATE_URL` avec `/latest` remplacé par `/essais`, `UPDATE_HEADER`), sans passer par l'ordinateur.

Jobs, sur `essai/mr-<iid>` protégée seulement :

| Job | Rôle |
|---|---|
| `essai-android` (manuel) | APK signé, entrée `companion-X.Y.Z-essai.N.json` ; écrit `dist/backend-changed` si `git diff origin/main...HEAD -- backend/` n'est pas vide |
| `essai-backend` | seulement si `dist/backend-changed` : `npm run package -- essai` fait `dist/backend-mac.tar.gz` et `dist/backend-windows.tar.gz` (le backend de chaque plateforme, sans node ni lanceur Windows, sans téléchargement) |
| `essai-updates` | `node scripts/updates-publish.mjs --essai <iid> dist updates` : lit le manifest du registre, remplace l'entrée de cette MR, dépose les fichiers puis `manifest.json`, supprime les fichiers que le manifest ne nomme plus. `resource_group: essais` : les publications se suivent (lecture puis écriture) |

`scripts/updates-publish.mjs --retire <iid> <sortie>` retire l'entrée d'une MR (fusion ou abandon, par `essai.sh --retire`) ; il n'est pas dans le `resource_group` : à lancer quand aucun essai ne se publie. Testé contre un faux registre : `backend/test/updates-publish.test.ts`.

**Backend de la MR sur l'ordinateur** (`backend/src/essai-backend.ts`, routes de `routes/updates.ts`). La tablette télécharge l'archive de la plateforme de l'ordinateur actif (`os` de l'ordinateur), vérifie taille et SHA-256, puis l'envoie à l'ordinateur : `POST /companion/backend/essai?mr=<iid>&sha256=<empreinte>` (corps `application/gzip`, jeton d'appairage ; aucun secret ne va à l'ordinateur, qui ne télécharge rien). L'ordinateur vérifie l'empreinte et le contenu (`dist/server.mjs` et le fichier propre à sa plateforme), extrait à part (`tar`, présent sur Mac et sous Windows 10), prend une copie de son backend installé dans `<données>/backend-origin` (une seule, celle d'origine : un essai suivant ne l'écrase pas), remplace `dist/`, `src/`, `scripts/`, `assets/` et les fichiers de `config/` livrés, écrit `<données>/essai-backend.json` (MR en cours et empreinte du serveur posé) et redémarre (relancé par le lancement automatique). Jamais touchés : `node/` et le lanceur `UnowhyCompanion.exe` (verrouillés), `.env`, `config/deck.json`, `config/tasks.json`. Un échec en cours de remplacement remet l'origine. `GET /companion/backend` → `{packaged, mode: "origin"|"essai", mr}` ; `POST /companion/backend/origin` restaure la copie, supprime la marque et la copie, redémarre (`{ok, changed}` : sans essai en cours, rien ne se passe). Compagnon lancé depuis le dépôt (`npm run dev`, non installé par paquet) : l'envoi est refusé en 409 avec une phrase en français.

La tablette met l'ordinateur sur le bon backend à trois moments : au choix d'une MR ; à « Ouvrir l'essai » (si l'ordinateur n'est pas déjà sur le backend de la MR installée) ; au retour à la stable, qui restaure l'origine si l'ordinateur est encore sur un backend d'essai (filet si l'essai a planté). Une MR sans backend alors que l'ordinateur est sur celui d'une autre MR : l'origine est restaurée. « Revenir à la stable » dans l'essai restaure avant de rendre la main. Compagnon réinstallé pendant un essai (nouvel installeur) : la marque garde l'empreinte du `dist/server.mjs` posé, l'ordinateur voit que le serveur n'est plus celui de l'essai et oublie l'essai et sa copie d'origine périmée.

Sécurité : l'APK d'essai porte la clé de release et le deploy token des tablettes, comme une release. Les jobs ne tournent que sur une branche protégée (`$CI_COMMIT_REF_PROTECTED`, sinon pas de job), la clé n'est jamais affichée, l'artefact expire au bout d'un jour. Sur une branche `essai/*`, aucun autre job ne tourne (les tests sont ceux de la MR). La variable `MR_TITLE` est du texte libre : affichée telle quelle sur la tablette, jamais exécutée. L'archive du backend est du code qui tourne sur l'ordinateur : elle vient du registre de ce projet (lecture par jeton) et de la CI, vérifiée par SHA-256 avant l'envoi puis par l'ordinateur ; la route exige le jeton d'appairage.

## Mettre à jour les ordinateurs de test (installés par paquet)

Le PC d'Eliot et le Mac tournent depuis le paquet (pas depuis le dépôt). Le plus simple : relancer le nouvel installeur (Windows : `Compagnon-Unowhy-Windows.exe`, même dossier, données gardées ; Mac : ouvrir la nouvelle appli, elle remplace celle d'Applications et redémarre le compagnon). Un ordinateur encore installé par un ancien zip passe à l'installeur de la même façon : le lancement automatique est repris, l'identité gardée ; sur Mac, l'Accessibilité est à redonner une fois (nouveau chemin de node), puis elle reste d'une version à l'autre. Sans installeur, pour aller vite : `cd backend && npm run package`, le dossier `dist/<windows|mac>/Compagnon Unowhy/` est le contenu préparé, puis rsync comme ci-dessous.

**PC Windows**, par le partage SMB `Compagnon` (= dossier `Documents\Compagnon-Unowhy-PC\Compagnon Unowhy` du PC) monté sur le Mac :

```bash
rsync -rt --exclude node --exclude .data --exclude .DS_Store "dist/windows/Compagnon Unowhy/" <partage monté>/
curl -X POST -H "Authorization: Bearer <jeton du PC>" -H "Content-Type: application/json" -d '{}' http://<ip-du-pc>:3000/companion/restart
```

Le compagnon redémarre seul en ~9 s. Vérifier ensuite une route Windows (`/system/media`, `/windows/open`) : le C# est compilé sur le Mac (`npm run build:helper`, avant `npm run package`), mais rien ne remplace un essai sur Windows.

**Mac** (dossier `~/Documents/Compagnon-Unowhy-Mac/Compagnon Unowhy`, service launchd `com.unowhy.companion`) :

```bash
rsync -rt --exclude node --exclude .DS_Store "dist/mac/Compagnon Unowhy/Compagnon Unowhy.app/Contents/Resources/backend/" ~/Documents/Compagnon-Unowhy-Mac/"Compagnon Unowhy"/backend/
launchctl kickstart -k gui/$(id -u)/com.unowhy.companion
```

Le paquet range le serveur dans `Compagnon Unowhy.app/Contents/Resources/backend` ; ce Mac tourne encore sur l'ancien dossier `backend/` (service launchd), d'où ce chemin. Garder le même dossier : l'Accessibilité est accordée au chemin du `node` du paquet. `config/tasks.json` (worklog) est dans le dossier du paquet, rsync ne l'efface pas. Journal : `~/Library/Logs/UnowhyCompanion/backend.log`. `npm run dev` ne peut pas tourner en même temps (port 3000, le service redémarre seul) : pour revenir au dev, `node/darwin-arm64/node dist/service.mjs uninstall` dans le paquet, puis appairer de nouveau (identité du dev dans `backend/.data`). Le serveur MCP `tablette` lit le jeton du paquet (`DATA_DIR` dans `.mcp.json`).

## Agents Claude (hooks)

`cd backend && npm run agents:hooks` affiche le bloc de hooks HTTP à fusionner dans `~/.claude/settings.json` ; `-- --install` le fusionne (sauvegarde `settings.json.bak-agents-<date>` avant) et `-- --remove` le retire, sans toucher aux autres hooks (Orca, scripts perso). `--file <chemin>` vise un autre fichier, `--port` et `--hold <s>` règlent l'adresse et l'attente. `--install --statusline` enveloppe aussi la statusline (seule à recevoir le quota 5 h / 7 j) : sa sortie ne change pas, son entrée part en plus au compagnon (`POST /agents/statusline`, en arrière-plan, `curl`) ; `--remove` rend la statusline d'origine. Le secret et le port viennent des données de CET ordinateur : pour le compagnon installé par paquet, `DATA_DIR="$HOME/Library/Application Support/UnowhyCompanion" npm run agents:hooks`. Chaque dossier de config de Claude a ses propres sessions et son propre `settings.json` : les sessions d'Orca tournent avec le profil `~/Library/Application Support/orca/claude-profiles/<id>/home`, pas `~/.claude`. Le compagnon lit les deux (et `AGENTS_CLAUDE_CONFIG_DIRS`, dossiers séparés par « : ») ; les hooks se posent dans le `settings.json` des sessions à suivre (`npm run agents:hooks` vise `$CLAUDE_CONFIG_DIR`, donc le profil Orca lancé depuis un terminal Orca ; sinon `--file`). Les sessions dont le processus n'existe plus (arrière-plan abandonné) sont ignorées. Sans hook récent, l'état vu par Orca prime pour ses sessions (au prompt mais une surveillance tourne : travaille ; tour fini : fini), et un tour qui s'arrête passe à « fini » ; « fini » reste jusqu'au prompt suivant. Le titre affiché est celui que Claude donne à la session (`ai-title`, lu à la fin de son journal `<config>/projects/<dossier>/<session>.jsonl`, format interne de Claude Code : à revérifier après une mise à jour), avec le ticket et la MR liés (Orca, journal, branche). Sans hooks, l'état se lit quand même (`claude agents --json`) ; répondre depuis la tablette demande les hooks, posés à la main par l'utilisateur. Les sessions déjà ouvertes les prennent au prochain démarrage ; compagnon éteint, chaque hook affiche une erreur de connexion dans le terminal, sans rien bloquer.

Essai sans toucher à son `settings.json` : `npm run agents:hooks -- --install --file /tmp/essai-settings.json --port 3921`, puis `claude --settings /tmp/essai-settings.json` contre un compagnon de test (`HOST=127.0.0.1 PORT=3921 COMPANION_TOKEN= DATA_DIR=/tmp/companion-agents AGENTS_ALWAYS_ALERT=1 npx tsx src/server.ts`). `AGENTS_ALWAYS_ALERT=1` alerte même si un terminal est devant (sinon le compagnon se tait : l'utilisateur est sur Claude) ; `AGENTS_HOLD_S` (90) est l'attente d'une réponse ; `AGENTS_POLL_MS` (4000) le rythme du filet. Pas de tablette : un script qui fait son long-polling sur `/tablet/poll` et répond sur `/agents/answer` suffit. `CLAUDE_BIN` et `ORCA_BIN` forcent les chemins de `claude` et d'`orca` (tests). Mac seulement : `agents` est absent de `/deck/features` ailleurs et `/agents/*` répondent 501.

## Extension de navigateur (onglets et page Média sur la tablette)

`backend/extension/` (Manifest V3, Chromium : Opera, Chrome, Edge, Brave) envoie les onglets du navigateur à `POST http://127.0.0.1:3000/browser/extension` (sans jeton, origine `chrome-extension://` exigée) à chaque changement et chaque minute ; le compagnon les garde en mémoire 3 min (`src/browser-extension.ts`). Le paquet la copie dans « Extension navigateur ». Installer : `opera://extensions` (ou `chrome://extensions`), Mode développeur, « Charger l'extension non empaquetée », choisir ce dossier. Après une mise à jour de l'extension : bouton Recharger sur la même page. Après un redémarrage du compagnon, la liste revient au prochain changement d'onglet (au plus 1 min). Depuis la 1.1.0 (27/10), elle est dans les deux paquets et pilote aussi la lecture (page Média) : elle attend les ordres en long-polling (`POST /browser/extension/commands`, local, origine d'extension exigée, ordre suivant envoyé avec le résultat du précédent, lu dans le journal : `extension : ordre de lecture`), joue ou met en pause le lecteur de l'onglet qui joue, et Suivant/Précédent passent par les actions Media Session du site, gardées par `media-session.js` (script de page, `document_start` : recharger l'onglet après avoir rechargé l'extension).

## Lecture en cours sur Mac (MediaRemote)

`src/mac/NowPlaying.dylib` (arm64 + x86_64) est compilée par `npm run build:nowplaying` (clang des Command Line Tools) depuis `src/mac/nowplaying/NowPlaying.m` et gardée dans le dépôt ; recompiler après toute modification. Elle est chargée par `/usr/bin/perl` (`DynaLoader`), qui fait partie des programmes d'Apple encore admis par MediaRemote depuis macOS 15.4 : une action par lancement (`NP_ACTION` = `get`, `art`, `play-pause`, `next`, `previous`, `seek`). Infos par `MRNowPlayingRequest` (immédiat) ; `MRMediaRemoteGetNowPlayingInfo` ne rappelle pas toujours (Opera en lecture), il ne sert qu'à la pochette, 1,5 s au plus. Essai à la main : `/usr/bin/perl -e '$ENV{NP_ACTION}="get"; require DynaLoader; DynaLoader::dl_load_file($ARGV[0])' "$PWD/src/mac/NowPlaying.dylib"` (chemin absolu obligatoire). En production, signer la bibliothèque (Developer ID) pour ne pas alerter un antivirus d'entreprise.

Icône de la barre des menus du Mac : `src/mac/UnowhyMenu` (arm64 + x86_64, signature ad hoc) est compilée par `npm run build:menubar` (swiftc des Command Line Tools) depuis `src/mac/menubar/MenuBar.swift` et gardée dans le dépôt ; recompiler après toute modification. Le compagnon la lance au démarrage (`src/mac/menubar.ts`, absente des instances `COMPANION_HEADLESS` et `NO_ALERT`) ; elle s'arrête quand son parent disparaît. Windows : même rôle pour le lanceur (`src/win/launcher/Tray.cs`), recompilé par `npm run build:helper`.

## Fabriquer les installeurs (sans Node à installer)

Sur le Mac (ou sous Linux, comme le job `package` d'une release ; `zip`, `unzip`, `tar` et `makensis` requis : `brew install makensis`, `apt-get install nsis`), dans `backend/` : `npm run package` (`scripts/package.ts`). Résultat dans `dist/` à la racine (ignoré par git) :

- `Compagnon-Unowhy-Windows.exe` (~23 Mo) : installeur NSIS (`backend/installer/windows/compagnon.nsi`), par utilisateur, sans droits d'administrateur, dans `%LOCALAPPDATA%\Programs\Compagnon Unowhy`. Il arrête le compagnon de ce dossier s'il tourne (mise à jour), copie, s'inscrit dans Programmes et fonctionnalités (« Compagnon Unowhy », désinstalleur `Desinstaller.exe`), ouvre le pare-feu (seule demande d'administrateur), puis `dist\service.mjs install`. Le désinstalleur retire le lancement automatique et les fichiers posés ; les données restent (`%LOCALAPPDATA%\UnowhyCompanion\data`), une réinstallation retrouve la tablette ;
- `Compagnon-Unowhy-Mac.zip` (~77 Mo) : « Compagnon Unowhy.app » (exécutable `backend/installer/mac/launcher.sh`, `Info.plist` écrit par `package.ts`, icône `backend/assets/compagnon.icns`, rendue depuis `compagnon.svg` avec la marge de la grille macOS) et « Extension navigateur » à côté. Au premier lancement, l'appli se copie dans `/Applications` (sinon `~/Applications`) et s'ouvre de là : chemin de node stable, l'Accessibilité reste accordée d'une version à l'autre. Relancée alors que le même build tourne (`Info.plist` comparé à la copie gardée dans Application Support), elle rouvre seulement la page ; un autre build réinstalle. Échec = boîte avec la fin du message ;
- dans chacun : `backend/dist/server.mjs` et `service.mjs` (sources regroupées par esbuild), `backend/config` (sans `deck.json` ni `tasks.json`), `UnowhyHelper.exe` et `UnowhyCompanion.exe` (lanceur) pour Windows, et le node officiel de nodejs.org (ligne 22, téléchargé une fois dans `backend/.cache/node`, vérifié par SHA-256).

Le lanceur Windows et `service.ts` lancent `dist/server.mjs` s'il existe, sinon `src/server.ts` par tsx. Commandes du service dans un paquet : `node\node.exe dist\service.mjs status|stop|start|uninstall` dans `backend` (Windows), `node/darwin-arm64/node dist/service.mjs …` dans `Compagnon Unowhy.app/Contents/Resources/backend` (Mac). Depuis le dépôt (sans installeur) : `npm run service:install` dans `backend/`, avec le Node du système.

Données d'un ordinateur installé par paquet : hors du dossier extrait, dans `%LOCALAPPDATA%\UnowhyCompanion\data` (Windows) ou `~/Library/Application Support/UnowhyCompanion` (Mac) (`src/paths.ts`). Mettre à jour = relancer le nouvel installeur : l'identité est gardée, la tablette retrouve l'ordinateur. Le dossier Partage du paquet est `Documents\Partage tablette` (Documents lu dans le registre sous Windows, OneDrive compris). Un paquet plus ancien gardait `backend/.data` et `shared/` dans le dossier extrait : ils sont recopiés une fois au premier lancement (les originaux restent). Sur Mac, l'autorisation Accessibilité est donnée au node du paquet. Sur Windows, l'installeur ouvre le pare-feu au `node.exe` du paquet (`scripts/firewall-windows.ps1`, règle « Compagnon Unowhy », réseaux privés, accord administrateur seulement si la règle manque ou vise un autre dossier).

Testés sur un vrai PC Windows (installeur, données hors du dossier, Partage, raccourci, relance après un redémarrage) et sur un vrai Mac le 25/10 (client neuf : installeur, Application Support, Partage dans Documents, raccourci `.webloc`, Accessibilité donnée au node du paquet, appairage ; pas encore vu : relance à l'ouverture de session). Installeurs du 28/10 : NSIS vérifié en mode silencieux sur le PC de QA (installation, Programmes et fonctionnalités, lancement automatique, sanity, pilote, désinstalleur) ; appli Mac vérifiée par `install-mac.sh` (sur place). Non signés : la signature Authenticode des exe n'existe pas encore dans le dépôt (SmartScreen : « Informations complémentaires » > « Exécuter quand même ») ; l'appli Mac n'est ni signée ni notarisée (compte Apple Developer requis ; Gatekeeper : Réglages Système > Confidentialité et sécurité > « Ouvrir quand même »).

## Pièges connus

- **Pilote Windows** : C# 7.3, .NET Framework 4.8 (`src/win/helper/`, lanceur dans `src/win/launcher/`), compilé par `npm run build:helper` avec le SDK .NET 8 figé par `backend/global.json` (sur le Mac : `~/.dotnet`, installé par le script officiel `dotnet-install.sh --version <version de global.json>`). `helper-windows` vérifie en CI que les exe du dépôt correspondent aux sources. `npm run package` embarque la version de node fixée dans `scripts/package.ts` (`NODE_VERSION`) : la changer à la main pour mettre node à jour. Les exe compilés sont gardés dans le dépôt : recompiler après toute modification du C#. Jamais de PowerShell lancé par le compagnon (antivirus).
- **Mac** : le compagnon a besoin de l'Accessibilité (pour `node`, ou pour le terminal qui lance `npm run dev`). Sans elle : raccourcis, fenêtres et macros échouent (bandeau sur le Deck).
- Redémarrer le compagnon actif fait basculer la tablette sur un autre ordinateur qui répond. Il faut ensuite revenir à la main (nom en bas de la barre latérale).
- Le compagnon lancé depuis une session (terminal, éditeur) meurt avec elle. La tablette affiche alors l'ordinateur en rouge et « Aucun événement ».
- Un champ de texte construit dans le code doit être gonflé depuis `item_input.xml` : un `EditText` construit avec un style perd le focus et le curseur.
- Toute saisie dans une boîte de dialogue passe par `showForTyping` : sinon le clavier cache la boîte.

## Avec Claude Code

`CLAUDE.md` à la racine donne à Claude le contexte et les conventions. Il est versionné : il voyage avec le bundle. Les notes de session de Claude, elles, restent sur l'ordinateur où il tourne : ce qui compte est recopié dans [etat-du-projet.md](etat-du-projet.md). Le serveur MCP `tablette` (`.mcp.json`) permet à Claude de piloter la tablette.

**Passe qualité d'un écran** : skill Claude `design-companion` (`.claude/skills/`, non versionné comme `/ship` : installé depuis [config-claude/](../config-claude/README.md)) (`critique`, `etats`, `polish`, `distill`, `harden`, `animate`), pour l'appli et les pages du backend ; première passe dans `docs/team/design-companion/premiere-passe.md`.

**Changer d'ordinateur de développement** (par exemple du Mac au PC Windows, avec un autre compte Claude) : commiter, `scripts/sauvegarde.sh`, et joindre les notes de Claude (`~/.claude/projects/<projet>/memory/`, zippées) au bundle et aux données perso. Sur l'autre ordinateur, on clone le bundle, puis on demande à Claude de recopier les notes dans sa propre mémoire. Pour revenir : commiter, `git bundle create retour.bundle --all`, rezipper les notes, puis `git pull retour.bundle main` et fusionner les notes sur l'ordinateur d'origine. Le serveur MCP `tablette` lit les données du paquet installé sur Mac comme sur Windows (`DATA_DIR=paquet` dans `.mcp.json`). Sur Windows, sa commande `backend/node_modules/.bin/tsx` (script shell) peut ne pas démarrer : s'il n'apparaît pas, le déclarer en local avec `claude mcp add`, sans modifier `.mcp.json`.
