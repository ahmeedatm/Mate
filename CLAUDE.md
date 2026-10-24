# Hackathon Unowhy : compagnon de bureau sur tablette 4.2

### Le projet
Unowhy a des tablettes **Unowhy 4.2** (tablettes SQOOL) en stock, sous Android 8 à 11 selon les lots. **On cible Android 10** (celle d'Eliot, et l'image sans surcouche demandée). Un hackathon interne doit leur trouver une seconde vie. Elles sont peu puissantes : pas question de faire tourner de l'IA en local ni des applis lourdes.

### L'idée de départ (Eliot)
Un **compagnon de bureau** : la tablette posée sur le bureau, avec plusieurs modes :
- **Mode assistant IA** : on parle (ou on écrit), l'IA répond à l'écran et à la voix.
- **Mode "Steam Deck" / sons** : interface média et ambiance (sons d'ambiance, contrôle audio, retours sonores, Bluetooth vers une enceinte). Interprétation à confirmer avec Eliot.
- **Autres usages d'assistant** : timer / Pomodoro, agenda, météo, notifications, etc.

### Contraintes techniques connues
- Cible : **Android 10 (API 29)**, tests sur l'émulateur `companion_api29` puis la vraie tablette. `minSdk 24` est gardé (ne coûte rien, laisse tourner les lots en Android 8). Les `<queries>` du manifeste restent pour Android 11+.
- D'après le support SQOOL (à confirmer par adb) : MediaTek MT8167 4 cœurs 1,3 GHz, 2 ou 3 Go de RAM, écran 10,1" 1280×800. Numéro de série commençant par 7733 = V4.2.
- Pas d'IA locale. Toute l'intelligence est dans le cloud (speech-to-text, LLM, text-to-speech). La tablette ne fait que l'interface : micro, écran, haut-parleur.
- Aucun secret (clé API) ne doit être stocké sur la tablette : passer par un petit backend proxy.

### Architecture "client léger + cloud"
1. La tablette capte la voix (bouton ou mot de réveil léger type Porcupine).
2. L'audio part vers un service de speech-to-text.
3. La transcription part vers une API LLM (ex. Claude).
4. La réponse est lue via synthèse vocale sur le haut-parleur.

Le mode ambiance/sons et le mode timer sont très légers (lecture audio, Bluetooth, UI simple).

### Points à vérifier avant de coder (bloquants possibles)
1. **MDM** : vérifié par adb sur la tablette d'Eliot (23/10/2026, détails dans `docs/tablette-4.2.md`). Le système est un Android 10 Unowhy quasi standard (services Google, Play Store, Google TTS). Le MDM SQOOL (`com.hisqool.devicemanager`) est une appli installée dans `/data`, **profile owner** (pas device owner) ; seule restriction : `no_add_user`. adb et l'installation d'APK par adb fonctionnent déjà. Bloquant pour le kiosque seulement : `dpm set-device-owner` échoue tant qu'il y a un profile owner. Fait le 23/10/2026 (pas de reflash : bootloader verrouillé) : réinitialisation d'usine **hors ligne**, Companion device owner avant le WiFi ; il masque l'agent SQOOL de l'image (`com.unowhy.sqoolota`, qui réinstalle le MDM dès qu'il a du réseau) et bloque toute installation (Réglages > Autoriser les installations pour les mises à jour). Procédure : `docs/kiosque.md`. L'appli n'a pas besoin des services Google ; elle utilise le WebView (« Page de l'ordi »), qu'elle met à jour elle-même (carte Mises à jour).
2. **Google Play Services** et WebView à jour.
3. **Qualité micro / haut-parleur** intégrés : sinon dock avec micro/enceinte externe (USB ou Bluetooth).
4. **Débogage USB (adb)** activable ?
5. **Réseau** : WiFi sur le lieu du hackathon, accès sortant aux API cloud autorisé.
6. **Alimentation** : usage branché en permanence (batterie vieillissante).

### Autres pistes si l'idée principale bloque
Panneau domotique (Home Assistant), companion focus / Pomodoro, cadre photo / affichage ambiant, borne d'accueil / salle de réunion, CAA pour élèves, console rétro-gaming légère, second écran de notifications, dock/accessoire vendu avec le logiciel.

### Décisions techniques proposées (à valider)
- **Appli Android native en Kotlin**, `minSdk 24`, une seule Activity plein écran (mode kiosque / lanceur), architecture simple par "modes" (écrans).
- Dépendances minimales (APK léger, compat API 24).
- **Backend proxy** léger qui détient les clés API et expose 2 endpoints (`/transcribe` et `/chat`). Clés dans `.env` (ignoré par git).
- Phase 1 : chat texte + bouton micro. Phase 2 : mot de réveil + synthèse vocale. Phase 3 : modes ambiance et timer.
- Test sur **tablette réelle** via adb dès le premier jour (l'émulateur ne reflète pas les perfs).

### Pas encore décidé
- Nom du projet.
- Public cible (perso bureau vs produit Unowhy vs usage scolaire).
- Fournisseur STT/TTS/LLM et budget.
- Sens exact du mode "Steam Deck" (média/ambiance ? gaming rétro ?).

### Règles de travail
- Une question à la fois si une info manque, plutôt que deviner.
- Expliquer les choix en 1-2 phrases.
- Ne rien commit sans demande explicite.

### Conventions du code
- **Vision (Eliot)** : la tablette est un compagnon de l'ordinateur (Mac ou PC Windows ; bureau le jour, maison le soir : tâches, rappels, fenêtres/écrans et macros pour jouer). Deck façon Stream Deck (lancer/piloter les applis, son, micro), documents ouverts à côté des conversations, agenda affiché, caméra (photo → Mac), raccourcis. Tablette lente : tout doit rester léger ; elle passera en mode kiosque (docs/kiosque.md).
- **Android** (`app/`) : Kotlin, vues XML (pas de Compose), une Activity (`MainActivity`) + un Fragment par mode (Accueil, Deck, Assistant, Partage — ancien Docs, `DocsFragment`, `Mode.DOCS` —, Focus, Caméra, Réglages) + `DocumentPanel` à droite (documents et page web de l'ordinateur ; en grand dans Partage, à côté ailleurs, `setExpanded`). L'Accueil est le tableau de bord : il regroupe agenda, tâches (`TasksCard`), mini-Focus et son du Mac (`MacAudioView`, partagé avec le Deck) ; l'ancien mode `tasks` envoyé par le Mac mène à l'Accueil. Style : couleurs dans `colors.xml`, styles `Card`/`Chip`/`IconButton`/`PrimaryButton` dans `themes.xml`, icônes vectorielles `ic_*` (Material) ; état sélectionné des pastilles et de la barre latérale via `isSelected`. `FocusTimer` et `NoisePlayer` (package `focus/`) sont des singletons : ils survivent aux changements de mode. Dépendances limitées à appcompat, lifecycle-runtime-ktx, coroutines : en ajouter une seulement si c'est indispensable et compatible API 24. HTTP via `HttpURLConnection` + `org.json` (`net/BackendClient.kt`). Noms historiques `Mac*` (`MacWindow`, `MacAudioView`, `openOnMac`) = l'ordinateur piloté, Mac ou Windows ; les textes affichés disent « ordinateur » (« ordi » sur les boutons courts).
- AGP 9 avec Kotlin intégré : pas de plugin `kotlin-android` à appliquer. Sources dans `src/main/kotlin`.
- Ordinateurs : la tablette garde une liste (`Computers.kt` : id du backend, nom renommable, url, jeton, os) et pilote l'actif (`Prefs.backendUrl/token` en dérivent ; sans ordinateur, `BuildConfig.DEFAULT_BACKEND_URL`). Ajout : recherche UDP sur le WiFi (`net/Discovery.kt`) ou adresse saisie, puis code à 6 chiffres affiché sur l'ordinateur (`ui/ComputerAdder.kt`). `net/Connection.kt` : si l'actif ne répond plus, adresse mise à jour par la recherche ou bascule vers un autre ordinateur qui répond ; `MainActivity.onComputerChanged` recrée tous les écrans et relance le canal. Chaque ordinateur garde ses données (agenda, tâches, deck). HTTP en clair autorisé seulement en debug (`src/debug/res/xml`).
- **Backend** (`backend/`) : Node 22, TypeScript exécuté par `tsx`, Fastify. Fournisseurs derrière l'interface `Providers` (`src/providers.ts`), choisis par `MODE=mock|live`. Pilotage par système derrière `src/os.ts` (seul point d'entrée : `launchApp`, `openTarget`, `sendKeys`, fenêtres, son…) : `src/mac/` (osascript/JXA) et `src/win/` (`helper.ts` garde un Windows PowerShell 5.1 résident, lancé sur une copie de `helper.ps1`/`native.cs` dans `%LOCALAPPDATA%\UnowhyCompanion\helper` pour ne pas verrouiller les originaux ; `native.cs` : C# 5 seulement ; testé sur le PC d'Eliot). Types d'écrans/fenêtres partagés dans `src/screens.ts`. Identité de l'ordinateur et jeton créés seuls dans `.data/identity.json` (`DATA_DIR` pour un 2e backend de test) ; appairage et découverte UDP dans `src/pairing.ts` ; `/health` et `/pair/*` sans jeton ; page locale `GET /` (`src/local-page.ts` : adresse, code, lien ICS de l'agenda → `.data/calendar.json`). `POST /companion/restart` : arrêt, relancé par le lancement automatique (mise à jour à distance). Lancement automatique : `scripts/service.ts` (`npm run service:install|stop|start|status|uninstall` ; LaunchAgent sur Mac, clé Run + `scripts/run-windows.ps1` sur Windows), installeurs à double-cliquer à la racine.
- Le backend tourne **sur l'ordinateur piloté**. Par défaut il écoute en WiFi (0.0.0.0) avec le jeton de l'appairage ; `HOST=127.0.0.1` pour l'USB seul (`adb reverse`). Les actions du deck sont définies uniquement dans `backend/config/deck.json` et dans les touches perso (`backend/.data/keys.json`, page Perso, `src/perso.ts` ; une touche = liste d'étapes, plusieurs = macro), toutes exécutées par `runAction` (`src/deck.ts`) ; deck de départ `config/deck.example.json` (Mac) ou `deck.example.windows.json`, filtré aux applis installées. Page Apps (`"apps": true` dans la config) : profil de base complété ou réduit depuis la tablette (`.data/apps.json`, `AppAdder`, Retirer dans `WindowPicker`) ; elle décide aussi des cartes de l'Accueil (`GET /deck/features` : agenda si l'ordinateur a un lien ICS, Rejoindre si Teams est dans la page Apps). Page d'appli (`"app": "Discord"`, Réunion = `"Microsoft Teams"`) : montrée seulement si l'appli est dans la page Apps, onglet sur la tablette seulement pendant un appel (micro utilisé par l'appli, `GET /deck/live`, gardé 20 s) ; sinon toucher l'appli ouvre sa page sans la lancer, sa tuile sur la page la lance, volume propre à l'appli au-dessus (`AppAudioView`, `GET/POST /system/audio/app`, mélangeur de Windows, 501 sur Mac). Page Média (`"media": true`, Windows) : lecture en cours (`NowPlayingView`, `/system/media`) lue dans les contrôles multimédias de Windows (WinRT) par `helper.ps1` en PowerShell, car le C# d'Add-Type ne sait pas appeler WinRT ; touches = sites choisis depuis la tablette (`.data/links.json`, vide au départ, `LINK_SUGGESTIONS`, `LinkAdder`), icônes des sites cherchées par l'ordinateur (`siteIcon`). « Page de l'ordi » : `GET /browser/page` (Windows : UI Automation dans `helper.ps1`, secours = lien copié, jamais le contenu du presse-papiers dans la réponse ; Mac : `src/mac/browser.ts`), `POST /browser/open`. Mises à jour de la tablette : « serveur Unowhy » simulé (`src/updates.ts`, `.data/updates/manifest.json` + APK), installées en silence par `kiosk/Updater.kt` (device owner). WiFi réglé dans Réglages (`net/Wifi.kt`, `WifiPicker` ; device owner, active la localisation). Touches envoyées en CGEvent depuis JXA (`src/mac/keys.ts`, disposition du clavier du Mac détectée) : System Events refuse les saisies (erreur 1002). Fenêtres et écrans du Mac : `src/mac/windows.ts` (un script JXA : NSScreen + System Events, Accessibilité requise), panneau `WindowPicker` (schéma des écrans `ScreenMap` : toucher une fenêtre la met devant, la glisser sur un écran l'y déplace, bords = moitié, haut = agrandir ; fenêtres des autres bureaux, dont celui caché derrière une appli plein écran, lues dans le menu Fenêtre de l'appli, l'Accessibilité ne voyant que les bureaux affichés ; leur écran vient des cadres CoreGraphics ; après un déplacement, on passe sur le bureau de la fenêtre si elle a disparu) et éditeur `KeyEditor` par-dessus le Deck ; champs de formulaires construits dans le code gonflés depuis `item_input.xml` (un `EditText` construit avec un style perd focus et curseur). Les tâches quotidiennes (worklog Jira…) sont dans `backend/config/tasks.json` ; état des tâches dans `backend/.data/`. Rappels de tâches poussés par le Mac via le canal `/tablet` (`startReminders`, `src/tasks.ts`). Jira optionnel (`src/jira.ts`, jeton API dans `.env`). Réunions Teams (`src/meetings.ts`) : le lien reste sur le Mac, la tablette envoie l'`id` de l'événement à `POST /calendar/join` ; ouverture auto 1 min avant si `.data/meetings.json` l'active. Après « Rejoindre », la tablette affiche la page du deck marquée `"meeting": true` (micro, caméra, raccrocher).
- **Visualiseur** (mode Caméra) : `camera/DocCamera.kt` (Camera2 : aperçu + YUV 720p compressé en JPEG + photo JPEG pleine taille) et `ui/CameraFragment.kt` (envoi des images, Figer, zoom, Arrêter) ; côté Mac `backend/src/camera.ts` (`POST /camera/frame` en `image/jpeg`, `POST /camera/state`, page `GET /camera` en MJPEG). Mesures de la caméra dans `docs/tablette-4.2.md`.
- Contrat API (voir aussi `/deck`, `/system/audio`, `/docs`, `/calendar`, `/camera` dans `backend/src/server.ts`) : `GET /health` → `{ok, mode}` ; `POST /chat` `{messages:[{role,content}]}` → `{reply}` ; `POST /transcribe` multipart champ `audio` → `{text}`.
- **Extension Claude (MCP)** : `backend/src/mcp.ts` (serveur MCP stdio, déclaré dans `.mcp.json`) relaie vers `POST /tablet/command` ; la tablette récupère les ordres en long-polling (`POST /tablet/poll`, `backend/src/tablet.ts`) et les exécute dans `remote/RemoteChannel.kt`. Nouvel ordre = type dans `tablet.ts` + schéma dans `server.ts` + outil dans `mcp.ts` + branche dans `RemoteChannel.execute`. Synthèse vocale partagée : `audio/Speaker`.
- Textes de l'UI en français, dans `res/values/strings.xml`.
- Tester sur l'émulateur `companion_api29` (Android 10, 1280×800, sans services Google) avant la vraie tablette : voir README. Captures avec `adb exec-out screencap -p`.

### Environnement local (Mac, Apple Silicon)
- Projet isolé dans `~/side-projects/unowhy-companion`, séparé des repos du boulot.
- SDK Android : `~/Library/Android/sdk` (à référencer via `local.properties`, car `ANDROID_HOME` dans `~/.zshrc` pointe vers le cask platform-tools).
- JDK 21, Node 22, adb 1.0.41.
