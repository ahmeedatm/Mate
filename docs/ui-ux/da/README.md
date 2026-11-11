# DA de Mate

## Instrument tracé (thèmes au choix, 11/11/2026)

Choisie par Ahmed sur la maquette de fusion (`fusion/index.html`, issue #216, PR #229). Un appareil d'aluminium avec des
fenêtres LCD et des touches qui s'enfoncent, dont chaque écran se dessine à l'arrivée, sur une hiérarchie d'affiche : l'heure
et le prochain rendez-vous en héros, le reste petit et dense. Deux thèmes : Instrument (jour) et Instrument nuit (noir pur à filets gris, 11/11 : « un écran qui se dégage du fond »),
tous deux en tête des Réglages ; Chaleur reste le thème par défaut (sans choix, rien ne change par rapport à master) ; Instrument se choisit dans les Réglages, et son Accueil Travail (`Template.WORK_INSTRUMENT`) ne s'applique qu'à lui. Aucun logo ni marque repris.

D'où vient chaque idée :

| Source | Ce qu'on lui prend |
|---|---|
| Instrument (la base) | aluminium, fenêtres LCD, Departure Mono, un seul orange, touches à deux couches avec DEL, nuit graphite |
| Carnet | le geste du dessin à chaque arrivée d'écran : les cadres se tracent au trait fin, sans papier ni crayon tremblé |
| Affiche | héros énorme (heure + prochain rendez-vous), titres Barlow SC 600, aplat orange qui balaie entre les écrans, touches numérotées 01 à 05 |

Jetons (`Palette.Instrument`, `Palette.InstrumentNuit`, `res/values/palettes.xml` ; contrastes en test, `ThemePalettesTest`) :

| Jeton | Jour | Nuit |
|---|---|---|
| `c_bg`, `c_rail` (alu ; nuit : noir pur, bandeau, dock, Réglages) | `#E6E3DC` | `#000000` |
| `c_surface` (carte) | `#EFEDE7` | `#050505` (à peine au-dessus du fond : les styles Voile / Sans filet / À même le fond s'appliquent) ; touche et bouton `c_surface_raised` `#0D0D0D`, appui `#1C1C1C` |
| `c_stroke` (filet de 1 dp, rayon 12, ni ombre ni dégradé) | `#B7B2A6` | `#3A3A3A` (1,8:1 sur noir : filet décoratif) |
| `c_text` | `#1E1D1B` | `#ECE8DF` |
| `c_text_dim`, `c_text_faint` | `#5A564E`, `#625E55` (5,0:1 au plus bas, sur la tuile : 4,5:1 tenus) | `#B4AFA3`, `#AEA99D` |
| `c_accent` (texte, aplats) | `#B3380D` (4,7:1 sur l'alu) | `#FF8559` (4,5:1) |
| `c_signal` (LCD, DEL, aplat) | `#FF5A1F` | `#FF5A1F` |
| fenêtre LCD (`Lcd.Instrument`, `Lcd.InstrumentNuit`) | `#141414`, texte `#F1EDE4` | fond `#000000` (surface `#050505`), filet `#3A3A3A`, rayon 12, texte `#F1EDE4` |

Orange vif `#FF5A1F` réservé aux fenêtres LCD, à l'aplat de balayage et aux DEL (il ne fait que 2,43:1 sur l'alu du jour : pas de
texte dessus ; une DEL active ne tient pas par lui seul) ; le texte et les boutons de l'alu prennent le `c_accent` foncé. La DEL
active se cerne de `c_signal_edge` (`#B3380D`, 4,7:1 sur l'alu). La DEL éteinte (1,65:1) n'a pas à se lire : c'est l'état actif qui doit
tenir, y compris quand le capuchon est enfoncé (aussi marqué par son abaissement de 4 dp). Nouveaux attributs : `c_signal`, `c_signal_edge`, `c_on_signal`,
`c_dock_height` (116 dp pour Instrument, 0 ailleurs : c'est ce qui allume la rangée de touches) et `c_lcd_overlay` (le style LCD), `c_home_card_outline` (la carte des widgets de l'Accueil garde son filet : Applis et Touches posés en panneau, titre en capitales en Colonne) et `c_block_radius` (12 dp au lieu de 40 : le bloc de Depuis l'ordi n'a plus de grands coins).

Instrument nuit, tout en filet : chaque module de l'Accueil (LCD ou non, y compris Héros, Journée et Ligne du jour posés à nu : `c_bare_frame`)
prend le même cadre ; « À même le fond » le retire. Boutons ronds et pastilles : noir, filet `c_btn_edge` (= `c_stroke` en nuit, transparent
ailleurs), icône au trait ; Eau + : noir, filet gris, icône orange (Lcd.InstrumentNuit : `c_primary`). Touches du bas : plus de socle (`c_dock_socle`,
`c_dock_socle_on` transparents), capuchon noir à filet 1 dp, 18 dp d'air sous la DEL et 10 dp avant le bord (`c_dock_cap_bottom` = 10 dp ; `c_dock_cap_top`, `c_dock_cap_bottom`) ;
Réunion en appel garde son contour orange de 2 dp. Rangées de Touches et d'Applis : 6 dp entre deux lignes (`c_list_gap`, 4 dp ailleurs) ; dans un panneau à filet, lignes de 56 dp et 8 dp d'écart (`TileShapes.LIST_ROW_PANEL_DP`, `LIST_GAP_PANEL_DP`). Touche active : DEL allumée, capuchon enfoncé de 4 dp et contour `c_signal` de 2 dp.

Polices : Departure Mono (`c_font_display` : heure, chiffres, minuteur, numéros des touches ; OFL 1.1, convertie en TrueType
depuis la release rektdeckard/departure-mono v1.500) ; Barlow Semi Condensed 600 pour les titres et libellés en capitales
(`c_font_title` = `c_font_label`, `c_label_caps`), 500 pour le moyen ; Barlow 400 pour le texte.

Navigation : une rangée de touches numérotées en bas (`ui/KeyDock`, `KeyDockModel`), à la place de la barre de gauche (les
autres thèmes la gardent). Parler en touche ronde orange à gauche, Verrou en touche ronde à droite ; 01 Accueil, 02 Focus
(le minuteur en Departure Mono quand il tourne), 03 Ordi, 04 Caméra, 05 Réglages, renumérotées quand un outil est masqué.
Une DEL au-dessus de la touche active ; celle de 01 a un segment par profil (orange sur l'affiché, contour pour Réunion en
appel), toucher 01 déjà active passe au profil suivant, appui long = liste des profils. L'ordinateur actif est une pastille
LCD à droite du bandeau du haut. Cibles de 48 dp au moins.

Signature de mouvement (une exception assumée au budget de `ecran-design/references/android.md` ; durées à mesurer sur la
tablette, 55 Hz et 2 Go) :

| Geste | Durée |
|---|---|
| Touche qui s'enfonce (capuchon de 6 dp) | 90 ms, retour par ressort |
| DEL qui s'allume (flash) | 140 ms |
| Aplat orange qui balaie entre deux écrans (`ui/SweepView`, `SweepModel`) | 300 ms pour couvrir + 360 ms pour révéler, courbe (.65, 0, .35, 1) ; numéro et nom de l'écran sur l'aplat |
| Cadre qui se trace (`ui/FrameTracer`, `FrameTracerModel`) | 440 ms par cadre, 60 ms entre deux cadres, 8 cadres tracés au plus (les suivants en fondu) |
| Contenu du module | fondu 200 ms, 300 ms après le départ du cadre |
| Fenêtre LCD qui s'allume | 4 paliers sur 300 ms, 700 ms après le départ du cadre |

Le tracé démarre 140 ms après le début de la révélation du balayage. Réduire les animations, la famille Accueil coupée ou
l'échelle d'animation à zéro : rien ne s'anime (le tracé n'est pas lancé, tout est visible). Écrans tracés : l'Accueil (tous
ses modules, plus la tuile ajoutée), Focus (Ambiance et humeurs), Ordi et Caméra (les cartes du choix), Réglages (les trois
premières cartes de l'onglet). Pas encore faits : chiffres qui roulent à l'allumage des fenêtres LCD (`ClockWidget` et
`NextWidget` n'utilisent pas `RollingText`), vis, mascotte et module Ordi de l'Accueil.

Aperçu dans Réglages > Thème (`ThemePreview`) : une rangée de touches en bas à la place de la barre de gauche.

## Chaleur (thème par défaut, 05/11/2026)

Choisie par Ahmed parmi trois directions (Sobre, Chaleur, Nuit), sur maquettes HTML. Esprit de claude.ai et anthropic.com,
couleurs relevées sur les sites le 05/11 : calme, chaleureux, comme un carnet posé sur le bureau. Aucun logo ni marque repris.

- Fond crème `#FAF9F5`, barre `#F0EEE6`, tuiles blanches à filet crème `#E8E6DC` (1 dp), rayon 16, sans ombre (`chaleur_tile_bg`).
- Accent terre cuite `#A34A2B` : le `#D97757` du site ne fait que 3:1 sur le crème, la version foncée tient 4,5:1
  sur le fond, les tuiles et la tuile appuyée (`ThemePalettesTest`). Parler en terre cuite, encre `#141413`, textes secondaires `#3D3D3A` et `#5E5D59`.
- Titres et grands chiffres en serif de livre Source Serif 4 (500 et 300), textes en Hanken Grotesk (400 et 500), OFL.
- Palette `Palette.Chaleur` (`res/values/palettes.xml`), thème `AppTheme.CHALEUR`, groupe Chaleur en tête des Réglages.
  Les autres thèmes restent au choix ; un thème enregistré est gardé.
- À venir (maquette retenue) : version nuit, composition de l'Accueil (prochain rendez-vous entouré d'un trait de crayon,
  petits dessins au trait), heure et état de l'ordi dits une seule fois.

| Écran | Chaleur |
|---|---|
| Accueil | [accueil-chaleur.png](accueil-chaleur.png) |
| Focus | [focus-chaleur.png](focus-chaleur.png) |
| Réglages | [reglages-chaleur.png](reglages-chaleur.png) |

# DA « Console douce »

Mate est un accessoire gamer et domotique casual posé sur le bureau : lisible de loin, calme, chaleureux. Nuit par défaut, une seule couleur vivante.

## Références

| Référence | Ce qu'on lui prend |
|---|---|
| Accueil de la Nintendo Switch | tuiles arrondies et calmes, un seul accent, rien de décoratif |
| Google Nest Hub | domotique chaleureuse, gros chiffres fins, lisible de loin |
| Interface du Steam Deck | fond ardoise sombre, accent vif, barre de navigation à gauche |

## Principes

Ces principes valent pour Console, Console jour, Contraste élevé et leurs dérivés ; Chaleur a les siens (plus haut), les autres thèmes gardent leur DA.


- Nuit (Console, le thème par défaut jusqu'au 05/11/2026), jour au choix (Console jour), Contraste élevé pour l'accessibilité, Hush pour le style (nuit noire, accent rose, halos rose et violet animés derrière les écrans, widgets transparents ; `Palette.Hush`, `HushPaletteTest`), Spider-Verse pour le style (le logo araignée en fond fixe, accent magenta, libellés cyan, widgets sans cadre rangés en deux colonnes autour d'une colonne vide où le logo reste net, filigrane quand le milieu est occupé ; `Palette.SpiderMan`, `SpiderManPaletteTest`).
- Une seule couleur vivante : le vert acide `c_accent`. Il marque l'élément choisi de la barre fixe, l'action principale, les curseurs et interrupteurs actifs.
- Teintes de fonction `c_hue_*` colorées mais douces, réservées aux pastilles d'icônes et aux tuiles d'applis.
- Tuiles et cartes à 28 dp de rayon, en aplat : ni filet ni ombre. Appui = `c_surface_pressed` (`console_tile_bg`). Contraste élevé ajoute un filet de 2 dp.
- Figtree pour tous les rôles : `c_font_regular` 400, `c_font_medium` 500, `c_font_light` 300, `c_font_title` 500, `c_font_display` 300 (heure, chrono).
- Pas de dégradé violet-bleu, d'emoji, de carte dans une carte, ni de texture dans Console, Console jour et Contraste élevé, sauf le grain s'il est allumé (jamais dans Contraste élevé). Tubes et motifs n'existent que dans les thèmes repris d'UnoMate (plus bas).
- Couleurs toujours par attribut (`?attr/c_…`, `themeColor(context, R.attr.c_…)`), polices par rôle (`c_font_*`), jamais en dur.

## Thèmes repris d'UnoMate

Clair, Sombre, Cadran, Cadran nuit, Rétro, Registre, Studio, Maison, Tableau et Terminal (04/11, PR #145) gardent la DA d'UnoMate, pas celle de Console douce. Choix de Réglages en groupes (`ThemeGroup`) : Console, Sobres, Rétro, Ambiances, Accessibilité. Exceptions assumées, comme pour Hush :

- grain par-dessus l'écran, sur tous les thèmes, seulement si Réglages > Thème > Grain est allumé (éteint par défaut, `Prefs.grain`) : chaque palette a ses deux couleurs (`c_grain_*`, `ui/Grain`), transparentes dans Contraste élevé, plus chaudes dans Cadran, Cadran nuit, Rétro et Maison ;
- tubes qui coulent à droite de la zone des modes, sous la barre du haut (Cadran, Cadran nuit, Rétro : `c_flow*`, `ui/home/FlowBandView`), textes de l'Accueil avec un halo pour rester lisibles ;
- motif statique (Registre, Studio, Tableau, Terminal : `c_pattern`, `ui/ThemePattern`) ; le grain allumé se pose par-dessus le motif ;
- polices propres à chaque thème, à empattement (Abril Fatface, Young Serif, Instrument Serif) ou monospace (Terminal en JetBrains Mono partout, chiffres de Registre en IBM Plex Mono) ;
- plusieurs teintes vives dans les tubes.

Contrastes : `ThemePalettesTest` (textes et couleurs de sens 4,5:1, teintes et `c_graphic` 3:1, sans exception).

## Jetons

Source : `app/src/main/res/values/palettes.xml`. Page web du compagnon : `backend/src/web-style.ts` (mêmes valeurs).

| Jeton | Console (nuit) | Console jour |
|---|---|---|
| `c_bg` / `c_rail` | #17181B / #17181B | #F4F2EE / #ECE9E3 |
| `c_surface` | #23252A | #FFFFFF |
| `c_surface_raised` | #2A2D33 | #EFECE6 |
| `c_surface_pressed` | #31343B | #E6E2DB |
| `c_stroke` | #3A3D44 | #D8D3CA |
| `c_accent` / `c_on_accent` | #B8F34A / #16210A | #3F6B00 / #FFFFFF |
| `c_accent_soft` | #2C3A12 | #DFF5B8 |
| `c_text` / `c_text_dim` / `c_text_faint` | #ECEDEF / #B4B8C0 / #9095A0 | #1B1C1F / #474B54 / #5E626C |
| `c_graphic` | #737883 | #7A7F89 |
| `c_ok` / `c_ko` / `c_warn` | #7FD8A6 / #FF7A70 / #FFC24B | #1F7A4D / #C62828 / #8A5A00 |
| `c_hue_orange` | #FFA45B | #B04A00 |
| `c_hue_cyan` | #5FD4E8 | #006B7D |
| `c_hue_violet` | #B39CFF | #6B46D6 |
| `c_hue_pink` | #FF8FB8 | #B8305F |
| `c_hue_yellow` | #FFD45B | #7A5B00 |
| `c_hue_blue` | #6FA8FF | #1F5FBF |
| `c_hue_red` / `c_hue_green` | #FF7A70 / #B8F34A | #C62828 / #3F6B00 |

Les textes, l'accent et les teintes font au moins 4,5:1 sur le fond, la tuile et le relief (`ConsolePaletteTest`). Contraste élevé (Console jour, Figtree, mêmes formes) : textes et couleurs de sens au moins 7:1 (`ContrastPaletteTest`). Les signaux des agents (`c_agent_*`) gardent les couleurs d'Orca ; le jaune de surveillance en jour reste sous 3:1.

Hush, exception assumée (choix d'Ahmed, 31/10) : accent rose au lieu du vert, lueur rose et violette animée derrière les écrans, widgets sans fond ni bordure (halo rose à l'appui, `hush_tile_bg`), table de mixage sans plaque au style « fil et pastille » (`c_mx_bare`, maquette B d'après Elgato Wave Link, 01/11 : icône = Muet, rail fin `c_graphic` à 3:1 rempli d'accent, pastille ronde, vumètre fin et continu au fond discret, pilules de scène plates ; un trait sous les scènes et entre deux tranches seulement), ombre légère sous les textes (`Widget.Hush.Text`, `c_text_shadow`), barre fixe transparente, quatre éclats décoratifs. Polices : Chakra Petch (textes, heure en 700) et Share Tech Mono en majuscules espacées pour les étiquettes (`c_font_label`, `c_label_caps`) ; fenêtres et cartes de Réglages gardent le verre (`c_card_bg`). Choix d'Ahmed (31/10) assumés : étiquettes en monospace (exception à la règle « monospace pour codes et adresses » : seulement les titres de widgets), et aucune forme au repos pour un widget ou une touche (le halo rose n'est qu'un état d'appui, pas un décor ; l'icône, le nom et le « + » restent les repères). Le reste de la DA s'applique (formes, un seul accent). Jetons : `c_bg` #070509, `c_rail` transparent, `c_surface` #B82C2238 (verre, #221A2B posé sur le fond), `c_surface_raised` #251C2E, `c_accent` / `c_on_accent` #FF6EB4 / #22030F, `c_text` / `c_text_dim` / `c_text_faint` #F6F0F8 / #CBC0D3 / #B3A6BD, `c_graphic` #9A8CA6, `c_hue_violet` #B98CFF (couleur des reflets). La lumière des halos est plafonnée pour que l'accent garde 4,5:1 sur le point le plus clair du fond (`HushPaletteTest`).

Widget Valorant (style « Chaleur », choix d'Ahmed du 05/11 : « plus Valorant, moins de texte ») : exception assumée à « une seule couleur vivante ». Les équipes prennent les couleurs du jeu par les teintes du thème (`c_hue_cyan` pour la mienne, `c_hue_red` en face, titres d'équipe seulement) ; les stats sont dans des pastilles teintées à 22 % de `c_ok`, `c_warn` ou `c_ko` selon leur valeur, toujours avec le chiffre écrit ; noms et rangs en capitales espacées. Logos de rang et icônes d'agent : images de Riot (valorant-api.com).

Console de mixage (onglet Son) : afficheur #0E0F11 à chiffres vert acide, LED vert / jaune #FFD45B / corail #FF7A70. Curseur en galet (pilule pleine, index court) et touche Muet ronde au pictogramme du haut-parleur, barré, anneau et fond corail en sourdine (variante « Galet », 04/11) ; Hush et Spider-Verse gardent leur table nue, où l'icône de la voie fait Muet.

## Captures

Émulateur `companion_api29` (Android 10, 1280×800), backend de démonstration (`backend/test/widgets-demo.ts`).

| Écran | Console | Console jour |
|---|---|---|
| Accueil | [accueil-console.png](accueil-console.png) | [accueil-console-jour.png](accueil-console-jour.png) |
| Réunion (appel en cours, mixage) | [reunion-console.png](reunion-console.png) | [reunion-console-jour.png](reunion-console-jour.png) |
| Focus | [focus-console.png](focus-console.png) | [focus-console-jour.png](focus-console-jour.png) |
| Réglages | [reglages-console.png](reglages-console.png) | [reglages-console-jour.png](reglages-console-jour.png) |
| Page locale du compagnon | [page-locale-console.png](page-locale-console.png) | [page-locale-console-jour.png](page-locale-console-jour.png) |
| Bienvenue | [bienvenue-console.png](bienvenue-console.png) | |
| Écrans (profils) | [ecrans-console.png](ecrans-console.png) | |

Icônes de Mate : `icone.svg`, `icone-visage.svg` et leurs versions monochromes.
