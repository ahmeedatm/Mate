# Unowhy Companion

Compagnon de bureau pour tablettes Unowhy 4.2 (Android 10), posé à côté de l'ordinateur (**Mac ou PC Windows**). La tablette est une interface légère ; le backend tourne sur l'ordinateur et sert d'agent de pilotage. Pas d'IA. Une tablette peut connaître plusieurs ordinateurs (bureau, maison) et passe toute seule à celui qui répond.

**Modes** : Profils (ex-Accueil : un profil nommé par page de widgets à disposer : heure, météo, agenda, tâches, eau, Focus, son de l'ordinateur) · Deck (applis, raccourcis, touches perso et macros, Média, toutes les fenêtres ouvertes sur le schéma des écrans, son et micro ; les boutons de volume de la tablette pilotent l'ordinateur) · Depuis l'ordi (« Ordi » dans la barre : fichiers du dossier partagé, page web de l'ordinateur) · Focus (Pomodoro + ambiance) · Caméra (visualiseur de documents, photo → ordinateur) · Réglages. La tablette tourne en kiosque : le bouton Retour ne quitte jamais l'appli.

```
app/      Appli Android (Kotlin, vues XML, minSdk 24)
backend/  Agent de l'ordinateur + proxy (Node/TypeScript, Fastify) ; src/mac/ et src/win/ : pilotage par système
docs/     Fonctionnement, état du projet, développement, tablette, kiosque
scripts/  Sauvegarde (bundle git + données perso)
```

**Documentation** :
[comment ça marche](docs/architecture.md) ·
[état du projet](docs/etat-du-projet.md) (fait, pas testé, à faire) ·
[cible et positionnement](docs/cible-et-positionnement.md) (direction B2B) ·
[travailler dessus et sauvegarder](docs/developpement.md) ·
[la tablette 4.2](docs/tablette-4.2.md) ·
[kiosque](docs/kiosque.md) ·
[agenda par compte Microsoft/Google](docs/agenda-comptes.md) ·
[GitLab par connexion au compte](docs/gitlab-oauth.md) ·
[ouverture aux développeurs : deck.json, touches perso, API, MCP](docs/ouverture-developpeurs.md)

## Installation (Mac ou Windows)

Sur chaque ordinateur à piloter (une fois) :

1. Télécharger l'appli : le plus simple est la tablette, qui les sert sur le WiFi (`http://<ip de la tablette>:8080`, affichée sur l'écran Bienvenue ; la page propose celle de l'ordinateur qui la visite). Sinon, celle de la dernière release (`npm run package` pour la fabriquer, voir [développement](docs/developpement.md)). Node y est embarqué, rien d'autre à installer, et rien ne s'installe : l'appli reste où on la met.
   - **Windows** : lancer **`Compagnon-Unowhy-Windows.exe`** (un seul fichier, sans droits d'administrateur ; non signé : SmartScreen > « Informations complémentaires » > « Exécuter quand même »).
   - **Mac** : extraire `Compagnon-Unowhy-Mac.zip`, ouvrir **Compagnon Unowhy** (non notarisée : la première fois, Réglages Système > Confidentialité et sécurité > « Ouvrir quand même »).
2. Le compagnon tourne tant qu'on ne le quitte pas ; relancer l'appli rouvre la page. Pour qu'il démarre à l'ouverture de session, cocher « Lancer au démarrage » dans la page (rien n'est inscrit avant). Mettre à jour : bouton « Chercher une mise à jour » de la page (l'appli vient de la tablette). Depuis ce dépôt, sans l'appli : Node.js 22 ou plus, puis `npm install && npm run service:install` dans `backend/`.
3. La page **http://127.0.0.1:3000/** (le panneau) s'ouvre : adresse de l'ordinateur, case où saisir le code d'appairage affiché par la tablette, agenda, et la carte « Cette appli » (lancement au démarrage, pare-feu, mise à jour, ancienne installation, quitter, tout oublier).
4. Autorisations, une fois :
   - **Mac** : Réglages Système > Confidentialité et sécurité > **Accessibilité** > ajouter `node` (bouton « Ouvrir le réglage » du panneau ; `node` est dans `~/Library/Application Support/UnowhyCompanion/runtime`) pour les raccourcis, macros et fenêtres.
   - **Windows** : « Ouvrir le pare-feu » dans le panneau (demande d'administrateur) pour les réseaux **privés** ; le WiFi doit être en profil « Privé ».

Sur la tablette : au premier lancement, l'écran **Bienvenue** guide en trois étapes (WiFi, adresse de la tablette à ouvrir sur l'ordinateur pour y télécharger le logiciel, `http://<ip>:8080`, ordinateurs trouvés sur le WiFi). Ensuite : **Réglages > Ordinateurs > Ajouter un ordinateur**, toucher l'ordinateur trouvé sur le WiFi, puis saisir sur l'ordinateur (page du compagnon, qui s'ouvre seule) le code à 6 chiffres affiché par la tablette. C'est tout : le jeton arrive une fois le code accepté. Ordinateur introuvable (autre réseau, diffusion bloquée) : « Saisir l'adresse » avec celle de la page.

**Plusieurs ordinateurs** (bureau le jour, maison le soir) : appairer chacun une fois. La tablette pilote l'ordinateur en gras ; quand il ne répond plus, elle cherche les autres ordinateurs enregistrés et bascule sur celui qui répond (bandeau « Connecté à … »), et retrouve un ordinateur dont l'adresse IP a changé. Changer à la main : toucher le nom de l'ordinateur en bas de la barre de gauche, ou Réglages. ✎ pour renommer (« Mac du bureau », « PC maison »). Chaque ordinateur garde ses propres données : agenda, tâches, deck, touches perso, applis et écrans (lus en direct). Le deck de départ ne montre que les applis installées sur cet ordinateur.

**Agenda** : sur la page http://127.0.0.1:3000/ de l'ordinateur, « Se connecter avec Microsoft / Google » (appli à enregistrer une fois par Unowhy : `docs/agenda-comptes.md`), ou coller le lien ICS de l'agenda (Outlook, Google Agenda, iCloud : marche à suivre sur la page). Sans agenda, les cartes Agenda et Prochain rendez-vous sont masquées sur la tablette.

| | Commande (dans `backend/`) |
|---|---|
| État | `npm run service:status` |
| Arrêter (pour `npm run dev`) | `npm run service:stop` |
| Redémarrer | `npm run service:start` |
| Retirer le lancement automatique | `npm run service:uninstall` |

Journal : `~/Library/Logs/UnowhyCompanion/` (Mac), `%LOCALAPPDATA%\UnowhyCompanion\` (Windows).

### Windows : ce qui change

Le pilotage passe par un programme résident (`backend/src/win/UnowhyHelper.exe`, .NET Framework 4.8 fourni avec Windows 10/11 : rien à installer), compilé depuis `helper/` par `npm run build:helper` (SDK .NET 8).

- **Applis** : celles du menu Démarrer (classiques et du Store), noms tels qu'affichés (« Outlook », « Microsoft Teams »).
- **Raccourcis** : `cmd+…` est envoyé comme **Ctrl+…** (les touches perso écrites sur Mac restent utilisables) ; `win` = touche Windows (`win+shift+s`, `win+tab`). Le deck de départ est `config/deck.example.windows.json`.
- **Fenêtres et écrans** : mêmes gestes (moitiés, agrandir, changer d'écran) ; les bureaux virtuels masqués ne sont pas listés.
- **Son** : volume et sourdine de la sortie par défaut, sourdine du micro par défaut.
- Pas de « Raccourci Apple » (appli Raccourcis de macOS). `Win+L` est remplacé par l'action système « Verrouiller ».
- Testé sur un vrai PC Windows (lancement automatique, deck, fenêtres, son, Média, Discord).

## Prérequis

- JDK 17+ (testé avec 21), Android SDK (`compileSdk 36`), adb
- Node 22+
- `local.properties` à la racine : `sdk.dir=/Users/<toi>/Library/Android/sdk` (non versionné)

## Backend

Pour développer (le service doit être arrêté : `npm run service:stop`) :

```bash
cd backend
npm install
npm run dev               # http://0.0.0.0:3000, rechargement auto ; .env facultatif (voir .env.example)
```

Test rapide :

```bash
curl localhost:3000/health
```

### Personnaliser le deck

Copier `backend/config/deck.example.json` (Mac) ou `deck.example.windows.json` (Windows) en `backend/config/deck.json` (non versionné) et l'éditer : pris en compte sans redémarrer (bouton ⟳ sur la tablette). Types d'action :

Chaque tuile accepte aussi `glyph` (un emoji affiché à la place de l'icône) et `color` (fond, ex. `#C62828`).

| type | exemple |
|---|---|
| `app` | `{ "type": "app", "name": "Microsoft Teams" }` |
| `keys` | `{ "type": "keys", "keys": "cmd+shift+m", "app": "Microsoft Teams" }` (`app` : activée avant ; Windows : cmd = Ctrl, `win` = touche Windows) |
| `shortcut` | `{ "type": "shortcut", "name": "Ne pas déranger" }` (appli Raccourcis de macOS, Mac seulement) |
| `url` | `{ "type": "url", "url": "https://..." }` |
| `media` | `play-pause`, `next`, `previous` |
| `system` | `sleep-display`, `toggle-dark-mode`, `screenshot-clipboard`, `lock` |
| `text` | `{ "type": "text", "text": "Bonjour," }` (collé dans l'appli au premier plan) |
| `wait` | `{ "type": "wait", "ms": 500 }` |
| `window` | `{ "type": "window", "app": "Visual Studio Code", "display": "BenQ GW2480", "layout": "max" }` (`layout` : `keep`, `left`, `right`, `max` ; `display` : libellé d'écran, "Mac" pour l'écran intégré) |
| `macro` | `{ "type": "macro", "steps": [ …étapes ci-dessus… ] }` |

La tablette n'envoie qu'un identifiant de tuile : seules les actions écrites dans ce fichier et dans les touches perso sont exécutables.

**Fenêtres et écrans** : toucher une appli qui a plusieurs fenêtres (ex. VS Code) ouvre la liste de ses fenêtres au lieu de l'activer ; appui long sur une tuile d'appli pour l'ouvrir dans tous les cas. Chaque fenêtre peut être mise devant, envoyée sur un autre écran (pastilles affichées dès que deux écrans sont branchés) ou calée sur une moitié d'écran / agrandie. Seules les fenêtres du bureau (Space) courant sont visibles.

**Page Perso** : au moins 14 touches vides (toujours une libre après la dernière remplie, jusqu'à 100) à programmer depuis la tablette (toucher une touche libre, appui long pour modifier) ou par Claude (outils `deck_touche_*`). Une étape = raccourci simple, plusieurs = macro. Stockées dans `backend/.data/keys.json`. Attention : programmer une touche depuis la tablette revient à pouvoir taper n'importe quoi sur l'ordinateur ; le jeton obtenu à l'appairage (code affiché sur l'ordinateur) reste la seule protection.

**Autorisations macOS** (Réglages Système > Confidentialité et sécurité) : les actions `keys`, `media`, `text` et tout ce qui touche aux fenêtres demandent **Accessibilité** pour `node` (service automatique), ou pour l'appli qui lance `npm run dev` (Terminal, iTerm ou VS Code). Après une mise à jour de Node, macOS peut redemander l'autorisation. `toggle-dark-mode` demande l'autorisation **Automatisation** (popup au premier usage).

### Agenda et documents

- Agenda : lien ICS collé dans la page http://127.0.0.1:3000/ de l'ordinateur (gardé dans `backend/.data/calendar.json`), ou `CALENDAR_ICS_URLS` dans `.env`. Sans lien, pas d'agenda ; `demo` donne l'agenda d'exemple.
- Réunions Teams : bouton « Rejoindre » sur le prochain rendez-vous des Profils (15 min avant jusqu'à la fin), lien pris dans la description ICS et ouvert dans l'appli Teams de l'ordinateur (écran d'avant-réunion). Option dans Réglages : rejoindre automatiquement 1 min avant (désactivée par défaut, gardée dans `backend/.data/meetings.json`, ignore les réunions « Libre »).
- Documents : `DOCS_DIR` dans `.env` (défaut : `shared/` à la racine du repo). Les photos de la tablette arrivent dans `Photos tablette/`.

### Visualiseur (mode Caméra)

La tablette, tenue à plat à environ 30 cm au-dessus d'une feuille (écran vers le haut), filme avec sa caméra arrière ; l'ordinateur affiche l'image sur **http://127.0.0.1:3000/camera** (bouton « Afficher sur l'ordi » de la tablette), à envoyer sur le vidéoprojecteur. Sur la page : Pivoter (R) si le support met la tablette à l'envers, Plein écran (F).

- Flux : JPEG 720p, environ 10 images/s mesurées sur la 4.2 en WiFi. Seule la dernière image est gardée, un spectateur lent saute des images.
- Sur la tablette : Figer (l'image de l'ordinateur s'arrête, l'aperçu continue), zoom 1× à 3× (recadrage du capteur 5 Mpx, plus net qu'un agrandissement), Photo HD (5 Mpx dans `Photos tablette/`), Arrêter. La caméra s'éteint aussi en quittant le mode.
- Sans jeton, la page n'est lisible que depuis l'ordinateur lui-même (`/camera`, `/camera/stream`, `/camera/status` en GET local) ; l'envoi d'images demande le jeton comme le reste.

### Tâches et rappels

Depuis la tablette : « Nouvelle tâche » + pour une tâche simple ; ⏰ pour la répéter (tous les jours, lun–ven, jours choisis) et/ou être rappelé à voix haute à une heure ; 🔁 liste toutes les tâches récurrentes (même celles qui ne tombent pas aujourd'hui) pour les supprimer. Elles sont gardées sur l'ordinateur (`backend/.data/tasks.json`). Pour aller plus loin (lien à ouvrir, coche par Jira), le fichier ci-dessous :

Copier `backend/config/tasks.example.json` en `backend/config/tasks.json` (non versionné ; sans ce fichier, pas de tâche quotidienne). Chaque tâche quotidienne accepte :

| champ | rôle |
|---|---|
| `days` | jours (1 = lundi … 7 = dimanche), défaut : tous |
| `remindAt`, `repeatMinutes`, `remindUntil` | rappel parlé sur la tablette à partir de `remindAt`, répété jusqu'à ce que ce soit fait (au plus tard `remindUntil`, défaut 19:00) |
| `url` | page ouverte sur l'ordinateur par le bouton « Ouvrir sur l'ordi » |
| `jiraWorklogHours` | cochée toute seule quand ce temps est saisi dans Jira (nécessite `JIRA_SITE`, `JIRA_EMAIL`, `JIRA_API_TOKEN` dans `.env`) |

Les tâches ponctuelles s'ajoutent depuis la tablette ou par Claude ; cochées, elles disparaissent le lendemain. L'état est stocké dans `backend/.data/` (non versionné). Un rappel n'est perdu que si la tablette reste injoignable jusqu'à `remindUntil` (écran éteint = appli en pause).

### Extension Claude : piloter la tablette (MCP)

Claude (Claude Code, Claude Desktop ou tout client MCP) peut piloter la tablette via l'ordinateur : changer d'écran, afficher ou dire un message, ouvrir un document du dossier partagé, lancer le Pomodoro ou un son d'ambiance, lister/ajouter/cocher des tâches, lire l'état, faire une capture d'écran.

```
Claude ──MCP (stdio)──▶ backend/src/mcp.ts ──HTTP──▶ backend (npm run dev) ◀──long-polling── tablette
```

La tablette n'expose aucun serveur : elle garde une requête ouverte vers le backend (`POST /tablet/poll`) et exécute les ordres reçus. Il faut donc que le backend tourne et que l'appli soit à l'écran.

- **Claude Code** : le fichier `.mcp.json` à la racine déclare le serveur `tablette`. Ouvrir Claude Code dans le projet, accepter le serveur, vérifier avec `/mcp`.
- **Claude Desktop** : dans `~/Library/Application Support/Claude/claude_desktop_config.json` :

  ```json
  { "mcpServers": { "tablette": {
      "command": "/chemin/vers/unowhy-companion/backend/node_modules/.bin/tsx",
      "args": ["/chemin/vers/unowhy-companion/backend/src/mcp.ts"] } } }
  ```

Exemple de demande : « Passe la tablette en Focus 50 minutes avec la pluie en fond, puis affiche-moi une capture. »

Test sans Claude : `curl -X POST localhost:3000/tablet/command -H 'content-type: application/json' -d '{"type":"message","text":"Salut","speak":true}'`, état avec `curl localhost:3000/tablet`.

## Appli Android

Sur la tablette : Paramètres > À propos > taper 7 fois sur « Numéro de build », puis Options développeurs > Débogage USB.

```bash
adb devices                              # la tablette doit apparaître en "device"
./gradlew installDebug                   # build + installation
adb reverse tcp:3000 tcp:3000            # la tablette joint le backend de l'ordinateur via USB
adb shell am start -n com.unowhy.companion/.MainActivity
```

`adb reverse` est à relancer après chaque rebranchement du câble. En USB, ajouter l'ordinateur avec « Saisir l'adresse » : `127.0.0.1` (appairage par code comme en WiFi).

En WiFi, rien à configurer au build : Réglages > Ordinateurs > Ajouter (voir Installation). Depuis l'**émulateur**, comme en USB : `adb reverse tcp:3000 tcp:3000` puis « Saisir l'adresse » `127.0.0.1` (la recherche WiFi ne traverse pas l'émulateur ; l'appli n'envoie pas son jeton en clair à `10.0.2.2`).

### Tester sans tablette (émulateur Android 10)

Un émulateur `companion_api29` calqué sur la tablette (Android 10 sans services Google, 2 Go de RAM, 4 cœurs, 1280×800 à 160 dpi) est configuré ; `companion_api24` (Android 7.0) reste disponible. Les variables `ANDROID_*` du `~/.zshrc` pointent vers le mauvais dossier, d'où leur surcharge :

```bash
ANDROID_HOME=~/Library/Android/sdk ANDROID_SDK_ROOT=~/Library/Android/sdk ~/Library/Android/sdk/emulator/emulator -avd companion_api29 &
./gradlew installDebug && adb reverse tcp:3000 tcp:3000
```

Autres commandes utiles :

```bash
./gradlew assembleDebug                  # APK dans app/build/outputs/apk/debug/
adb logcat --pid=$(adb shell pidof com.unowhy.companion)   # logs de l'appli
adb uninstall com.unowhy.companion
```

**Deux « ordinateurs » sur un même Mac** (tester la bascule) : `PORT=3011 DATA_DIR=/tmp/a COMPUTER_NAME="Mac du bureau" npx tsx src/server.ts` et un second avec un autre port et dossier ; les ajouter depuis l'émulateur par `adb reverse tcp:3011 tcp:3011` et `127.0.0.1:3011`, puis arrêter l'actif.

**Mode kiosque** : voir [docs/kiosque.md](docs/kiosque.md).

> En WiFi, la tablette parle à l'ordinateur en HTTPS, certificat épinglé à l'appairage ; le HTTP en clair ne sert qu'en local (USB, page de l'ordinateur). Voir [docs/securite-appairage.md](docs/securite-appairage.md).

## Où en est le projet

Voir [docs/etat-du-projet.md](docs/etat-du-projet.md) : ce qui marche, ce qui n'a pas été testé, la suite, et ce qu'il faut faire avant une démo.
