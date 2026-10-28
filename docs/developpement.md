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

Le pipeline (`.gitlab-ci.yml`) tourne sur le runner Linux, sauf `install-mac` (Mac, section suivante), `install-windows` (PC Windows de QA) et `helper-windows` (runner Windows partagé) :

| Job | Vérifie |
|---|---|
| `backend` | `npm run lint` (format prettier, puis typecheck), puis `npm run coverage` : tests unitaires et d'intégration (`backend/test/`) sous seuil de couverture (voir plus bas), le vrai serveur démarre avec un dossier de données vide et répond à de vraies requêtes HTTP ; le pilotage de l'ordinateur y est simulé (voir plus bas). Comprend la sanity (`test/sanity.test.ts`) |
| `e2e` | pages web du backend dans Chromium (Playwright, `backend/e2e/`) : page locale (adresse, code d'appairage, lien ICS), visualiseur `/camera`, retours d'erreur de `/oauth/:provider/callback`. Même serveur que les tests d'intégration. Artefacts : `e2e-results/junit.xml` et traces des tests en échec |
| `android` | style Kotlin (`ktlintCheck`), tests JVM (`app/src/test/` : `Computers`, `Prefs`, `Connection` contre de petits serveurs HTTP locaux, `FocusTimer`, `Water`, ordre `focus`, modes, widgets, commande vocale ; contexte simulé `FakeContext`), lint Android, APK debug (téléchargeable dans les artefacts du job, à installer sur la tablette) |
| `android-emulateur` | tests instrumentés (`app/src/androidTest/`, Espresso) sur un émulateur Android 10 sans services Google, 1280×800 à 160 dpi ; dont le Deck contre un vrai backend ; artefacts : rapport JUnit, journal du backend, capture de l'écran de bienvenue |
| `package-windows`, `package-mac` | paquets de l'ordinateur (`npm run package -- windows|mac`), utilisés par les tests d'installation et la release |
| `install-windows` | paquet Windows installé sur un vrai PC : installeur silencieux, lancement automatique, sanity, pilote, désinstallation (`backend/qa/install-windows.ps1`) |
| `helper-windows` | pilote et lanceur C# recompilés sur Windows : échoue si le C# ne compile pas ou si les exe du dépôt ne sont plus ceux des sources (`backend/qa/build-helper-windows.ps1`) |
| `zones` | rien : garde un pipeline aux MR qui ne touchent aucune zone (sans pipeline, la MR ne fusionne pas) |

**Ciblage par zone.** En MR, un job ne tourne que si sa zone a bougé (`rules:changes`, ancres `.files-*` en tête du fichier). Sur `main` et sur un tag, tout tourne. Toucher `.gitlab-ci.yml` relance tout.

| Zone | Fichiers | Jobs |
|---|---|---|
| backend | `backend/**` | `backend`, `e2e` |
| android | `app/**`, `*.gradle*`, `gradle/**`, `gradlew*`, `scripts/test-android.sh` | `android`, `android-emulateur` |
| backend du Deck sur émulateur | `backend/src/**`, `backend/test/server.ts`, `backend/test/fake-os*.ts`, `backend/test/deck-emulateur.*`, `backend/package-lock.json` | `android-emulateur` |
| installation Windows | `backend/scripts/**`, `backend/src/os.ts`, `backend/src/paths.ts`, `backend/package-lock.json`, `backend/qa/**`, `backend/src/win/**`, `Installer (Windows).cmd` | `package-windows`, `install-windows` |
| pilote Windows | `backend/src/win/**`, `backend/global.json`, `backend/assets/compagnon.ico`, `backend/qa/build-helper-windows.ps1`, `.gitattributes` | `helper-windows` |
| installation Mac | liste propre au job (`backend/src/mac/**`, `service.ts`, `package.ts`, `os.ts`, `paths.ts`…) | `install-mac` (manuel sur les autres MR) ; `package-mac` ne tourne qu'hors MR, `install-mac` fabriquant son paquet |

**Sanity** (`backend/qa/sanity.ts`) : un contrôle par feature contre un compagnon réel déjà démarré : santé et identité, appairage par code (sans boîte avec `COMPANION_HEADLESS=1` ou `NO_ALERT=1`), deck et deck de départ filtré aux applis installées, son, fenêtres ouvertes, refus d'un `.exe` par `/docs/open`, tâches, agenda personnel, agenda ICS (fichier servi en local, seulement si aucun agenda n'est relié), `/camera`, page locale. Réponse attendue selon le système : hors Mac et Windows, le deck se lit et le reste du pilotage répond 501. Ce qu'il crée est retiré à la fin. Sans dépendance : le node du paquet le lance tel quel.

```sh
node qa/sanity.ts --url http://127.0.0.1:3000 --token <jeton> --os mac --junit sanity.xml
```

**`install-windows`** : runner 976 « Local QA runner » (tag `local-runner`), le PC Windows de QA, exécuteur shell PowerShell, avec une vraie session de bureau : il faut que le PC soit allumé et la session ouverte, sinon le job attend. Le runner ne prend que les refs protégées (`ref_protected`) : `main`, et les branches `ci/*`, protégées pour ça (push et merge Developers + Maintainers, sans force push). En MR, le job ne tourne donc que depuis une branche `ci/…` ; une MR `feat/` ou `fix/` qui touche la zone Windows ne l'a pas (il attendrait sans fin), il tourne après fusion sur `main` : nommer la branche `ci/…` pour le voir avant. Une branche `ci/*` protégée n'est pas supprimée à la fusion : la retirer ensuite par l'API (`DELETE projects/:id/repository/branches/ci%2F…`). Le PC sert aussi aux tests UEM : instance de test comme pour `install-mac` (port 3917, `HOST=127.0.0.1`, `DATA_DIR`, `COMPANION_LOG_DIR`, `LOCALAPPDATA` pour les copies du pilote, valeur `UnowhyCompanionCI` de la clé Run par `COMPANION_LABEL`, `COMPANION_HEADLESS=1` : ni boîte, ni page, ni raccourci, tout sous `%TEMP%\compagnon-ci`), et `-Nettoyage` (before_script et after_script, même après un échec) désinstalle et supprime ce dossier. Un compagnon déjà installé sur le PC n'est pas touché. L'installeur a un mode non interactif, `"Installer (Windows).cmd" /silencieux` : ni pause, ni pare-feu, ni demande d'administrateur ; le double-clic ne change pas. À la main sur un PC : `powershell -ExecutionPolicy Bypass -File backend\qa\install-windows.ps1 -Zip <zip>`. Journaux du lanceur et du serveur dans les artefacts (`install-logs/`), résultat de la sanity dans l'onglet Tests.

**`helper-windows`** : runner Windows partagé « HiSqool Windows » (473, tags `windows`, `docker`), image `mcr.microsoft.com/dotnet/sdk:<version>-windowsservercore-ltsc2019` au SDK exact de `backend/global.json` (les monter ensemble, digest compris). Compile `src/win/helper` et `src/win/launcher`, copie les exe sur ceux du dépôt, puis `git diff --exit-code`. Les exe sont reproductibles (mêmes octets d'une machine et d'un dossier à l'autre) tant qu'ils sont compilés avec ce SDK : pas de SHA du commit dans la version (`IncludeSourceRevisionInInformationalVersion`), sources en LF partout (`.gitattributes`). Job rouge « exe du dépôt différents des sources » : `npm run build:helper` dans `backend/`, puis commiter les exe. À la main sur un PC : `powershell -ExecutionPolicy Bypass -File backend\qa\build-helper-windows.ps1` (installe le SDK dans `.dotnet-ci/` s'il manque, rien sur le poste).

Le runner Windows partagé « HiSqool Windows » (473) ne sert qu'à compiler : Docker Windows Server 2019 sans bureau (Server Core), le dossier Applications du shell y manque, donc le pilote ne liste ni applis ni fenêtres. Le runner 976 n'accepte que les refs protégées (`access_level: ref_protected`) : les branches `ci/*` sont protégées pour qu'il prenne les jobs de leurs MR.

Sur un tag `vX.Y.Z`, `android-release` et `release` construisent et publient la release (section suivante).

### Installation sur Mac (`install-mac`)

Job sur le runner Mac (tag `macos`, exécuteur shell, Mac d'un développeur où tourne son vrai compagnon ; compte dédié `ci-runner`, voir plus bas). Il construit le paquet Mac, le dézippe dans `/tmp/companion-ci-<job>`, lance `Installer (Mac).command`, vérifie l'agent launchd, `/health` (`accessibility: false` attendu : pas d'autorisation pour le node du job), la relance par launchd après un arrêt, lance le sanity (`backend/qa/sanity.ts --os mac`, rapport `sanity-mac.xml` ; « fenêtres ouvertes » sautée sans Accessibilité, System Events demanderait une autorisation à l'écran), puis la désinstallation. Script : `backend/qa/install-mac.sh run <zip>` (et `cleanup`, en `after_script`, même après un échec ou une annulation).

Isolation du vrai compagnon : agent `com.unowhy.companion.ci-<job>`, port `41000 + job % 1000` en `HOST=127.0.0.1`, `DATA_DIR`, `DOCS_DIR` et journal (`COMPANION_LOG_DIR`) dans le dossier du job. `COMPANION_HEADLESS=1` : ni dialogue d'appairage, ni page ou Réglages ouverts, ni raccourci sur le Bureau. Le vrai compagnon (agent `com.unowhy.companion`, plist, port 3000, identité) est relevé avant et après : une différence fait échouer le job. Le script ne lit jamais `~/Desktop`, `~/Documents`… : macOS (TCC) bloque le runner en attendant une autorisation, et le job reste suspendu.

Obligatoire seulement sur les MR qui touchent `backend/src/mac/`, `os.ts`, `paths.ts`, `scripts/service.ts`, `scripts/package.ts`, `package-lock.json`, l'installeur Mac, `install-mac.sh` ou `sanity.ts` (Mac éteint = job en attente, la MR aussi). Sur les autres MR : bouton manuel, sans bloquer. Sur `main` et un tag : lancé d'office. En local : `cd backend && npm run package -- mac && cd .. && backend/qa/install-mac.sh run dist/Compagnon-Unowhy-Mac.zip`.

L'installeur, lancé sans terminal (stdin non interactif), ne fait pas de pause et rend le code de sortie de l'installation ; au double-clic, rien ne change.

**Compte du runner Mac.** Les jobs du runner (1584, Mac du développeur) peuvent venir de n'importe quel membre du projet : le runner ne tourne pas sous le compte du développeur, mais sous un compte macOS dédié, `ci-runner`, standard (pas administrateur). Il lit `/opt/homebrew` (node 22 ou plus, npm) sans pouvoir y écrire, et une ACL lui refuse le dossier personnel du développeur (fichiers, clés SSH, jetons, dépôts) : ce dossier est ouvert au groupe `staff`, dont tout compte local fait partie. Même runner, même jeton : `config.toml` est copié, pas réenregistré. GitLab ne prend en charge sur macOS qu'un agent utilisateur dans une session graphique (pas de LaunchDaemon), et `install-mac.sh` a besoin du domaine `gui/$UID` pour installer l'agent de test : la session de `ci-runner` reste ouverte en arrière-plan. Le vrai compagnon tourne sous le compte du développeur, hors de portée du job ; seul son port 3000 (`/health`) reste comparé avant et après.

- Mise en place, depuis le compte du développeur : `sudo scripts/runner-mac.sh installer` (crée le compte, demande son mot de passe puis celui du développeur pour lui donner un secure token : avec FileVault, un compte sans jeton n'est pas proposé à la connexion ; pose l'ACL, copie la config en `600`, pose l'agent `~ci-runner/Library/LaunchAgents/gitlab-runner.plist`, vérifie ; rapport dans `/tmp/runner-mac-verif.txt`), puis `brew services stop gitlab-runner`, puis ouvrir la session `ci-runner` par le menu de changement rapide d'utilisateur (icône de silhouette de la barre des menus ; à afficher dans Réglages > Centre de contrôle > Changement rapide d'utilisateur ; l'écran verrouillé ne le propose pas) > CI runner, passer l'assistant de premier démarrage, revenir à sa session sans fermer l'autre.
- Après chaque redémarrage : FileVault interdit la connexion automatique. Se connecter, puis rouvrir la session `ci-runner` de la même façon (son mot de passe déverrouille aussi le disque : le choisir solide) ; sinon `install-mac` reste en attente. Vérifier : `sudo scripts/runner-mac.sh verifier` (ligne « agent : running »).
- Retour arrière : `sudo scripts/runner-mac.sh retour` (arrête l'agent de `ci-runner`, retire sa config et l'ACL ; `--supprimer` retire aussi le compte), puis `brew services start gitlab-runner` sous le compte du développeur (sa config `~/.gitlab-runner/config.toml` n'est pas touchée).
- Mise à jour du runner : `brew upgrade gitlab-runner` sous le compte du développeur, puis fermer et rouvrir la session `ci-runner` (l'agent pointe sur `/opt/homebrew/opt/gitlab-runner`).

Avant de pousser : `cd backend && npm run lint && npm run coverage`, et `./gradlew ktlintCheck testDebugUnitTest lintDebug`. Si l'écran ou un parcours change : `scripts/test-android.sh`.

**Format et style** (bloquants en CI) :

| | Corriger | Vérifier (CI) | Règles |
|---|---|---|---|
| Backend | `cd backend && npm run format` | `npm run lint` = `format:check` + `typecheck` | `backend/.prettierrc.json` (lignes de 140), `.prettierignore` ; `tsconfig.json` refuse variables, imports et paramètres inutilisés |
| Android | `./gradlew ktlintFormat` | `./gradlew ktlintCheck` | `.editorconfig` : ktlint (style IntelliJ) sans les règles qui coupent les lignes ; tâches dans `build.gradle.kts` (sans plugin) |

Pas d'eslint : typescript-eslint ne prend pas encore TypeScript 7. Un paramètre inutilisé mais imposé par une signature se préfixe par `_`. Si `ktlintFormat` échoue sur `MaxPermSize`, c'est `~/.gradle/gradle.properties` : retirer l'option (Java 8) ou lancer avec `-Dorg.gradle.jvmargs=-Xmx2048m`.

**Couverture du backend** (bloquante en CI) : `npm run coverage` lance les tests sous c8, serveur de test compris (processus enfant ; `test/fake-os-hook.ts` le fait sortir proprement sur `SIGTERM`, sinon V8 n'écrit pas sa couverture). Le job échoue si les lignes, branches ou fonctions de `src/` passent sous les seuils de `backend/.c8rc.json` ; la MR affiche l'écart de couverture et les lignes non couvertes du diff. Hors mesure : vrais pilotes (`src/mac/`, `src/win/`, `src/os.ts`, vérifiés par `install-mac` et `install-windows`) et `src/mcp.ts`. Seuils = valeur du job arrondie à l'entier inférieur : les remonter dans la MR qui fait monter la couverture, jamais les baisser pour faire passer un pipeline. La valeur locale (Mac, Node récent) peut dépasser celle de la CI de quelques dixièmes.

Tests Playwright (page web touchée : `src/local-page.ts`, `src/camera.ts`, routes OAuth) : `cd backend && npm run test:e2e`, après un `npx playwright install chromium` la première fois. Sans navigateur installé, dans l'image du job :

```sh
docker run --rm -e CI=true -v "$PWD":/w -v /w/backend/node_modules -w /w/backend \
  mcr.microsoft.com/playwright:v1.64.1-noble bash -c 'npm ci && npm run test:e2e'
```

Le tag de l'image du job `e2e` suit la version exacte de `@playwright/test` (`backend/package.json`) : changer les deux ensemble (et le digest). Trace d'un échec : `npx playwright show-trace e2e-results/traces/<test>/trace.zip`.

**Pilotage simulé dans les tests** : `backend/test/fake-os.ts` remplace `src/os.ts` (hook de résolution `test/fake-os-hook.ts`) ; aucun test ne lance d'appli, n'envoie de touches ni n'ouvre de fenêtre. Il note chaque action (`calls`) et répond selon un scénario (`state` : applis installées, fenêtres, boutons, Ne pas déranger, échecs). Serveur de `test/server.ts` : toujours simulé (`startServer({ os: "windows" })` pour Windows), scénario par `server.setOs(...)`, actions par `server.osCalls()`. Test unitaire d'un module qui pilote l'ordinateur : `import "./fake-os-hook.ts"` en tête, puis le module en import dynamique (`await import("../src/deck.ts")`). Un export ajouté à `os.ts` doit l'être aussi au faux, sinon les tests échouent au chargement.

Ce que la CI ne voit pas : le pilotage réel du Mac (simulé dans les tests ; `install-mac` ne teste que l'installation), les actions qui changent l'ordinateur (lancer une appli, déplacer une fenêtre, régler le son : `install-windows` ne fait que lire), l'appli sur la vraie tablette (perfs, kiosque, micro, caméra). Ils restent testés à la main (case « Testé sur » de la MR).

### Tests instrumentés (émulateur)

Espresso, dans `app/src/androidTest/` : le test tourne dans l'appli, peut effacer ses préférences avant de la lancer, et attend seul que l'écran soit stable. Premier parcours : `WelcomeTest` (lancement sans ordinateur, écran de bienvenue, « Plus tard », Deck → « Connecter » ramène à la bienvenue) ; `HomeTest` (ordinateur appairé mais éteint : pas de bienvenue, widgets Focus et Eau utilisables) ; `ErrorsTest` (phrases de `errorText`, qui a besoin des vraies ressources) ; `DeckTest` (Deck contre un vrai backend, ci-dessous). Préférences vidées par `edit().clear().commit()`, pas en supprimant les fichiers : un `apply()` du test précédent n'est peut-être pas encore sur le disque. Noms de tests en camelCase : D8 refuse les espaces dans les noms de méthode sous API 30.

`scripts/test-android.sh [--window]` fait tout, dans un environnement jetable : ports libres tirés à chaque lancement (émulateur, backend), AVD `companion_test_api29` dans un dossier temporaire supprimé à la fin (`companion_api29` n'est pas touché), compilation, installation, tests, capture `app/build/test-android/bienvenue.png`, arrêt de l'émulateur. Sortie non nulle si un test échoue.

- **Mac Apple Silicon** : émulateur natif (Hypervisor.framework) et image `android-29;default;arm64-v8a`, téléchargés au premier lancement (~800 Mo, empreintes vérifiées) dans `~/Library/Android/sdk`. Sans JDK, les APK sont compilés dans l'image Docker de la CI et les tests lancés par `am instrument`. Avec un JDK : `./gradlew connectedDebugAndroidTest`. L'émulateur ne peut pas tourner dans Docker sur le Mac : pas de KVM (pas de virtualisation imbriquée sur M1).
- **CI** : même script, image `x86_64` par `sdkmanager`. Le runner Kubernetes partagé expose `/dev/kvm` (pod privilégié, vérifié le 26/10/2026 : `emulator -accel-check` → « KVM is installed and usable », boot en ~25 s). Si ce runner change de configuration, le job échoue avec « /dev/kvm absent » : il faudra alors un runner dédié avec KVM, taggé.
- Sur le WiFi du bureau, l'émulateur local peut trouver un vrai compagnon (recherche UDP) : les tests n'en dépendent pas.
- Plusieurs lancements en parallèle (worktrees) ne se gênent pas. Sans JDK, le build Docker a un cache Gradle par worktree (volume `companion-gradle-<empreinte du chemin>`, amorcé au premier lancement depuis `companion-gradle`) : deux builds sur un même volume se bloquent sur ses verrous. Volumes des worktrees supprimés : `docker volume ls -q -f name=companion-gradle-`, puis `docker volume rm`.

**Deck contre un vrai backend** (`DeckTest`) : le script démarre `backend/test/deck-emulateur.ts` pendant le boot de l'émulateur. Vrai serveur, pilotage simulé sans contrôle (`fake-os.ts`, `os: "other"` : le deck s'affiche, toucher une touche répond 501 comme sous Linux, rien n'agit sur la machine), deck de test `backend/test/deck-emulateur.json` (`DECK_FILE`), jeton `jeton-de-test`, données dans un dossier temporaire ; l'émulateur le joint par `10.0.2.2` sur un port libre, passé aux tests en argument d'instrumentation (`backendPort`, `controlPort` ; 3910 et 3911 par défaut, lancé à la main). Le port de pilotage sert aux tests : `POST /reset` (touches perso vides), `/stop` et `/start` (perte du backend), `GET /runs` (tuiles reçues, lues dans le journal du serveur). Les tests appairent sans dialogue : ils lisent `/health` et enregistrent l'ordinateur (`Computers.save`) avant de lancer l'appli. Scénarios : onglets et touches, toucher et balayage, refus 501 affiché en français, touche perso créée avec l'éditeur puis relue après relance, backend arrêté puis revenu.

- node 22 du poste ; en CI, l'image Android n'en a pas : celui de nodejs.org (version de `backend/scripts/package.ts`, somme SHA-256 vérifiée).
- Journal du backend : `app/build/test-android/backend.log`. Le lancer seul : `cd backend && node --import tsx test/deck-emulateur.ts`.

Lint : les erreurs déjà présentes sont figées dans `app/lint-baseline.xml`, seules les nouvelles font échouer le job. Après en avoir corrigé, `./gradlew updateLintBaseline`.

## Release (tag vX.Y.Z)

Un tag `vX.Y.Z` posé sur `main` lance les mêmes tests, puis :

| Job | Produit |
|---|---|
| `package-windows`, `package-mac` | `Compagnon-Unowhy-Windows.zip` et `Compagnon-Unowhy-Mac.zip` (`npm run package -- windows` ou `mac` sur le runner Linux, un job par plateforme : artefacts sous 100 Mo) |
| `android-release` | `companion-X.Y.Z.apk` signé, et `companion-X.Y.Z.json` : son entrée pour le « serveur Unowhy » |
| `updates` | serveur central des mises à jour de la tablette (registre de paquets du projet) : `manifest.json` et l'APK de la dernière version |
| `release` | release GitLab du tag, fichiers déposés un par un dans le registre de paquets du projet (`glab`, jeton du job) |

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

`scripts/updates-publish.mjs` (à la racine) lit l'entrée `companion-X.Y.Z.json` d'`android-release`, calcule taille et SHA-256, dépose l'APK puis `manifest.json` (le manifest ne désigne jamais un fichier absent), puis supprime les anciens fichiers du paquet : seule la dernière version reste. Un même nom déposé deux fois se télécharge dans sa dernière version (vérifié le 27/10 sur un paquet d'essai). Essai local sans dépôt : `node scripts/updates-publish.mjs dist updates` ; avec dépôt, `UPDATES_URL=<base d'un paquet d'essai>` et `GITLAB_TOKEN` (jeton personnel).

**Alimenter le « serveur Unowhy » d'un ordinateur** (mise à jour de la tablette depuis l'ordinateur, sans le serveur central) : télécharger de la release `companion-X.Y.Z.apk` et `companion-X.Y.Z.json` dans un même dossier, puis sur l'ordinateur, dans `backend/` :

```bash
npm run updates:add -- ~/Téléchargements/companion-1.2.0.json                  # données du dépôt (backend/.data)
DATA_DIR=paquet npm run updates:add -- ~/Téléchargements/companion-1.2.0.json  # compagnon installé par paquet
```

`scripts/updates.ts` copie l'APK dans `<données>/updates/` et remplace la version précédente du Companion dans `manifest.json` ; les autres entrées (WebView) restent. La tablette la voit dans Réglages > Mises à jour.

Changement de signature : Android refuse une mise à jour signée par une autre clé. Une tablette qui a reçu l'APK debug (adb, artefact du job `android`) n'acceptera l'APK de release qu'après désinstallation du Companion, donc, en kiosque, après avoir refait la procédure de [kiosque.md](kiosque.md) avec l'APK de release.

### Déployer un essai depuis une MR

Pour essayer l'appli d'une MR sur une tablette en kiosque, sans fusionner ni tagger. La tablette n'accepte qu'un APK signé par la clé de release, d'un `versionCode` plus grand : seul un pipeline de ref protégée voit la clé.

1. Une fois : protéger les branches `essai/*` (Settings > Repository > Protected branches, Allowed to merge et Allowed to push : Maintainers).
2. Depuis la branche de la MR, la pousser sur une branche d'essai à son numéro : `git push origin HEAD:essai/mr-<iid>`. Les commits suivants de la MR s'y poussent de la même façon. Push refusé (MR rebasée, branche protégée sans force push) : supprimer la branche dans Code > Branches, puis repousser. Supprimer aussi la branche quand l'essai est fini.
3. Dans le pipeline de `essai/mr-<iid>`, lancer le job manuel `essai-android` (Build > Pipelines). `essai-updates` suit seul et écrit dans son journal la commande à lancer.
4. Sur l'ordinateur de la tablette, dans `backend/` (`GITLAB_TOKEN`, jeton personnel `read_api`, ou `UPDATE_DEPLOY_TOKEN` dans `backend/.env`) :

```bash
npm run updates:essai                   # compagnon lancé depuis le dépôt (backend/.data)
DATA_DIR=paquet npm run updates:essai   # compagnon installé par paquet
```

5. Sur la tablette : Réglages > Mises à jour. Sans rien toucher, elle l'installe aussi seule à sa prochaine vérification (démarrage, puis toutes les 6 h), comme toute tablette reliée à cet ordinateur.

Version : `X.Y.Z-essai.N`, X.Y.Z = dernier tag de la branche, N = numéro du pipeline ; code = celui du tag + 1 + N mod 999 (`app/build.gradle.kts`), au-dessus du tag installé et sous le tag suivant. Tous les 999 pipelines, le code repart du bas : un essai peut alors être plus petit que le précédent et la tablette le refuse ; relancer un pipeline ne suffit pas, poser un tag. L'APK d'essai lit les mises à jour du serveur central dans `latest`, comme une release : la tablette repasse seule à la release suivante. Pas de retour à une version plus ancienne (Android refuse un code plus petit).

`essai-updates` dépose l'APK et son `manifest.json` dans le paquet générique `companion-updates`, version `essai`, jamais `latest` : aucune tablette ne lit ce paquet seule. Un seul essai à la fois, le dernier remplace. `npm run updates:essai` (`backend/scripts/updates.ts`) le télécharge, vérifie taille et SHA-256, puis fait comme `updates:add`. Autre paquet : `UPDATES_URL=<base>`.

Sécurité : l'APK d'essai porte la clé de release et le deploy token des tablettes, comme une release. Les jobs ne tournent que sur une branche protégée (`$CI_COMMIT_REF_PROTECTED`, sinon pas de job), la clé n'est jamais affichée, l'artefact expire au bout d'un jour. Sur une branche `essai/*`, aucun autre job ne tourne (les tests sont ceux de la MR).

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

## Agents Claude (hooks)

`cd backend && npm run agents:hooks` affiche le bloc de hooks HTTP à fusionner dans `~/.claude/settings.json` ; `-- --install` le fusionne (sauvegarde `settings.json.bak-agents-<date>` avant) et `-- --remove` le retire, sans toucher aux autres hooks (Orca, scripts perso). `--file <chemin>` vise un autre fichier, `--port` et `--hold <s>` règlent l'adresse et l'attente. `--install --statusline` enveloppe aussi la statusline (seule à recevoir le quota 5 h / 7 j) : sa sortie ne change pas, son entrée part en plus au compagnon (`POST /agents/statusline`, en arrière-plan, `curl`) ; `--remove` rend la statusline d'origine. Le secret et le port viennent des données de CET ordinateur : pour le compagnon installé par paquet, `DATA_DIR="$HOME/Library/Application Support/UnowhyCompanion" npm run agents:hooks`. Sans hooks, l'état se lit quand même (`claude agents --json`) ; répondre depuis la tablette demande les hooks, posés à la main par l'utilisateur. Les sessions déjà ouvertes les prennent au prochain démarrage ; compagnon éteint, chaque hook affiche une erreur de connexion dans le terminal, sans rien bloquer.

Essai sans toucher à son `settings.json` : `npm run agents:hooks -- --install --file /tmp/essai-settings.json --port 3921`, puis `claude --settings /tmp/essai-settings.json` contre un compagnon de test (`HOST=127.0.0.1 PORT=3921 COMPANION_TOKEN= DATA_DIR=/tmp/companion-agents AGENTS_ALWAYS_ALERT=1 npx tsx src/server.ts`). `AGENTS_ALWAYS_ALERT=1` alerte même si un terminal est devant (sinon le compagnon se tait : l'utilisateur est sur Claude) ; `AGENTS_HOLD_S` (90) est l'attente d'une réponse ; `AGENTS_POLL_MS` (4000) le rythme du filet. Pas de tablette : un script qui fait son long-polling sur `/tablet/poll` et répond sur `/agents/answer` suffit. `CLAUDE_BIN` et `ORCA_BIN` forcent les chemins de `claude` et d'`orca` (tests). Mac seulement : `agents` est absent de `/deck/features` ailleurs et `/agents/*` répondent 501.

## Extension de navigateur (onglets et page Média sur la tablette)

`backend/extension/` (Manifest V3, Chromium : Opera, Chrome, Edge, Brave) envoie les onglets du navigateur à `POST http://127.0.0.1:3000/browser/extension` (sans jeton, origine `chrome-extension://` exigée) à chaque changement et chaque minute ; le compagnon les garde en mémoire 3 min (`src/browser-extension.ts`). Le paquet la copie dans « Extension navigateur ». Installer : `opera://extensions` (ou `chrome://extensions`), Mode développeur, « Charger l'extension non empaquetée », choisir ce dossier. Après une mise à jour de l'extension : bouton Recharger sur la même page. Après un redémarrage du compagnon, la liste revient au prochain changement d'onglet (au plus 1 min). Depuis la 1.1.0 (27/10), elle est dans les deux paquets et pilote aussi la lecture (page Média) : elle attend les ordres en long-polling (`POST /browser/extension/commands`, local, origine d'extension exigée, ordre suivant envoyé avec le résultat du précédent, lu dans le journal : `extension : ordre de lecture`), joue ou met en pause le lecteur de l'onglet qui joue, et Suivant/Précédent passent par les actions Media Session du site, gardées par `media-session.js` (script de page, `document_start` : recharger l'onglet après avoir rechargé l'extension).

## Lecture en cours sur Mac (MediaRemote)

`src/mac/NowPlaying.dylib` (arm64 + x86_64) est compilée par `npm run build:nowplaying` (clang des Command Line Tools) depuis `src/mac/nowplaying/NowPlaying.m` et gardée dans le dépôt ; recompiler après toute modification. Elle est chargée par `/usr/bin/perl` (`DynaLoader`), qui fait partie des programmes d'Apple encore admis par MediaRemote depuis macOS 15.4 : une action par lancement (`NP_ACTION` = `get`, `art`, `play-pause`, `next`, `previous`, `seek`). Infos par `MRNowPlayingRequest` (immédiat) ; `MRMediaRemoteGetNowPlayingInfo` ne rappelle pas toujours (Opera en lecture), il ne sert qu'à la pochette, 1,5 s au plus. Essai à la main : `/usr/bin/perl -e '$ENV{NP_ACTION}="get"; require DynaLoader; DynaLoader::dl_load_file($ARGV[0])' "$PWD/src/mac/NowPlaying.dylib"` (chemin absolu obligatoire). En production, signer la bibliothèque (Developer ID) pour ne pas alerter un antivirus d'entreprise.

## Fabriquer les paquets clients (sans Node à installer)

Sur le Mac (ou sous Linux, comme le job `package` d'une release ; `zip`, `unzip` et `tar` requis), dans `backend/` : `npm run package` (`scripts/package.ts`). Résultat dans `dist/` à la racine (ignoré par git) :

- `Compagnon-Unowhy-Windows.zip` (~34 Mo) et `Compagnon-Unowhy-Mac.zip` (~76 Mo, node Apple Silicon et Intel) ;
- dans chaque zip, un dossier « Compagnon Unowhy » : l'installeur à double-cliquer, `backend/dist/server.mjs` et `service.mjs` (sources regroupées par esbuild), `backend/config` (sans `deck.json` ni `tasks.json`), `UnowhyHelper.exe` et `UnowhyCompanion.exe` (lanceur) pour Windows, et le node officiel de nodejs.org (ligne 22, téléchargé une fois dans `backend/.cache/node`, vérifié par SHA-256).

Les installeurs prennent `backend/node/…` s'il existe (sans `npm install`), sinon le Node du système comme avant ; Le lanceur Windows et `service.ts` lancent `dist/server.mjs` s'il existe, sinon `src/server.ts` par tsx. Commandes du service dans un paquet : `node\node.exe dist\service.mjs status|stop|start|uninstall` (Windows), `node/darwin-arm64/node dist/service.mjs …` (Mac).

Données d'un ordinateur installé par paquet : hors du dossier extrait, dans `%LOCALAPPDATA%\UnowhyCompanion\data` (Windows) ou `~/Library/Application Support/UnowhyCompanion` (Mac) (`src/paths.ts`). Mettre à jour = extraire le nouveau zip n'importe où et relancer son installeur : l'identité est gardée, la tablette retrouve l'ordinateur. Le dossier Partage du paquet est `Documents\Partage tablette` (Documents lu dans le registre sous Windows, OneDrive compris). Un paquet plus ancien gardait `backend/.data` et `shared/` dans le dossier extrait : ils sont recopiés une fois au premier lancement (les originaux restent). Sur Mac, l'autorisation Accessibilité est donnée au node du paquet. Sur Windows, l'installeur ouvre le pare-feu au `node.exe` du paquet (`scripts/firewall-windows.ps1`, règle « Compagnon Unowhy », réseaux privés, accord administrateur seulement si la règle manque ou vise un autre dossier).

Testés sur un vrai PC Windows (installeur, données hors du dossier, Partage, raccourci, relance après un redémarrage) et sur un vrai Mac le 25/10 (client neuf : installeur, Application Support, Partage dans Documents, raccourci `.webloc`, Accessibilité donnée au node du paquet, appairage ; pas encore vu : relance à l'ouverture de session). Sur un Mac avec oh-my-zsh, sa question de mise à jour avale le début de la commande du `.command` : répondre à la question, puis relancer.

## Pièges connus

- **Pilote Windows** : C# 7.3, .NET Framework 4.8 (`src/win/helper/`, lanceur dans `src/win/launcher/`), compilé par `npm run build:helper` avec le SDK .NET 8 figé par `backend/global.json` (sur le Mac : `~/.dotnet`, installé par le script officiel `dotnet-install.sh --version <version de global.json>`). `helper-windows` vérifie en CI que les exe du dépôt correspondent aux sources. `npm run package` embarque la version de node fixée dans `scripts/package.ts` (`NODE_VERSION`) : la changer à la main pour mettre node à jour. Les exe compilés sont gardés dans le dépôt : recompiler après toute modification du C#. Jamais de PowerShell lancé par le compagnon (antivirus).
- **Mac** : le compagnon a besoin de l'Accessibilité (pour `node`, ou pour le terminal qui lance `npm run dev`). Sans elle : raccourcis, fenêtres et macros échouent (bandeau sur le Deck).
- Redémarrer le compagnon actif fait basculer la tablette sur un autre ordinateur qui répond. Il faut ensuite revenir à la main (nom en bas de la barre latérale).
- Le compagnon lancé depuis une session (terminal, éditeur) meurt avec elle. La tablette affiche alors l'ordinateur en rouge et « Aucun événement ».
- Un champ de texte construit dans le code doit être gonflé depuis `item_input.xml` : un `EditText` construit avec un style perd le focus et le curseur.
- Toute saisie dans une boîte de dialogue passe par `showForTyping` : sinon le clavier cache la boîte.

## Avec Claude Code

`CLAUDE.md` à la racine donne à Claude le contexte et les conventions. Il est versionné : il voyage avec le bundle. Les notes de session de Claude, elles, restent sur l'ordinateur où il tourne : ce qui compte est recopié dans [etat-du-projet.md](etat-du-projet.md). Le serveur MCP `tablette` (`.mcp.json`) permet à Claude de piloter la tablette.

**Passe qualité d'un écran** : skill Claude `design-companion` (`.claude/skills/`, local et non versionné comme `/ship`) (`critique`, `etats`, `polish`, `distill`, `harden`, `animate`), pour l'appli et les pages du backend ; première passe dans `docs/team/design-companion/premiere-passe.md`.

**Changer d'ordinateur de développement** (par exemple du Mac au PC Windows, avec un autre compte Claude) : commiter, `scripts/sauvegarde.sh`, et joindre les notes de Claude (`~/.claude/projects/<projet>/memory/`, zippées) au bundle et aux données perso. Sur l'autre ordinateur, on clone le bundle, puis on demande à Claude de recopier les notes dans sa propre mémoire. Pour revenir : commiter, `git bundle create retour.bundle --all`, rezipper les notes, puis `git pull retour.bundle main` et fusionner les notes sur l'ordinateur d'origine. Le serveur MCP `tablette` lit les données du paquet installé sur Mac comme sur Windows (`DATA_DIR=paquet` dans `.mcp.json`). Sur Windows, sa commande `backend/node_modules/.bin/tsx` (script shell) peut ne pas démarrer : s'il n'apparaît pas, le déclarer en local avec `claude mcp add`, sans modifier `.mcp.json`.
