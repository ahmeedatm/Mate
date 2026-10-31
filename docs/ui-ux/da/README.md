# DA « Console douce »

Mate est un accessoire gamer et domotique casual posé sur le bureau : lisible de loin, calme, chaleureux. Nuit par défaut, une seule couleur vivante.

## Références

| Référence | Ce qu'on lui prend |
|---|---|
| Accueil de la Nintendo Switch | tuiles arrondies et calmes, un seul accent, rien de décoratif |
| Google Nest Hub | domotique chaleureuse, gros chiffres fins, lisible de loin |
| Interface du Steam Deck | fond ardoise sombre, accent vif, barre de navigation à gauche |

## Principes

- Nuit par défaut (Console), jour au choix (Console jour), Contraste élevé pour l'accessibilité.
- Une seule couleur vivante : le vert acide `c_accent`. Il marque l'élément choisi de la barre fixe, l'action principale, les curseurs et interrupteurs actifs.
- Teintes de fonction `c_hue_*` colorées mais douces, réservées aux pastilles d'icônes et aux tuiles d'applis.
- Tuiles et cartes à 28 dp de rayon, en aplat : ni filet ni ombre. Appui = `c_surface_pressed` (`console_tile_bg`). Contraste élevé ajoute un filet de 2 dp.
- Figtree pour tous les rôles : `c_font_regular` 400, `c_font_medium` 500, `c_font_light` 300, `c_font_title` 500, `c_font_display` 300 (heure, chrono).
- Pas de dégradé violet-bleu, d'emoji, de carte dans une carte, ni de texture : le grain et les tubes de l'ancienne DA sont retirés.
- Couleurs toujours par attribut (`?attr/c_…`, `themeColor(context, R.attr.c_…)`), polices par rôle (`c_font_*`), jamais en dur.

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

Console de mixage (onglet Son) : afficheur #0E0F11 à chiffres vert acide, LED vert / jaune #FFD45B / corail #FF7A70.

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
