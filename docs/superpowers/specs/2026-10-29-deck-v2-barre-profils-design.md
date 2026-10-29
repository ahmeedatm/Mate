# Deck V2 : barre fixe et profils Accueil, Deck, Réunion

Conception du 29/10/2026, ticket COMP-40 (porté par Momo). Fait suite au lot 1 d'Ahmed (Accueil devenu Profils, 28/10) et aux widgets venus du Deck (29/10). Prototype cliquable validé : barre « A » (rail étiqueté de 96 dp) et profils Accueil / Deck / Réunion.

## Pourquoi

Aujourd'hui, la navigation est cachée : une poignée de 4 px au bord gauche, un panneau qui se referme seul après 6 s, deux touchers et une attente pour passer de l'Accueil au Deck (premier toucher souvent raté sur la 4.2). Le Deck empile 8 onglets de natures différentes (lancer, piloter, surveiller), avec des cases « Libre » vides, des modes d'emploi en texte et un bouton ⟳. Les profils d'Ahmed apportent un bon moteur (pages de widgets, un seul geste de personnalisation), mais on y circule par une barre de flèches en haut, et le plan prévoyait de supprimer le Deck sans le remplacer par une destination claire.

La cible est le **B2B : salariés de bureau** (Mac, Windows). Pour eux, le moment où l'écran de l'ordinateur est bloqué, et où la tablette vaut le plus, c'est la **réunion** (écran partagé ou projeté). `docs/cible-et-positionnement.md` (gamers, streamers) est périmé sur ce point.

## Objectifs

1. **Navigation toujours visible** : une barre fixe à gauche, jamais cachée. Accueil et Deck à un toucher, depuis n'importe quel écran.
2. **Un seul moteur** : Accueil, Deck et Réunion sont des profils (pages de widgets d'Ahmed). Une seule façon de personnaliser partout (appui long → Modifier).
3. **Une config de base propre, pensée bureau**, qui marche sans rien régler : rien de vide, rien à expliquer en texte.
4. **Réunion qui vient d'elle-même** pendant un appel et repart au raccrochage.
5. **Animations soignées mais sobres**, dans le budget de la 4.2.

## Non-objectifs

- Linux (le backend ne le pilote pas : 501). Chantier à part.
- Refonte du backend. Seuls trois ajouts ciblés sont prévus (voir plus bas) : l'ordre `meeting` poussé sur le canal `/tablet`, deux commandes `system` (micro, Ne pas déranger), un champ `icon` sur les tuiles.
- Compose, nouvelle dépendance.
- Refaire l'intérieur des widgets d'Ahmed. On les compose, on peut leur ajouter une taille. Un seul nouveau type, le widget d'appel générique (voir Réunion).

## Anti-objectifs (échec même si « ça marche »)

- Une navigation qu'il faut chercher ou faire revenir.
- Des écrans qui se réordonnent tout seuls (casse la mémoire du geste).
- Des animations en boucle, qui sautent, ou qui font tomber sous 55 i/s sur la 4.2.
- Des cases vides, des emoji, des pastilles de couleur, des modes d'emploi permanents en texte.
- Perdre une fonction du Deck actuel en le retirant.

## Contraintes

- Matériel : `docs/tablette-4.2.md` (1280 × 800 à 160 dpi, donc 1 dp = 1 px ; 55 Hz, 18,2 ms par image ; PowerVR GE8300 ; 128 Mo de tas). Tout se vérifie sur la vraie tablette, en kiosque.
- DA Cadran (`docs/ui-ux/da/README.md`) : rouge = maintenant seulement, couleurs par attributs `?attr/c_…`, Outfit par rôles `c_font_*`, ressorts de `ui/Look.kt`, translation / échelle / alpha seulement, pas de boucle, `Look` respecte l'échelle d'animation à 0.
- **Animations et kiosque (vérifié le 29/10 dans le code)** : la procédure du kiosque (`docs/kiosque.md`) met `animator_duration_scale` à 0. Les `ViewPropertyAnimator` / `ValueAnimator` y deviennent instantanés ; les `SpringAnimation` ne suivent pas cette échelle, mais `Look` les coupe quand elle vaut 0 (`Look.reduced`) sauf avec `always = true` (déjà le cas de `slideIn` et de la pastille de `TabStrip`). Décision : **toutes les animations de navigation de ce design (pastille, transitions d'écran, fondus compris, arrivée de Réunion, repos) passent par des ressorts `always = true`**, aucun `ViewPropertyAnimator`. Elles restent peu nombreuses (quelques vues à la fois), ce qui justifie de passer outre le réglage du kiosque, fait pour couper les animations système coûteuses.
- Conventions du projet : vues XML, textes dans `strings.xml`, logique pure testée dans `app/src/test/`, ktlint, MR par lot, `docs/etat-du-projet.md` et CLAUDE.md tenus à jour.
- Les trois thèmes (Cadran, Cadran nuit, Rétro) passent par la même barre.

## Ce qui est décidé

### La barre (option A du prototype)

Rail fixe de 96 dp, à gauche, sur tous les écrans, dans les trois thèmes. Elle remplace `MenuPanel` (menu caché, poignée, `DrawerGesture`) **et** la barre de profils en haut de l'Accueil (flèches, nom, pastilles).

De haut en bas :

| Zone | Contenu |
|---|---|
| Tête | **Parler** (bouton plein, 56 dp, libellé dessous). Appui long = commande écrite, comme aujourd'hui. |
| Profils | Les profils, dans leur ordre : **Accueil**, **Deck**, puis ceux que l'utilisateur crée. Icône + libellé (nom du profil). Le profil **Réunion** n'apparaît ici que pendant un appel, avec un point rouge. |
| Séparateur | Filet court (`c_stroke`). |
| Outils | **Focus** (le libellé devient le temps restant, en rouge, tant qu'un Pomodoro est lancé ou entamé), **Ordi**, **Caméra**. |
| Pied | Notifications (la cloche arrivée le 29/10 y passe), ordinateur actif (icône + point vert / orange / rouge ; toucher = changer d'ordinateur), **Réglages**. |

- Élément choisi : pastille de fond `c_surface_raised` (56 × 32) derrière l'icône, icône pleine, libellé en 500. La pastille **glisse** d'un élément à l'autre sur un ressort (spatial default), elle ne saute pas.
- Pastilles d'information gardées : nombre de tâches non faites sur Accueil (comme `Rail.setPendingTasks`).
- **Repos** (même déclencheur que le repos de l'Accueil) : la barre passe à 40 % d'opacité, les libellés s'effacent, rien ne disparaît ; un toucher la ravive.
- Clavier ouvert : la barre reste (elle ne gêne pas la saisie) sauf si `KeyboardControl` a besoin de la place ; à trancher par le builder.
- Changer de profil : toucher son entrée dans la barre. Le balayage entre profils reste, en geste secondaire : vertical depuis le 29/10 (vers le haut : profil suivant, comme l'animation de changement de profil). La liste des profils (créer, renommer, réordonner, supprimer, `ProfilesDialog`) s'ouvre par **appui long sur une entrée de profil** de la barre ; elle reste aussi dans Réglages.

### Rôles des profils (mon choix)

Trois rôles marqués dans `HomeLayout`, sur le modèle de `gameProfile` : `homeProfile`, `deckProfile`, `callProfile`. Ils sont stockés par index et suivent les déplacements comme `gameProfile`.

| Rôle | Renommer | Déplacer | Supprimer | Sert de cible à |
|---|---|---|---|---|
| Accueil | oui | oui | non | mode `home`, ancien mode `tasks`, démarrage |
| Deck | oui | oui | oui ; ses cibles retombent alors sur l'Accueil | mode `deck` du canal `/tablet` et de l'extension Claude, voix « deck », `openAgentsTab` et `openAppPage` (le profil Deck s'affiche, puis le widget voulu y est mis en avant s'il y est) |
| Réunion | oui | non (hors barre) | non (on peut le vider ou revenir au modèle) | arrivée automatique pendant un appel |

Hors appel, le profil Réunion n'est **ni dans la barre ni dans le balayage**. On le modifie depuis la liste des profils : son entrée « Modifier » l'affiche hors appel, avec un bandeau « Aperçu : ce profil s'affiche pendant un appel ».

### Les profils de base (nouvelle installation, cible bureau)

Trois profils sont créés au premier lancement. Les noms et le contenu sont **mes choix (Claude)**, à ajuster sur la tablette :

| Profil | Rôle | Widgets (intention) |
|---|---|---|
| **Accueil** | Lire d'un coup d'œil | Le modèle Bureau Cadran actuel : héros (heure, date, météo, eau), ligne du jour, prochain rendez-vous (avec Rejoindre), tâches, Focus, touches. |
| **Deck** | Agir sur l'ordinateur | Une rangée d'**applis** en haut (Outlook, Teams, navigateur, Word, Excel, PowerPoint, Slack, Notes… filtrées aux applis installées), **touches** système (Verrouiller, Capture, Ne pas déranger, Presse-papiers, Fenêtres, Couper le micro, Couper la caméra, Veille écran), **son et lecture** (titre en cours, volume, micro). |
| **Réunion** | Piloter l'appel en cours | Le **widget d'appel générique** `CALL` en grand ; **ensuite** (prochain rendez-vous) ; **son de l'appel**. |

- Le builder peut ajouter une taille à un widget (par exemple `W` à `APPS`) pour obtenir ces compositions sur la grille 4 × 4.
- **Sans ordinateur appairé**, les widgets du Deck montrent l'état « Connecter » qui existe déjà (`ConnectWidget`). Les touches arrivent à l'appairage : le deck de départ de l'ordinateur (`deck.example*.json`, filtré aux applis installées) porte les touches Bureau, et le widget Touches du profil Deck lit cette page. Rien n'est prérempli côté tablette.
- **« Sans rien perdre » = présent au catalogue (« + »)** (mon choix). Le profil Deck de base reste sobre : applis, touches, son et lecture. Ouvertes, Pavé, Console et Agents ne tiennent pas avec eux sur une page 4 × 4 ; ils restent à un « + » près, dans le catalogue des widgets.
- **Tablette déjà configurée** : on ne touche à rien de ce que l'utilisateur a fait. On reconnaît son premier profil comme l'Accueil (index 0, marqué `homeProfile`). On ajoute une seule fois un profil Deck **juste après l'Accueil** (même ordre que la barre) et le profil Réunion (hors barre). Pas de recréation automatique des 8 onglets.

### Les touches : actions et icônes (mon choix)

- **Icônes Material à la place des emoji** : les tuiles du deck reçoivent un champ facultatif `icon`, un nom pris dans un petit jeu de symboles fourni avec la tablette (une vingtaine de vecteurs `ic_*` : verrou, capture, Ne pas déranger, micro, caméra, presse-papiers, fenêtres, lune, veille…). La tablette affiche `icon` s'il est connu, sinon `glyph` (emoji) en secours pour les touches créées par l'utilisateur. Le deck de départ Bureau (Mac et Windows) passe à `icon`.
- **Deux commandes `system` ajoutées au backend** : `mute-mic` (bascule la sourdine du micro par défaut, qui existe déjà pour le son) et `dnd` (bascule Ne pas déranger, qui existe déjà par `/system/dnd`). Presse-papiers et Fenêtres ouvrent sur la tablette ce qui existe déjà (historique du presse-papiers, widget Ouvertes en grand) : ce sont des touches « tablette », pas des actions de l'ordinateur. La caméra se coupe dans l'appli de réunion, donc par la page de l'appli : la touche « Couper la caméra » n'est pas dans le profil Deck, seulement dans Réunion.

### Le profil Réunion vient de lui-même

**Détection (mon choix) : c'est l'ordinateur qui prévient la tablette.**
- Le backend pousse un ordre `meeting` (`{ state: "start" | "end", app }`) sur le canal `/tablet`, comme l'ordre `call` des appels entrants. Il suit l'état de lui-même, avec la logique de `GET /deck/live` (micro utilisé par une appli de réunion), toutes les 3 s environ, **seulement tant qu'une tablette est connectée**. Il n'envoie qu'un changement d'état.
- Plus aucune lecture périodique de `/deck/live` par la tablette hors du Deck : arrivée immédiate, pas de charge réseau en plus.
- Nouvel ordre = type dans `tablet.ts` + schéma dans `routes/tablet.ts` + branche dans `RemoteChannel.execute`. Un contrôle dans la sanity, et un outil MCP seulement si utile.

**Ce qui compte comme un appel** : toute appli dont le backend sait dire que le micro est utilisé (Teams, Discord aujourd'hui), et toute réunion lancée par « Rejoindre » sur la tablette (Teams, Meet, Zoom, Webex).
- Une réunion lancée par « Rejoindre » sans détection de fin (Meet, Zoom, Webex sans page) se termine à l'**heure de fin de l'événement**, au toucher de « Quitter », ou quand l'utilisateur va ailleurs et n'y revient pas avant la fin.
- Un salon vocal Discord compte aussi : sans risque en B2B, et utile.

**Widget d'appel générique `CALL`** (le seul nouveau type) : il affiche les commandes de l'appli de l'appel en cours.
- Il reprend `MEETING` pour Teams et `DISCORD` pour Discord, en réutilisant la logique de `CallWidget`.
- Pour une appli sans page (Meet, Zoom, Webex), il propose ce que l'ordinateur sait faire partout : sourdine du micro (`mute-mic`), son de l'ordinateur et « Ouvrir l'appli ». Il ne propose pas « Quitter » sans action fiable.
- Hors appel (aperçu), il montre l'état vide prévu.

**Comportement :**
- Un appel commence : l'entrée Réunion **apparaît** dans la barre, puis l'écran y **passe seul**. Exceptions : l'utilisateur est en train d'écrire, ou il est dans un écran plein (Caméra, mode Modifier).
- L'appel se termine : on revient au profil d'avant (si l'utilisateur n'a pas changé d'écran entre-temps), puis l'entrée se replie.
- Remplace `MainActivity.showMeetingControls` (qui ouvrait la page Réunion du Deck).

### Le mode Deck actuel disparaît, sans rien perdre

Le lot 2 d'Ahmed (« suppression du Deck ») se fait ici, **après un inventaire de parité** : chaque onglet et chaque page du Deck actuel doit avoir son équivalent en widget avant de retirer `DeckFragment` et `Mode.DECK` :

| Deck actuel | Équivalent attendu |
|---|---|
| Apps | `APPS` |
| Ouvertes | `OPEN` |
| Pavé | `PAD` |
| Son (console) | `MIXER` |
| Agents | `AGENTS` |
| Média | `MEDIA` |
| Raccourcis, Perso | `KEYS` |
| Pages d'appli (Discord, Réunion Teams, Word / Excel / PowerPoint / Outlook d'Ahmed du 29/10, autres pages de `deck.json`) | à vérifier : widget dédié, ou `KEYS` réglé sur la page |

Tout ce qui menait au Deck mène au profil Deck (ou au widget voulu) : mode `deck` du canal `/tablet` et de l'extension Claude, commandes vocales (« deck », pages d'appli), `openAgentsTab`, `openAppPage`. Un manque dans l'inventaire bloque le retrait, pas le reste.

### Animations

Une seule grammaire, les ressorts de la DA :

| Moment | Ce qui bouge |
|---|---|
| Changer d'écran | Pastille de la barre qui glisse (spatial default). L'ancien écran s'efface vite (~150 ms), le nouveau arrive de 24 dp dans le **sens du déplacement dans la barre** (vers le bas de la barre → il monte) avec un fondu (~300 ms). |
| Appui sur une touche ou une appli | Échelle 0,94 (spatial fast), puis une onde neutre (`c_graphic`) quand l'action est partie (déjà décrite dans la DA). |
| Réunion qui arrive | L'entrée s'ouvre (hauteur via un conteneur qui coupe + échelle 0,6 → 1), puis bascule d'écran. Pas de hauteur animée sur une vue qui relayoute la barre à chaque image : le builder choisit la technique la moins chère. |
| Repos | Opacité de la barre 1 → 0,4, libellés en fondu. |
| Mode Modifier | Les widgets se resserrent à 0,94, poignées en fondu. Pas de tremblement (boucle). |

Mesure obligatoire avec `dumpsys gfxinfo` sur la tablette pour le changement d'écran et l'arrivée de Réunion.

## Ce qui reste au builder

- Taille exacte des éléments, icônes Material Symbols choisies, espacements (grille de 8 de la DA).
- Que faire au-delà de 4 ou 5 profils dans la barre (hauteur de 800 dp) : défilement, ou entrée « Plus » qui ouvre la liste.
- Technique d'animation de l'apparition de Réunion (ressorts `always = true`, voir Contraintes).
- Découpage en lots et en MR. Ordre suggéré : barre fixe (remplace menu et barre de profils) → rôles des profils, profils de base et migration → touches (champ `icon`, commandes `mute-mic` et `dnd`, deck de départ Bureau) → ordre `meeting` et widget `CALL`, Réunion contextuelle → inventaire de parité puis retrait du Deck → docs. Ancienne suggestion, pour mémoire : barre fixe (remplace menu et barre de profils) → profils de base et migration → Réunion contextuelle → inventaire de parité puis retrait du Deck → docs.
- Tests : logique pure de la barre (quelles entrées, dans quel ordre, Réunion visible ou non, élément choisi), migration des profils, retour au profil d'avant après un appel ; test instrumenté de la navigation (`app/src/androidTest/`) si un parcours change.
- Mises à jour de docs : CLAUDE.md (navigation, profils, plus de mode Deck), `docs/etat-du-projet.md`, principe 5 de la DA (« le menu est caché » devient « la barre est toujours là, elle s'efface au repos »), `docs/architecture.md`.
