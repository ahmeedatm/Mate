# Android : l'appli de la tablette

## Le matériel et la scène

Fiche complète : `docs/tablette-4.2.md`. Ce qui compte pour le design :

| Fait | Conséquence |
|---|---|
| MediaTek MT8167, 4 cœurs, 2 Go de RAM | animations courtes, peu d'éléments animés à la fois, pas de flou ni d'ombre animés |
| Écran 10,1" 1280×800 à 160 dpi (1 dp = 1 px) | une seule classe d'écran, en paysage ; ce qu'on voit dans la capture est la taille réelle |
| Android 10 (API 29), `minSdk 24` | pas de Material You dynamique, pas de Compose |
| Posée sur un bureau, lue en passant | lisible d'un coup d'œil, de plus loin qu'un téléphone tenu en main |
| Kiosque : barre d'état masquée | heure, WiFi, batterie viennent de `TabletStatusView` (barre latérale), pas du système |
| Branchée en permanence, allumée toute la journée | thèmes sombres par défaut, luminosité atténuée 22 h – 7 h (`device/Screen`), pas de boucle qui tourne pour rien |

L'émulateur donne la mise en page et les couleurs, jamais les performances ni le toucher. Dire
d'où vient chaque preuve.

## La pile (ne pas en sortir)

- Vues XML, une Activity (`MainActivity`) et un Fragment par mode. Pas de Compose.
- Thèmes : `ui/Themes.kt` (Nuit, Clair, Néon, Chaud), changement = `recreate()`.
- Couleurs : attributs `?attr/c_…` (`res/values/attrs.xml`, valeurs dans `palettes.xml`). Dans le
  code `themeColor(context, R.attr.c_…)`. Jamais de couleur en dur dans une vue ou un drawable
  (exception admise : couleurs choisies par l'utilisateur pour une touche du Deck).
- Rôles : `c_bg` < `c_rail` < `c_surface` < `c_surface_raised` < `c_surface_pressed` (élévation par
  la teinte, pas par l'ombre) ; `c_text` / `c_text_dim` / `c_text_faint` ; `c_accent` +
  `c_on_accent` ; `c_ok`, `c_ko`, `c_warn` (+ `_soft`) ; teintes de fonction `c_hue_*`.
- `ui/Look.kt` : une teinte par fonction (`iconHue`, `nameHue`), pastilles (`badge`), arrivée
  décalée (`enter`), `pop`, boucles `float` / `pulse` à arrêter avec `stop`. `TitleView` pour tout
  titre de carte.
- Styles de `themes.xml` : `Card`, `CardTitle`, `Chip` (état via `isSelected`), `ActionButton`,
  `PrimaryButton`, `IconButton` (+ `.Flat`, `.Primary`), `Input`, `Slider`. Un nouveau bouton
  hérite d'un de ces styles.
- Appui : `@animator/press` (déjà dans les styles de boutons). Arrivée d'un mode : `@animator/mode_in`.
- Icônes : vecteurs `res/drawable/ic_*` (Material, un seul jeu). Pas d'emoji ni de caractère
  Unicode à la place d'une icône.
- Textes : `res/values/strings.xml`, en français, tutoiement. Les boutons courts disent « ordi »,
  le reste « ordinateur ».
- Erreurs : `errorText(e)` / `toastError(e)` (`ui/Errors.kt`). Jamais `e.message` à l'écran.
- Clavier : l'Activity est en `adjustNothing`. Toute saisie dans une boîte passe par
  `showForTyping` (`ui/Dialogs.kt`). Pas de vrai champ dans une carte de l'Accueil : un faux champ
  ouvre une boîte. Champs construits dans le code : gonfler `item_input.xml`.

## Règles chiffrées

### Texte

| Rôle | Taille | Note |
|---|---|---|
| Corps, libellés de liste, boutons | 15 à 17 sp | `ActionButton` 16 sp, `Input` 17 sp |
| Secondaire (heure de fin, sous-titre) | 13 à 14 sp | `c_text_dim`, jamais `c_text_faint` |
| Titres de carte en capitales | 13 sp | `CardTitle`, espacement 0,1 |
| Plancher | 12 sp | rien en dessous, même dans la barre latérale |

Toujours en `sp`. `c_text_faint` reste pour ce qui ne se lit pas (indice de champ vide, séparateur) :
il est à environ 2,3 à 3,2:1 sur les surfaces des 4 thèmes (calcul du 27/10/2026), sous le seuil
de 4,5:1 du texte.

### Contraste (WCAG AA)

- Texte ≥ 4,5:1, grand texte (≥ 18 sp ou 14 sp gras) et icônes utiles ≥ 3:1.
- À vérifier dans les 4 thèmes : une couleur qui passe en Nuit peut tomber en Clair (`c_warn` et
  `c_hue_orange` sur `c_surface` en Clair sont à 3:1).
- Calcul depuis `palettes.xml`, pas à l'œil. Couleur sur fond coloré : prendre une teinte du fond,
  pas du gris.

### Cibles tactiles

- 48 × 48 dp minimum, 8 dp entre deux cibles. Action principale d'un écran : 56 dp
  (`IconButton.Primary`).
- Une icône peut être plus petite que sa zone : agrandir la vue, pas l'icône.
- Toute action par geste (balayage, appui long, glisser) a un équivalent visible ou documenté à
  l'écran (bande `TrashZone`, mode Modifier).
- Tout `ImageButton` a un `contentDescription` (TalkBack et commande vocale).

### Budget d'animation

Règles tirées du matériel ci-dessus et des valeurs déjà en place (`press.xml` 90 / 260 ms,
`mode_in.xml` 200 / 240 ms, `Look.enter` 260 ms + 45 ms par élément). La cadence réelle de
l'écran et le taux d'images en retard ne sont pas encore mesurés : à relever sur la tablette avec
`adb shell dumpsys gfxinfo app.mate` avant de pousser plus loin.

| Usage | Durée |
|---|---|
| Retour d'appui | 80 à 120 ms |
| Changement d'état (sélection, coche) | 150 à 250 ms |
| Arrivée d'un mode, d'une boîte | 200 à 300 ms |
| Décalage entre éléments d'une liste | ≤ 50 ms par élément, 300 ms de décalage total au plus |
| Sortie | plus courte que l'entrée |

- Propriétés : `alpha`, `translationX/Y`, `scaleX/Y`, `rotation`. Avec `withLayer()` pour une vue
  complexe. Jamais `width`/`height`/marges animées, jamais de flou, d'ombre ou de dégradé animé.
- Décélération (`DecelerateInterpolator`). Rebond (`Overshoot`) seulement pour un geste qui l'appelle
  (appui, ajout) ; jamais par défaut sur une arrivée.
- Une boucle infinie (`float`, `pulse`) seulement si elle porte un état vivant (chrono qui tourne) ;
  arrêtée dès que la vue quitte l'écran. Une seule visible à la fois.
- Pas d'animation de fenêtre (`windowAnimationStyle` = `@null`, déjà en place).
- Animations désactivées (Réglages > Accessibilité > Supprimer les animations, ou
  `adb shell settings put global animator_duration_scale 0`) : l'écran reste juste, rien ne reste
  à `alpha = 0`.

## Vérifier

Émulateur `companion_api29` (Android 10, 1280×800, 160 dpi, sans services Google ; README). S'il
tourne déjà pour une autre session, prendre un autre AVD et un autre port. Backend de test sur un
port libre avec `DATA_DIR` jetable ; l'émulateur le joint par `10.0.2.2`.

```bash
A="$HOME/Library/Android/sdk/platform-tools/adb -s emulator-5554"   # adapter le numéro
$A exec-out screencap -p > capture.png
# relever puis rétablir après la passe
$A shell settings get system font_scale
$A shell settings put system font_scale 1.3
$A shell settings put system font_scale 1.0
$A shell uiautomator dump /sdcard/ui.xml && $A shell cat /sdcard/ui.xml   # textes et bornes
```

Passe minimale d'un écran :

- les 4 thèmes (Réglages > Thème), une capture chacun ;
- `font_scale 1.3` : rien de coupé, rien de chevauché ;
- les états de `etats.md` qui le concernent, provoqués pour de vrai ;
- mode Modifier si l'écran en a un ;
- puis la vraie tablette pour le toucher, la lisibilité à distance et la fluidité.
