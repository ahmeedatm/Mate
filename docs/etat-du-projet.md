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
| Caméra | visualiseur à environ 10 images/s en WiFi, page `/camera` sur l'ordinateur, Figer, zoom, Arrêter |
| Kiosque | lock task, SQOOL masqué, installations bloquées, relance après mise à jour, WiFi dans Réglages, mises à jour silencieuses (WebView 152 installé), heure et fuseau auto, état de la tablette (batterie, luminosité, atténuation de nuit), écran de bienvenue |
| Extension Claude | serveur MCP `tablette` (modes, messages, tâches, agenda, captures…) |

## Fait mais pas encore testé en vrai

- **Ouvertes** (24/10 soir) : balayage entre les onglets du Deck, écrans agrandis, **corbeille** (fermer une fenêtre). Le PC Windows doit d'abord recevoir le compagnon à jour.
- Photo réelle en mode « Envoyer une photo ». Sur l'émulateur, la caméra plante.
- Toucher Discord fermé (doit le lancer). « Ouvrir Discord » quand Discord est réduit dans la zone de notification.
- Accueil sans ordinateur (`ConnectWidget`). Widget Touches sur Windows. Conflit entre l'appui long d'un widget et le mode Modifier.
- Spotify sur la page Média (pas encore de touche Spotify).
- Comptes Microsoft/Google pour l'agenda : le code est prêt, mais il faut enregistrer l'appli (Azure bloqué pour le compte Unowhy ; message préparé pour l'informatique). Voir [agenda-comptes.md](agenda-comptes.md).
- Coche automatique du worklog par Jira (il faut remplir les identifiants dans `.env`).
- Clavier dans KeyEditor, LinkAdder, WiFi et code d'appairage ; atténuation de nuit réelle ; rappels avec la vraie voix ; option « rejoindre automatiquement ».
- WiFi de la tablette (mauvais mot de passe, retour arrière), boutons Média (Pause, barre, Suivant), « Page de l'ordi » avec Edge ou Firefox, Annuler pendant une mise à jour.

## À faire, dans l'ordre

1. **Spotify** sur la page Média : ajouter la touche, tester la lecture.
2. **Dofus Rétro** : UI Automation ne voit rien dans la fenêtre. Le bouton « Jouer » demande un clic à une position dans la fenêtre. Il faut savoir ce qui s'ouvre quand on touche « Dofus 1 ».
3. Relecture du code du 24/10 (widgets, musique, Discord, Ouvertes), si demandée.
4. **Discord, lot 2** : nom du salon, Quitter, volume par personne, état du micro. Il faut une appli créée sur discord.com/developers (RPC, 50 testeurs sans approbation).
5. **Thèmes** (lot 5).
6. **Logiciel de l'ordinateur sans Node** : Node embarqué + lanceur Swift sur Mac, installeur NSIS sur Windows. Choix en attente. Avec : icône pour rouvrir la page, règle du pare-feu Windows, dossier Partage dans Documents.

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

Mot de réveil · Focus ↔ Ne pas déranger de l'ordinateur · presse-papiers partagé · empêcher la veille de l'ordinateur · extension de navigateur pour « Page de l'ordi » · bouton « Éteindre l'écran » · webcam USB (UVC, à demander à l'équipe firmware) · noms de programmes bruts sous Windows quand l'appli n'est pas au menu Démarrer · fenêtre de devant mise en avant dans Ouvertes.

## Historique

| Commit | Contenu |
|---|---|
| 9cb9d49 | premier état complet (deck, agenda, tâches, MCP, visualiseur, multi-ordinateurs, Windows, kiosque, Média, Page de l'ordi, mises à jour) |
| 60caf50 | audit « client » (erreurs lisibles, bienvenue, Assistant masqué), agenda perso, tâches récurrentes, eau, clavier |
| 3cf10e9 | Accueil à widgets, Discord, Focus, Caméra, Mes agendas |
| f0d1d4c | musique, Rejoindre multi-services, Accessibilité Mac, finitions kiosque |
| 638818b | onglet Ouvertes, YouTube Music |
| (suivant) | balayage des onglets, écrans agrandis et corbeille dans Ouvertes, documentation |
