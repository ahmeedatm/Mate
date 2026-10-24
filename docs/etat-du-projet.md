# État du projet

Mis à jour le 24/10/2026. À tenir à jour en fin de session : c'est la mémoire partagée du projet (les notes de Claude restent sur l'ordinateur où il tourne).

## Pourquoi ce projet

Hackathon interne Unowhy. Unowhy a **environ 20 000 tablettes 4.2** en stock. L'objectif réel est de **les vendre**, pas seulement de leur trouver un usage. Unowhy vend aussi des PC : la tablette peut devenir un **accessoire du PC** (posée sur le bureau, sans configuration, en kiosque), sur le modèle du Stream Deck ou du Corsair Xeneon Edge.

Deux publics possibles, pas encore tranché :

- **Bureau / maison** : compagnon du PC (agenda, tâches, deck, fenêtres, son, musique, jeux).
- **École, « console du prof »** : télécommande du PC de la classe, minuteur et surtout **visualiseur de documents**. La caméra filme une feuille, le PC la projette. Les écoles achètent ces appareils entre 100 et 300 € : c'est ce qui donne une valeur matérielle à la tablette.

Éléments de pitch déjà validés :

- **Mises à jour** : pour la démo, un mini « serveur Unowhy » met à jour la tablette toute seule, en kiosque. Avant la démo, remettre le WebView d'usine (`adb shell pm uninstall-system-updates com.google.android.webview`). En direct : Réglages > Mises à jour > Rechercher > Mettre à jour, puis montrer une page web moderne dans « Page de l'ordi ». En production, on s'appuierait sur le MDM et l'OTA SQOOL plutôt que de réinventer la gestion de flotte.
- **Règle produit** : un client fait tout depuis la tablette, en kiosque, sans adb et sans quitter le kiosque (WiFi, ordinateurs, sites, applis…).
- **Limite Windows** : le pilote (PowerShell qui compile du C# à la volée, frappes clavier, lecture des fenêtres) ressemble à un malware pour les antivirus (Bitdefender l'a bloqué une fois). En production, il faudra un exécutable signé par Unowhy.

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
| Partage | dossier partagé, documents à côté de chaque mode, « Page de l'ordi » (page du navigateur de l'ordinateur dans la tablette) |
| Focus | Pomodoro, ambiance qui suit le chrono |
| Apparence | 4 thèmes (Nuit, Clair, Néon, Chaud) choisis dans Réglages ; une couleur par fonction (pastilles des titres, tuiles d'applis aux couleurs des marques) ; animations légères (appui, changement de mode, widgets, météo, Focus, eau), fluides sur la tablette |
| Caméra | visualiseur à environ 10 images/s en WiFi, page `/camera` sur l'ordinateur, Figer, zoom, Arrêter |
| Kiosque | lock task, SQOOL masqué, installations bloquées, relance après mise à jour, WiFi dans Réglages, mises à jour silencieuses (WebView 152 installé), heure et fuseau auto, état de la tablette (batterie, luminosité, atténuation de nuit), écran de bienvenue |
| Extension Claude | serveur MCP `tablette` (modes, messages, tâches, agenda, captures…) |
| Commande vocale | bouton Parler (reconnaissance Android, sans clé) : heure, ouvrir une appli même mal entendue (« ouvre Ankama Launcher »), modes, son, Focus… ; testé le 24/10 |
| Appels entrants | Discord (Windows) : carte Répondre / Vidéo / Ignorer sur la tablette, annonce parlée, puis page Discord du Deck ; testé le 24/10 (appel privé reçu, Répondre) ; touche « Arrêter le partage » pendant un stream (état lu, touche pas encore touchée) |

## Fait mais pas encore testé en vrai

- **Commande vocale** (24/10) : dictée « écris … » (collée dans l'appli de devant, sans doute par le presse-papiers), fenêtres (« réduis Chrome », « mets Discord à gauche / sur l'autre écran », « ferme … »), « quelles tâches aujourd'hui », « coche … », « qu'est-ce que j'ai aujourd'hui / demain », « rappelle-moi de … à 15 h / dans 10 minutes », « cherche … », « raccroche », « arrête le partage ». Pas de mot de réveil (bouton seulement). La carte d'appel cache le bouton Parler : « réponds » / « ignore » à la voix ne servent pas encore.
- **Appels entrants** (24/10) : Vidéo, Ignorer, « Appel manqué » ; Discord en anglais ; Teams, WhatsApp, Zoom (une règle chacun dans `config/calls.json` après une capture pendant un vrai appel) ; Mac (pas fait).
- Discord dans un salon de serveur : la liste des personnes est vide (les boutons du stream en sont retirés, les vrais participants ne sont pas lus).
- Carte « Prochain rendez-vous » teintée orange (fond et heure) comme son titre : pas encore vue sur la tablette.

- **Discord sans portail développeur** (24/10 soir, testé avec un appel de groupe sur le PC) : Discord expose son interface à UI Automation une fois l'accessibilité de Chromium demandée. `GET /deck/voice` lit le panneau vocal : appel ou salon, participants (vue d'appel privé), micro et sourdine. Page Discord : carte « En appel · … », touches selon l'état (`"when": "call" | "idle"` dans `deck.json`), « Quitter l'appel » = étape `press` (bouton « Déconnecter »). Plus de vert. PAS testé : « Quitter l'appel » en vrai, salon d'un serveur (nom attendu « salon / serveur »), Discord en anglais. **Qui parle** : illisible dans un appel privé (ni nom ni classe ne change) ; à vérifier dans un salon de serveur (avatar marqué). Diagnostic : `GET /deck/buttons?app=Discord&detail=true` (éléments + classes HTML).
- **Bascule d'ordinateur patiente** (24/10 soir) : l'actif injoignable depuis moins de 2 min est seulement recherché (nouvelle adresse) ; au-delà, un autre ordinateur prend le relais. Pas encore vu en vrai (prochain redémarrage du compagnon).
- Touche Spotify ajoutée à la page Média du PC (pas encore touchée). Corbeille : correctif d'un affichage resté après un glisser annulé.

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
1. **Discord** : tester « Quitter l'appel » et un salon de serveur (nom, qui parle, liste des personnes vide). Pas de RPC ni de portail développeur (décision d'Eliot : les clients ne doivent rien créer, pas de dépendance à une validation de Discord).
2. **Spotify** : toucher la touche (s'ouvre ? la musique part ?).
3. ~~Thèmes (lot 5)~~ : fait le 24/10 (carte « Prochain rendez-vous » corrigée, à voir).
3b. **Voix et appels** : appels Teams / WhatsApp (captures), faire réactiver l'appli Google par Companion (tablette neuve sans adb), idées : météo, réglages de la tablette à la voix.
4. **Logiciel de l'ordinateur sans Node** : Node embarqué + lanceur Swift sur Mac, installeur NSIS sur Windows. Choix en attente. Avec : icône pour rouvrir la page, règle du pare-feu Windows, dossier Partage dans Documents.

## Décisions en attente

- Nom du projet, public cible (bureau ou école).
- Adresse provisoire de l'écran de bienvenue : `unowhy.com/compagnon` (`INSTALL_URL`), à revoir à la fin.
- Fournisseur et budget de l'Assistant IA (`MODE=live` pas branché ; l'Assistant est masqué en `mock`).
- Le Play Store reste-t-il actif en kiosque ?

## Avant de livrer ou de faire la démo

- **Désactiver « Autoriser les installations »** sur la tablette (Réglages > code technicien). Activé pendant le développement, il laisse aussi le Play Store se mettre à jour.
- APK release **non signé** (pas de `signingConfig`).
- Préparation des tablettes en usine : réinitialisation hors ligne, device owner, langue, heure ([kiosque.md](kiosque.md)).
- Deck Mac de départ : c'est un profil de développeur (VS Code, iTerm…), à revoir pour un client.
- Bluetooth (enceinte, casque) impossible à appairer en kiosque.
- HTTP en clair sur le réseau local, protégé par le jeton. En production : HTTPS épinglé à l'appairage.

## Idées notées, pas faites

Dofus Rétro (« Jouer » par un clic dans la fenêtre : perso, pas pour la démo) · mettre en commun `dp()` (15 copies) · réglage « ne jamais changer d'ordinateur » · Mot de réveil · Focus ↔ Ne pas déranger de l'ordinateur · presse-papiers partagé · empêcher la veille de l'ordinateur · extension de navigateur pour « Page de l'ordi » · bouton « Éteindre l'écran » · webcam USB (UVC, à demander à l'équipe firmware) · noms de programmes bruts sous Windows quand l'appli n'est pas au menu Démarrer · fenêtre de devant mise en avant dans Ouvertes.

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
| (ce commit) | commande vocale (bouton Parler, noms d'applis rapprochés à l'oreille, dictée, fenêtres, tâches), appels entrants Discord, Arrêter le partage |
