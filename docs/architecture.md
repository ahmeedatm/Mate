# Comment ça marche

Pour comprendre le projet avant d'y toucher. Installation et commandes : [README](../README.md) ; travailler dessus : [developpement.md](developpement.md) ; où on en est : [etat-du-projet.md](etat-du-projet.md).

## L'idée en une phrase

Une tablette Unowhy 4.2 (Android 10, lente) posée à côté de l'ordinateur sert d'écran de contrôle : tableau de bord, Stream Deck, fenêtres, son, agenda, tâches, visualiseur de documents. **La tablette ne fait que l'interface.** Tout ce qui agit sur l'ordinateur (lancer une appli, taper un raccourci, lire l'agenda, bouger une fenêtre) est fait par un petit programme installé sur l'ordinateur : le « compagnon » (dossier `backend/`).

```
┌────────────── Tablette (app/) ──────────────┐            ┌──────── Ordinateur Mac ou Windows (backend/) ────────┐
│ Appli Android Kotlin, en kiosque            │  WiFi      │ Node 22 + Fastify, port 3000                          │
│ MainActivity + un Fragment par mode         │ ─HTTP────▶ │ server.ts : jeton ; routes/ : routes par domaine      │
│ BackendClient (HttpURLConnection)           │  + jeton   │ deck.ts → os.ts ─┬─ mac/ (osascript, JXA)             │
│ RemoteChannel (long-polling) ◀──────────────│─ ordres ── │ tablet.ts (file   └─ win/ (UnowhyHelper.exe, C#)      │
│ Discovery (UDP) ◀──────── diffusion ────────│─ UDP ───── │   d'ordres)        calendar, tasks, events, camera…   │
└─────────────────────────────────────────────┘            │ .data/ : données de cet ordinateur                   │
        │ Open-Meteo (météo, sans clé)                     └───────────────────────────────────────────────────────┘
        ▼                                                     ▲ mcp.ts (Claude Code / Desktop) relaie vers /tablet/command
```

Pas de serveur dans le cloud : chaque ordinateur a son compagnon, la tablette parle directement à celui qu'elle pilote. La seule chose prévue dans le cloud, c'est le serveur de mises à jour (simulé par le compagnon pour la démo). Pas d'IA : l'Assistant IA a été retiré le 26/10/2026.

## Les deux programmes

### La tablette (`app/`)

- **Une seule Activity** (`MainActivity`) plein écran, barre latérale à gauche (modes, heure, WiFi, batterie, nom de l'ordinateur), un **Fragment par mode** : Accueil, Deck, Depuis l'ordi, Focus, Caméra, Réglages. Les fragments sont créés une fois et cachés/montrés ; ils sont recréés quand on change d'ordinateur (`onComputerChanged`).
- **Panneau document** (`DocumentPanel`) à droite : document du dossier partagé ou page web de l'ordinateur, à côté de n'importe quel mode ou en grand.
- **Kiosque** : l'appli est *device owner* (installée sur une tablette réinitialisée, voir [kiosque.md](kiosque.md)). Elle verrouille l'écran sur elle-même (lock task), masque l'agent SQOOL, bloque les installations, s'installe elle-même ses mises à jour et règle le WiFi.
- Vues XML, pas de Compose ; dépendances : appcompat, lifecycle, coroutines. HTTP par `HttpURLConnection` + `org.json`. Tout doit rester léger (MT8167, 2 Go de RAM).

### Le compagnon de l'ordinateur (`backend/`)

- **Node 22, TypeScript lancé par `tsx`** (pas de compilation), **Fastify**. Démarre seul à l'ouverture de session (`scripts/service.ts` : LaunchAgent sur Mac, clé Run + lanceur sans fenêtre `src/win/UnowhyCompanion.exe` sur Windows).
- **`server.ts`** vérifie le jeton `Authorization: Bearer`, traduit les erreurs, enregistre les routes et lance les tâches de fond. Les routes (contrat de l'API) sont dans **`routes/`**, un plugin Fastify par domaine (`pairing.ts` pour `/health` et `/pair/*`, `local.ts`, `deck.ts`, `windows.ts`, `system.ts`, `docs.ts`, `browser.ts`, `camera.ts`, `calendar.ts`, `tasks.ts`, `calls.ts`, `tablet.ts`, `updates.ts`) ; les hooks posés dans `server.ts` valent pour tous. Sans jeton : `/health`, `/pair/*`, la page locale `GET /` (seulement depuis l'ordinateur).
- **`os.ts`** est le seul point d'entrée vers le système : il appelle `mac/` ou `win/` selon l'OS. Ajouter une action système = l'écrire dans les deux.
  - **Mac** (`src/mac/`) : `osascript` en AppleScript ou JXA. Frappes en CGEvent (System Events refuse les saisies), fenêtres par l'Accessibilité + NSScreen + CoreGraphics. Demande l'autorisation Accessibilité.
  - **Windows** (`src/win/`) : un **programme résident `UnowhyHelper.exe`** (C# .NET Framework 4.8, compilé d'avance : `helper/Native*.cs` pour fenêtres, son, frappes, une classe partielle découpée par thème ; `helper/Program.cs` pour l'UI Automation ; `helper/Media.cs` pour les contrôles multimédias WinRT). `helper.ts` lui envoie des commandes JSON ligne par ligne. L'exe est copié dans `%LOCALAPPDATA%\UnowhyCompanion\helper` avant lancement pour ne pas verrouiller l'original. Pas de PowerShell : un script qui compile du C# de frappes à la volée était classé malveillant par Bitdefender.
- **`.data/`** (non versionné) : tout ce qui est propre à cet ordinateur (voir « Où sont les données »).

## Les flux principaux

### 1. Appairage et découverte

1. Le compagnon crée au premier lancement son identité et son jeton (`.data/identity.json`) et répond à une diffusion UDP sur le WiFi (`pairing.ts`).
2. La tablette cherche les ordinateurs (`net/Discovery.kt`), ou on saisit l'adresse.
3. L'ordinateur affiche un code à 6 chiffres (page `http://127.0.0.1:3000/`). La tablette l'envoie à `/pair/*` et reçoit le jeton.
4. La tablette garde une **liste d'ordinateurs** (`Computers.kt` : id, nom, url, jeton, os) et pilote l'actif. Si l'actif ne répond plus, `net/Connection.kt` retrouve sa nouvelle adresse par la recherche, ou bascule vers un autre ordinateur qui répond.

### 2. Une requête ordinaire (tablette → ordinateur)

`BackendClient` envoie du JSON avec le jeton ; les erreurs sont traduites en phrase par `errorText(e)` (`ui/Errors.kt`), jamais affichées brutes. Côté compagnon, `setErrorHandler` remplace les erreurs techniques par une phrase en français.

### 3. Les ordres de l'ordinateur vers la tablette (canal `/tablet`)

La tablette n'ouvre aucun port. `remote/RemoteChannel.kt` garde une requête `POST /tablet/poll` ouverte (long-polling) ; le compagnon y dépose les ordres (`tablet.ts`) : rappel parlé d'une tâche ou d'un événement, message, changement de mode, capture d'écran… Livraison « au moins une fois », dédoublonnée par la tablette. C'est aussi par là que passe **l'extension Claude** (`mcp.ts`, serveur MCP qui relaie vers `POST /tablet/command`). Si l'écran de la tablette est éteint, l'appli est en pause et les ordres attendent.

### 4. Le Deck

- Les touches sont définies **uniquement côté ordinateur** : `config/deck.json` (départ copié de `deck.example.json` ou `deck.example.windows.json`, filtré aux applis installées), touches perso (`.data/keys.json`, `perso.ts`), applis ajoutées (`.data/apps.json`), sites de la page Média (`.data/links.json`).
- La tablette reçoit les pages (`GET /deck`) et n'envoie qu'un **identifiant de touche** ; `runAction` (`deck.ts`) exécute les étapes (app, keys, text, url, media, system, wait, window, macro). Aucune commande arbitraire ne vient de la tablette.
- Onglets : pages fixes, puis pages d'appli (Discord, Réunion) tant que l'appli tourne ou est en appel (`GET /deck/live`). On change d'onglet en touchant ou en balayant la grille.
- **Ouvertes** : toutes les fenêtres de l'ordinateur sur le schéma de ses écrans (`OpenWindowsView` + `ScreenMap`, `GET/POST /windows/open`), relu toutes les 5 s.
- **Média** (Windows et Mac) : lecture en cours lue dans les contrôles multimédias de Windows (`/system/media`) ou, sur Mac, par MediaRemote (« À l'écoute », `src/mac/media.ts` + `NowPlaying.dylib` chargée par `/usr/bin/perl`, seul accès possible depuis macOS 15.4 : risque qu'Apple le ferme, secours par les titres d'onglets) ; musique ouverte dans l'appli du site si elle est installée, sinon le site (`music.ts`). Sur Mac, les commandes passent par l'extension du navigateur qui joue quand elle est chargée (dans la page : lecteur, actions Media Session), sinon par MediaRemote.

### 5. Agenda, tâches, rappels

- Agenda (`calendar.ts`) = liens ICS (`.data/calendar.json`) + comptes Microsoft/Google (`accounts.ts`, OAuth, pas encore activé : identifiant d'appli à obtenir) + agenda personnel noté depuis la tablette (`events.ts`). Réunions en ligne détectées dans les événements (`meeting-links.ts`), ouvertes sur l'ordinateur par `POST /calendar/join`.
- Tâches (`tasks.ts`) : ponctuelles, récurrentes (créées depuis la tablette), ou quotidiennes écrites dans `config/tasks.json` (exemple : saisir son temps dans Jira, cochée seule via `jira.ts`). Les rappels partent de l'ordinateur par le canal `/tablet` et sont lus à voix haute par la tablette (`audio/Speaker`).
- Eau et Focus (Pomodoro, ambiance) sont **sur la tablette seulement** (`focus/`).

### 6. Visualiseur (mode Caméra)

`camera/DocCamera.kt` (Camera2) envoie du JPEG 720p à ~10 i/s à `POST /camera/frame` ; le compagnon garde la dernière image et la sert en MJPEG sur `http://127.0.0.1:3000/camera`, à projeter. « Envoyer une photo » : photo pleine taille dans le dossier Partage.

### 7. Commande vocale (bouton Parler)

Pas d'IA : la reconnaissance est celle d'Android (`SpeechRecognizer`, servie par l'appli Google de l'image d'usine : en ligne, sans clé ni compte ; à ne pas désactiver dans la procédure de kiosque). La phrase est comprise **sur la tablette** par une liste de tournures (`voice/VoiceCommands.kt`) : modes, son et micro, musique, Focus, ambiance, fenêtres (comme l'onglet Ouvertes), tâches et journée, rappels, recherche, dictée (`POST /system/type`), appels ; puis toute touche du Deck par son nom, puis toute appli installée (`POST /apps/launch`). Les noms d'applis mal entendus sont rapprochés à l'oreille (prononciation approximative + distance d'édition, sur les 5 variantes du reconnaisseur : « en cabane launcher » → Ankama Launcher). `voice/VoiceControl.kt` écoute et exécute ; appui long = commande écrite (émulateur sans reconnaissance). Journal : `adb logcat -s Voice` (niveau info : la tablette jette le debug).

### 8. Appels entrants (Windows)

`calls.ts` lit le titre des fenêtres toutes les 2 s (« Appel entrant - Discord »), puis l'interface une fois pour le nom de l'appelant, et prévient la tablette par le canal `/tablet` (ordre `call`). `ui/CallOverlay.kt` : carte Répondre / Vidéo / Ignorer, qui appuient sur les vrais boutons (`/calls/answer`, `/calls/decline`, UI Automation) ; Répondre ouvre la page de l'appli dans le Deck ; « Appel manqué » sinon. Règles par appli dans `config/calls.json` (titre, appelant, noms des boutons) : une appli de plus = une capture pendant un vrai appel (`GET /deck/buttons?app=…&detail=true`). Discord seulement pour l'instant.

### 9. Mises à jour de la tablette

Deux sources, même format (`{"updates":[{package, label, versionCode, versionName, file, size, sha256}]}`) :

- **L'ordinateur relié** (« serveur Unowhy » simulé, `updates.ts` : `.data/updates/manifest.json` + APK), avec le jeton d'appairage. Il sert aussi le WebView.
- **Le serveur central** : registre de paquets GitLab du projet (`BuildConfig.UPDATE_URL`, `GET <base>/manifest.json` puis `GET <base>/<file>`), rempli par la CI à chaque tag `vX.Y.Z`. Marche sans ordinateur. En-tête `BuildConfig.UPDATE_HEADER` (« Deploy-Token: … », lecture seule, injecté par la CI au build release, vide en debug). Rien de publié (404), accès refusé (401) ou page HTML : « aucune mise à jour », pas d'erreur.

`kiosk/Updater.kt` interroge les deux en parallèle et fusionne (`kiosk/UpdatePlan.kt` : par paquet, la plus haute `versionCode` ; à égalité, l'ordinateur). Il télécharge, vérifie le SHA-256 et installe en silence (device owner), une installation à la fois. `kiosk/AutoUpdater.kt` vérifie au démarrage puis toutes les 6 h (échéance relue tous les quarts d'heure) et installe seul la version la plus récente du Companion, en device owner seulement, sans rien afficher. La carte Mises à jour de Réglages montre les deux sources (nom de la source sur chaque ligne, une ligne par source en erreur). `kiosk/UpdatedReceiver` relance l'appli en kiosque après sa propre mise à jour.

### 10. Agents Claude (Mac)

La tablette montre les sessions Claude Code de l'ordinateur (qui travaille, qui a fini, qui attend) et y répond. `agents.ts` tient l'état par `session_id`, en mémoire, de trois sources : `claude agents --json` (toutes les 4 s, seulement tablette connectée : le filet), les hooks HTTP de Claude Code (`POST /agents/hook`, local, secret propre dans `.data/agents-hook.json`, posés par `npm run agents:hooks`) et `orca worktree ps --json` si Orca est là (branche, terminal). États : travaille, attend, fini, limite, inactif. La tablette est prévenue par l'ordre `agent` du canal `/tablet` (lumière de bord et son), sauf si l'appli de devant est un terminal ou Orca : l'utilisateur n'est alors pas prévenu. Répondre : le hook `PermissionRequest` (permission) ou `PreToolUse` sur `AskUserQuestion` (question à choix) reste ouvert jusqu'à `POST /agents/answer` ou `AGENTS_HOLD_S` (90 s), puis reçoit `{}` : le terminal décide. Sur la tablette, la carte de la demande (`AgentAskOverlay`) s'ouvre en touchant la lumière de bord ou une session de l'onglet : permission (Autoriser une fois / Refuser ; une commande à risque, `isRisky` côté compagnon, s'autorise en maintenant le bouton) ou questions à choix, une page par question, multi-choix, « Autre » écrit ou dicté. Sur une session au repos dans un terminal Orca (volet exact donné par `$ORCA_PANE_KEY` en en-tête du hook, terminal par `orca terminal list`), « continue » ou une phrase partent par `POST /agents/send` (`orca terminal send`) ; après une limite d'usage (`StopFailure` `rate_limit`), la tablette peut armer l'envoi de « continue » à l'heure de reprise lue dans le message. Les sessions de chat Orca et les terminaux ordinaires n'ont pas de moyen fiable : pas de bouton. Jamais de décision sans action sur la tablette, ni « toujours autoriser ». Détails et essais : [recherche-agents-claude.md](recherche-agents-claude.md).

## Carte du code

### Tablette : `app/src/main/kotlin/com/unowhy/companion/`

| Où | Quoi |
|---|---|
| `MainActivity.kt` | câblage des modes (`Mode.kt`), panneau document, changement d'ordinateur ; barre latérale `ui/Rail`, clavier `ui/KeyboardControl`, bandeau `ui/Banner`, rappels Eau et batterie `ui/TabletAlerts`, Ne pas déranger `focus/FocusDnd`, canal `remote/RemoteLink` |
| `Prefs.kt`, `Computers.kt` | préférences, liste des ordinateurs appairés |
| `net/` | `BackendClient` (point d'accès : un client par domaine, `DeckApi`, `WindowsApi`, `SystemApi`, `DocsApi`, `CalendarApi`, `TasksApi`, `CameraApi`, `UpdatesApi`, `TabletApi`, `AgentsApi`, `PairingApi`), `Http` (plomberie, `HttpException`), `Connection` (bascule), `Discovery` (UDP), `Wifi` |
| `remote/RemoteChannel.kt` | ordres reçus de l'ordinateur |
| `ui/` | un fichier par écran ou composant : `HomeFragment`, `DeckFragment`, `FromComputerFragment` (Depuis l'ordi : choix Fichiers / Page de l'ordi, puis la liste ; `BrowserTabsDialog` : onglets de l'ordi), `FocusFragment`, `CameraFragment`, `SettingsFragment`, `WelcomeView` (premier lancement), `DocumentPanel`, boîtes de dialogue (`TaskDialog`, `EventDialog`, `ComputerAdder`, `WifiPicker`…) |
| `ui/home/` | Accueil à widgets : `WidgetModel` (grille 4×4, tailles, modèles), `WidgetGrid` (placement, mode Modifier, pages), widgets |
| `ui/ScreenMap.kt`, `WindowPicker.kt`, `OpenWindowsView.kt` | écrans et fenêtres de l'ordinateur |
| `focus/` | `FocusTimer`, `NoisePlayer` (bruit généré), `Water` |
| `audio/` | `Speaker` (synthèse vocale) |
| `voice/` | commande vocale : `VoiceCommands` (phrases comprises, noms rapprochés à l'oreille), `VoiceControl` (écoute, exécution) |
| `ui/CallOverlay.kt` | carte d'appel entrant |
| `agents/`, `ui/AgentsView.kt`, `ui/AgentLight.kt`, `ui/AgentAskOverlay.kt`, `ui/HoldButton.kt`, `voice/Dictation.kt`, `ui/home/AgentsWidget.kt`, `audio/AgentSounds.kt` | agents Claude : relevé partagé des sessions (`AgentsFeed`, 3 s tant qu'une vue les montre), alertes de l'ordre `agent` (`AgentAlerts` : lumière de bord + son court, tus pendant le Focus sauf « attend »), onglet Agents du Deck (après « Son », si `/deck/features` annonce `agents`), widget d'Accueil |
| `camera/DocCamera.kt` | caméra du visualiseur |
| `device/` | batterie, signal, luminosité (atténuée la nuit) |
| `kiosk/` | `Kiosk` (verrouillage, SQOOL masqué), `Updater`, `UpdatePlan`, `AutoUpdater`, `UpdatedReceiver`, `KioskAdminReceiver` |
| `res/values/strings.xml` | tous les textes (français) ; `attrs.xml` + `palettes.xml` (couleurs des 4 thèmes), `themes.xml` pour le style ; `ui/Themes.kt`, `ui/Look.kt` |

### Ordinateur : `backend/src/`

| Fichier | Quoi |
|---|---|
| `server.ts` | démarrage, jeton, erreurs, enregistrement des plugins de routes |
| `routes/` | routes de l'API, un plugin Fastify par domaine |
| `config.ts` | variables d'environnement (`.env`) |
| `pairing.ts` | identité, jeton, code d'appairage, découverte UDP |
| `local-page.ts` | page `http://127.0.0.1:3000/` (adresse, code, agenda) |
| `deck.ts`, `perso.ts`, `music.ts` | pages du deck, `runAction`, touches perso, musique |
| `os.ts` → `mac/`, `win/` | pilotage du système ; `screens.ts` : types d'écrans et fenêtres |
| `calendar.ts`, `accounts.ts`, `events.ts`, `meetings.ts`, `meeting-links.ts` | agenda et réunions |
| `tasks.ts`, `jira.ts` | tâches et rappels |
| `tablet.ts`, `mcp.ts` | canal vers la tablette, extension Claude |
| `calls.ts` (+ `config/calls.json`) | appels entrants : surveillance des fenêtres, Répondre / Ignorer / Raccrocher |
| `agents.ts`, `agents-sources.ts`, `agents-secret.ts`, `agents-hooks.ts`, `routes/agents.ts` | agents Claude : état et réponses, lecture de `claude` et d'Orca, secret des hooks, bloc de hooks (`scripts/agents-hooks.ts`) |
| `camera.ts`, `docs.ts` | visualiseur, dossier partagé |
| `updates.ts` | mises à jour de la tablette |

## Où sont les données

| Où | Quoi | Dans git ? |
|---|---|---|
| Tablette, préférences de l'appli | ordinateurs appairés et jetons, disposition de l'Accueil, eau, réglages | non |
| `backend/.data/` (sur chaque ordinateur) | identité et jeton, agendas reliés, comptes, agenda perso, tâches, touches perso, applis, sites, réunions, mises à jour | non (perso) |
| `backend/config/deck.json`, `tasks.json` | deck et tâches quotidiennes de cet ordinateur | non (perso) |
| `backend/config/*.example*.json`, `accounts.json` | modèles de départ, identifiants publics OAuth | oui |
| `backend/.env` | secrets et réglages (`.env.example` pour la liste) | non |
| `shared/` | dossier Partage par défaut | non |

## Ajouter une fonction : les recettes

- **Nouvelle route** : la déclarer dans le plugin de son domaine sous `routes/` (avec son schéma ; nouveau plugin = `app.register` dans `server.ts`), l'appeler dans le client de son domaine (`net/*Api.kt`). Erreur pour l'utilisateur = `throw new Error("phrase en français")`.
- **Nouvelle action système** : l'ajouter à `os.ts`, l'écrire dans `mac/` **et** `win/` (C# 7.3 dans `win/helper/`, `Native.<thème>.cs` pour le pilotage, puis `npm run build:helper`).
- **Nouvelle étape de touche** : type dans `deck.ts` (`runAction`), et dans `KeyEditor` si elle se règle depuis la tablette.
- **Nouvel ordre vers la tablette** : type dans `tablet.ts` + schéma dans `routes/tablet.ts` + outil dans `mcp.ts` + branche dans `RemoteChannel.execute`.
- **Nouveau widget d'Accueil** : le déclarer dans `WidgetModel`, le construire dans `HomeWidgets` (ou un fichier dédié de `ui/home/`).
- **Nouveau texte** : `strings.xml`, en français, court (la tablette est lue de loin), « ordinateur » (« ordi » sur les boutons courts).

Les conventions détaillées (noms historiques `Mac*` = l'ordinateur, Mac ou Windows ; clavier ; dialogues ; pièges de l'UI) sont dans [CLAUDE.md](../CLAUDE.md), tenu à jour à chaque changement.
