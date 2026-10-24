# Comment ça marche

Pour comprendre le projet avant d'y toucher. Installation et commandes : [README](../README.md) ; travailler dessus : [developpement.md](developpement.md) ; où on en est : [etat-du-projet.md](etat-du-projet.md).

## L'idée en une phrase

Une tablette Unowhy 4.2 (Android 10, lente) posée à côté de l'ordinateur sert d'écran de contrôle : tableau de bord, Stream Deck, fenêtres, son, agenda, tâches, visualiseur de documents. **La tablette ne fait que l'interface.** Tout ce qui agit sur l'ordinateur (lancer une appli, taper un raccourci, lire l'agenda, bouger une fenêtre) est fait par un petit programme installé sur l'ordinateur : le « compagnon » (dossier `backend/`).

```
┌────────────── Tablette (app/) ──────────────┐            ┌──────── Ordinateur Mac ou Windows (backend/) ────────┐
│ Appli Android Kotlin, en kiosque            │  WiFi      │ Node 22 + Fastify, port 3000                          │
│ MainActivity + un Fragment par mode         │ ─HTTP────▶ │ server.ts : routes, jeton                             │
│ BackendClient (HttpURLConnection)           │  + jeton   │ deck.ts → os.ts ─┬─ mac/ (osascript, JXA)             │
│ RemoteChannel (long-polling) ◀──────────────│─ ordres ── │ tablet.ts (file   └─ win/ (PowerShell résident + C#)  │
│ Discovery (UDP) ◀──────── diffusion ────────│─ UDP ───── │   d'ordres)        calendar, tasks, events, camera…   │
└─────────────────────────────────────────────┘            │ .data/ : données de cet ordinateur                   │
        │ Open-Meteo (météo, sans clé)                     └───────────────────────────────────────────────────────┘
        ▼                                                     ▲ mcp.ts (Claude Code / Desktop) relaie vers /tablet/command
```

Pas de serveur dans le cloud : chaque ordinateur a son compagnon, la tablette parle directement à celui qu'elle pilote. La seule chose prévue dans le cloud, c'est l'IA de l'Assistant (`MODE=live`, pas encore branché) et le serveur de mises à jour (simulé par le compagnon pour la démo).

## Les deux programmes

### La tablette (`app/`)

- **Une seule Activity** (`MainActivity`) plein écran, barre latérale à gauche (modes, heure, WiFi, batterie, nom de l'ordinateur), un **Fragment par mode** : Accueil, Deck, Assistant, Partage, Focus, Caméra, Réglages. Les fragments sont créés une fois et cachés/montrés ; ils sont recréés quand on change d'ordinateur (`onComputerChanged`).
- **Panneau document** (`DocumentPanel`) à droite : document du dossier partagé ou page web de l'ordinateur, à côté de n'importe quel mode ou en grand.
- **Kiosque** : l'appli est *device owner* (installée sur une tablette réinitialisée, voir [kiosque.md](kiosque.md)). Elle verrouille l'écran sur elle-même (lock task), masque l'agent SQOOL, bloque les installations, s'installe elle-même ses mises à jour et règle le WiFi.
- Vues XML, pas de Compose ; dépendances : appcompat, lifecycle, coroutines. HTTP par `HttpURLConnection` + `org.json`. Tout doit rester léger (MT8167, 2 Go de RAM).

### Le compagnon de l'ordinateur (`backend/`)

- **Node 22, TypeScript lancé par `tsx`** (pas de compilation), **Fastify**. Démarre seul à l'ouverture de session (`scripts/service.ts` : LaunchAgent sur Mac, clé Run + `scripts/run-windows.ps1` sur Windows).
- **`server.ts`** déclare toutes les routes (contrat de l'API) et vérifie le jeton `Authorization: Bearer`. Sans jeton : `/health`, `/pair/*`, la page locale `GET /` (seulement depuis l'ordinateur).
- **`os.ts`** est le seul point d'entrée vers le système : il appelle `mac/` ou `win/` selon l'OS. Ajouter une action système = l'écrire dans les deux.
  - **Mac** (`src/mac/`) : `osascript` en AppleScript ou JXA. Frappes en CGEvent (System Events refuse les saisies), fenêtres par l'Accessibilité + NSScreen + CoreGraphics. Demande l'autorisation Accessibilité.
  - **Windows** (`src/win/`) : un **Windows PowerShell 5.1 résident** (`helper.ps1`) qui compile `native.cs` (C# 5 : fenêtres, son, frappes, UI Automation) et lit les contrôles multimédias WinRT. `helper.ts` lui envoie des commandes JSON ligne par ligne. Les deux fichiers sont copiés dans `%LOCALAPPDATA%\UnowhyCompanion\helper` avant lancement pour ne pas verrouiller les originaux.
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
- **Média** (Windows) : lecture en cours lue dans les contrôles multimédias de Windows (`/system/media`), musique ouverte dans l'appli du site si elle est installée (`music.ts`).

### 5. Agenda, tâches, rappels

- Agenda (`calendar.ts`) = liens ICS (`.data/calendar.json`) + comptes Microsoft/Google (`accounts.ts`, OAuth, pas encore activé : identifiant d'appli à obtenir) + agenda personnel noté depuis la tablette (`events.ts`). Réunions en ligne détectées dans les événements (`meeting-links.ts`), ouvertes sur l'ordinateur par `POST /calendar/join`.
- Tâches (`tasks.ts`) : ponctuelles, récurrentes (créées depuis la tablette), ou quotidiennes écrites dans `config/tasks.json` (exemple : saisir son temps dans Jira, cochée seule via `jira.ts`). Les rappels partent de l'ordinateur par le canal `/tablet` et sont lus à voix haute par la tablette (`audio/Speaker`).
- Eau et Focus (Pomodoro, ambiance) sont **sur la tablette seulement** (`focus/`).

### 6. Visualiseur (mode Caméra)

`camera/DocCamera.kt` (Camera2) envoie du JPEG 720p à ~10 i/s à `POST /camera/frame` ; le compagnon garde la dernière image et la sert en MJPEG sur `http://127.0.0.1:3000/camera`, à projeter. « Envoyer une photo » : photo pleine taille dans le dossier Partage.

### 7. Mises à jour de la tablette

Le compagnon joue le rôle d'un « serveur Unowhy » (`updates.ts` : `.data/updates/manifest.json` + APK). La tablette (`kiosk/Updater.kt`) télécharge et installe en silence (device owner) son propre APK ou le WebView ; `kiosk/UpdatedReceiver` la relance en kiosque après sa propre mise à jour. En production, ce serait l'infrastructure SQOOL (MDM/OTA).

## Carte du code

### Tablette : `app/src/main/kotlin/com/unowhy/companion/`

| Où | Quoi |
|---|---|
| `MainActivity.kt` | barre latérale, modes, clavier, rappel Eau, changement d'ordinateur |
| `Prefs.kt`, `Computers.kt` | préférences, liste des ordinateurs appairés |
| `net/` | `BackendClient` (toutes les routes), `Connection` (bascule), `Discovery` (UDP), `Wifi` |
| `remote/RemoteChannel.kt` | ordres reçus de l'ordinateur |
| `ui/` | un fichier par écran ou composant : `HomeFragment`, `DeckFragment`, `ChatFragment`, `DocsFragment` (Partage), `FocusFragment`, `CameraFragment`, `SettingsFragment`, `WelcomeView` (premier lancement), `DocumentPanel`, boîtes de dialogue (`TaskDialog`, `EventDialog`, `ComputerAdder`, `WifiPicker`…) |
| `ui/home/` | Accueil à widgets : `WidgetModel` (grille 4×4, tailles, modèles), `WidgetGrid` (placement, mode Modifier, pages), widgets |
| `ui/ScreenMap.kt`, `WindowPicker.kt`, `OpenWindowsView.kt` | écrans et fenêtres de l'ordinateur |
| `focus/` | `FocusTimer`, `NoisePlayer` (bruit généré), `Water` |
| `audio/` | `Speaker` (synthèse vocale), `VoiceRecorder` |
| `camera/DocCamera.kt` | caméra du visualiseur |
| `device/` | batterie, signal, luminosité (atténuée la nuit) |
| `kiosk/` | `Kiosk` (verrouillage, SQOOL masqué), `Updater`, `UpdatedReceiver`, `KioskAdminReceiver` |
| `res/values/strings.xml` | tous les textes (français) ; `attrs.xml` + `palettes.xml` (couleurs des 4 thèmes), `themes.xml` pour le style ; `ui/Themes.kt`, `ui/Look.kt` |

### Ordinateur : `backend/src/`

| Fichier | Quoi |
|---|---|
| `server.ts` | toutes les routes, jeton, erreurs |
| `config.ts` | variables d'environnement (`.env`) |
| `pairing.ts` | identité, jeton, code d'appairage, découverte UDP |
| `local-page.ts` | page `http://127.0.0.1:3000/` (adresse, code, agenda) |
| `deck.ts`, `perso.ts`, `music.ts` | pages du deck, `runAction`, touches perso, musique |
| `os.ts` → `mac/`, `win/` | pilotage du système ; `screens.ts` : types d'écrans et fenêtres |
| `calendar.ts`, `accounts.ts`, `events.ts`, `meetings.ts`, `meeting-links.ts` | agenda et réunions |
| `tasks.ts`, `jira.ts` | tâches et rappels |
| `tablet.ts`, `mcp.ts` | canal vers la tablette, extension Claude |
| `camera.ts`, `docs.ts` | visualiseur, dossier partagé |
| `updates.ts` | mises à jour de la tablette |
| `providers.ts` | Assistant : transcription et LLM (`MODE=mock` ou `live`) |

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

- **Nouvelle route** : la déclarer dans `server.ts` (avec son schéma), l'appeler dans `BackendClient.kt`. Erreur pour l'utilisateur = `throw new Error("phrase en français")`.
- **Nouvelle action système** : l'ajouter à `os.ts`, l'écrire dans `mac/` **et** `win/` (C# 5 seulement dans `native.cs`, pas d'accents dans `helper.ps1`).
- **Nouvelle étape de touche** : type dans `deck.ts` (`runAction`), et dans `KeyEditor` si elle se règle depuis la tablette.
- **Nouvel ordre vers la tablette** : type dans `tablet.ts` + schéma dans `server.ts` + outil dans `mcp.ts` + branche dans `RemoteChannel.execute`.
- **Nouveau widget d'Accueil** : le déclarer dans `WidgetModel`, le construire dans `HomeWidgets` (ou un fichier dédié de `ui/home/`).
- **Nouveau texte** : `strings.xml`, en français, court (la tablette est lue de loin), « ordinateur » (« ordi » sur les boutons courts).

Les conventions détaillées (noms historiques `Mac*` = l'ordinateur, Mac ou Windows ; clavier ; dialogues ; pièges de l'UI) sont dans [CLAUDE.md](../CLAUDE.md), tenu à jour à chaque changement.
