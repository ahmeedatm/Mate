# Cyberpunk : les points d'intérêt du jeu sur la carte de la tablette

Design du 2026-11-14, demande d'Ahmed. Suite de `2026-11-14-plugin-cyberpunk-design.md` (PR #260, #261),
qui gardait hors périmètre « Points d'intérêt de la carte du jeu (vendeurs, ripperdocs, crimes) » : cette
exclusion est levée ici.

## Intention

La carte de la page Cyberpunk montre aujourd'hui le joueur, l'objectif suivi et les repères posés depuis
la tablette. Ahmed veut y voir tout ce que la carte du jeu montre : quêtes et contrats, métro et voyage
rapide, boutiques et charcudocs, crimes NCPD, etc., avec les icônes du jeu, et un bouton par type pour
cacher ce qui l'encombre. Il garde ainsi la carte du jeu ouverte sur la tablette sans mettre le jeu en
pause.

## Décisions d'Ahmed

- Tout ce que la carte du jeu montre, pas une sélection.
- Un bouton par type pour le cacher.
- Icônes : copie de celles du jeu. Comme pour le téléphone, elles sont extraites du jeu installé sur
  ATLAS et servies par le PC, jamais commitées (dépôt public, droits CD Projekt).
- Spike d'abord sur sa vraie partie : lire les mappins du jeu depuis le mod CET. Si c'est impossible,
  retour vers Ahmed avant de construire.

## Ce qu'on sait déjà (lu, pas encore essayé en jeu)

- Scripts décompilés du jeu (`In-Question/MOD_Development_Reference`, `orphans.swift`) :
  `MappinSystem.GetMappins(targetType, out mappins: array<ref<IMappin>>)` avec
  `gamemappinsMappinTargetType.Map` (= 2), et `GetMappinEntries(targetType, out array<MappinEntry>)`
  (`{ id, worldPosition }`). Aucun appelant connu dans les scripts ni dans un mod CET : le passage du
  paramètre `out` en Lua est à prouver (CET rend d'habitude les `out` en valeurs de retour, comme
  `GetItemList` dans `logic.lua`).
- `IMappin` : `GetWorldPosition`, `GetVariant` (`gamedataMappinVariant`, 145 valeurs), `GetPhase`
  (`CompletedPhase`, `DefaultPhase`, `DiscoveredPhase`, `UndiscoveredPhase`), `GetDisplayName`,
  `IsVisible`, `IsActive`, `IsQuestMappin`, `IsPlayerTracked`, `GetNewMappinID`.
- Voyage rapide et métro : `FastTravelSystem:GetFastTravelPoints()` (nom, `IsSubway`, `mappinID`) ; le
  jeu pose les stations NCART en variante `Zzz17_NCARTVariant`, les bornes en `FastTravelVariant`.
- Icônes (vérifié sur ATLAS le 2026-11-14 avec le WolvenKit déjà posé par le téléphone) :
  `base\gameplay\gui\common\icons\mappin_icons.inkatlas` + `mappin_icons_1080p.xbm`, 203 pièces en
  masques blancs (`quest`, `minor_quest`, `gigs`, `fixer`, `fast_travel`, `fast_travel_metro`, `metro`,
  `ripperdoc`, `clothes`, `gun`, `food_vendor`, `bar`, `junk_shop`, `black_market_vendor`, `map_bounty`,
  `hunt_for_psycho`, `apartment`, `car_to_buy`, `hidden_stash`, `dropbox`, `undiscovered`…). Même
  chaîne que le téléphone : `uncook -s`, `export --uext png`, découpe d'après l'`.inkatlas`.

## Objectifs

1. Voir sur la carte de la tablette les points d'intérêt que la carte du jeu montre, au bon endroit,
   avec l'icône du jeu.
2. Cacher ou remontrer chaque type d'un geste ; le choix reste d'une ouverture de la page à l'autre.
3. La carte reste fluide sur la tablette 4.2 et le jeu ne saccade pas.

## Non-objectifs

- Suivre un point d'intérêt au GPS, voyager rapidement, ouvrir une boutique depuis la tablette.
- Ennemis, butin, mappins de combat (ce que la mini-carte montre en jeu mais pas la carte du monde).
- Infobulle riche du jeu (description, récompense, niveau conseillé) : le nom seulement.
- Mini-carte véhicule et cyberware (autres PR en cours).
- Mac, Epic, GOG : comme le plugin (Windows, Steam).

## Anti-objectifs (échec même si ça marche)

- Une image ou une icône du jeu dans le dépôt, l'APK, une release, un test ou une fixture.
- Un point d'intérêt que la carte du jeu cache (quête pas encore débloquée, contrat pas encore
  proposé) montré sur la tablette : on spoile. On copie l'état du jeu, phase « non découvert » comprise.
- Une saccade du jeu à chaque lecture, ou un plantage : appels du moteur sous `pcall`, seulement ceux
  vus marcher au spike, temps mesuré.
- Une carte qui rame sur la tablette quand des centaines d'icônes sont à l'écran.
- Écrire dans une sauvegarde de partie.

## Contraintes

- Tablette 4.2 (`docs/tablette-4.2.md`) : 18,2 ms par image, tas de 128 Mo, vues natives, bitmaps
  décodées à la taille d'affichage.
- Mod : `logic.lua` remplaçable jeu ouvert ; pas de variable globale ; un bloc lent par seconde à tour
  de rôle (`SLOW` dans `logic.lua`).
- Deux autres sessions touchent la page Cyberpunk : la mini-carte véhicule (`MapView.kt`,
  `MapCamera.kt`) et le cyberware (tiroir, peut-être l'extraction d'images). Le dessin des points
  d'intérêt vit dans un fichier à part ; `MapView.kt` ne reçoit qu'un appel. La 2e PR fusionnée se
  rebase sur la 1re.

## Le comment

1. Mod : nouveau bloc lent `mappins.json`, dans la rotation `SLOW` (toutes les 5 s), réécrit seulement
   s'il change (`putBlock`, compteur `blocks.mappins`). Lecture par `GetMappins(Map)` si le spike la
   valide ; repli sur `GetMappinEntries` + `GetMappin(id)` ; voyage rapide et métro par
   `FastTravelSystem` s'ils manquent. Les repères posés par Mate (`mappinIds`) et ceux que le jeu ne
   montre pas (`IsVisible` faux, variantes de combat) sont exclus. Positions arrondies au mètre pour ne
   pas réécrire le bloc à chaque pas d'un PNJ de quête.
2. Backend : `GET /cyberpunk/mappins` (jeton d'appairage, 403 plugin coupé, comme les autres blocs)
   rend la liste avec, pour chaque point, sa **catégorie** et le **nom de son icône**, calculés depuis
   la variante par une table du backend (une ligne par variante ; variante inconnue → catégorie
   « Autres », icône générique). Les icônes rejoignent l'extraction existante : atlas `mappin_icons`,
   seulement les pièces de la table, liste blanche servie comme celles du téléphone.
3. Tablette : les points sont dessinés sous les repères et le joueur, dans `PoiLayer.kt` appelé par
   `MapView`. Icône du jeu teintée de la couleur de sa catégorie ; sans icônes extraites, un rond de la
   couleur de la catégorie (la carte reste utilisable). Phase « non découvert » : l'icône
   `undiscovered` du jeu. Points hors écran non dessinés.
4. Bouton « Filtres » posé sur la carte, à côté de « Centrer » : il ouvre un petit panneau avec une
   ligne par catégorie (icône, nom, nombre de points, interrupteur). Choix gardés dans les préférences
   de l'appli. Tout est visible par défaut.

Catégories (proposées, calquées sur les filtres de la carte du jeu ; ajustées au spike sur ce que sa
partie contient vraiment) :

| Catégorie | Variantes du jeu (exemples) | Couleur |
|-----------|-----------------------------|---------|
| Quêtes | `DefaultQuestVariant`, `QuestGiverVariant`, `QuestionMarkVariant` | jaune |
| Contrats | `FixerVariant`, contrats (gigs) | jaune |
| Crimes NCPD | `Zzz06_NCPDGigVariant`, `HiddenStashVariant`, `OutpostVariant`, `GangWatchVariant`, `SmugglersDenVariant`, `ResourceVariant`, `FailedCrossingVariant`, cyberpsychos | cyan |
| Transports | `FastTravelVariant`, `Zzz17_NCARTVariant` | cyan |
| Boutiques | `ServicePoint*` (armes, vêtements, nourriture, bar, junk, tech, médicaments), `Zzz14_ServicePointBlackMarketVariant`, `OpenVendorVariant` | blanc |
| Charcudocs | `ServicePointRipperdocVariant`, `ServicePointCyberwareVariant` | rouge |
| Autres | appartements, véhicules à acheter, points de dépôt, cache du joueur, tout le reste | gris |

Une variante = une seule catégorie.

Couleurs : attributs `cp_*` existants (`colors.xml`), aucune nouvelle teinte en dur.

Contrat ajouté :
```
state.blocks.mappins : compteur
mappins.json (mod) : { v:1, mappins:[ { id, x, y, z, variant, phase, name, quest } ] }
GET /cyberpunk/mappins → { v:1, mappins:[ { id, x, y, cat, icon, name, undiscovered } ], icons:[nom…] }
```

## Laissé au constructeur

- Taille des icônes à l'écran et règle de densité quand on dézoome (masquer les plus petites
  catégories en dessous d'un zoom, regrouper, ou réduire), tant que l'image tient en 18 ms.
- Emplacement exact du bouton Filtres et forme du panneau, avec `ecran-design` (captures par thème).
- Toucher un point : son nom s'affiche (bulle ou toast) au lieu de poser un repère (mon choix : poser un
  repère pile sur une boutique n'a pas de sens, et le nom évite d'ouvrir le jeu).
- Extraction des icônes : étendre celle du téléphone ou en faire une sœur, selon ce que la PR cyberware
  aura fait du même code ; une seule installation de WolvenKit dans tous les cas.

## Réponses au contrôle du constructeur (mes choix, tirés de la demande)

- Le spike se fait après le go d'Ahmed, par Claude : `ssh pc`, mod rechargé par `reload.txt`, pendant
  qu'Ahmed a une partie ouverte. Rien n'est construit avant ; la table des variantes se fige après.
- Si le jeu ne rend que les points du secteur chargé : c'est accepté, la demande dit « autour du
  joueur ». Voyage rapide et métro viennent alors en plus de `FastTravelSystem` (toute la ville).
  Pas de source statique tirée des fichiers du jeu.
- Points cachés : `IsVisible` faux, jamais envoyé. Phase « non découvert » : l'icône
  `undiscovered` seulement si la carte du jeu montre aussi un « ? » à cet endroit (vérifié au spike) ;
  sinon pas envoyé.
- Quêtes : l'objectif suivi garde son losange jaune actuel ; son mappin (`IsPlayerTracked`) n'est pas
  dessiné une 2e fois. Toutes les autres quêtes que la carte du jeu montre sont dessinées.
- Pas de plafond silencieux. Si le spike mesure trop de points pour 5 s de bloc ou 18 ms d'image, le
  mod n'envoie que ceux d'un rayon autour de V (rayon fixé au spike), transports compris en entier.
- Filtres : gardés par tablette (préférences de l'appli), pas par ordinateur.

## Questions du spike (sur la partie d'Ahmed, mod rechargé par `reload.txt`)

1. `GetMappins(Map)` rend-il la liste en Lua ? Combien de points, en combien de millisecondes ?
2. Les vendeurs et bornes loin du joueur sont-ils dans la liste, ou seulement ceux du secteur chargé ?
3. Que valent `GetVariant`, `GetPhase`, `GetDisplayName`, `IsVisible` sur des points connus (une
   borne, un charcudoc, la quête suivie, un contrat) ? Les points cachés par la carte du jeu sont-ils
   reconnaissables ?
4. Les repères de Mate sont-ils dans la liste (à exclure) ?
5. Les icônes découpées s'affichent-elles nettes à 24-32 dp sur la tablette ?

Si 1 et le repli échouent : retour vers Ahmed avec ce qui a été vu, avant toute construction.

## Critère de réussite

Ahmed ouvre la page Cyberpunk en jeu : autour de V, la tablette montre les mêmes icônes que la carte du
jeu au même endroit (une borne de métro, un charcudoc, sa quête). Il cache « Boutiques » : elles
disparaissent, et restent cachées à la prochaine ouverture. La carte glisse sans à-coup.

## Premier geste

Spike : un `logic.lua` de sonde (copie du mod + une fonction qui écrit `mappins-spike.json` avec le
nombre de points, le temps de lecture et 30 exemples), posé sur ATLAS pendant une partie, rechargé par
`reload.txt`, puis le `logic.lua` d'origine remis.
