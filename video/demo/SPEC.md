# SPEC : la démo produit « Un cadre qui se réarrange »

GOAL: le spectateur a vu, dans la vraie interface, ce que la tablette fait aujourd'hui pour chacune des six personas, et sait laquelle lui ressemble.

OUTPUT: HyperFrames, 1920 × 1080, 30 i/s, H.264.

TIME: 84 s × 30 = 2520 images. Ouverture 4 s, six chapitres de 12 s (360 images), fin 8 s. Musique HeyGen 58e906c3 « subtle modern corporate background » (86 s, la plus longue du catalogue).

LOOK:
- Sol : Cadran jour `#FAF2F1` plein cadre (nuit `#100D0D` pour Sacha), aplat, grain 1 %. Aucun dégradé.
- Héros : la tablette Unowhy 4.2, sa vraie coque vue de face (`assets/brand/tablette-4.2.png`, tirée de la photo produit SQOOL_FACE-EMPTY_SCREEN, plaque SQOOL effacée), 1040 px de large, coin haut gauche (150, 80). Dans sa zone d'écran : uniquement les captures et enregistrements réels de `../captures/`.
- Colonne de droite (x 1340 à 1824) : le chapitre de la persona. Prénom et âge en Outfit 300 72 px, métier en Outfit 400 30 px `#4D4545`, citation en Outfit 300 40 px, 3 lignes au plus.
- Gros plan : la partie de l'écran qui porte l'action sort de la tablette à 1,6 à 2× (tuile, touche, carte), garde ses vrais libellés, puis y retourne.
- Accent `#CA0538` seulement sur le point « maintenant » de la ligne du jour qui sert de sommaire.

OBJECTS:
- La tablette Unowhy 4.2 (coque blanche, sans plaque).
- Le sommaire : la ligne du jour en bas du cadre (y 1000), six points à égale distance pour les six personas ; le point du chapitre en cours est rouge.
- Le toucher : l'onde neutre de la DA (cercle 44 px qui s'ouvre à 120 px en 12 images).

MOTION:
- Ressorts M3 de la DA (spatial default 0,9 / 700 pour entrer, spatial fast 0,9 / 1400 pour sortir, effects default 1 / 1600 pour les fondus).
- Changement d'écran dans la tablette : le fondu enchaîné de l'appli (sortie alpha, entrée alpha + échelle 0,92 → 1, 12 images).
- Gros plan : 15 images à l'aller, 12 au retour, `power3.out` / `power2.in`.
- Changement de chapitre : la colonne de droite sort en 9 images, la suivante entre en 15, décalage de 3 images par ligne ; le point du sommaire glisse.

SHOTS (secondes, une seule scène `parcours`, chapitres dans l'ordre de la journée ; chaque persona est choisi dans Réglages > Démo, son thème de démo habille la tablette):
- 0-4 : ouverture. Écran de la tablette éteint, « Six personnes, une tablette » puis « Mate : chaque utilisateur configure son Mate. », le sommaire se dessine ; l'écran s'allume sur Nour.
- 4-16 : Nour (Registre). Écran Excel (toucher Enregistrer, gros plan des touches Excel et Libellés), puis Accueil : widget Clôture en gros plan (étapes, J-2).
- 16-28 : Gwen (Studio). Écran Campagnes (gros plan des touches Réseaux et Modèles), puis écran Présentation : toucher Suivante, gros plan du widget Présentation.
- 28-40 : Camille (Maison). Écran Travail, toucher « Je m'absente » (gros plan), puis l'absence en cours : Reprendre (gros plan).
- 40-52 : Aimée (Tableau). Accueil 14:29, « Point client Boréal dans 5 min » en gros plan, puis l'alerte plein écran 5 min avant (gros plan du compte à rebours).
- 52-64 : Sam (Contraste élevé, texte ×1,5). Accueil (toucher Courrier type, gros plan des grandes touches), puis Réglages > Accessibilité : retour parlé des actions (gros plan).
- 64-76 : Sacha (Terminal). Fond en nuit, écran Code : agents Claude en gros plan, puis écran GitLab : widget Pipelines en gros plan.
- 76-84 : fin. Boîte des six personas, les six points rouges, icône, « Mate », logo Unowhy, « Mac et Windows, en WiFi. Sur la tablette Unowhy 4.2. ».

SOUND: musique HeyGen calme, sans voix, fondus 1 s / 2 s. Clic doux à chaque toucher.

BANNED: les défauts de `spec.md` ; plus : UI inventée ou redessinée ; fonctions non codées (OBS, soundboard, SimHub, chat Twitch) ; puces de fonctionnalités ; trois cartes en ligne ; texte qui bouge pendant la lecture.

QA:
- 7,5 / 12,7 / 19,5 / 24,7 / 31,5 / 36,7 / 43,5 / 48,7 / 55,5 / 60,7 / 67,5 / 72,7 s : capture réelle, gros plan lisible, citation lisible.
- 82 s : six points rouges, marque et texte dans la zone titre-sûre.
