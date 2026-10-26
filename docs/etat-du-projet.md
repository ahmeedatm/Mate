# État du projet

Mis à jour le 26/10/2026 (Assistant IA retiré ; avant, 5e session du 25/10 : MCP revérifié, extension de navigateur reportée à la vente, poste de dev installé sur le PC perso d'Eliot, sans changement du code). À tenir à jour en fin de session : c'est la mémoire partagée du projet (les notes de Claude restent sur l'ordinateur où il tourne).

## Pourquoi ce projet

Hackathon interne Unowhy. Unowhy a **environ 20 000 tablettes 4.2** en stock. L'objectif réel est de **les vendre**, pas seulement de leur trouver un usage. Unowhy vend aussi des PC : la tablette peut devenir un **accessoire du PC** (posée sur le bureau, sans configuration, en kiosque), sur le modèle du Stream Deck ou du Corsair Xeneon Edge.

Deux publics possibles, pas encore tranché :

- **Bureau / maison** : compagnon du PC (agenda, tâches, deck, fenêtres, son, musique, jeux).
- **École, « console du prof »** : télécommande du PC de la classe, minuteur et surtout **visualiseur de documents**. La caméra filme une feuille, le PC la projette. Les écoles achètent ces appareils entre 100 et 300 € : c'est ce qui donne une valeur matérielle à la tablette.

Éléments de pitch déjà validés :

- **Mises à jour** : pour la démo, un mini « serveur Unowhy » met à jour la tablette toute seule, en kiosque. Avant la démo, remettre le WebView d'usine (`adb shell pm uninstall-system-updates com.google.android.webview`). En direct : Réglages > Mises à jour > Rechercher > Mettre à jour, puis montrer une page web moderne dans « Page de l'ordi ». En production, on s'appuierait sur le MDM et l'OTA SQOOL plutôt que de réinventer la gestion de flotte.
- **Règle produit** : un client fait tout depuis la tablette, en kiosque, sans adb et sans quitter le kiosque (WiFi, ordinateurs, sites, applis…).
- **Limite Windows** : le pilote (frappes clavier, lecture des fenêtres) peut ressembler à un malware pour les antivirus. Ancienne version en PowerShell bloquée par Bitdefender ; depuis le 25/10, exe compilés (`UnowhyHelper.exe`, lanceur `UnowhyCompanion.exe`) acceptés par Bitdefender sans exception. Pour la vente : les signer avec un certificat Unowhy.

## La tablette

Unowhy 4.2 : MT8167, 4 cœurs à 1,3 GHz, 2 Go de RAM, écran 1280×800, **Android 10**, bootloader verrouillé. Mesures et vérifications : [tablette-4.2.md](tablette-4.2.md). La tablette de test est **réinitialisée en kiosque**, avec Companion comme device owner et sans le MDM SQOOL : [kiosque.md](kiosque.md).

## Ce qui marche (testé sur la vraie tablette)

| Domaine | Fonctions |
|---|---|
| Ordinateurs | Mac **et** PC Windows, appairage par code, plusieurs ordinateurs, bascule automatique, lancement automatique à l'ouverture de session (Mac et Windows) |
| Accueil | widgets (grille 4×4, 3 pages, modèles Travail / Maison-Jeu / Veille, mode Modifier), heure, météo, agenda 8 jours, prochain rendez-vous + Rejoindre (Teams testé en vrai), tâches, eau, mini-Focus, son de l'ordinateur |
| Agenda | lien ICS (Outlook), agenda personnel noté depuis la tablette avec rappel parlé, « Mes agendas » |
| Tâches | ponctuelles, récurrentes (jours + rappel parlé), quotidiennes par fichier (worklog Jira) |
| Deck | Apps (ajout/retrait depuis la tablette), raccourcis, Perso (touches et macros éditées sur la tablette), pages d'appli (Discord, Réunion), Média (Windows : lecture en cours, sites choisis, YouTube Music), **Ouvertes** (toutes les fenêtres sur le schéma des écrans : toucher, réduire, agrandir, changer d'écran, défiler), fenêtres d'une appli (appui long), son et micro |
| Depuis l'ordi (ex-Partage, « Ordi » dans la barre) | écran de choix expliqué (Fichiers de l'ordi / Page de l'ordi), dossier partagé, documents à côté de chaque mode, « Page de l'ordi » (page du navigateur de l'ordinateur dans la tablette) ; écran de choix et liste vus sur la tablette le 25/10 |
| Focus | Pomodoro, ambiance qui suit le chrono |
| Apparence | 4 thèmes (Nuit, Clair, Néon, Chaud) choisis dans Réglages ; une couleur par fonction (pastilles des titres, tuiles d'applis aux couleurs des marques) ; animations légères (appui, changement de mode, widgets, météo, Focus, eau), fluides sur la tablette |
| Bascule d'ordinateur | redémarrage du compagnon du PC (revenu en 8 s) : la tablette reste sur le PC, plus de bascule vers le Mac ; testé le 24/10 |
| Média | touche Spotify : ouvre Spotify, la musique part seule, lecture en cours affichée ; testé le 24/10 |
| Caméra | visualiseur à environ 10 images/s en WiFi, page `/camera` sur l'ordinateur, Figer, zoom, Arrêter |
| Kiosque | lock task, SQOOL masqué, installations bloquées, relance après mise à jour, WiFi dans Réglages, mises à jour silencieuses (WebView 152 installé), heure et fuseau auto, état de la tablette (batterie, luminosité, atténuation de nuit), écran de bienvenue |
| Extension Claude | serveur MCP `tablette` (modes, messages, tâches, agenda, captures…), sur les données du paquet ; `tablette_etat` revérifié le 25/10 (la tablette doit piloter le Mac, sinon « non connectée ») |
| Commande vocale | bouton Parler (reconnaissance Android, sans clé) : heure, ouvrir une appli même mal entendue (« ouvre Ankama Launcher »), modes, son, Focus, dictée (« écris … »), fenêtres (réduire, montrer, à gauche / à droite, « sur l'écran de droite / de gauche / 2 »), tâches (ajouter, lister, cocher), rappels (« rappelle-moi … dans 2 minutes »), agenda (« qu'est-ce que j'ai demain ») ; testé le 24/10 |
| Appels entrants | Discord (Windows) : carte Répondre / Vidéo / Ignorer sur la tablette, annonce parlée, puis page Discord du Deck ; testé le 24/10 (appel privé reçu, Répondre) ; « Quitter l'appel » et « Arrêter le partage » testés ; appel de groupe privé lu (nom, participants, micro) |
| Paquet Windows sans Node | zip de `npm run package` extrait dans Documents sur le PC, `.data` copié (appairage gardé), installeur : ancien compagnon arrêté, nouveau lancé, relancé seul après un redémarrage à distance (~9 s) ; testé le 24/10 |
| Icône | « compagnon » (bulle souriante, dégradé violet → turquoise, `backend/assets/compagnon.svg`) : raccourci du PC (`.ico`) et appli de la tablette (icône adaptative, `drawable/ic_launcher*`) ; raccourci vu sur le PC le 25/10 |
| Discord, salon de serveur | carte « En appel · salon / serveur », personnes par leur nom affiché (vignettes de l'appel, soi exclu) ; testé le 25/10 avec une autre personne. Qui parle : illisible |
| Teams en appel (Mac) | page Réunion seulement pendant un appel (fenêtre d'appel de Teams lue par l'API d'Accessibilité, `src/mac/calls.ts`), carte « En appel · réunion » + micro coupé, touches Micro / Caméra / Partager / Arrêter le partage / Quitter = vrais boutons de Teams (étape `press` sur Mac), retour à la 1re page à la fin de l'appel ; testé le 26/10 pendant un vrai appel. Participants : pas lus (affichage compact) ; « Lever la main » reste un raccourci ; Teams sous Windows : toujours raccourcis + micro utilisé |
| Paquet Mac sans Node | zip installé comme un client neuf le 25/10 : lancement automatique (launchd), données dans `~/Library/Application Support/UnowhyCompanion`, Partage dans `~/Documents/Partage tablette`, raccourci `.webloc`, Accessibilité au node du paquet, appairage (l'ancien Mac remplacé sur la tablette, sans doublon). Le Mac d'Eliot tourne désormais depuis le paquet (`~/Documents/Compagnon-Unowhy-Mac`) |

## Fait mais pas encore testé en vrai

- **Tests instrumentés sur émulateur** (26/10) : Espresso (`app/src/androidTest/`), émulateur Android 10 sans services Google calqué sur la tablette, en local (`scripts/test-android.sh`, émulateur natif du Mac) et en CI (job `android-emulateur` : le runner Kubernetes partagé a KVM). Premier parcours : lancement sans ordinateur → bienvenue → « Plus tard » → Deck → « Connecter ». Espresso plutôt que Maestro : dans Gradle et l'image CI déjà là, rien à installer, préférences effacées depuis le test, synchronisation automatique ; Maestro (YAML, boîte noire) reste une piste pour un test de fumée sur la vraie tablette en kiosque. Pas couvert : kiosque (l'émulateur n'est pas device owner), appairage avec un vrai backend.

- **Commande vocale** (24/10) : « ferme … », « cherche … », « raccroche », « arrête le partage » à la voix. La reconnaissance entend souvent « mais » pour « mets » et « de » pour « 2 » : acceptés. Pas de mot de réveil (bouton seulement). La carte d'appel cache le bouton Parler : « réponds » / « ignore » à la voix ne servent pas encore.
- **Appels entrants** (24/10) : Vidéo, Ignorer, « Appel manqué » ; Discord en anglais ; Teams, WhatsApp, Zoom (une règle chacun dans `config/calls.json` après une capture pendant un vrai appel) ; Mac (pas fait).
- **Couverture des tests Android** (26/10) : tests JVM de `Computers` (liste, actif, provisoire adopté, migration, adresses), `Prefs`, `Connection` (probe et bascule contre de petits serveurs HTTP locaux, recherche WiFi injectée), `FocusTimer`, `Water` (rappel « Pense à boire », heure injectée) et de l'ordre `focus` du canal (sorti de `RemoteChannel` en `focusCommand`) ; Espresso : `HomeTest` (ordinateur appairé éteint : Accueil sans bienvenue, verre d'eau, chrono) et `ErrorsTest` (`errorText`). Pas couvert : le reste de `RemoteChannel.execute` (lié à `MainActivity`), le décompte réel du chrono (horloge Android figée sur la JVM).
- **Tests du backend étendus** (26/10) : deck (`runAction`, filtrage des pages, `/deck/features`, `/deck/live`), touches perso et macros, agenda personnel, liens de visio et « Rejoindre », Ne pas déranger, appels entrants, tâches récurrentes ; pilotage de l'ordinateur simulé (`backend/test/fake-os.ts`). Pas couvert : icônes des tuiles (`siteIcon`, réseau), ouverture automatique des réunions (`startAutoJoin`), page Média (deck Windows).
- **Tests Playwright des pages web** (26/10) : job CI `e2e` (page locale, visualiseur, retour OAuth), 18 tests verts dans l'image du job en local (arm64 et amd64). Pas encore passé sur le runner GitLab.
- **Format et style vérifiés en CI** (26/10) : prettier (`npm run lint` du backend, avec variables et paramètres inutilisés refusés par `tsc`) et ktlint (`./gradlew ktlintCheck`, sans plugin), règles calées sur le style existant. Pas d'eslint tant que typescript-eslint ne prend pas TypeScript 7.
- Carte « Prochain rendez-vous » teintée orange (fond et heure) comme son titre : pas encore vue sur la tablette.

- **Discord sans portail développeur** (24/10 soir ; « Quitter l'appel » et « Arrêter le partage » testés ensuite) : Discord expose son interface à UI Automation une fois l'accessibilité de Chromium demandée. `GET /deck/voice` lit le panneau vocal : appel ou salon, participants (vue d'appel privé), micro et sourdine. Page Discord : carte « En appel · … », touches selon l'état (`"when": "call" | "idle"` dans `deck.json`), « Quitter l'appel » = étape `press` (bouton « Déconnecter »). Plus de vert. PAS testé : Discord en anglais. **Qui parle** : illisible partout (appel privé, groupe, salon de serveur : ni nom ni classe ne change pendant qu'une personne parle, vérifié le 25/10). Diagnostic : `GET /deck/buttons?app=Discord&detail=true` (éléments + classes HTML).
- **Ouvertes sur Mac** (25/10) : les fenêtres des autres bureaux (applis en plein écran, Spaces) apparaissent, « autre bureau » ; vu sur la tablette avec deux Chrome en plein écran, toucher = passer sur leur bureau. Fenêtres d'une même appli triées par titre (elles ne s'échangent plus). Triple toucher (Mac) = plein écran sur son bureau / en sortir : PAS testé. Lecture de la liste : 1,6 à 2,5 s au lieu de 0,7 s (à surveiller). Word (fenêtre hors des écrans) toujours absent.
- **Caméra « Montrer sur l'ordi »** (25/10, testé sur la tablette) : la fenêtre de la caméra s'ouvre seule sur l'ordi au choix de l'usage (plus de bouton « Afficher sur l'ordi ») ; état « Ouverture sur l'ordi… » puis « En direct » ; bandeau « La fenêtre de la caméra est fermée sur l'ordi » + Rouvrir seulement si la page a été fermée (`GET /camera/status` → `viewers`, relu toutes les 2 s, 8 s laissées au navigateur).
- **Onglets de la Page de l'ordi** (25/10, testé sur la tablette avec le Mac et le PC) : le bouton actualiser (ambigu) remplacé par « Onglets » : liste des onglets ouverts sur l'ordi, celui affiché sur l'ordi en tête ; toucher = l'afficher sur la tablette, rien ne bouge sur l'ordi. Mac : AppleScript (Safari, Chrome, Edge…). Windows : **extension de navigateur « Compagnon Unowhy »** (`backend/extension/`, dossier « Extension navigateur » du paquet, chargée en mode développeur dans Opera GX sur le PC) qui pousse ses onglets au compagnon en local ; elle donne aussi la page de devant (plus fiable qu'UI Automation). Essais écartés sur Windows : UI Automation (Opera GX n'expose rien même en simulant un lecteur d'écran), fichier de session Chromium (verrouillé tant que le navigateur tourne). Pas testé : Firefox, plusieurs navigateurs à la fois.
- **Ne pas déranger pendant le Focus** (25/10, sur le PC perso) : pendant la concentration (pas la pause), l'ordinateur passe en Ne pas déranger, rendu à la pause ou à la fin ; pastille « Ne pas déranger sur l'ordi » dans Focus (activée par défaut). `POST /system/dnd {on, minutes}` (`src/dnd.ts` : ne rend que ce qu'il a activé, `.data/dnd.json`, minuteur de secours si la tablette ne revient pas), `GET /system/dnd`. Windows 11 : seule voie qui marche = l'interrupteur du centre de notifications (Win+N, UI Automation, ~0,5 s à l'écran) ; écartés, vérifiés sur 26H2 : état WNF des heures calmes (ignoré), `FocusSessionManager` (réservé par Microsoft), réglage du CloudStore (seulement le reflet de l'état, lu pour savoir l'état sans rien ouvrir). Testé sur le PC par le backend (activer 1 s, rendre 0,3 s). Mac (25/10, sur le Mac) : d'abord deux raccourcis Apple à créer à la main, remplacés (pas acceptable pour un client) par le Centre de contrôle via l'Accessibilité déjà accordée (`src/mac/dnd.ts`, identifiants AX indépendants de la langue, ~1 s à l'écran), testé par la route sur macOS 15 (activé/rendu, Ne pas déranger déjà mis laissé tel quel). Testé de bout en bout depuis la tablette sur le Mac et sur le PC (25/10 ; PC : activé au démarrage du chrono, rendu à la pause, compagnon installé). Bitdefender a mis `helper.ps1` en quarantaine à l'ajout de ce code (exception ajoutée sur le dossier du dépôt) : la production doit passer par un pilote signé. Même chose sur le compagnon installé du PC à sa mise à jour (25/10) : `helper.ps1` bloqué puis supprimé, tout le pilotage Windows en HTTP 500 ; réparé par une exception Bitdefender sur `Documents\Compagnon-Unowhy-PC`, redémarrage et recopie. Chez un client, tout antivirus risque d'en faire autant : bloquant pour la vente. Détection Bitdefender : `cmd:Heur.BZC.PZQ.Boxter` (analyse des lignes de commande : `powershell -ExecutionPolicy Bypass -File` lancé par Node, comme la clé Run de `run-windows.ps1`). Fait (25/10, sur le Mac) : `UnowhyHelper.exe` (pilote) et `UnowhyCompanion.exe` (lanceur de la clé Run) compilés en C# .NET Framework 4.8 à la place de `helper.ps1` et `run-windows.ps1` ; testés sur le PC sans aucune exception Bitdefender (toutes les routes Windows, Ne pas déranger depuis la tablette, relance après arrêt), aucune alerte. Reste : redémarrage du PC (démarrage à l'ouverture de session), signature des exe, pare-feu encore en PowerShell à l'installation (`firewall-windows.ps1`, une fois, avec accord administrateur).
- Corbeille : correctif d'un affichage resté après un glisser annulé.
- Boîtes de saisie (25/10, vu sur la tablette) : le clavier restait affiché après la fermeture (code d'appairage) et le premier appui sur un bouton était perdu (barres système rappelées). Fermeture du clavier encore un peu lente (animation d'Android).

- **Deck et Ouvertes** (24/10 soir, testés sur la tablette avec le PC) : onglets déplacés par appui long puis glisser (ordre gardé par ordinateur), balayage entre onglets, écrans agrandis, corbeille dans la bande du bas (garder 0,9 s puis lâcher, « Miam ! » + son). Bug vu une fois, pas reproduit : au démarrage, l'onglet Discord recouvrait Perso (revenu en déplaçant Discord) ; noter ce qui précède s'il revient.
- Installeur Windows corrigé (24/10 soir) : la boucle de relance ne démarrait pas depuis l'installeur (PowerShell lancé en `detached` par Node) → lancée par `start` comme à l'ouverture de session ; arrêt limité à `powershell.exe` (un numéro de processus réutilisé pouvait tuer un autre programme). Vérifié sur le PC de test : installation, puis redémarrage à distance (revenu en ~9 s). Cause de l'arrêt du compagnon à 18h49 inconnue (pas d'erreur au journal, Bitdefender n'a rien signalé).
- Photo réelle en mode « Envoyer une photo ». Sur l'émulateur, la caméra plante.
- Toucher Discord fermé (doit le lancer). « Ouvrir Discord » quand Discord est réduit dans la zone de notification.
- Accueil sans ordinateur (`ConnectWidget`). Widget Touches sur Windows. Conflit entre l'appui long d'un widget et le mode Modifier.
- Comptes Microsoft/Google pour l'agenda : le code est prêt, mais il faut enregistrer l'appli (Azure bloqué pour le compte Unowhy ; message préparé pour l'informatique). Voir [agenda-comptes.md](agenda-comptes.md).
- Coche automatique du worklog par Jira (il faut remplir les identifiants dans `.env`).
- Clavier dans KeyEditor, LinkAdder, WiFi et code d'appairage ; atténuation de nuit réelle ; rappels avec la vraie voix ; option « rejoindre automatiquement ».
- WiFi de la tablette (mauvais mot de passe, retour arrière), boutons Média (Pause, barre, Suivant), « Page de l'ordi » avec Edge ou Firefox, Annuler pendant une mise à jour.

## À faire, dans l'ordre

0. Bug des onglets du Deck (voir plus haut) s'il revient.
1. **Voix et appels** : appels Teams / WhatsApp (captures pendant un vrai appel, reporté par Eliot) ; idées : météo, réglages de la tablette à la voix. Réactiver l'appli Google sans adb : impossible (un device owner ne peut pas annuler `pm disable-user`), inutile car la procédure du kiosque la garde ; sans elle, Parler ouvre la commande écrite.
2. **Paquet** : relance du compagnon Mac à l'ouverture de session : vue le 25/10 (Mac redémarré, compagnon relancé seul, Accessibilité gardée). Pas de NSIS ni d'appli .app pour l'instant.

## Décisions prises

- **Plus d'Assistant IA** (26/10/2026) : jamais branché (`MODE=live` levait « pas encore implémenté »). Retirés : mode Assistant et `ChatFragment`, `POST /chat`, `POST /transcribe`, `providers.ts`, `MODE` et le champ `mode` de `/health`. La commande vocale (bouton Parler) et la synthèse vocale restent. La maquette cliquable du pitch montre encore une étape Assistant (non refaite).

## Décisions en attente

- Nom du projet, public cible (bureau ou école).
- Adresse provisoire de l'écran de bienvenue : `unowhy.com/compagnon` (`INSTALL_URL`), à revoir à la fin.
- Le Play Store reste-t-il actif en kiosque ?

## Avant de livrer ou de faire la démo

- **Maquette cliquable du pitch** (25/10, faite sur le PC) : tablette 4.2 + MacBook + écran en plus, 10 étapes onglet par onglet (Connexion, Accueil, Deck : Apps, Ouvertes, Média, Raccourcis, Discord seulement s'il est ouvert, Perso ; Assistant, Ordi, Focus avec Ne pas déranger, Caméra, Réglages), à projeter en partage d'écran si la démo en direct échoue ou pour la présentation. Artefact claude.ai du compte du PC, sources dans `docs/demo-hackathon/project/` (type Design : `Main.dc.html` + `canvas.json`) (déroulé de 10 min proposé : problème 1 min, démo 4 min, marché, offre et prix, time to market, demande au jury).

- **Désactiver « Autoriser les installations »** sur la tablette (Réglages > code technicien). Activé pendant le développement, il laisse aussi le Play Store se mettre à jour.
- **Release sur tag** (26/10) : un tag `vX.Y.Z` construit les paquets de l'ordinateur et l'APK release signé, et publie une release GitLab ([developpement.md](developpement.md), section Release). Reste à faire une fois sur GitLab : créer la clé, les variables `RELEASE_KEYSTORE_*` et protéger les tags `v*` ; aucun tag poussé encore. Les tablettes en APK debug devront refaire le kiosque pour passer à l'APK signé (autre clé).
- Préparation des tablettes en usine : réinitialisation hors ligne, device owner, langue, heure ([kiosque.md](kiosque.md)).
- Deck Mac de départ : c'est un profil de développeur (VS Code, iTerm…), à revoir pour un client.
- Bluetooth (enceinte, casque) impossible à appairer en kiosque.
- HTTP en clair sur le réseau local, protégé par le jeton. En production : HTTPS épinglé à l'appairage.

## Pour la vente (pas nécessaire à la démo)

- **Extension de navigateur** (bouton Onglets de « Page de l'ordi » sur Windows ; sur Mac, AppleScript suffit sauf Firefox). Aujourd'hui chargée en mode développeur, ce qui suffit pour la démo. Pour un client :
  - **Publier** avec un compte Unowhy : Chrome Web Store (5 $ une fois ; couvre Chrome, Brave, Vivaldi, et Opera par son module « Installer des extensions Chrome »), Edge Add-ons (gratuit), addons.mozilla.org (gratuit, signature obligatoire). Hors boutique, Chrome refuse l'extension sur Windows.
  - **Installeur** : clé de registre « extension externe » par navigateur (`HKCU\Software\Google\Chrome\Extensions\<id>`, idem Edge ; sur Mac, fichier `External Extensions/<id>.json`) : le navigateur propose l'extension au lancement suivant, le client clique « Activer ». Ne marche qu'avec une extension publiée.
  - **Page locale et tablette** : carte « Extension du navigateur » (reçue ou non, lien vers la boutique) ; dans Onglets, phrase « Installe l'extension sur l'ordi » si rien n'arrive.
  - **Firefox** : un seul manifeste pour les deux, en ajoutant `"background": {"scripts": ["background.js"]}` à côté de `service_worker` (Chrome l'ignore) et `browser_specific_settings.gecko.id` ; `chrome.*` marche tel quel.

## Idées notées, pas faites

Dofus Rétro (« Jouer » par un clic dans la fenêtre : perso, pas pour la démo) · mettre en commun `dp()` (15 copies) · réglage « ne jamais changer d'ordinateur » · Mot de réveil · presse-papiers partagé · empêcher la veille de l'ordinateur · extension de navigateur pour « Page de l'ordi » · bouton « Éteindre l'écran » · webcam USB (UVC, à demander à l'équipe firmware) · noms de programmes bruts sous Windows quand l'appli n'est pas au menu Démarrer · fenêtre de devant mise en avant dans Ouvertes · boutons « bureau précédent / suivant » (Mac) dans Ouvertes, si le triple toucher ne suffit pas.

## Historique

| Commit | Contenu |
|---|---|
| 9cb9d49 | premier état complet (deck, agenda, tâches, MCP, visualiseur, multi-ordinateurs, Windows, kiosque, Média, Page de l'ordi, mises à jour) |
| 60caf50 | audit « client » (erreurs lisibles, bienvenue, Assistant masqué), agenda perso, tâches récurrentes, eau, clavier |
| 3cf10e9 | Accueil à widgets, Discord, Focus, Caméra, Mes agendas |
| f0d1d4c | musique, Rejoindre multi-services, Accessibilité Mac, finitions kiosque |
| 638818b | onglet Ouvertes, YouTube Music |
| a37912b | balayage des onglets, écrans agrandis et corbeille dans Ouvertes, documentation |
| ca0ae6b | onglets déplaçables, corbeille en bande du bas avec son, installeur Windows réparé |
| 99eb31c | Discord par UI Automation (appel, participants, Quitter), bascule d'ordinateur après 2 min, relecture légère |
| 1cce5f2 | thèmes Nuit / Clair / Néon / Chaud, couleurs par fonction, animations |
| 5b2ac89 | commande vocale (bouton Parler, noms d'applis rapprochés à l'oreille, dictée, fenêtres, tâches), appels entrants Discord, Arrêter le partage |
| bf63b2b | paquets clients sans Node (node embarqué, `npm run package`), voix « sur l'écran de droite / de gauche / 2 » ; tests du 24/10 au soir (Spotify, Discord, voix, bascule) |
| 69c7081 → 2a2bd04 | paquet Windows testé sur le PC (installeur réparé), Discord salon de serveur, données et Partage du paquet hors du dossier extrait (`Documents\Partage tablette`), pare-feu Windows, raccourci et icône du compagnon (PC + tablette) |
| après 955bb58 | paquet Mac testé comme un client neuf (le Mac tourne depuis le paquet), clavier et premier appui des boîtes de saisie, MCP sur les données du paquet |
| après f45797e | Ouvertes sur Mac : autres bureaux et plein écran, fenêtres triées par titre, triple toucher = plein écran |
| après fb94e2d | Partage renommé « Depuis l'ordi » avec écran de choix expliqué, Caméra « Montrer sur l'ordi » |
| après 00ee201 | tests Playwright des pages web du backend (`backend/e2e/`), job CI `e2e` |
| après 12da92f | release sur tag `vX.Y.Z` : paquets et APK signé construits par la CI, release GitLab, `npm run updates:add` pour le « serveur Unowhy » |
| après a53cc32 | format et style vérifiés en CI : prettier (backend), ktlint (Android) |
