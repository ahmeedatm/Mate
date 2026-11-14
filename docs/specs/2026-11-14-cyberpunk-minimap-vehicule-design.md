# Cyberpunk : en voiture, la carte devient une mini-map GPS

Design du 2026-11-14, demande d'Ahmed. Suite de `2026-11-14-plugin-cyberpunk-design.md` (carte de la page
Cyberpunk, PR #260 et #261).

## Intention

La carte de la page Cyberpunk est aujourd'hui une carte à plat, nord en haut, qui suit le joueur. Quand
Ahmed conduit dans le jeu, il veut que la tablette se comporte comme le GPS de GTA V ou la navigation de
Google Maps : la carte zoome près de la voiture, tourne pour que l'avant de la voiture pointe vers le
haut, se penche en perspective (vue troisième personne), et montre le trajet GPS. En descendant de la
voiture, la carte revient à plat. Rien à toucher : la bascule est automatique.

## Décisions d'Ahmed

- Toute la carte bascule (pas une petite mini-map dans un coin).
- Bascule seule à la montée et à la descente du véhicule.
- Zoomée près du joueur, cap de la voiture en haut, penchée en perspective, trajet GPS tracé.
- Les rues viennent des tuiles raster existantes : le jeu n'a pas de données de routes vectorielles
  exploitables (dit à Ahmed, accepté).
- Spike d'abord sur sa vraie partie : savoir lire « en véhicule » (et la vitesse) depuis le mod. Si c'est
  impossible, on revient vers Ahmed avant de construire.

## Objectifs

1. Monter dans un véhicule (conducteur ou passager, voiture ou moto) : en 2 s au plus, la carte passe en mode conduite,
   avec une transition animée (zoom, rotation, inclinaison ensemble, ~400 ms).
2. Mode conduite : joueur au tiers bas de l'écran, cap du véhicule vers le haut, carte inclinée
   (~45°, constante réglée à l'essai), zoom qui recule un peu avec la vitesse (plus on roule vite, plus
   on voit loin), trajet GPS dessiné, objectif ou repère suivi visibles.
3. Le mouvement est fluide : la voiture ne saute pas d'un point à l'autre une fois par seconde.
4. Descendre du véhicule : retour animé à la carte à plat, nord en haut, zoom d'avant la montée.
5. Tenir sur la tablette 4.2 (MT8167, PowerVR GE8300, 18,2 ms par image) sans saccade visible, et sans
   faire clignoter les tuiles (le cache de #261 reste bon).

## Non-objectifs

- Petite mini-map dans un coin, ou mini-map par-dessus un autre écran.
- Données de routes vectorielles, rues redessinées, noms de rues.
- Calcul d'itinéraire par Mate : le trajet est celui du jeu, ou à défaut la ligne droite d'aujourd'hui.
- Bouton pour forcer le mode conduite à pied, ou réglage pour le couper (à proposer plus tard si besoin).
- Compteur de vitesse, radar, police : hors demande.
- Toucher au téléphone, au cyberware ou aux points d'intérêt (autres sessions).

## Anti-objectifs (échec même si ça marche)

- La carte saccade ou chauffe la tablette pendant une course.
- La voiture « téléporte » à chaque nouvelle position.
- La carte clignote entre les deux modes, ou repasse à plat parce que le jeu est en pause ou au menu.
- Le jeu ralentit parce que le mod écrit plus souvent.

## Contraintes

- Mod CET : tout appel au jeu sous `pcall`, pas de variable globale, aucun appel inconnu hors de ce que
  le spike prouve. Jamais d'écriture dans les sauvegardes de partie.
- Backend du PC : copie de `backend/` mise à jour à la main sur ATLAS (`maison/pc-atlas.md`).
- Carte : tuiles raster WebP déjà servies (`GET /cyberpunk/map`, pyramide), teintées au dessin.
- Android : vues XML et `Canvas`, pas de nouvelle dépendance, pas d'OpenGL écrit à la main.

## Le comment, partie tranchée

### Ce que le mod lit (à prouver au spike)

- En véhicule : `Game.GetMountedVehicle(player)` (ou `player:GetMountedVehicle()`), non nul en voiture.
- Cap : `vehicle:GetWorldYaw()` (le yaw du joueur peut suivre la caméra en voiture : on prend celui du
  véhicule s'il se lit).
- Vitesse : `vehicle:GetCurrentSpeed()` (m/s), pour le zoom et l'extrapolation.
- Trajet GPS du jeu : tenter de lire les points du chemin que le jeu trace vers l'objectif suivi. Si rien
  ne se lit proprement, garder la ligne droite en pointillés d'aujourd'hui (pas un échec).

`state.json` gagne un champ `vehicle` : `{ speed, yaw }` en véhicule, absent à pied. Rétrocompatible :
un ancien mod sans ce champ laisse la carte à plat.

### Fluidité : position plus fréquente en véhicule, et interpolée

Aujourd'hui la position arrive au mieux toutes les secondes (mod à 1 s + sondage à 1 s). À 100 km/h,
c'est un saut de ~30 m par image de position.

- Mod : en véhicule seulement, écrit un petit `pos.json` (`{ seq, at, x, y, yaw, speed, vehicle }`) toutes
  les ~0,2 s, à côté du `state.json` qui reste à 1 s. À pied, il ne l'écrit pas.
- Backend : route légère `GET /cyberpunk/pos` qui renvoie ce fichier avec `fresh` (faux au-delà de 2 s).
  Périmé : la tablette garde sa dernière position et son mode, sans extrapoler.
- Tablette : en mode conduite, sonde `/cyberpunk/pos` toutes les ~250 ms (le sondage de 1 s continue
  pour le reste) ; entre deux positions, la caméra glisse vers la dernière (interpolation, et légère
  extrapolation par la vitesse et le cap pour ne pas avoir de retard visible). Le cap tourne par le plus
  court chemin (pas de tour complet à 359° → 0°).

### Bascule

- Règle : monté dans un véhicule (conducteur ou passager, voiture ou moto, même à l'arrêt) = mode
  conduite. Pas de seuil de vitesse.
- Entrée : `vehicle` présent dans une lecture fraîche de `state.json` (2 s au plus avec le mod à 1 s et
  le sondage à 1 s) ; sortie : absent dans 2 lectures fraîches de suite (anti-clignotement).
- Données périmées (jeu en pause, menu, mode photo : le mod n'écrit plus) : le mode reste figé, jamais
  une descente.
- La caméra garde le zoom à plat d'avant la montée et le rend à la descente.
- Pendant la conduite, glisser ou pincer la carte reste possible (comme aujourd'hui « carte déplacée à la
  main ») ; le bouton « Centrer » ramène à la vue conduite.

### Dessin en perspective

- `MapCamera` gagne une rotation (cap) et une inclinaison ; elle donne une matrice écran (rotation puis
  perspective par `Matrix.setPolyToPoly` sur 4 points) appliquée au `Canvas` pour les tuiles et le trajet.
  Les repères, l'objectif et la flèche du joueur sont placés par la même projection mais dessinés droits
  (pas écrasés par l'inclinaison).
- Tuiles visibles : celles qui touchent le trapèze de sol vu à l'écran (les 4 coins de l'écran ramenés
  sur la carte), pas le rectangle d'aujourd'hui. Le haut de l'écran est coupé par un fondu vers le fond
  (pas d'horizon infini) : la profondeur visible est bornée, donc le nombre de tuiles aussi.
- Niveau de tuiles : choisi pour le bas de l'écran (le plus proche) ; le loin réutilise ce niveau (pas de
  mélange de niveaux dans une même image). Le cache doit tenir les tuiles du trapèze : `tileCacheBytes`
  est recalculé pour ce cas.
- Toucher la carte en mode conduite : la même projection inversée (poser un repère reste juste).

## Réponses au contrôle du constructeur (2026-11-14)

- Véhicules qui font basculer : tout véhicule monté, conducteur ou passager, même à l'arrêt (la demande
  dit « quand le joueur monte dans un véhicule »). Mon choix.
- Délai d'entrée : 2 s au plus, par `state.json` ; `pos.json` n'est écrit et sondé qu'en véhicule, pour ne
  rien coûter à pied. Mon choix.
- Pause ou menu : le mode reste figé (voir Bascule). Mon choix.
- Spike en partie négatif : cap illisible → cap déduit des deux dernières positions (au-delà de 2 m) ;
  vitesse illisible → zoom fixe et interpolation sans extrapolation. Seule l'absence de « en véhicule »
  renvoie vers Ahmed. Mon choix.
- Tablette qui décroche (image au-delà de 18 ms, mesurée par `dumpsys gfxinfo` sur la vraie tablette dès
  le premier dessin incliné) : dans l'ordre, fondu plus proche (moins de tuiles), inclinaison réduite,
  redessin à 30 Hz. Mon choix.
- Zoom rendu à la descente : celui d'avant la montée, même après un pincement en conduite. Mon choix.

## Laissé au constructeur

Valeurs exactes (inclinaison, zoom par vitesse, durée de transition, profondeur du fondu), forme du trapèze
de tuiles, découpage de `MapView`/`MapCamera` en fichiers, nom des chaînes.

## Questions ouvertes (le spike les tranche)

1. Quel appel dit « en véhicule » de façon sûre (voiture, moto, passager, taxi) et pas en cinématique ?
2. Le cap du véhicule se lit-il, et dans le même repère que le yaw du joueur (0 = nord) ?
3. La vitesse se lit-elle ?
4. Le chemin GPS du jeu se lit-il (liste de points) ? Sinon, ligne droite.
5. Écrire `pos.json` toutes les 0,2 s coûte-t-il quelque chose de visible au jeu ?

## Critère de réussite

Ahmed monte dans sa voiture dans sa partie : la tablette bascule seule en vue conduite, la carte tourne
avec la voiture sans saut, la route est lisible sous la flèche ; il descend : la carte revient à plat. Sur
la tablette 4.2, pas de saccade visible pendant une minute de conduite en ville.

## Premier geste

Spike sur ATLAS, jeu ouvert sur la partie d'Ahmed : un `logic.lua` d'essai qui écrit `vehicle`, la
vitesse, le cap et une tentative de chemin GPS, lu à pied puis en voiture.
