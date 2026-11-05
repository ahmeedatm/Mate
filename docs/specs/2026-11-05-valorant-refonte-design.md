# Widget Valorant : refonte visuelle et stats de tracker

Date : 2026-11-05. Demandé par Ahmed. Niveau 3 (backend + tablette, appels Riot nouveaux, nouvel écran de fin).

## Intention

Le widget Valorant actuel (`app/src/main/kotlin/app/mate/ui/home/ValorantWidget.kt`, `backend/src/valorant.ts`) donne
les infos en texte : « Or 2 », « Jett », une pastille de couleur. Ahmed le trouve fade. Il veut lire la partie d'un coup
d'œil, comme sur un tracker (tracker.gg, VRY) : des images à la place des mots, et ce que chaque joueur vaut.

Réussite : posé à côté du PC pendant une partie, Ahmed voit en un regard qui est fort dans chaque équipe, qui joue en
groupe et qui smurfe ; à la fin, il voit le tableau des scores des 10 joueurs sans ouvrir le jeu ni tracker.gg.

## Limite technique (connue, acceptée)

Riot ne donne **aucune stat par joueur pendant la partie** (ni kills, ni score) : le client ne fournit que le score des
équipes (déjà affiché). Les stats « tracker » viennent donc de l'historique de chaque joueur ; le tableau des scores de la
partie n'existe qu'après la fin du match (`match-details`).

## Objectifs

### 1. Images à la place des mots (partout)
- **Rang** : logo officiel du rang (insigne) à la place du nom et de la pastille de couleur. Le nom reste dans
  `contentDescription` (accessibilité) ; Non classé a son logo propre.
- **Agent** : icône carrée du visage (`displayIcon` de valorant-api.com) à la place du nom. Pendant la sélection, agent
  survolé mais pas verrouillé : icône estompée ; pas encore choisi : case vide.
- Source : valorant-api.com (`/v1/agents`, `/v1/competitivetiers`, dernier épisode). Le **compagnon** télécharge chaque
  image une fois, la garde sur le disque de l'ordinateur et la sert à la tablette (route du domaine Valorant, jeton
  d'appairage), comme les photos Discord (`IconCache` côté tablette). La tablette ne parle jamais à Internet pour ça.
- La table `AGENTS` en dur peut être remplacée par la liste de valorant-api.com (gardée en cache), pour que les nouveaux
  agents aient nom et icône sans nouvelle version.

### 2. En partie (sélection d'agents et partie en cours)
Par joueur, deux équipes (pendant la sélection, ton équipe seulement, comme aujourd'hui) :
- icône d'agent, nom (caché si incognito), logo du rang actuel + RR ;
- **niveau du compte**, petit chiffre sous le nom (`PlayerIdentity.AccountLevel` de core-game, déjà reçu ; caché si
  `HideAccountLevel`) : repère les smurfs avec le meilleur rang et le K/D ;
- **meilleur rang atteint** (logo, plus petit) ;
- **K/D** et **% de tirs à la tête** sur ses **3 dernières parties classées** ;
- **% de victoires de l'acte en cours** ;
- **groupes** : un trait de couleur relie les joueurs venus ensemble (dans les deux équipes).

Sources Riot (pd, jeton du client, déjà en place) :
- `mmr/v1/players/{puuid}` (déjà appelé par `rankOf`) : rang, RR, et `QueueSkills.competitive.SeasonalInfoBySeasonID`
  → victoires/parties de l'acte, meilleur rang (plus haut tier de `WinsByTier` sur toutes les saisons).
- `match-history/v1/history/{puuid}?queue=competitive&endIndex=3` puis `match-details/v1/matches/{id}` → K/D, % tête.
- Groupes : `partyId` des présences du chat local (`/chat/v4/presences`, comme VRY). **À vérifier sur une vraie partie**
  que les présences des 10 joueurs y sont ; sinon, groupes de ton équipe seulement, et le dire.

Ces stats se chargent dans **tous les modes à équipes** (elles viennent de l'historique classé, pas de la partie) ;
combat à mort : rang et agent seulement, pas de stats.

Chargement : une seule fois par partie et par joueur, en cache (comme `ranks`), ~40 appels au plus, **étalés et par
priorité** : le rang de base d'abord (comme aujourd'hui), puis meilleur rang et % victoires (même appel `mmr`), puis K/D
et % tête ; une file au débit limité, pour qu'un refus de Riot ne fasse jamais disparaître le rang. Les cases se
remplissent une à une : logos et noms tout de suite, tiret gris pour une stat pas encore arrivée. Riot refuse (429, 5xx) :
le tiret reste, nouvel essai 30 s plus tard (même principe que `RANK_FAIL_TTL`).

### 3. Fin de partie : tableau des scores (2 minutes)
Quand la partie passe de « en partie » au menu, le compagnon lit `match-details` du match qu'il vient de suivre et le
widget montre pendant **2 minutes** le tableau des 10 joueurs, puis revient au menu :
- équipe gagnante et score des manches ;
- par joueur : agent, nom, rang, **ACS**, **K / D / A**, **% tête**, **dégâts par manche (ADR)**, **premiers kills** ;
- **MVP** de chaque équipe (meilleur ACS), marqué d'une étoile ;
- tri par ACS dans chaque équipe.
Modes : tous les modes à équipes (compét, non classé, vélocité, spike rush, Premier, nouvelle carte, personnalisée).
Combat à mort (chacun pour soi) : simple classement par kills.
`match-details` peut ne pas être prêt à la seconde où la partie finit : réessayer quelques fois (toutes les 5 s, ~1 min) ;
toujours absent : retour au menu, avec une ligne d'état « Tableau indisponible ».
Une nouvelle file ou sélection d'agents pendant les 2 min **coupe** le tableau (la nouvelle partie gagne). Client Riot
fermé ou compagnon redémarré : le tableau est perdu, pas gardé sur disque.

### 4. Au menu (et en file)
- **Gros logo de ton rang**, RR et niveau dessous, Riot ID.
- **Barre de RR** 0 à 100 (Immortel et Radiant : pas de barre, le RR seul).
- **Tes 5 dernières parties classées (compétition seulement)**, une ligne chacune : victoire/défaite (bande verte ou rouge) et score, icône d'agent et
  carte, K/D/A et ACS, **RR gagnés ou perdus**
  (`mmr/v1/players/{puuid}/competitiveupdates` donne les 5 matchs et leurs RR, puis `match-details` de chacun).

### 5. Tailles du widget
- **XL et widget agrandi** : tout ce qui est au-dessus.
- **L** : icône d'agent, nom, logo du rang, K/D. Fin de partie : agent, nom, ACS, K/D/A. Menu : logo, RR, barre,
  3 dernières parties.
- **T** (bandeau) : ton rang (logo) et le score de la partie.

## Non-objectifs
- Kills ou score par joueur en direct (Riot ne les donne pas).
- Mac (le client Riot n'y existe pas ; le widget reste offert sur Windows seulement).
- Mode « Steam Deck », historique au-delà de 5 parties, page de profil d'un joueur.
- Toucher une ligne pour ouvrir le profil tracker.gg.

## Anti-objectifs (échec même si ça marche)
- Se faire bloquer par Riot (429 en boucle) au point que le rang de base disparaisse.
- Un widget illisible à 1 m (texte sous 12 sp, colonnes écrasées en L).
- Saccades sur la tablette (MT8167, 18,2 ms par image) : décoder les images à la bonne taille, une fois.
- Le jeton Riot qui sort de l'ordinateur (règle actuelle, inchangée).

## Contraintes
- Tablette 4.2 (`docs/tablette-4.2.md`) : tas de 128 Mo, images petites (≤ 128 px), décodées une fois et gardées.
- Conventions Mate : vues XML / code, couleurs `?attr/c_…`, textes dans `strings.xml`, erreurs par `errorText`,
  HTTP par `HttpURLConnection`, un client par domaine (`ValorantApi`).
- Backend : nouveau comportement = test dans `backend/test/` (faux serveurs Riot déjà en place,
  `VALORANT_GLZ_URL`/`VALORANT_PD_URL`, boucle locale seulement) ; un contrôle dans `backend/qa/sanity.ts`.
- Logique pure Android (choix des colonnes par taille, barre de RR, MVP) : tests JVM dans `app/src/test/`.
- Nouvel appel sortant de l'ordinateur vers valorant-api.com : à écrire dans `docs/confidentialite.md`,
  `backend/config/confidentialite.txt` et `app/src/main/assets/confidentialite.txt`.
- Doc : `docs/architecture.md` (détail Valorant) et `docs/etat-du-projet.md`.

## Tranché par Ahmed
Stats en partie : meilleur rang, K/D, % tête, % victoires de l'acte, groupes, niveau du compte. Historique : 3 dernières classées.
Tableau de fin : ACS, K/D/A, MVP, % tête, ADR, premiers kills ; affiché 2 min. Menu : gros logo, barre de RR,
5 dernières parties classées seulement (V/D et score, agent et carte, K/D/A et ACS, RR ±). Icône carrée de l'agent. Moins de stats en
L et T. Tous les modes à équipes. Cases remplies une à une. **3 variantes visuelles vraiment différentes, en capture sur
l'émulateur avec de fausses données (partie en cours, tableau de fin, menu), à choisir avant de finir.**

## Mes choix (Claude)
- Joueur incognito : stats visibles, nom caché (comme aujourd'hui pour le rang).
- Images servies par le compagnon, pas par Internet depuis la tablette.
- Durée des 2 min comptée par le compagnon (un seul état pour tous les widgets et le zoom).
- JSON de `/valorant` changé de façon **additive** : une tablette pas encore mise à jour garde le widget d'aujourd'hui.
- Ordre de construction : d'abord le backend (données, tests sur faux serveurs Riot), puis les **3 variantes visuelles**
  sur l'émulateur, nourries par un faux compagnon qui sert des parties figées (partie en cours, tableau de fin, menu) ;
  Ahmed choisit, puis seule la variante choisie est finie. Le faux compagnon reste un outil de dev, pas un mode de l'appli.

## Laissé au constructeur
Forme exacte du JSON de `/valorant` (champs ajoutés, rétrocompatible avec l'APK actuelle si possible), noms des routes
d'image, taille des caches, découpage de `valorant.ts` (fichier déjà à 381 lignes : sortir assets et stats dans leurs
propres modules), identification de l'acte en cours.

## Questions ouvertes
- Présences des 10 joueurs disponibles pour les groupes ? (vérifier sur une vraie partie)
- Limite réelle de Riot sur pd pour ~40 appels d'un coup.
