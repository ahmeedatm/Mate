# Cyberpunk : le tiroir Cyberware comme l'écran cyberware du jeu

Design du 2026-11-14, demande d'Ahmed. Suite de `2026-11-14-plugin-cyberpunk-design.md` (plugin
Cyberpunk, PR #260) et de `2026-11-14-cyberpunk-telephone-design.md` (PR #262), dont il reprend la
méthode : spike sur la vraie partie, images extraites du jeu installé, ordres par `commands.json`.

## Intention

Le tiroir Cyber liste aujourd'hui les 11 zones l'une sous l'autre, avec le nom des implants posés.
Ahmed veut l'écran cyberware du jeu : la silhouette du corps au centre (squelette, système nerveux,
etc.), les zones et leurs slots autour, les implants posés dans leurs cases. Et il veut agir, pas
seulement regarder : poser ou changer un implant dans un slot depuis la tablette.

## Décisions d'Ahmed

- Look : copie de l'écran du jeu (pas une version « style Mate »), comme le téléphone.
- Agir : toucher un slot montre les implants possédés qui y vont ; en toucher un le pose (ou remplace
  celui qui y est), dans la partie en cours.
- Spike d'abord : le mod sait-il poser un implant que le joueur possède, et avec quel risque pour la
  partie ? Si c'est impossible ou trop risqué, retour à Ahmed avant de construire l'écran.
- Images du jeu extraites de son jeu installé sur ATLAS et servies par le backend du PC ; jamais
  commitées (dépôt public, droits CD Projekt).

## Objectifs

1. Voir d'un coup d'œil tout son cyberware, placé sur le corps comme dans le jeu : zone, slots
   (pleins et vides), implant (icône, nom, rareté).
2. Toucher un implant posé : sa fiche (nom, rareté, description courte du jeu).
3. Toucher un slot : la liste des implants possédés compatibles avec ce slot ; en choisir un le pose,
   le tiroir se met à jour, le jeu le montre posé (écran cyberware du jeu, effet actif).
4. Un tiroir qui ressemble à l'écran cyberware du jeu, pas à un écran de Mate.

## Non-objectifs

- Acheter, vendre, améliorer (crafting) un implant ; débloquer un slot.
- Retirer un implant sans le remplacer.
- Les autres tiroirs (Journal, Téléphone, Objets, Perso) et la carte : inchangés.
- Le modèle 3D du corps qui tourne : images fixes de l'écran du jeu seulement.
- Mac, Epic/GOG : comme le plugin (Windows, Steam).

## Anti-objectifs (échec même si ça marche)

- Une image, une police ou une icône du jeu dans le dépôt, l'APK ou une release.
- Poser ce que le jeu refuserait chez le charcudoc : implant non possédé, mauvaise zone, slot
  verrouillé, capacité de cyberware dépassée. Le mod applique les mêmes contrôles que le jeu, ou
  refuse.
- Un implant perdu : le remplacé retourne dans l'inventaire, comme chez le charcudoc.
- Faire planter le jeu : appels du moteur sous `pcall`, seulement ceux vus marcher au spike.
- Écrire dans une sauvegarde de partie.
- Un ordre exécuté deux fois (double appui, ordre rejoué au rechargement du mod).

## Contraintes

- Tablette 4.2 (`docs/tablette-4.2.md`) : 1280 × 800, vues XML natives, images décodées à la taille
  d'affichage, cache borné (tas de 128 Mo).
- Le tiroir de 420 dp est trop étroit pour un corps entouré de 11 zones : le tiroir Cyber s'élargit
  sur toute la largeur à droite du rail, par-dessus la carte (les autres tiroirs gardent 420 dp).
- Couleurs `cp_*` de `colors.xml`, textes dans `strings.xml`, comme le reste du plugin.
- Images extraites optionnelles : sans elles, le tiroir marche avec une silhouette dessinée en
  `cp_*` (formes simples) et les noms des implants.
- Backend : jeton d'appairage sur toutes les nouvelles routes ; images servies seulement d'une liste
  blanche écrite par l'extraction (jamais un chemin libre).
- Un implant se désigne par son `ItemID` complet (id TweakDB + graine), pas seulement son id TweakDB :
  deux exemplaires du même implant peuvent différer (rareté).

## Le comment, partie tranchée

1. **Mod** (`logic.lua`) : `state.cyberware` garde ses 11 zones ; chaque slot gagne de quoi désigner
   l'implant (`ItemID` en chaîne), son icône (`iconPath` TweakDB) et, si le jeu le dit, son état
   verrouillé. Bloc lent nouveau `cyberware.json` : les implants possédés non posés, avec leur zone,
   leur `ItemID`, nom, rareté, icône, coût en capacité ; plus la capacité utilisée / max. Ordre
   `equip{area, slot, item}` : vérifie possession, zone, slot, capacité, pose par l'appel du jeu
   prouvé au spike ; `res` `ok`, `non-possede`, `mauvaise-zone`, `slot-verrouille`, `capacite`.
2. **Backend** (`cyberpunk.ts`, `routes/cyberpunk.ts`) : `GET /cyberpunk/cyberware` (bloc
   `cyberware.json`), `POST /cyberpunk/cyberware/equip` `{ area, slot, item, version }` (409 avec `res`,
   504 sans réponse du mod, comme les autres ordres). Images de l'écran cyberware : même extraction
   WolvenKit que le téléphone, autre liste de fichiers, servies par des routes sur le modèle de
   `/cyberpunk/phone`.
3. **Tablette** (`ui/cyberpunk/`, `net/CyberpunkApi.kt`) : nouvelle vue `CyberwareScreen` (corps au
   centre, zones placées comme dans le jeu, slots en cases avec icône teintée par rareté). Toucher une
   case : panneau des implants compatibles (et la fiche de celui posé) ; toucher un implant :
   `equip`, cases désactivées jusqu'à l'ack, puis rechargement. Placement des zones : table pure testée
   (`CyberwareLayout`), calée sur une capture de l'écran du jeu.

## Réponses au contrôle du constructeur (2026-11-14)

- Contrôles du charcudoc : le mod ne réécrit pas les règles du jeu en Lua. `equip` passe par la
  vérification et la pose du jeu lui-même (celles du charcudoc). Le spike ne trouve pas de
  vérification réutilisable : retour à Ahmed.
- Moment : le mod refuse (`res` `indisponible`) en combat, en scène ou dialogue, en véhicule, au
  chargement, et si l'état du jeu ne se lit pas. Le spike prouve comment lire ces états.
- Pose = état cible « X dans ce slot », jamais « échanger ». Le mod relit le slot après la pose ; si ce
  n'est pas X, il remet l'ancien et rend `echec`. Le slot contient déjà X : `ok` sans rien faire (un
  ordre rejoué ne pose rien deux fois). Un implant à la fois. Après un 504, la tablette relit l'état,
  elle ne renvoie jamais l'ordre seule.
- `ItemID` en chaîne de chiffres (64 bits), relu par `loadstring` + `ULL` comme les repères, motif
  `^[0-9]{1,20}$` ; le mod retrouve l'objet dans l'inventaire par cette clé, refuse sinon.
- Compatibilité : champs ajoutés seulement. Mod sans `cyberware.json` (ancien) : tiroir en lecture
  seule, sans erreur.
- Fraîcheur : après une pose, le mod réécrit `cyberware.json` tout de suite avec `cmd` (numéro de
  l'ordre), comme `thread.json` ; `state.cyberware` fait foi pour ce qui est posé.
- Écran : tiroir Cyber pleine largeur à droite du rail (rail toujours visible, il ferme le tiroir
  comme aujourd'hui), cases tactiles de 48 dp au moins, test de `CyberwareLayout` en 1280 × 800.
- Spike : aussi un rechargement de sauvegarde et un rechargement du mod avec l'implant posé.
- Implants que le jeu ne laisse pas retirer (quête) : affichés, case non touchable, raison dite.

## Laissé au constructeur

- Noms exacts des champs et de l'ordre, découpage en commits.
- Liste exacte des fichiers d'archive retenus (silhouettes, cadres des cases, icônes des implants).
- Mise en page fine, calée sur des captures de l'écran du jeu prises sur ATLAS (pas de 3 variantes :
  la référence est le jeu lui-même).

## Questions ouvertes (le spike les tranche, avant tout écran)

1. Possédés : l'inventaire du joueur (`TransactionSystem:GetItemList`) rend-il les implants non posés,
   avec leur zone (`EquipmentArea` de la fiche TweakDB) ?
2. Poser : quel appel le charcudoc du jeu utilise-t-il (`EquipmentSystem` `EquipRequest` avec
   `slotIndex`, `EquipmentSystemPlayerData:EquipItem`, autre) ? Marche-t-il hors de chez le charcudoc,
   l'effet est-il actif (stat qui bouge), l'ancien implant revient-il dans l'inventaire ?
3. Contrôles : capacité utilisée et max, slot verrouillé (perk ou attribut), lisibles ? Le jeu
   a-t-il une fonction de vérification à réutiliser plutôt que de la réécrire ?
4. Risques : poser en combat, pendant une scène, un implant lié à une arme (bras), un OS ; ce qui
   arrive à la sauvegarde suivante (implant toujours posé après rechargement).
5. Images : quels fichiers portent la silhouette par système (squelette, nerveux…), les cadres des
   slots et les icônes des implants ; rendu en PNG réduit sur la tablette. Déjà repérés dans les
   archives (liste WolvenKit, 2026-11-14) : `base\gameplay\gui\fullscreen\ripperdoc\man_body` et
   `woman_body` (`.xbm` + `.inkatlas`), `ripperdoc\assets\cw_paperdoll_assets`, `cw_bars_assets`,
   `ripperdoc\background`, la mise en page `ripperdoc\ripperdoc.inkwidget`, et l'écran cyberware de
   l'inventaire `fullscreen\stats\player_cyberware_screen.inkwidget` + `atlas_cyberware`. Le corps
   suit celui de V (homme ou femme), lu par le mod.

Ce que la lecture des scripts du jeu a appris (2026-11-14, dumps `CDPR-Modding-Documentation/Cyberpunk-Scripts`,
`psiberx/cp2077-cyberware-ex`, `axefrog/xf-studio` ; rien d'essayé en jeu) :

- Le charcudoc (`ripperdoc.script`, `EquipCyberware`) fait `CheckIfCanEquip`, puis `UnequipRequest` sur
  le slot occupé, puis `EquipRequest{ owner, itemID, slotIndex }` sur `EquipmentSystem`. L'implant retiré
  reste dans l'inventaire (`UnequipItem` ne le donne ni ne le retire).
- La pose par index ne vérifie ni le verrou du slot ni les bornes : le mod doit tester lui-même
  `GetNumberOfSlots`, `IsSlotLocked`, le tag `UnequipBlocked` de l'implant en place, et la capacité
  (coût `HumanityAvailable` du nouveau moins celui de l'ancien ≤ `HumanityAvailable` du joueur), comme
  `CheckIfCanEquip`.
- Bras : refuser si une arme de bras est sortie (maillage cassé rapporté par d'autres mods).
- Lire : zone par `GetItemRecord(...):EquipArea():Type()`, icône par `Icon()` (atlas + pièce), corps de V
  par `GetResolvedGenderName()`, combat par `IsInCombat()`, scène par le blackboard
  `PlayerStateMachine.HighLevel`, véhicule par le tag `VehicleScene`.

Spike sur la vraie partie d'Ahmed, avec son accord pour chaque pose : un implant qu'il possède
déjà, dans un slot vide si possible, puis remis comme avant. Avant tout essai, une sauvegarde
manuelle faite par Ahmed dans le jeu (pas par Mate). Questions 1 ou 2 en échec, ou risque pour la
partie non maîtrisé : retour à Ahmed avant de construire l'écran.

## Critère de réussite

Ahmed ouvre le tiroir Cyber : il voit le corps et ses 11 zones comme dans le jeu, avec ses implants
dans leurs cases. Il touche un slot, choisit un implant qu'il possède : dans le jeu, l'écran
cyberware le montre posé et son effet est actif ; l'ancien est dans l'inventaire. Un implant qu'il
ne pourrait pas poser chez le charcudoc est refusé avec la raison.

## Premier geste

Spike sur ATLAS : enveloppe temporaire autour de `mods\mate\logic.lua` (rechargée par `reload.txt`,
comme pour le téléphone), ordres par `cmd.txt` via `ssh pc` ; lecture des possédés et de la
capacité, puis une pose avec l'accord d'Ahmed ; `logic.lua` d'origine remis ensuite.
