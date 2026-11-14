# Carte Cyberpunk en blocs par quartier (design, 2026-11-14)

## Demande

La carte de Night City de la page Cyberpunk ressemble à la carte holographique du jeu : des blocs en relief,
une couleur par quartier, sans le détail des vrais bâtiments. Repères, points d'intérêt, filtres, vue conduite,
zoom sans clignotement et position du joueur restent tels quels.

## Choix

Trois rendus montrés en maquettes sur la tablette (cubes du jeu en fil lumineux, pâtés de blocs de 24 m, plan à
plat avec liseré entre quartiers) ; Ahmed a pris le premier, « Hologramme du jeu ».

## Données

Les cubes de la carte 3D du jeu (`base\entities\cameras\3dmap`, nuages de points `3d_map_cubes`) déjà extraits
par Open2077 : `Open2077/open77-resources`, `standalone/op77_map3d/web/data/` (`manifest.json` et
`buildings.bin`, 4 Mo, 255 532 boîtes rangées par quartier : position, quaternion, taille ; code MIT, données
dérivées du jeu). Commit et SHA-256 épinglés dans `backend/assets/cyberpunk-blocs.json` ; téléchargés par le
compagnon au premier usage, jamais dans le dépôt. Aucune carte de quartiers en GeoJSON libre n'a été trouvée :
le quartier vient du rangement du jeu lui-même.

Le jeu range les Badlands avec Pacifica : une boîte de `pacifica` à l'est de x = −800 m ou au nord de
y = −1700 m est comptée aux Badlands (limite tracée à l'œil). Dogtown et Morro Rock (`ep1_spaceport`) ont leur
couleur.

## Rendu (`backend/src/cyberpunk-blocks.ts`)

- Sol d'une boîte = le bas le plus bas des boîtes de sa maille de 80 m (relief, ponts, autoroutes surélevées) ;
  hauteur comptée depuis ce sol.
- Vue de dessus, toit levé de 0,3 m vers le nord par mètre de hauteur ; murs entre le pied et le toit.
- Dessin du nord au sud, puis du plus bas au plus haut (le sud cache le nord).
- Couleur du quartier : murs à 16 %, arêtes des murs à 55 %, toit de 32 à 72 % selon la hauteur, arêtes du toit
  à 135 % ; fond `cp_fond`.

## Tuiles (`backend/src/cyberpunk-map.ts`)

Même géométrie que l'ancienne carte de fans (28 672 px au niveau max, même `affine`), donc repères et points
d'intérêt inchangés. 49 groupes (une tuile de z0 et ses 84 tuiles en dessous) : les 64 tuiles de z3 dessinées,
réduites par moyenne 2 × 2 pour z2, z1, z0, encodées par `cwebp`. Une tuile vide n'est encodée qu'une fois puis
recopiée. Préparation mesurée : 16 s sur le Mac, 42 Mo de tuiles. Les anciennes tuiles (`.data/cyberpunk-map`,
~120 Mo) sont supprimées au premier usage.

La tablette dessine les tuiles telles quelles (plus de filtre de teinte).

## Limite connue

Les toits sont levés vers le nord dans l'image : en vue conduite, quand la carte tourne, le relief penche avec
elle.
