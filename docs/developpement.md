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

**Émulateur** `companion_api29` (Android 10, 1280×800, sans services Google) : l'ordinateur hôte y est `10.0.2.2` (« Saisir l'adresse »). La recherche WiFi ne traverse pas l'émulateur, son DNS peut être en panne (météo) et sa caméra plante. On teste sur la vraie tablette dès que possible : l'émulateur ne reflète pas les performances.

**Captures de la tablette** : `adb exec-out screencap -p > capture.png`. Une capture noire veut dire que l'écran est éteint : l'appli est alors en pause et n'écoute plus l'ordinateur.

## Branches, MR et CI

`main` est protégée : on n'y pousse plus directement. Une branche par changement (`feat/…`, `fix/…`, `docs/…`, `ci/…`), puis une MR vers `main`. Elle se fusionne quand le pipeline est vert et les discussions résolues.

Le pipeline (`.gitlab-ci.yml`, runner Linux) lance quatre jobs en parallèle :

| Job | Vérifie |
|---|---|
| `backend` | `npm run lint` (format prettier, puis typecheck), puis `npm test` : tests d'intégration (`backend/test/`), le vrai serveur démarre avec un dossier de données vide et répond à de vraies requêtes HTTP |
| `e2e` | pages web du backend dans Chromium (Playwright, `backend/e2e/`) : page locale (adresse, code d'appairage, lien ICS), visualiseur `/camera`, retours d'erreur de `/oauth/:provider/callback`. Même serveur que les tests d'intégration. Artefacts : `e2e-results/junit.xml` et traces des tests en échec |
| `android` | style Kotlin (`ktlintCheck`), tests JVM (`app/src/test/`), lint Android, APK debug (téléchargeable dans les artefacts du job, à installer sur la tablette) |
| `android-emulateur` | tests instrumentés (`app/src/androidTest/`, Espresso) sur un émulateur Android 10 sans services Google, 1280×800 à 160 dpi ; dont le Deck contre un vrai backend Linux ; artefacts : rapport JUnit, journal du backend, capture de l'écran de bienvenue |

Sur un tag `vX.Y.Z`, trois jobs de plus construisent et publient la release (section suivante).

### Installation sur Mac (`install-mac`)

Job sur le runner Mac (tag `macos`, exécuteur shell, Mac d'un développeur où tourne son vrai compagnon). Il construit le paquet Mac, le dézippe dans `/tmp/companion-ci-<job>`, lance `Installer (Mac).command`, vérifie l'agent launchd, `/health` (`accessibility: false` attendu : pas d'autorisation pour le node du job), la relance par launchd après un arrêt, puis la désinstallation. Script : `backend/qa/install-mac.sh run <zip>` (et `cleanup`, en `after_script`, même après un échec ou une annulation).

Isolation du vrai compagnon : agent `com.unowhy.companion.ci-<job>`, port `41000 + job % 1000` en `HOST=127.0.0.1`, `DATA_DIR`, `DOCS_DIR` et journal (`COMPANION_LOG_DIR`) dans le dossier du job. `COMPANION_HEADLESS=1` : ni dialogue d'appairage, ni page ou Réglages ouverts, ni raccourci sur le Bureau. Le vrai compagnon (agent `com.unowhy.companion`, plist, port 3000, identité) est relevé avant et après : une différence fait échouer le job. Le script ne lit jamais `~/Desktop`, `~/Documents`… : macOS (TCC) bloque le runner en attendant une autorisation, et le job reste suspendu.

Obligatoire seulement sur les MR qui touchent `backend/src/mac/`, `os.ts`, `paths.ts`, `scripts/service.ts`, `scripts/package.ts`, `package-lock.json`, l'installeur Mac ou le script (Mac éteint = job en attente, la MR aussi). Sur les autres MR : bouton manuel, sans bloquer. En local : `cd backend && npm run package -- mac && cd .. && backend/qa/install-mac.sh run dist/Compagnon-Unowhy-Mac.zip`.

L'installeur, lancé sans terminal (stdin non interactif), ne fait pas de pause et rend le code de sortie de l'installation ; au double-clic, rien ne change.

Avant de pousser : `cd backend && npm run lint && npm test`, et `./gradlew ktlintCheck testDebugUnitTest lintDebug`. Si l'écran ou un parcours change : `scripts/test-android.sh`.

**Format et style** (bloquants en CI) :

| | Corriger | Vérifier (CI) | Règles |
|---|---|---|---|
| Backend | `cd backend && npm run format` | `npm run lint` = `format:check` + `typecheck` | `backend/.prettierrc.json` (lignes de 140), `.prettierignore` ; `tsconfig.json` refuse variables, imports et paramètres inutilisés |
| Android | `./gradlew ktlintFormat` | `./gradlew ktlintCheck` | `.editorconfig` : ktlint (style IntelliJ) sans les règles qui coupent les lignes ; tâches dans `build.gradle.kts` (sans plugin) |

Pas d'eslint : typescript-eslint ne prend pas encore TypeScript 7. Un paramètre inutilisé mais imposé par une signature se préfixe par `_`. Si `ktlintFormat` échoue sur `MaxPermSize`, c'est `~/.gradle/gradle.properties` : retirer l'option (Java 8) ou lancer avec `-Dorg.gradle.jvmargs=-Xmx2048m`.

Tests Playwright (page web touchée : `src/local-page.ts`, `src/camera.ts`, routes OAuth) : `cd backend && npm run test:e2e`, après un `npx playwright install chromium` la première fois. Sans navigateur installé, dans l'image du job :

```sh
docker run --rm -e CI=true -v "$PWD":/w -v /w/backend/node_modules -w /w/backend \
  mcr.microsoft.com/playwright:v1.64.1-noble bash -c 'npm ci && npm run test:e2e'
```

Le tag de l'image du job `e2e` suit la version exacte de `@playwright/test` (`backend/package.json`) : changer les deux ensemble (et le digest). Trace d'un échec : `npx playwright show-trace e2e-results/traces/<test>/trace.zip`.

Ce que la CI ne voit pas : le pilotage réel du Mac et de Windows (sur Linux, le deck s'affiche mais toucher une touche répond 501 ; `install-mac` ne teste que l'installation), l'appli sur la vraie tablette (perfs, kiosque, micro, caméra). Ils restent testés à la main (case « Testé sur » de la MR).

### Tests instrumentés (émulateur)

Espresso, dans `app/src/androidTest/` : le test tourne dans l'appli, peut effacer ses préférences avant de la lancer, et attend seul que l'écran soit stable. Parcours : `WelcomeTest` (lancement sans ordinateur, écran de bienvenue, « Plus tard », Deck → « Connecter » ramène à la bienvenue), `DeckTest` (ci-dessous). Noms de tests en camelCase : D8 refuse les espaces dans les noms de méthode sous API 30.

`scripts/test-android.sh [--window]` fait tout : AVD dédié `companion_test_api29` (port 5580, effacé à chaque lancement ; `companion_api29` n'est pas touché), compilation, installation, tests, capture `app/build/test-android/bienvenue.png`, arrêt de l'émulateur. Sortie non nulle si un test échoue.

- **Mac Apple Silicon** : émulateur natif (Hypervisor.framework) et image `android-29;default;arm64-v8a`, téléchargés au premier lancement (~800 Mo, empreintes vérifiées) dans `~/Library/Android/sdk`. Sans JDK, les APK sont compilés dans l'image Docker de la CI et les tests lancés par `am instrument`. Avec un JDK : `./gradlew connectedDebugAndroidTest`. L'émulateur ne peut pas tourner dans Docker sur le Mac : pas de KVM (pas de virtualisation imbriquée sur M1).
- **CI** : même script, image `x86_64` par `sdkmanager`. Le runner Kubernetes partagé expose `/dev/kvm` (pod privilégié, vérifié le 26/10/2026 : `emulator -accel-check` → « KVM is installed and usable », boot en ~25 s). Si ce runner change de configuration, le job échoue avec « /dev/kvm absent » : il faudra alors un runner dédié avec KVM, taggé.
- Sur le WiFi du bureau, l'émulateur local peut trouver un vrai compagnon (recherche UDP) : les tests n'en dépendent pas.
- `EMULATOR_PORT=5590 scripts/test-android.sh` : second lancement en parallèle (autre worktree), avec son AVD et son cache Gradle. Le backend du Deck (ports 3910, 3911) reste unique : un seul `DeckTest` à la fois.

**Deck contre un vrai backend** (`DeckTest`) : le script démarre `backend/test/deck-emulateur.ts` pendant le boot de l'émulateur. Vrai serveur, deck de test `backend/test/deck-emulateur.json` (`DECK_FILE`), jeton `jeton-de-test`, données dans un dossier temporaire ; l'émulateur le joint sur `10.0.2.2:3910`. Le port 3911 sert aux tests : `POST /reset` (touches perso vides), `/stop` et `/start` (perte du backend), `GET /runs` (tuiles reçues par le backend, lues dans son journal). Les tests appairent sans dialogue : ils lisent `/health` et enregistrent l'ordinateur (`Computers.save`) avant de lancer l'appli. Scénarios : onglets et touches, toucher et balayage, refus 501 affiché en français, touche perso créée avec l'éditeur puis relue après relance, backend arrêté puis revenu.

- Le backend tourne **toujours sous Linux** : toucher une touche y répond 501, rien n'est piloté. `deck-emulateur.ts` refuse de démarrer sur macOS et Windows. Sur Mac, le script le lance dans Docker (`node:22.22-bookworm-slim`, Docker Desktop requis) ; en CI, directement, avec le node de nodejs.org (version de `backend/scripts/package.ts`, somme SHA-256 vérifiée) si l'image n'en a pas.
- Journal du backend : `app/build/test-android/backend.log`. Le lancer seul (Linux) : `cd backend && node --import tsx test/deck-emulateur.ts`.

Lint : les erreurs déjà présentes sont figées dans `app/lint-baseline.xml`, seules les nouvelles font échouer le job. Après en avoir corrigé, `./gradlew updateLintBaseline`.

## Release (tag vX.Y.Z)

Un tag `vX.Y.Z` posé sur `main` lance les mêmes tests, puis :

| Job | Produit |
|---|---|
| `package` | `Compagnon-Unowhy-Windows.zip` et `Compagnon-Unowhy-Mac.zip` (`npm run package -- windows` ou `mac` sur le runner Linux, un job par plateforme : artefacts sous 100 Mo) |
| `android-release` | `companion-X.Y.Z.apk` signé, et `companion-X.Y.Z.json` : son entrée pour le « serveur Unowhy » |
| `release` | release GitLab du tag, fichiers déposés dans le registre de paquets du projet (`glab release create`, jeton du job) |

```bash
git tag v1.2.0 origin/main && git push origin v1.2.0
```

Version de l'appli : `versionName` = `X.Y.Z` du tag, `versionCode` = X × 10000 + Y × 100 + Z (Y et Z sous 100), donnés à Gradle par `-PappVersion=X.Y.Z`. Sans ce paramètre : `0.1.0` (code 100). La tablette n'installe une mise à jour que si son `versionCode` est plus grand : chaque tag doit être supérieur au précédent.

**Clé de signature**, jamais dans le dépôt. Variables CI du projet, protégées et masquées : `RELEASE_KEYSTORE_BASE64` (fichier PKCS12 en base64, sur une ligne) et `RELEASE_KEYSTORE_PASSWORD` (mot de passe du fichier et de la clé, alias `companion`). Une variable protégée n'existe que pour une branche ou un tag protégé : protéger les tags `v*` (Settings > Repository > Protected tags), sinon `android-release` échoue (« Clé de signature absente »). Créer la clé une fois, puis la garder hors du dépôt (perdue = plus aucune mise à jour possible des tablettes) :

```bash
keytool -genkeypair -storetype PKCS12 -keystore companion-release.p12 -alias companion -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Unowhy Companion"
base64 -i companion-release.p12 | tr -d '\n'   # valeur de RELEASE_KEYSTORE_BASE64
```

Sur un poste de dev, `./gradlew assembleRelease` sans `RELEASE_KEYSTORE` donne un APK release non signé. Avec : `RELEASE_KEYSTORE=chemin.p12 RELEASE_KEYSTORE_PASSWORD=… ./gradlew assembleRelease -PappVersion=1.2.0`.

**Alimenter le « serveur Unowhy »** (mise à jour de la tablette depuis l'ordinateur) : télécharger de la release `companion-X.Y.Z.apk` et `companion-X.Y.Z.json` dans un même dossier, puis sur l'ordinateur, dans `backend/` :

```bash
npm run updates:add -- ~/Téléchargements/companion-1.2.0.json                  # données du dépôt (backend/.data)
DATA_DIR=paquet npm run updates:add -- ~/Téléchargements/companion-1.2.0.json  # compagnon installé par paquet
```

`scripts/updates.ts` copie l'APK dans `<données>/updates/` et remplace la version précédente du Companion dans `manifest.json` ; les autres entrées (WebView) restent. La tablette la voit dans Réglages > Mises à jour.

Changement de signature : Android refuse une mise à jour signée par une autre clé. Une tablette qui a reçu l'APK debug (adb, artefact du job `android`) n'acceptera l'APK de release qu'après désinstallation du Companion, donc, en kiosque, après avoir refait la procédure de [kiosque.md](kiosque.md) avec l'APK de release.

## Mettre à jour les ordinateurs de test (installés par paquet)

Le PC d'Eliot et le Mac tournent depuis le paquet (pas depuis le dépôt). D'abord `cd backend && npm run package` : le dossier `dist/<windows|mac>/Compagnon Unowhy/` est le contenu du zip.

**PC Windows**, par le partage SMB `Compagnon` (= dossier `Documents\Compagnon-Unowhy-PC\Compagnon Unowhy` du PC) monté sur le Mac :

```bash
rsync -rt --exclude node --exclude .data --exclude .DS_Store "dist/windows/Compagnon Unowhy/" <partage monté>/
curl -X POST -H "Authorization: Bearer <jeton du PC>" -H "Content-Type: application/json" -d '{}' http://<ip-du-pc>:3000/companion/restart
```

Le compagnon redémarre seul en ~9 s. Vérifier ensuite une route Windows (`/system/media`, `/windows/open`) : le C# est compilé sur le Mac (`npm run build:helper`, avant `npm run package`), mais rien ne remplace un essai sur Windows.

**Mac** (dossier `~/Documents/Compagnon-Unowhy-Mac/Compagnon Unowhy`, service launchd `com.unowhy.companion`) :

```bash
rsync -rt --exclude node --exclude .DS_Store "dist/mac/Compagnon Unowhy/" ~/Documents/Compagnon-Unowhy-Mac/"Compagnon Unowhy"/
launchctl kickstart -k gui/$(id -u)/com.unowhy.companion
```

Garder le même dossier : l'Accessibilité est accordée au chemin du `node` du paquet. `config/tasks.json` (worklog) est dans le dossier du paquet, rsync ne l'efface pas. Journal : `~/Library/Logs/UnowhyCompanion/backend.log`. `npm run dev` ne peut pas tourner en même temps (port 3000, le service redémarre seul) : pour revenir au dev, `node/darwin-arm64/node dist/service.mjs uninstall` dans le paquet, puis appairer de nouveau (identité du dev dans `backend/.data`). Le serveur MCP `tablette` lit le jeton du paquet (`DATA_DIR` dans `.mcp.json`).

## Extension de navigateur (onglets sur la tablette, Windows)

`backend/extension/` (Manifest V3, Chromium : Opera, Chrome, Edge, Brave) envoie les onglets du navigateur à `POST http://127.0.0.1:3000/browser/extension` (sans jeton, origine `chrome-extension://` exigée) à chaque changement et chaque minute ; le compagnon les garde en mémoire 3 min (`src/browser-extension.ts`). Le paquet la copie dans « Extension navigateur ». Installer : `opera://extensions` (ou `chrome://extensions`), Mode développeur, « Charger l'extension non empaquetée », choisir ce dossier. Après une mise à jour de l'extension : bouton Recharger sur la même page. Après un redémarrage du compagnon, la liste revient au prochain changement d'onglet (au plus 1 min).

## Fabriquer les paquets clients (sans Node à installer)

Sur le Mac (ou sous Linux, comme le job `package` d'une release ; `zip`, `unzip` et `tar` requis), dans `backend/` : `npm run package` (`scripts/package.ts`). Résultat dans `dist/` à la racine (ignoré par git) :

- `Compagnon-Unowhy-Windows.zip` (~34 Mo) et `Compagnon-Unowhy-Mac.zip` (~76 Mo, node Apple Silicon et Intel) ;
- dans chaque zip, un dossier « Compagnon Unowhy » : l'installeur à double-cliquer, `backend/dist/server.mjs` et `service.mjs` (sources regroupées par esbuild), `backend/config` (sans `deck.json` ni `tasks.json`), `UnowhyHelper.exe` et `UnowhyCompanion.exe` (lanceur) pour Windows, et le node officiel de nodejs.org (ligne 22, téléchargé une fois dans `backend/.cache/node`, vérifié par SHA-256).

Les installeurs prennent `backend/node/…` s'il existe (sans `npm install`), sinon le Node du système comme avant ; Le lanceur Windows et `service.ts` lancent `dist/server.mjs` s'il existe, sinon `src/server.ts` par tsx. Commandes du service dans un paquet : `node\node.exe dist\service.mjs status|stop|start|uninstall` (Windows), `node/darwin-arm64/node dist/service.mjs …` (Mac).

Données d'un ordinateur installé par paquet : hors du dossier extrait, dans `%LOCALAPPDATA%\UnowhyCompanion\data` (Windows) ou `~/Library/Application Support/UnowhyCompanion` (Mac) (`src/paths.ts`). Mettre à jour = extraire le nouveau zip n'importe où et relancer son installeur : l'identité est gardée, la tablette retrouve l'ordinateur. Le dossier Partage du paquet est `Documents\Partage tablette` (Documents lu dans le registre sous Windows, OneDrive compris). Un paquet plus ancien gardait `backend/.data` et `shared/` dans le dossier extrait : ils sont recopiés une fois au premier lancement (les originaux restent). Sur Mac, l'autorisation Accessibilité est donnée au node du paquet. Sur Windows, l'installeur ouvre le pare-feu au `node.exe` du paquet (`scripts/firewall-windows.ps1`, règle « Compagnon Unowhy », réseaux privés, accord administrateur seulement si la règle manque ou vise un autre dossier).

Testés sur un vrai PC Windows (installeur, données hors du dossier, Partage, raccourci, relance après un redémarrage) et sur un vrai Mac le 25/10 (client neuf : installeur, Application Support, Partage dans Documents, raccourci `.webloc`, Accessibilité donnée au node du paquet, appairage ; pas encore vu : relance à l'ouverture de session). Sur un Mac avec oh-my-zsh, sa question de mise à jour avale le début de la commande du `.command` : répondre à la question, puis relancer.

## Pièges connus

- **Pilote Windows** : C# 7.3, .NET Framework 4.8 (`src/win/helper/`, lanceur dans `src/win/launcher/`), compilé par `npm run build:helper` avec le SDK .NET 8 figé par `backend/global.json` (sur le Mac : `~/.dotnet`, installé par le script officiel `dotnet-install.sh --channel 8.0`). `npm run package` embarque la version de node fixée dans `scripts/package.ts` (`NODE_VERSION`) : la changer à la main pour mettre node à jour. Les exe compilés sont gardés dans le dépôt : recompiler après toute modification du C#. Jamais de PowerShell lancé par le compagnon (antivirus).
- **Mac** : le compagnon a besoin de l'Accessibilité (pour `node`, ou pour le terminal qui lance `npm run dev`). Sans elle : raccourcis, fenêtres et macros échouent (bandeau sur le Deck).
- Redémarrer le compagnon actif fait basculer la tablette sur un autre ordinateur qui répond. Il faut ensuite revenir à la main (nom en bas de la barre latérale).
- Le compagnon lancé depuis une session (terminal, éditeur) meurt avec elle. La tablette affiche alors l'ordinateur en rouge et « Aucun événement ».
- Un champ de texte construit dans le code doit être gonflé depuis `item_input.xml` : un `EditText` construit avec un style perd le focus et le curseur.
- Toute saisie dans une boîte de dialogue passe par `showForTyping` : sinon le clavier cache la boîte.

## Avec Claude Code

`CLAUDE.md` à la racine donne à Claude le contexte et les conventions. Il est versionné : il voyage avec le bundle. Les notes de session de Claude, elles, restent sur l'ordinateur où il tourne : ce qui compte est recopié dans [etat-du-projet.md](etat-du-projet.md). Le serveur MCP `tablette` (`.mcp.json`) permet à Claude de piloter la tablette.

**Changer d'ordinateur de développement** (par exemple du Mac au PC Windows, avec un autre compte Claude) : commiter, `scripts/sauvegarde.sh`, et joindre les notes de Claude (`~/.claude/projects/<projet>/memory/`, zippées) au bundle et aux données perso. Sur l'autre ordinateur, on clone le bundle, puis on demande à Claude de recopier les notes dans sa propre mémoire. Pour revenir : commiter, `git bundle create retour.bundle --all`, rezipper les notes, puis `git pull retour.bundle main` et fusionner les notes sur l'ordinateur d'origine. Le serveur MCP `tablette` lit les données du paquet installé sur Mac comme sur Windows (`DATA_DIR=paquet` dans `.mcp.json`). Sur Windows, sa commande `backend/node_modules/.bin/tsx` (script shell) peut ne pas démarrer : s'il n'apparaît pas, le déclarer en local avec `claude mcp add`, sans modifier `.mcp.json`.
