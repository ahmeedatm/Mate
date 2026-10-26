# Cadran : direction artistique du compagnon

Le temps, posé sur le bureau.

La tablette vit à côté de l'ordinateur toute la journée. Presque tout ce qu'elle affiche dépend de
l'heure : agenda, rappels, Focus, eau, passage en nuit. Cadran en fait le sujet de l'interface. Le
rouge Unowhy signale le présent, le reste s'efface.

| Accueil, jour | Accueil, nuit |
|---|---|
| ![Accueil jour](accueil-jour.png) | ![Accueil nuit](accueil-nuit.png) |

Mouvement : [`demo.mp4`](demo.mp4) (28 s, légendé). Prototype interactif : [`accueil.html`](accueil.html)
(`?demo&legende` rejoue la démonstration, `?theme=nuit`, `?fige`). Les tuiles, les touches et les
cases à cocher réagissent au clic.

## Les cinq principes

1. **Une seule couleur vivante.** Le rouge cardinal d'Unowhy (`#CA0538`) veut dire « maintenant » :
   le point sur la ligne du jour, la prochaine réunion, le temps qui reste au Focus, un appel en
   cours. Rien d'autre n'est rouge. Comme la trotteuse de l'horloge des gares suisses.
2. **La ligne du jour.** De 8 h à 20 h, une ligne porte les événements. Le passé s'efface, le présent
   est un point rouge, la suite reste lisible. C'est la signature de l'Accueil, de l'icône et du
   minuteur d'appairage sur la page web.
3. **Le battement de la minute.** Une fois par minute, tout ce qui dépend de l'heure avance d'un
   seul geste : les chiffres qui changent basculent, le compte à rebours suit, le point avance et
   émet une onde. Entre deux battements, rien ne bouge.
4. **Le contenu donne la couleur.** L'interface reste en gris chaud. Les couleurs viennent des
   vraies icônes d'applis (déjà fournies par `GET /windows/icon`), de la météo, du vert Unowhy pour
   ce qui est fait. Plus de pastille colorée dans les titres.
5. **Le jour, la nuit.** Papier le jour, graphite la nuit. La bascule suit le vrai coucher du
   soleil (Open-Meteo le donne déjà pour la météo), en fondu de 1,6 s.

## Bâtie sur de vrais design systems

| Fondation | Source | Ce qu'on prend |
|---|---|---|
| Charte Unowhy | `unowhy.com` (variables CSS du thème) | rouge cardinal `#CA0538`, vert `#2A6154`, blanc chaud `#FAF7F8`, police **Outfit** |
| Material Design 3 : couleur | `@material/material-color-utilities` 0.4.0 | palettes tonales HCT générées depuis le rouge Unowhy, rôles `surface…`, tons de la spec 2026 |
| Material Design 3 : composants | NavigationRail (indicateur 56 × 32), calque d'état (appui 10 %), échelle de formes | barre latérale, retours d'appui, rayons |
| Material Design 3 : mouvement | ressorts `MotionScheme.standard` (spatial / effects) | toutes les animations, valeurs exactes ci-dessous |
| Material Symbols Rounded | google/material-design-icons, Apache 2.0 | toutes les icônes d'interface |
| Apple HIG | WWDC23 « Animate with springs », WWDC25 session 356, HIG Accessibilité | coins concentriques, contenu d'abord, cibles de 44 pt au moins |

Valeurs officielles et liens : [`../specs-ds.md`](../specs-ds.md).

## Couleur

Neutres = palette tonale M3 à la teinte du rouge Unowhy (16,2°) et chroma 2 : un gris chaud qui
rappelle le blanc de la marque sans tirer vers le rose. Tous les rôles sont des tons M3, aucun hex
n'est choisi à l'œil.

| Rôle (attribut actuel) | Rôle M3 | Jour | Nuit |
|---|---|---|---|
| fond (`c_bg`) | surfaceContainerLow / surface | `#FAF2F1` (T96) | `#100D0D` (T4) |
| tuile (`c_surface`) | surfaceContainerLowest / surfaceContainerHigh | `#FFFFFF` (T100) | `#221F1F` (T12) |
| relief (`c_surface_raised`) | surfaceContainerHigh / Highest | `#EEE6E6` (T92) | `#282525` (T15) |
| texte (`c_text`) | onSurface | `#1E1B1B` (T10) | `#E9E1E0` (T90) |
| texte secondaire (`c_text_dim`) | onSurfaceVariant | `#4D4545` | `#D0C4C3` |
| texte tertiaire (`c_text_faint`) | outline | `#7E7575` | `#998E8E` |
| filet (`c_stroke`) | outlineVariant | `#D0C4C3` | `#4D4545` |
| maintenant (`c_accent`) | rouge Unowhy, palette tonale | `#CA0538` | `#FF5165` (T60) |
| fond du maintenant (`c_accent_soft`) | T90 / T20 | `#FFDADA` | `#680018` |
| fait (`c_ok`) | vert Unowhy, palette tonale | `#31685A` (T40) | `#9AD2C1` (T80) |

Contrastes (ratio M3) : rouge sur fond 5,3 en jour et 5,4 en nuit, texte secondaire sur tuile 9,3
et 11,4, vert sur tuile 6,4 et 9,6. Tout passe le seuil de 4,5.

Les huit teintes `c_hue_*` disparaissent. Les tâches, l'agenda et les touches se distinguent par
leur contenu et leur place, plus par une couleur.

## Typographie

Une seule famille : **Outfit**, la police d'Unowhy. Elle a les chiffres tabulaires (`tnum`,
vérifié dans le fichier) : les chiffres ne sautent pas quand l'heure change.

| Usage | Taille / interligne | Graisse | Base M3 |
|---|---|---|---|
| Horloge | 184 / 176 px, approche −3,5 % | 300 | au-delà de Display Large, comme StandBy |
| Valeurs (météo, prochain rendez-vous, Focus) | 44 à 52 / 48 à 56 | 300 | Display Small agrandi |
| Date | 26 / 32 | 400 | Headline Small |
| Titre d'élément | 18 / 24 | 500 | Title Medium |
| Texte | 16 / 20 | 400 | Body Large |
| En-tête de tuile, secondaire | 14 à 15 / 20 | 400 à 500 | Label Large |
| Heures de la ligne du jour | 12 / 16 | 500 | Label Medium |

Les grands chiffres sont fins, le texte courant reste en 400 : la hiérarchie vient de la taille, pas
de la graisse. Pas de capitales espacées, pas de titre en couleur.

Sur Android : instances statiques 300, 400, 500 réduites au latin dans `res/font/` (le fichier
variable fait 111 Ko ; les axes ne sont pilotables qu'à partir de l'API 26), et
`fontFeatureSettings="tnum"` sur les chiffres.

## Formes et espace

- Grille de 8 : marges 32, gouttières 16, marge intérieure des tuiles 24.
- Tuile : rayon 32. Éléments intérieurs collés au bord : 32 − 24 = **8** (coins concentriques, HIG).
- Touches d'applis : 60 px, rayon 22, coin continu (`corner-shape: squircle` dans le prototype,
  `Path` mis en cache sur Android).
- Boutons et puces : pilule (rayon = moitié de la hauteur), hauteur 44 au moins.
- Aucune ombre, aucun flou, aucun dégradé : la profondeur vient des tons (T96 → T100 le jour,
  T4 → T12 la nuit).

## Mouvement

Ressorts M3 `MotionScheme.standard`. Le schéma « expressive » rebondit davantage ; on le garde pour
plus tard si la tablette le permet.

| Jeton | Amortissement | Raideur | Usage |
|---|---|---|---|
| spatial fast | 0,9 | 1400 | appui, sortie d'un chiffre, barre de tâche faite |
| spatial default | 0,9 | 700 | entrée d'un chiffre, point « maintenant », tâche qui descend, tuiles qui arrivent |
| spatial slow | 0,9 | 300 | la ligne du jour qui se dessine à l'arrivée |
| effects fast | 1 | 3800 | disparition d'un chiffre |
| effects default | 1 | 1600 | apparitions, coche qui se trace |
| effects slow | 1 | 800 | onde du battement |

| Moment | Ce qui bouge | Ce qui ne bouge pas | Coût estimé sur le MT8167 |
|---|---|---|---|
| Battement de la minute | 1 à 4 chiffres (translation Y + alpha, cadre qui coupe), compte à rebours, point + onde | tout le reste | 6 à 10 vues ~400 ms, une fois par minute : négligeable |
| Arrivée de l'Accueil | trait du passé (échelle X), événements (alpha), tuiles décalées de 50 ms (Y + alpha) | horloge (simple fondu) | ~700 ms, 12 vues ; déjà le cas avec `Look.enter` |
| Appui | échelle 0,96 + calque d'état 10 % | voisins | 1 vue, 200 ms |
| Tâche faite | coche tracée (`AnimatedVectorDrawable`, RenderThread dès l'API 25), barre (échelle X), puis descente des lignes (translation Y) | tuile | 5 vues, 1 s |
| Nuit | fondu croisé plein écran 1,6 s (capture de l'écran en image, `recreate()`, l'image s'efface) | — | une image 1280 × 800 (4 Mo) une fois par jour |

Règles :

- Jamais de boucle. Seul le chrono du Focus change chaque seconde, en texte (chiffres tabulaires,
  largeur fixe : pas de relayout).
- Jamais de largeur ou de hauteur animée : translation, échelle, alpha seulement.
- Pas d'ombre, de flou ni d'`elevation` animés (coûteux sur le PowerVR GE8300, `RenderEffect`
  absent en API 29).
- Animations coupées si l'échelle d'animation Android vaut 0, comme `prefers-reduced-motion` dans
  le prototype.

Sur Android : `SpringAnimation` de `androidx.dynamicanimation` 1.1.0 avec
`SpringForce(stiffness, dampingRatio)` aux valeurs ci-dessus (mêmes équations que le prototype).
Il tourne sur le fil principal, pas sur le RenderThread : à garder pour des groupes de quelques
vues. Les fondus simples restent en `ViewPropertyAnimator`. ⚠️ Les coûts ci-dessus sont estimés.
Ils seront mesurés avec `dumpsys gfxinfo` sur l'écran de preuve.

## Marque

![Icône](icone.png)

- Icône : la ligne du jour, le présent et son onde, en blanc sur rouge cardinal ([`icone.svg`](icone.svg)),
  version monochrome pour les notifications et l'extension de navigateur ([`icone-mono.svg`](icone-mono.svg)).
  ⚠️ En petit, elle peut rappeler un curseur de réglage. À valider à 16 px dans la barre du navigateur.
- Pages web (page locale, `/camera`, retour OAuth) : mêmes tokens, même police, mêmes formes. Le
  code d'appairage reprend la ligne du jour : le point rouge avance pendant ses 2 minutes de validité.

| Page locale, jour | Page locale, nuit |
|---|---|
| ![Page locale jour](page-locale-jour.png) | ![Page locale nuit](page-locale-nuit.png) |

- Ton des textes : phrases courtes au présent, tutoiement comme aujourd'hui, l'heure avant le
  libellé (« 17:00 Point Eliot »), jamais de point d'exclamation.

## Ce que Cadran refuse

Le look « généré » : cartes bleu nuit, néon, dégradés violet → turquoise, pastille colorée devant
chaque titre, capitales espacées, emoji, polices Space Grotesk / Inter par défaut, trame de points
façon Nothing.

## Passage dans l'appli (après validation)

1. `palettes.xml` : une palette Jour, une palette Nuit, valeurs du tableau Couleur. Les attributs
   `?attr/c_…` ne changent pas de nom.
2. `res/font/` : Outfit 300, 400, 500, puis styles de texte dans `themes.xml`.
3. `ui/Look.kt` : jetons de ressort, appui, battement de la minute (un seul `Handler` calé sur la
   minute pleine).
4. Écran par écran, en commençant par une carte de l'Accueil (écran de preuve), captures avant/après
   et mesure `gfxinfo` sur `companion_api29`, puis la vraie tablette.
