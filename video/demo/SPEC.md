# SPEC : la démo produit « Un cadre qui se réarrange »

GOAL: le spectateur a vu, dans la vraie interface, ce que la tablette fait aujourd'hui pour chacune des six personas, et sait laquelle lui ressemble.

OUTPUT: HyperFrames, 1920 × 1080, 30 i/s, H.264.

TIME: 84 s × 30 = 2520 images. Ouverture 4 s, six chapitres de 12 s (360 images), fin 8 s. Musique HeyGen 58e906c3 « subtle modern corporate background » (86 s, la plus longue du catalogue).

LOOK:
- Sol : Cadran jour `#FAF2F1` plein cadre (nuit `#100D0D` pour Léo), aplat, grain 1 %. Aucun dégradé.
- Héros : la tablette (cadre noir 38 px, rayon 28 px). Au repos à gauche, 1180 px de large, centre (700, 540). Dans l'écran : uniquement les captures et enregistrements réels de `../captures/`.
- Colonne de droite (x 1340 à 1824) : le chapitre de la persona. Prénom et âge en Outfit 300 72 px, métier en Outfit 400 30 px `#4D4545`, citation en Outfit 300 40 px, 3 lignes au plus.
- Gros plan : la partie de l'écran qui porte l'action sort de la tablette à 1,6 à 2× (tuile, touche, carte), garde ses vrais libellés, puis y retourne.
- Accent `#CA0538` seulement sur le point « maintenant » de la ligne du jour qui sert de sommaire.

OBJECTS:
- La tablette, comme dans la pub.
- Le sommaire : la ligne du jour en bas du cadre (y 1000), 8 h à 20 h, six points pour les six personas ; le point du chapitre en cours est rouge.
- Le toucher : l'onde neutre de la DA (cercle 44 px qui s'ouvre à 120 px en 12 images).

MOTION:
- Ressorts M3 de la DA (spatial default 0,9 / 700 pour entrer, spatial fast 0,9 / 1400 pour sortir, effects default 1 / 1600 pour les fondus).
- Changement d'écran dans la tablette : le fondu enchaîné de l'appli (sortie alpha, entrée alpha + échelle 0,92 → 1, 12 images).
- Gros plan : 15 images à l'aller, 12 au retour, `power3.out` / `power2.in`.
- Changement de chapitre : la colonne de droite sort en 9 images, la suivante entre en 15, décalage de 3 images par ligne ; le point du sommaire glisse.

SHOTS (secondes, une seule scène `parcours`, chapitres dans l'ordre de la journée):
- 0-4 : ouverture. Accueil Cadran jour dans la tablette, « Six personnes, une tablette », le sommaire se dessine.
- 4-16 : Sylvie. Deck > Perso (toucher Export FEC, gros plan des touches), puis éditeur de touche : la macro en 5 étapes en gros plan.
- 16-28 : Camille. Enregistrement réel du balayage Travail → Perso → Clôture, puis page Réunion, micro coupé (gros plan).
- 28-40 : Julie. Deck > Apps (gros plan des 14 outils), puis Accueil 11:00, « Rejoindre » le webinaire (gros plan de la carte Agenda).
- 40-52 : Thomas. Accueil 14:29, carte « dans 1 min » en gros plan, puis Fichiers de l'ordi (gros plan de la liste).
- 52-64 : Karim. Accueil Contraste élevé (gros plan des touches), puis Réglages : taille du texte et thème.
- 64-76 : Léo. Fond en nuit, Agents Claude (gros plan des colonnes), puis MR GitLab.
- 76-84 : fin. Accueil, les six points rouges, icône, « UnoMate », logo Unowhy, « Mac et Windows, en WiFi. Sur la tablette Unowhy 4.2. ».

SOUND: musique HeyGen calme, sans voix, fondus 1 s / 2 s. Clic doux à chaque toucher.

BANNED: les défauts de `spec.md` ; plus : UI inventée ou redessinée ; fonctions non codées (OBS, soundboard, SimHub, chat Twitch) ; puces de fonctionnalités ; trois cartes en ligne ; texte qui bouge pendant la lecture.

QA:
- 7,2 / 12,4 / 22,5 / 30,5 / 43 / 66,5 / 72,5 s : capture réelle, gros plan lisible, citation lisible.
- 82 s : six points rouges, marque et texte dans la zone titre-sûre.
