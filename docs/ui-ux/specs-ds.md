# Specs design system : valeurs exactes (Material 3, Apple HIG, Material Symbols, Android)

Date de consultation : 2026-10-26. Cible : Android en vues XML, minSdk 24, compileSdk 36 (`app/build.gradle.kts`).

Légende : ⚠️ = valeur non lue dans une source officielle, ou extraite sans recoupement.

## 0. État du projet (vérifié dans le repo)

- Dépendances réelles de `app/build.gradle.kts` : `androidx.appcompat:appcompat:1.7.1`, `androidx.lifecycle:lifecycle-runtime-ktx:2.10.0`, `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2`.
- Aucune dépendance `com.google.android.material:material` ni `androidx.dynamicanimation`. Les attributs `motion*` et `MaterialSharedAxis` demandent d'ajouter la lib Material.
- Les attributs `motionEasing*` / `motionDuration*` exigent Material 1.6.0 ou plus. Les ressorts `motionSpring*` exigent Material 1.13.0 ou plus (source : Motion.md, ci-dessous).

## 1. Material 3, mouvement

### 1.1 Durées (`motionDuration*`)

Valeurs identiques dans Material Components Android et dans les tokens Compose v0_103.

| Attribut | ms | | Attribut | ms |
|---|---|---|---|---|
| Short1 | 50 | | Long1 | 450 |
| Short2 | 100 | | Long2 | 500 |
| Short3 | 150 | | Long3 | 550 |
| Short4 | 200 | | Long4 | 600 |
| Medium1 | 250 | | ExtraLong1 | 700 |
| Medium2 | 300 | | ExtraLong2 | 800 |
| Medium3 | 350 | | ExtraLong3 | 900 |
| Medium4 | 400 | | ExtraLong4 | 1000 |

Sources : https://github.com/material-components/material-components-android/blob/master/docs/theming/Motion.md (lu 2026-10-26) ; https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/MotionTokens.kt (VERSION v0_103).

Le guide Motion.md dit : la durée augmente avec la surface ou la distance parcourue.

### 1.2 Courbes (`motionEasing*`, notation cubic-bezier x1,y1,x2,y2)

| Courbe | Attribut Android | Valeur |
|---|---|---|
| emphasized | `motionEasingEmphasizedInterpolator` | path : `M 0,0 C 0.05,0 0.133333,0.06 0.166666,0.4 C 0.208333,0.82 0.25,1 1,1` |
| emphasized decelerate | `motionEasingEmphasizedDecelerateInterpolator` | 0.05, 0.7, 0.1, 1 |
| emphasized accelerate | `motionEasingEmphasizedAccelerateInterpolator` | 0.3, 0, 0.8, 0.15 |
| standard | `motionEasingStandardInterpolator` | 0.2, 0, 0, 1 |
| standard decelerate | `motionEasingStandardDecelerateInterpolator` | 0, 0, 0, 1 |
| standard accelerate | `motionEasingStandardAccelerateInterpolator` | 0.3, 0, 1, 1 |
| linear | `motionEasingLinearInterpolator` | 0, 0, 1, 1 |

Sources : Motion.md (ci-dessus) pour les attributs ; MotionTokens.kt v0_103 pour les cubic.

⚠️ Divergence : Compose v0_103 donne `EasingEmphasized` = 0.2, 0, 0, 1, alors que Motion.md donne `emphasized` = path. Pour l'Android XML, utiliser l'attribut Motion.md (le path).

Tokens Compose « Legacy » (hors M3, à ne pas utiliser en nouveau code) : 0.4,0,0.2,1 (legacy) ; 0.4,0,1,1 (legacy accelerate) ; 0,0,0.2,1 (legacy decelerate).

### 1.3 Ressorts M3 (standard et expressive)

Valeurs = damping ratio et stiffness (paramètres de `SpringForce` côté Android).

Standard (`StandardMotionTokens`, v0_14_0) :

| Jeton | Damping | Stiffness |
|---|---|---|
| spatial fast | 0.9 | 1400 |
| spatial default | 0.9 | 700 |
| spatial slow | 0.9 | 300 |
| effects fast | 1.0 | 3800 |
| effects default | 1.0 | 1600 |
| effects slow | 1.0 | 800 |

Expressive (`ExpressiveMotionTokens`, v0_14_0) :

| Jeton | Damping | Stiffness |
|---|---|---|
| spatial fast | 0.6 | 800 |
| spatial default | 0.8 | 380 |
| spatial slow | 0.8 | 200 |
| effects fast | 1.0 | 3800 |
| effects default | 1.0 | 1600 |
| effects slow | 1.0 | 800 |

Sources :
- https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/StandardMotionTokens.kt
- https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/ExpressiveMotionTokens.kt
- `MotionScheme.standard()` / `MotionScheme.expressive()` : https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/MotionScheme.kt

Attributs Android (Motion.md, Material 1.13.0 ou plus) :

| Attribut | Damping | Stiffness |
|---|---|---|
| `motionSpringFastSpatial` | 0.9 | 1400 |
| `motionSpringFastEffects` | 1 | 3800 |
| `motionSpringDefaultSpatial` | 0.9 | 700 |
| `motionSpringDefaultEffects` | 1 | 1600 |
| `motionSpringSlowSpatial` | 0.9 | 300 |
| `motionSpringSlowEffects` | 1 | 800 |

Source : Motion.md (ci-dessus). Ces valeurs correspondent au schéma Standard.

⚠️ Motion.md ne dit pas quel schéma (standard ou expressive) ces attributs représentent. ⚠️ Le guide Motion.md ne cite pas les « schémas » M3 ; la page https://m3.material.io/styles/motion/overview/specs n'a renvoyé que son titre.

Règle de Motion.md : effects = pas de dépassement (utile pour alpha et couleur). Spatial = mouvement et taille. Vitesse choisie selon la taille du composant ou la distance parcourue.

### 1.4 Shared axis (`MaterialSharedAxis`)

- Durée par défaut : attribut `motionDurationLong1` (450 ms par la table ci-dessus), identique à l'entrée et à la sortie.
- Courbe par défaut : `motionEasingEmphasizedInterpolator`, identique à l'entrée et à la sortie.
- Distance de glissement : `mtrl_transition_shared_axis_slide_distance` = 30 dp.
- Glissement : translation directe, pas de mise à l'échelle. Entrée : décalage vers la position d'origine. Sortie : position d'origine vers le décalage. En RTL, START et END s'inversent.
- Axe Z : `ScaleProvider`, non lu ⚠️.
- Découpage sortie / entrée (`MaterialVisibility`) : non lu ⚠️.

Sources :
- https://raw.githubusercontent.com/material-components/material-components-android/master/lib/java/com/google/android/material/transition/MaterialSharedAxis.java
- https://raw.githubusercontent.com/material-components/material-components-android/master/lib/java/com/google/android/material/transition/SlideDistanceProvider.java
- https://raw.githubusercontent.com/material-components/material-components-android/master/lib/java/com/google/android/material/transition/res/values/dimens.xml

### 1.5 Fade through (`MaterialFadeThrough`)

- Durée par défaut : `motionDurationLong1` (450 ms), identique à l'entrée et à la sortie.
- Courbe par défaut : `motionEasingEmphasizedInterpolator`.
- Mise à l'échelle : entrée à 0.92 (`DEFAULT_START_SCALE = 0.92f`), sortie sans mise à l'échelle.
- Fondu : `FADE_THROUGH_THRESHOLD = 0.35F`.
  - Sortie : progression 0 à 0.35, alpha de l'original à 0.
  - Entrée : progression 0.35 à 1, alpha de 0 à l'original.
- Calcul ⚠️ : avec 450 ms, la sortie dure environ 158 ms (0 à 157,5) et l'entrée environ 292 ms. Calcul à partir de la fraction, `TransitionUtils.lerp` non lu.

Sources :
- https://raw.githubusercontent.com/material-components/material-components-android/master/lib/java/com/google/android/material/transition/MaterialFadeThrough.java
- https://raw.githubusercontent.com/material-components/material-components-android/master/lib/java/com/google/android/material/transition/FadeThroughProvider.java

## 2. Material 3, couleur

### 2.1 Rôles (48 dans Compose)

Liste exacte de `ColorSchemeKeyTokens.kt` : Background, Error, ErrorContainer, InverseOnSurface, InversePrimary, InverseSurface, OnBackground, OnError, OnErrorContainer, OnPrimary, OnPrimaryContainer, OnPrimaryFixed, OnPrimaryFixedVariant, OnSecondary, OnSecondaryContainer, OnSecondaryFixed, OnSecondaryFixedVariant, OnSurface, OnSurfaceVariant, OnTertiary, OnTertiaryContainer, OnTertiaryFixed, OnTertiaryFixedVariant, Outline, OutlineVariant, Primary, PrimaryContainer, PrimaryFixed, PrimaryFixedDim, Scrim, Secondary, SecondaryContainer, SecondaryFixed, SecondaryFixedDim, Surface, SurfaceBright, SurfaceContainer, SurfaceContainerHigh, SurfaceContainerHighest, SurfaceContainerLow, SurfaceContainerLowest, SurfaceDim, SurfaceTint, SurfaceVariant, Tertiary, TertiaryContainer, TertiaryFixed, TertiaryFixedDim.

⚠️ `Shadow` n'existe pas dans ce fichier. Tones absents de ce fichier.

Source : https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/ColorSchemeKeyTokens.kt

### 2.2 Tones par défaut (spec 2026 de material-color-utilities)

Source : `typescript/dynamiccolor/color_spec_2026.ts` (https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/typescript/dynamiccolor/color_spec_2026.ts). Spec « 2026 » (`ColorSpecDelegateImpl2026`), variante CMF uniquement selon le fichier.

| Rôle | Tone clair | Tone sombre |
|---|---|---|
| surface | 98 | 4 |
| surfaceDim | 87 | 4 |
| surfaceBright | 98 | 18 |
| surfaceContainerLowest | 100 | 0 |
| surfaceContainerLow | 96 | 6 |
| surfaceContainer | 94 | 9 |
| surfaceContainerHigh | 92 | 12 |
| surfaceContainerHighest | 90 | 15 |
| onSurface | contraste 9 | contraste 11 |
| onSurfaceVariant | contraste 4.5 | contraste 6 |
| outline | contraste 3 | contraste 3 |
| outlineVariant | contraste 1.5 | contraste 1.5 |
| primary | 40 si chroma source ≤ 12, sinon tone source | 80 si chroma ≤ 12, sinon tone source |
| onPrimary | contraste 6 vs primary | idem |
| primaryContainer | 90 si chroma ≤ 12 (clair seulement), sinon tone source borné 61-90 ou 30-49 | tone source borné 61-90 ou 30-49 |
| onPrimaryContainer | contraste 6 vs primaryContainer | idem |

⚠️ Les tones « contraste » dépendent d'une courbe de contraste, dans `dynamic_color.ts`, non lu. Ne pas mélanger ces valeurs avec d'autres versions de spec.

### 2.3 Génération depuis une graine

- Paquet npm : `@material/material-color-utilities` (TypeScript). Dart : `material_color_utilities`. Java et Kotlin annoncés, nom de paquet non lu ⚠️. Licence : Apache-2.0.
  Source : https://github.com/material-foundation/material-color-utilities
- Variantes de schéma : le README lu ne liste pas TonalSpot, Neutral, Monochrome, Fidelity, Content ⚠️. Le code lu utilise `Variant.CMF`.
- Harmonisation : `Blend.harmonize(designColor: number, sourceColor: number): number` (ARGB en entrée et en sortie). Le décalage de teinte vaut `min(différence × 0.5, 15°)`, vers la teinte source. Chroma et tone gardés. Pas de paramètre de quantité.
  Source : https://raw.githubusercontent.com/material-foundation/material-color-utilities/main/typescript/blend/blend.ts
- Clair ou sombre : fonction de schéma non lue ⚠️.

## 3. Material 3, typographie (15 styles, valeurs de base)

Source : `TypeScaleTokens.kt` (https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/TypeScaleTokens.kt), tokens en sp.

| Style | Taille | Interligne | Graisse | Espacement |
|---|---|---|---|---|
| DisplayLarge | 57 | 64 | Regular | -0.2 |
| DisplayMedium | 45 | 52 | Regular | 0 |
| DisplaySmall | 36 | 44 | Regular | 0 |
| HeadlineLarge | 32 | 40 | Regular | 0 |
| HeadlineMedium | 28 | 36 | Regular | 0 |
| HeadlineSmall | 24 | 32 | Regular | 0 |
| TitleLarge | 22 | 28 | Regular | 0 |
| TitleMedium | 16 | 24 | Medium | 0.2 |
| TitleSmall | 14 | 20 | Medium | 0.1 |
| BodyLarge | 16 | 24 | Regular | 0.5 |
| BodyMedium | 14 | 20 | Regular | 0.2 |
| BodySmall | 12 | 16 | Regular | 0.4 |
| LabelLarge | 14 | 20 | Medium | 0.1 |
| LabelMedium | 12 | 16 | Medium | 0.5 |
| LabelSmall | 11 | 16 | Medium | 0.5 |

⚠️ Les poids sont donnés par constantes (`WeightRegular`, `WeightMedium`, `WeightBold`). Valeurs numériques 400, 500, 700 : fichier `TypefaceTokens` non lu.

⚠️ Variantes Emphasized (Medium ou Bold) : le fichier indique `TODO update with the generated tokens once available`. Valeurs provisoires :

| Style | Taille | Interligne | Graisse | Espacement |
|---|---|---|---|---|
| DisplayLargeEmphasized | 57 | 64 | Medium | 0 |
| DisplayMediumEmphasized | 45 | 52 | Medium | 0 |
| DisplaySmallEmphasized | 36 | 44 | Medium | 0 |
| HeadlineLargeEmphasized | 32 | 40 | Medium | 0 |
| HeadlineMediumEmphasized | 28 | 36 | Medium | 0 |
| HeadlineSmallEmphasized | 24 | 32 | Medium | 0 |
| TitleLargeEmphasized | 22 | 28 | Medium | 0 |
| TitleMediumEmphasized | 16 | 24 | Bold | 0.15 |
| TitleSmallEmphasized | 14 | 20 | Bold | 0.1 |
| BodyLargeEmphasized | 16 | 24 | Medium | 0.15 |
| BodyMediumEmphasized | 14 | 20 | Medium | 0.25 |
| BodySmallEmphasized | 12 | 16 | Medium | 0.4 |
| LabelLargeEmphasized | 14 | 20 | Bold | 0.1 |
| LabelMediumEmphasized | 12 | 16 | Bold | 0.5 |
| LabelSmallEmphasized | 11 | 16 | Bold | 0.5 |

## 4. Material 3, échelle de formes (`ShapeTokens.kt`, v14_1_0)

| Jeton | Rayon |
|---|---|
| CornerNone | 0 dp |
| CornerExtraSmall | 4 dp |
| CornerSmall | 8 dp |
| CornerMedium | 12 dp |
| CornerLarge | 16 dp |
| CornerLargeIncreased | 20 dp |
| CornerExtraLarge | 28 dp |
| CornerExtraLargeIncreased | 32 dp |
| CornerExtraExtraLarge | 48 dp |
| CornerFull | cercle (`CircleShape`, sans valeur fixe) |

Variantes directionnelles : CornerExtraSmallTop (4 haut, 0 bas), CornerLargeTop (16 haut), CornerLargeStart (16 début), CornerLargeEnd (16 fin), CornerExtraLargeTop (28 haut).

Les variantes « Increased » sont les équivalents Expressive. Le fichier ne contient pas le mot « Expressive ».

Source : https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/ShapeTokens.kt

## 5. Apple Human Interface Guidelines et SwiftUI

### 5.1 Ressorts SwiftUI

- Pages `spring(response:dampingFraction:blendDuration:)`, `smooth`, `snappy`, `bouncy` : la page HTML ne renvoie que le titre. Le JSON DocC de `smooth` donne les signatures et les résumés, sans valeurs par défaut : https://developer.apple.com/tutorials/data/documentation/swiftui/animation/smooth.json
  - `smooth(duration:extraBounce:)` : « A smooth spring animation with a predefined duration and no bounce that can be tuned. »
  - `snappy(duration:extraBounce:)` : « A spring animation with a predefined duration and small amount of bounce that feels more snappy and can be tuned. »
  - `bouncy(duration:extraBounce:)` : « A spring animation with a predefined duration and higher amount of bounce that can be tuned. »
- ⚠️ Valeurs numériques des presets (`smooth`, `snappy`, `bouncy`) : non trouvées dans les pages lues.
- ⚠️ `response` et `dampingFraction` : non trouvés dans les pages lues. La conversion response vers stiffness n'est donc pas confirmée.

Source officielle lue : WWDC23 « Animate with springs », https://developer.apple.com/videos/play/wwdc2023/10158/ (transcription, extraction).
- Paramètres : « It uses just 2 parameters: duration and bounce. »
- Défaut conseillé : « When you're not sure, use a spring with bounce 0 »
- Bounce entre -1.0 et 1.0. Au-delà d'environ 0.4, prudence.
- ⚠️ Formules (masse = 1), extraites de la transcription, à recouper avec la vidéo :
  - stiffness = (2π / duration)²
  - damping = 1 − 4π × bounce / duration si bounce ≥ 0 ; 4π / (duration + 4π × bounce) si bounce < 0

### 5.2 Coins concentriques

Source : WWDC25 session 356, https://developer.apple.com/videos/play/wwdc2025/356/ (transcription).
- « And concentric shapes calculate their radius by subtracting padding from the parent's. »
  Donc : rayon intérieur = rayon extérieur − marge.
- Trois types : forme fixe (rayon constant) ; capsule (rayon = moitié de la hauteur du conteneur).
- ⚠️ Courbure continue (squircle) : non trouvée dans la transcription lue. Ne pas l'affirmer sans source.
- ⚠️ Page HIG « Layout » : JSON sans passage sur les coins concentriques.

### 5.3 Taille minimale de cible tactile

Source : HIG Accessibility (JSON DocC : https://developer.apple.com/tutorials/data/design/human-interface-guidelines/accessibility.json ; page : https://developer.apple.com/design/human-interface-guidelines/accessibility).

« Offer sufficiently sized controls. » Tableau de la page :

| Plateforme | Taille par défaut | Taille minimale |
|---|---|---|
| iOS, iPadOS | 44 x 44 pt | 28 x 28 pt |
| macOS | 28 x 28 pt | 20 x 20 pt |
| tvOS | 66 x 66 pt | 56 x 56 pt |
| visionOS | 60 x 60 pt | 28 x 28 pt |
| watchOS | 44 x 44 pt | 28 x 28 pt |

Espacement : « it works well to add about 12 points of padding around elements that include a bezel. For elements without a bezel, about 24 points of padding works well around the element's visible edges. »

Nuance : 44 pt est la taille par défaut sur iOS, 28 pt est le minimum. Le « 44 pt minimum » souvent cité ne figure pas dans cette page.

### 5.4 Principes de mouvement (HIG Motion)

Source : https://developer.apple.com/design/human-interface-guidelines/motion (JSON : https://developer.apple.com/tutorials/data/design/human-interface-guidelines/motion.json).

- « Add motion purposefully, supporting the experience without overshadowing it. »
- « Make motion optional. »
- « Strive for realistic feedback motion that follows people's gestures and expectations. »
- « Aim for brevity and precision in feedback animations. »
- « In apps, generally avoid adding motion to UI interactions that occur frequently. »
- « Let people cancel motion. »

## 6. Material Symbols

- Licence : Apache License 2.0 (page Google Fonts et dépôt). Le texte de la doc est sous CC BY 4.0.
  Sources : https://developers.google.com/fonts/docs/material_symbols ; https://github.com/google/material-design-icons
- Axes (page Google Fonts) :

| Axe | Plage | Défaut |
|---|---|---|
| FILL | 0 à 1 | 0 |
| wght | 100 à 700 | 400 |
| GRAD | -50 à 200 | 0 |
| opsz | 20 à 48 | 48 |

⚠️ Conflit dans la page : l'exemple Google Fonts donne opsz 48 par défaut, la note sur la police statique indique une valeur de 24. ROND (0 à 100) apparaît dans les exemples, hors des quatre axes listés.
- Android : « In the Material Symbols Library, all icons are in the Vector Drawable format. » Pas de procédure d'export ni de règle sur les axes en VD.
  ⚠️ Déduction : un VD est une variante figée. Les axes se choisissent à l'export, pas au runtime. Non confirmé par la doc.
- ⚠️ Le dossier `symbols/android` et le nommage des fichiers n'ont pas été lus.

## 7. Android

### 7.1 ViewPropertyAnimator et RenderThread

Source lue : `ViewPropertyAnimator.java` (AOSP, branche main, https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/java/android/view/ViewPropertyAnimator.java).
- Aucun passage sur le RenderThread. Aucune condition de bascule vers le RT dans le fichier.
- `startAnimation()` crée un `ValueAnimator`, applique l'interpolateur seulement s'il est défini, puis lance l'animation.
- `setUpdateListener` : appelé en dernier dans `onAnimationUpdate()`.
- `withLayer()` : passe en `LAYER_TYPE_HARDWARE` pour la prochaine animation.
- ⚠️ Conclusion : aucune condition RT officielle trouvée pour `ViewPropertyAnimator`. Le nom `ViewPropertyAnimatorRT` n'apparaît pas dans ce fichier.
- `RenderNodeAnimator` : annoté `@hide` (non public), https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/java/android/view/RenderNodeAnimator.java

### 7.2 AnimatedVectorDrawable (RenderThread, API 25+)

Source : https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/graphics/java/android/graphics/drawable/AnimatedVectorDrawable.java
- Javadoc : « Starting from API 25, AnimatedVectorDrawable runs on RenderThread (as opposed to on UI thread for earlier APIs). »
- Javadoc : « This means animations in AnimatedVectorDrawable can remain smooth even when there is heavy workload on the UI thread. »
- Dans le code : seuls les `ObjectAnimator` sont convertis en animation RT ; les autres `ValueAnimator` ne modifient pas le VD.
- Pause et reprise non implémentées sur le RT (TODO dans le code).
- `forceAnimationOnUI()` : impossible si l'animation a déjà démarré sur le RT.
- Avant l'API 25 : UI thread.

### 7.3 SpringAnimation (androidx.dynamicanimation)

- Dernière version : `androidx.dynamicanimation:dynamicanimation:1.1.0`, publiée le 9 avril 2025. Bibliothèque en maintenance (correctifs critiques seulement). Google recommande Compose pour les nouvelles UI.
  Source : https://developer.android.com/jetpack/androidx/releases/dynamicanimation
- Constantes (`SpringForce.java`, androidx main) :

| Constante | Valeur |
|---|---|
| STIFFNESS_HIGH | 10000 |
| STIFFNESS_MEDIUM (défaut) | 1500 |
| STIFFNESS_LOW | 200 |
| STIFFNESS_VERY_LOW | 50 |
| DAMPING_RATIO_HIGH_BOUNCY | 0.2 |
| DAMPING_RATIO_MEDIUM_BOUNCY (défaut) | 0.5 |
| DAMPING_RATIO_LOW_BOUNCY | 0.75 |
| DAMPING_RATIO_NO_BOUNCY | 1.0 |

Source : https://raw.githubusercontent.com/androidx/androidx/androidx-main/dynamicanimation/dynamicanimation/src/main/java/androidx/dynamicanimation/animation/SpringForce.java
- Page officielle `SpringForce` : non lisible (navigation seule).
- Pour reprendre les valeurs M3 : `SpringForce().setStiffness(...).setDampingRatio(...)`, avec les valeurs de la section 1.3.

### 7.4 ValueAnimator.areAnimatorsEnabled

Source : `ValueAnimator.java` (AOSP main, https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/java/android/animation/ValueAnimator.java).
- Code : `public static boolean areAnimatorsEnabled() { return !(sDurationScale == 0); }`
- Javadoc : « Returns whether animators are currently enabled, system-wide. » Faux si l'option développeur « animator duration scale » vaut 0, ou si le mode Économiseur de batterie est actif.
- ⚠️ API 26 : pas d'annotation `@apiSince` dans le fichier lu, non confirmé.
- ⚠️ `Settings.Global.ANIMATOR_DURATION_SCALE` : absent du fichier lu, non confirmé.

## 8. Sources consultées (2026-10-26)

- M3 motion (attributs) : https://github.com/material-components/material-components-android/blob/master/docs/theming/Motion.md
- M3 Compose tokens (androidx-main) : https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/
- MaterialSharedAxis, MaterialFadeThrough, FadeThroughProvider, SlideDistanceProvider : https://raw.githubusercontent.com/material-components/material-components-android/master/lib/java/com/google/android/material/transition/
- material-color-utilities : https://github.com/material-foundation/material-color-utilities
- Apple SwiftUI (JSON DocC) : https://developer.apple.com/tutorials/data/documentation/swiftui/animation/smooth.json
- WWDC23 Animate with springs : https://developer.apple.com/videos/play/wwdc2023/10158/
- WWDC25 session 356 : https://developer.apple.com/videos/play/wwdc2025/356/
- HIG Accessibility : https://developer.apple.com/design/human-interface-guidelines/accessibility
- HIG Motion : https://developer.apple.com/design/human-interface-guidelines/motion
- Material Symbols : https://developers.google.com/fonts/docs/material_symbols ; https://github.com/google/material-design-icons
- AOSP (ViewPropertyAnimator, RenderNodeAnimator, AnimatedVectorDrawable, ValueAnimator) : https://github.com/aosp-mirror/platform_frameworks_base
- androidx dynamicanimation : https://developer.android.com/jetpack/androidx/releases/dynamicanimation

## 9. Non vérifié (récapitulatif)

- Ressorts `spring(response:dampingFraction:)` et presets SwiftUI : valeurs numériques absentes.
- Couleur : tones de la courbe de contraste ; variantes de schéma TonalSpot, Neutral, Monochrome, Fidelity, Content (non lues).
- Shared axis : découpage sortie / entrée (`MaterialVisibility`) et axe Z (`ScaleProvider`).
- Fade through : interpolation exacte (`TransitionUtils.lerp`).
- Typographie : valeurs numériques des graisses (400, 500, 700), styles Emphasized provisoires.
- HIG : coins concentriques, courbure continue.
- Material Symbols : axes en VD, dossier `symbols/android`, conflit de défaut opsz.
- Android : `ViewPropertyAnimatorRT` (pas de condition RT officielle), `areAnimatorsEnabled` API 26, `ANIMATOR_DURATION_SCALE`.
