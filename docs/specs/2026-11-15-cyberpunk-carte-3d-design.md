# Carte Cyberpunk en 3D, moteur d'Open2077 embarqué (design, 2026-11-15)

## Demande

« Exactement la même interface de map que celle du jeu », sans tout réécrire : reprendre un moteur existant, en
copie chez nous, intégré à la tablette, sans dépendre d'un site web.

## Ce qui existe (recherche du 15/11)

Aucun second écran avec la carte en direct pour Cyberpunk (Wand = surcouche dans le jeu, applis iPad = cartes fixes).
Le seul moteur qui refait la carte du jeu : `op77_map3d` d'Open2077 (`Open2077/open77-resources`,
`standalone/op77_map3d/web`, MIT, three.js) : ville en 3D tirée des fichiers de la carte 3D du jeu, caméra
inclinable, noms de quartiers, recherche, légende par catégorie, GPS calculé sur les 15 906 voies de circulation du jeu.
Le dépôt plus récent `Open2077/open77_3dmap` a abandonné la version web (interface native du jeu) : on garde la
version web, figée au commit `f3328271`. Seule la version compilée est publiée (pas le code source TypeScript) :
on règle le moteur par sa configuration (couleurs, textes, catégories), on ne le modifie pas.

## Essai sur la tablette (Lenovo M10, Android 12, WebView 102, 15/11)

| Bâtiments | Images/s en faisant glisser |
|---|---|
| tous (255 532) | 8 à 10 |
| aucun | 55 à 60 |
| 25 % les plus gros | ~30 |
| 10 % les plus gros | ~40 |

Retenu : 10 % par quartier (`KEEP` du compagnon), rendu à la densité 1 (`map.resolution` 0,67). Mémoire ~380 Mo.

## Architecture

- Moteur compilé dans `app/src/main/assets/carte3d/assets/` (650 Ko, MIT, sans aucune adresse Internet) ;
  `index.html` et `mate.js` à nous.
- `Map3dView` : WebView qui ne charge que `https://carte.mate.invalid/` (servi par `shouldInterceptRequest` :
  assets de l'APK, données du dossier de l'appli ; tout le reste refusé, Internet compris).
- Données (`world.glb`, `roads.bin`, `buildings.bin`, `manifest.json`, dérivées du jeu, jamais dans le dépôt) :
  le compagnon les télécharge une fois (SHA-256 épinglés, `assets/cyberpunk-map3d.json`), allège les bâtiments,
  les sert (`GET /cyberpunk/map3d`, `/cyberpunk/map3d/:fichier`) ; la tablette les copie dans `noBackupFilesDir`
  (empreintes vérifiées), puis ne les redemande plus.
- Pont (`mate.js`) : Mate joue le rôle d'« Open77 » (`window.Open77.on/emit`). Mate → carte :
  `MateMap3d.fire(nom, json)` (`map3d:role` full, `map3d:config`, `map3d:open`, `map3d:player`, `map3d:blips`,
  `map3d:waypoint`). Carte → Mate : `MateNative.emit` pour `hello`, `ready`, `waypoint`, `error`, `arrived`.
  Pincer = molette (le moteur ne gère pas le multi-touch).
- `Map3dBridge` (pur, testé) : position (yaw du jeu, même convention), blips (repères `pin:*`, points d'intérêt
  `poi:*`, objectif `quest` qui pulse), destination (repère suivi, sinon objectif), et lecture d'une destination
  posée sur la carte : nouveau repère suivi, repère existant suivi (à 3 m), ou effacée = retour à la quête et repère
  suivi retiré.
- En véhicule, la position rapide (`GET /cyberpunk/pos`, 4 par seconde) alimente la carte.

## Ce qui disparaît côté tablette

L'ancienne carte (tuiles en blocs, caméra, couches de points, filtres, vue conduite inclinée, tracé du chemin
In-World Navigation) : la carte 3D a sa propre légende, sa caméra et son GPS. Les routes du compagnon
(`/cyberpunk/map`, tuiles, `/cyberpunk/route`) restent pour les tablettes pas encore à jour.

## Limites connues

- Icônes des points d'intérêt : icônes intégrées du moteur par catégorie, pas encore celles du jeu.
- Pas de rotation à deux doigts (bouton « N » et vue 2D/3D du moteur à la place).
- Données d'Open2077, pas encore régénérées depuis le jeu d'ATLAS (`tools/export_web.py`, WolvenKit) : possible plus tard.
