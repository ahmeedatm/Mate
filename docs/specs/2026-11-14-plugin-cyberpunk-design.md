# Plugin Cyberpunk 2077 : la tablette en compagnon de jeu

Design du 2026-11-14, idée d'Ahmed. Premier « plugin de jeu » de Mate.

## Intention

Pendant qu'Ahmed joue à Cyberpunk 2077 sur son PC Windows (ATLAS), la tablette posée à côté devient
un écran de jeu : le personnage, le cyberware, l'inventaire, le téléphone du jeu, le journal des quêtes
et surtout la carte, avec des repères posés depuis la tablette qui apparaissent dans le jeu. Le jeu
n'a pas d'API : un petit mod Cyber Engine Tweaks (CET) signé Mate fait le pont.

Il a demandé « des plugins spéciaux pour certains jeux ». Cyberpunk est le premier : le design reste
ouvert à d'autres jeux (un plugin par jeu, dans le catalogue de `backend/src/plugins.ts`), sans cadre
générique de « plugins de jeu » tant qu'il n'y a qu'un jeu.

## Ce que le spike a prouvé (2026-11-14, sur la vraie partie d'Ahmed)

Mod CET jetable `mods\mate_spike` (lecture d'état chaque seconde vers `state.json`, commandes lues dans
`cmd.txt`, les deux dans le dossier du mod). Jeu 2.x Steam, CET v1.38.0, sur ATLAS.

- Lu sans erreur : niveau, street cred, 5 attributs, humanité, santé (%), argent, points d'attribut et
  de perk, cyberware par emplacement (11 zones, nombre de slots variable), inventaire (126 objets : nom
  localisé en français, type, rareté, quantité, id TweakDB), 24 contacts (appelable ou non, non lus),
  fils de messages (nom, aperçu, non lus, réponse possible), quête suivie (objectif « Rejoindre Judy. »,
  quête « Disasterpiece »), position monde (x, y, z) et orientation, appel en cours (phase, contact,
  audio ou vidéo, via le blackboard `UI_ComDevice.PhoneCallInformation`).
- Agi : `ItemActionsHelper.ConsumeItem` a utilisé un MaxDoc (santé 86 % → 100 %, objet retiré) ;
  `MappinSystem:RegisterMappin` (type `Mappins.DefaultStaticMappin`, variante
  `CustomPositionVariant`) a posé un repère visible sur la carte du jeu.
- Pas encore vu marcher : liste complète des quêtes (`JournalManager:GetQuests` a rendu une table vide :
  forme du paramètre de sortie à trouver), liste des véhicules (`VehiclesManagerDataHelper.GetVehicles`
  vide ; essai suivant : `VehicleSystem:GetPlayerUnlockedVehicles`), appel d'un contact
  (`questTriggerCallRequest` vers `PhoneSystem`, bloqué pendant l'essai par un appel de quête en cours),
  retrait d'un repère, suivi GPS d'un repère posé (`SetMappinTrackingAlternative`, supposé).
- CET (lu dans son code) : `io` confiné au dossier du mod, pas de réseau, `json` disponible. Le pont se
  fait donc par fichiers. CET ne tourne pas sur Mac : plugin Windows seulement.

Références open source lues : `dariulone/cyberpunk-vr-port` (MIT : contacts, messages, appel, véhicule),
`psiberx/cp2077-respector` (MIT : stats, équipement), scripts décompilés du jeu (journal, repères).
Aucun mod existant n'exporte l'état vers une appli : le mod est à écrire, en reprenant ces appels.

## Décisions d'Ahmed

- Tout voir : perso, cyberware, inventaire, téléphone, journal, carte.
- Lire et agir tout de suite : appeler un contact, décrocher et raccrocher, utiliser un objet, faire
  venir le véhicule, suivre une quête, poser des repères.
- Page du Deck, pas un widget de l'Accueil. Au lancement du jeu, la tablette bascule toute seule sur la
  page ; à la fermeture, retour à l'écran d'avant (comme un appel).
- DA du jeu (jaune néon, rouge, cyan sur noir, cadres coupés en biais), propre à la page, quel que soit
  le thème de Mate (comme Valorant garde ses couleurs d'équipe).
- Carte : tuiles de fans (dépôt `More-TALK/Cyberpunk-2077-__Map`, 197 tuiles WebP 256 px, grille
  14 × 14) téléchargées par le backend d'ATLAS au premier usage, jamais dans le dépôt public de Mate
  (captures du jeu : droits CD Projekt). Calage par 2 ou 3 points relevés en jeu.
- Repères : plusieurs, posés depuis la tablette ; toucher un repère le fait suivre par le GPS du jeu.
  Mate les garde (sur ATLAS, hors sauvegarde) et les repose à chaque chargement de partie.
- Journal : lire les quêtes actives et leurs objectifs ; toucher une quête la fait suivre dans le jeu ;
  l'objectif suivi s'affiche sur la carte de la tablette.
- Le mod CET est installé par Mate, depuis un bouton du plugin, et mis à jour avec le backend.

## Objectifs

1. Voir l'état de sa partie sur la tablette, à jour à la seconde, sans quitter le jeu des yeux longtemps.
2. Agir depuis la tablette sans ouvrir de menu dans le jeu (téléphone, soin, véhicule, quête, repères).
3. Une carte utilisable en jouant : sa position, la quête suivie, ses repères.
4. Brancher le plugin en un geste (installer le mod) et ne plus y penser.

## Non-objectifs (v1)

- Mac (CET n'y tourne pas) et versions Epic/GOG du jeu (Steam seulement en v1, à élargir si demandé).
- Répondre aux SMS (choix de dialogue) : lecture des messages seulement.
- Équiper ou changer le cyberware, vendre, fabriquer, dépenser des points.
- Points d'intérêt de la carte du jeu (vendeurs, ripperdocs, crimes) : seulement position, quête, repères.
- Cadre générique pour d'autres jeux.

## Anti-objectifs (échec même si ça marche)

- Ouvrir, modifier ou remplacer un fichier de sauvegarde. (Les repères que le jeu enregistre lui-même
  avec la partie sont voulus, voir « Réponses après relecture ».)
- Faire planter le jeu : tout appel au jeu est protégé (`pcall`), et un appel inconnu du moteur (assert
  natif que `pcall` n'attrape pas, cf. vr-port sur `GetVar`) est banni ; seuls des appels vus marcher.
- Ralentir le jeu (lecture limitée, pas de boucle lourde par image).
- Exposer une commande de jeu à n'importe quel programme local : le fichier de commandes n'est écrit que
  par le backend, et le backend n'accepte que des ordres authentifiés de la tablette (jeton d'appairage).
- Une page trop dense pour la tablette 4.2 (1280×800, 2 Go, PowerVR GE8300).
- Mettre les tuiles de la carte dans le dépôt public.

## Contraintes

- Tablette : `docs/tablette-4.2.md`. Carte en vues Android natives (pas de WebView lourde) ; tuiles
  WebP 256 px décodées à la demande, cache borné.
- Android : vues XML, une page par Fragment ou vue de page du Deck, textes dans `strings.xml`, erreurs
  par `errorText(e)`, HTTP par `HttpURLConnection` + `org.json` (un client `net/CyberpunkApi.kt`).
  Couleurs propres à la page : exception assumée aux attributs `?attr/c_…`, comme Valorant (palette
  dédiée dans les ressources, pas en dur dans le code).
- Backend : un plugin de catalogue (`plugins.ts`, `kind: "app"`, `present` = jeu trouvé sur Windows),
  une route par domaine `routes/cyberpunk.ts`, accès système par `os.ts` (Windows seulement).
- Réseau : comme le reste, HTTPS sur le WiFi, jeton d'appairage.
- CET : chemins relatifs au dossier du mod seulement, pas de réseau.

## Le comment, partie tranchée

Trois morceaux :

1. **Mod CET `mods\mate\`** (Lua, versionné dans le dépôt sous `backend/assets/cyberpunk-mod/` ou
   équivalent). Écrit `state.json` toutes les ~1 s (écriture dans un fichier temporaire puis
   renommage, pour ne jamais livrer un JSON coupé), avec un numéro de séquence et l'heure. Lit
   `commands.json` (file d'ordres numérotés), exécute, écrit le résultat de chaque ordre dans
   `state.json` (`acks`). L'inventaire complet, les contacts et les quêtes changent peu : écrits à part
   et moins souvent, ou à la demande, pour garder le fichier rapide.
2. **Backend (ATLAS)** : trouve le jeu (registre Steam `SteamPath` + `libraryfolders.vdf` +
   `appmanifest_1091500.acf`), vérifie CET, installe et met à jour le mod (bouton « Installer »),
   lit `state.json`, sait si le jeu tourne (processus `Cyberpunk2077.exe`), expose l'état et accepte les
   ordres (`GET /cyberpunk`, `POST /cyberpunk/action`), garde les repères (`.data/cyberpunk-pins.json`)
   et les renvoie au mod à chaque chargement de partie détecté, télécharge et sert les tuiles de la carte
   (cache dans `.data`).
3. **Tablette** : page Cyberpunk du Deck, ouverte toute seule au lancement du jeu (nouveau veilleur
   « jeu lancé » sur le modèle de `meeting-watch.ts` + ordre vers la tablette, retour à l'écran d'avant
   à la fermeture), onglets Perso / Cyberware / Inventaire / Téléphone / Journal / Carte. Lecture par
   sondage (`GET /cyberpunk`, 1 s jeu ouvert), comme Valorant.

Plugin coupé : le veilleur ne bascule plus, la page disparaît du Deck, le backend refuse les ordres ;
le mod reste installé (le désinstaller est un bouton à part).

## Maquette retenue : « Carte d'abord » (2026-11-14)

Choisie par Ahmed parmi 3 variantes (hub à onglets, carte d'abord, moniteur). La carte occupe tout
l'écran (fond de fans assombri et teinté rouge-ocre, joueur en flèche jaune avec onde, objectif de
quête en losange jaune, trajet GPS en pointillés jaunes, repères cyan, repère suivi jaune qui pulse).
Posés dessus, en panneaux coupés en biais : un rail d'icônes à gauche (Journal, Téléphone, Objets,
Cyber, Perso ; pastille cyan sur un message non lu) qui ouvre un tiroir de 420 px par-dessus la carte ;
en haut au centre, V, niveau, barre de santé, argent ; dessous, le bandeau d'appel cyan (Raccrocher) ;
à droite, les actions rapides Soin et Voiture ; en bas à droite, la quête suivie et le nombre de repères.
Toucher le vide pose un repère, toucher un repère le fait suivre par le GPS, glisser déplace la carte.
Polices : Chakra Petch (déjà dans `res/font`) et Share Tech Mono pour les chiffres. Le coin haut-droit
(72 × 72) reste libre pour le bouton Fermer.

Carte de fans mesurée : 14 × 14 images de 7936 px de côté, 766 Mo au total. Le backend la télécharge une
fois et la réduit en petites tuiles (pyramide de 2 ou 3 niveaux, quelques dizaines de Mo) avant tout
envoi à la tablette ; l'outil de réduction sous Windows est au choix du constructeur (sans dépendance
lourde si possible), la tablette ne reçoit jamais une image de plus de 512 px.

## Laissé au constructeur

- Format exact de `state.json` et `commands.json`, cadence de chaque bloc.
- Ordre des PR (proposé : 1. mod + lecture + page ; 2. actions ; 3. carte et repères ; le plan peut
  regrouper autrement).
- Méthode de calage de la carte (2 ou 3 points en dur, relevés une fois).

## Questions ouvertes (à trancher par un 2e spike, avant la PR concernée)

- Liste des quêtes : forme de `GetQuests` / `GetChildren` sous CET.
- Véhicules : liste et `SummonActiveVehicle`.
- Appel sortant : `questTriggerCallRequest` hors appel de quête.
- Décrocher / raccrocher : appel du jeu ou touche simulée.
- Repère suivi par le GPS (`SetMappinTrackingAlternative` ou autre) ; retrait d'un repère.
- Chargement de partie : comment le mod le détecte (événement CET `onInit` / changement de session) pour
  reposer les repères.
- Repères posés par le mod : le jeu les garde-t-il dans la sauvegarde ? (voulu, voir plus bas ; décide
  si le mod doit reposer les manquants à chaque chargement.)
- La carte de fans couvre-t-elle Dogtown ?

## Réponses après relecture

Décisions d'Ahmed :
- Action qui ne marche pas proprement par le mod (appel, décrocher, véhicule, GPS d'un repère) : Mate
  simule la touche du jeu (ex. T pour décrocher, V pour le véhicule ; touches lues dans la config du
  jeu, envoyées par `os.ts` seulement si la fenêtre du jeu est devant) ; sans touche possible, l'action
  sort de la v1 et devient une note « à faire ». Aucune PR bloquée par une action.
- Repères : Ahmed veut qu'ils restent dans sa sauvegarde. Mate ne touche jamais aux fichiers de
  sauvegarde ; si le jeu enregistre lui-même les repères avec la partie, c'est voulu. Mate garde aussi
  sa copie (`.data/cyberpunk-pins.json`) et, à chaque chargement, ne repose que les repères absents
  (comparaison par position, à 1 m près), pour ne jamais créer de doublon.
- KVM sur le Mac, jeu lancé sur ATLAS : la tablette bascule quand même sur ATLAS et ouvre la page.
  La tablette écoute donc aussi l'état de jeu des autres ordinateurs appairés (ATLAS) même quand ils ne
  sont pas actifs, puis change d'ordinateur actif et revient à l'ordinateur d'avant à la fermeture.

Mes choix (un constructeur sensé trancherait pareil) :
- Bascule : quand le mod signale une partie chargée (`inGame` dans un `state.json` frais), pas au simple
  lancement du processus : on évite les minutes d'écran de chargement et le menu principal. Retour à
  l'écran d'avant quand le processus s'arrête ou que l'état est vieux de plus de 10 s.
- Routes séparées : `GET /cyberpunk` léger et sondé à 1 s (santé, position, quête suivie, appel en
  cours, numéro de version de chaque bloc) ; `GET /cyberpunk/{inventaire,contacts,messages,quetes}`
  à la demande, quand leur version change ou que l'onglet s'ouvre.
- Tuiles : URL épinglée sur un commit du dépôt de fans, SHA-256 vérifié, cache dans `.data`. Échec
  ou hors ligne : fond de grille sombre avec position, quête et repères (carte toujours utilisable).
- Calage : transformation affine à 3 points relevés une fois en jeu, en dur dans le code ; hors grille,
  le fond de grille prend le relais.
- Mise à jour du mod jeu ouvert : différée à la fermeture du jeu (le bouton le dit).

## Critère de réussite

Ahmed lance Cyberpunk sur ATLAS : la tablette bascule sur la page Cyberpunk, montre son niveau, son
cyberware et sa quête suivie. Il pose un repère sur la carte de la tablette, le voit dans le jeu, le
touche, et le GPS du jeu l'y mène. Il recharge sa partie : le repère est toujours là.

## Premier geste

2e spike sur ATLAS (mod `mate_spike` déjà posé) pour fermer les questions ouvertes ci-dessus, puis
retirer `mods\mate_spike`.

## Contrat (fixé avant le code, 2026-11-14)

Écarts assumés au texte d'origine, tranchés par Claude (mode autonome accordé par Ahmed) :
- Pas de veilleur backend ni d'ordre « jeu lancé » : la tablette ne parle qu'à son ordinateur actif
  (`RemoteChannel`), elle ne recevrait rien d'ATLAS quand elle est sur le Mac. C'est donc la tablette
  qui interroge `GET /cyberpunk/presence` sur chaque ordinateur Windows appairé (toutes les 4 s, délai
  2 s, appli au premier plan seulement), bascule d'ordinateur actif (`Computers.setActive` +
  `onComputerChanged(…, automatic = true)`) et ouvre la page quand `inGame` devient vrai ; fin quand
  `inGame` est faux depuis 10 s : fermer la page et rendre l'ordinateur d'avant, sauf si Ahmed en a
  choisi un autre entre-temps. Pas de bascule pendant une saisie, un appel ou un écran plein.
- La page est un calque plein écran (modèle `ui/PageOverlay.kt`, bouton Fermer en haut à droite), pas
  une page de profil de l'Accueil.
- Palette propre dans `app/src/main/res/values/colors.xml` (`cp_*`, lue par `R.color`), aucun hex dans
  le Kotlin.

Mod (dossier `…\cyber_engine_tweaks\mods\mate\`, sources dans `backend/assets/cyberpunk-mod/`) :
`init.lua` fixe (charge `logic.lua` par `loadfile`, le recharge quand `reload.txt` est non vide, écrit
`loader-error.txt`), `logic.lua` (toute la logique, remplaçable jeu ouvert), `VERSION`.

`state.json` (écrit ~1 s par le mod) :
```
{ v:1, seq, at, inGame, session, mod:"1.0.0",
  player:{ level, cred, attrs:{ body, ref, tech, int, cool }, health, humanity, money, pts:{ attr, perk } },
  pos:{ x, y, z, yaw },
  cyberware:{ <zone>: [ {tdb,name,quality} | false ] },
  tracked:{ kind:"quest"|"pin", id, quest, desc } | null,
  call:{ phase:"none"|"incoming"|"active", contact, audio } | null,
  blocks:{ inv, contacts, quests },
  pins:[ { pid, x, y, z } ],
  acks:[ { id, ok, res } ],
  errors:{} }
```
Blocs lents, réécrits seulement quand ils changent (le compteur `blocks.*` suit) :
`inventory.json` `{ v, items:[ {tdb,name,type,quality,qty,usable} ] }`, `contacts.json`
`{ v, contacts:[ {id,name,callable,unread} ], threads:[ {name,preview,unread,time} ] }`,
`quests.json` `{ v, quests:[ {id,title,kind,objective,tracked} ] }`.

`commands.json` (écrit seulement par le backend, fichier temporaire puis renommage) :
`{ v:1, cmds:[ { id, t, … } ] }` ; le mod exécute les `id` supérieurs au dernier traité, ne vide jamais
le fichier, et rend `acks`. `t` : `call{contact}`, `answer`, `hangup`, `use{tdb}`, `summon`,
`track{kind,ref}` (`id` est déjà le numéro de l'ordre), `pin{pid,x,y,z}`, `unpin{pid}`.

Routes (jeton d'appairage ; 403 si plugin coupé, sauf `presence` qui rend `available:false`) :
- `GET /cyberpunk/presence` → `{ available, gameFound, modInstalled, running, inGame, session }`
- `GET /cyberpunk` → `{ status:"off"|"menu"|"ingame", install:{ gameFound, cet, modInstalled, modVersion,
  current, pending }, state }` (`ingame` = `state.json` de moins de 10 s et `inGame`)
- `GET /cyberpunk/inventory`, `/cyberpunk/contacts`, `/cyberpunk/quests` → blocs lents
- `POST /cyberpunk/install`, `/cyberpunk/uninstall` `{}` → `{ ok, deferred }` (`init.lua` changé jeu ouvert :
  différé à la fermeture ; `logic.lua` seul : remplacé et rechargé par `reload.txt`)
- `POST /cyberpunk/action` `{ type:"call"|"answer"|"hangup"|"summon"|"use"|"track", contact?, tdb?, kind?, id? }`
  → `{ ok, id, res }` (409 hors partie, 504 sans réponse du mod en 5 s)
- `GET /cyberpunk/pins`, `POST /cyberpunk/pins` `{ x, y, z?, name? }` → `{ id }`, `DELETE /cyberpunk/pins/:id`,
  `POST /cyberpunk/pins/:id/track`
- `GET /cyberpunk/map` → `{ status:"absent"|"loading"|"ready"|"error", done, total, tile:512, levels, size:[w,h],
  affine:[a,b,c,d,e,f] }` ; `POST /cyberpunk/map/prepare` `{}` ; `GET /cyberpunk/tiles/:z/:x/:y.webp`

Tuiles : `dwebp`/`cwebp` officiels de libwebp (BSD, zip `libwebp-1.6.0-windows-x64.zip` téléchargé dans
`.data/` au premier usage, SHA-256 épinglé), images sources épinglées par commit et SHA-1 de blob du
dépôt de fans ; pyramide de tuiles 512 px dans `.data/cyberpunk-map/`.

## Calage de la carte (mesuré le 2026-11-14)

Sans mesure en jeu : les 255 532 bâtiments de Night City en coordonnées du jeu (données de
`Open2077/open77-resources`, `standalone/op77_map3d/web/data/buildings.bin`, MIT, extraites de
`base\entities\cameras\3dmap`) ont été dessinés vus du dessus puis recalés par corrélation sur les
contours de la carte de fans (aperçu 1792 px). Pic net à 0,174 px d'aperçu par mètre, même échelle
en x et y, nord en haut, sans rotation ; la superposition tombe sur les bâtiments (place Arasaka,
ponts, côte). Sur la carte pleine résolution (111 104 px de côté) :
pixel = (61008 + 10,788·x ; 46934 − 10,788·y), x et y en mètres du jeu (X est, Y nord).
