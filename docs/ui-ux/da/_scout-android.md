# Scout Android : animations, morphing, capteurs, son, haptique, mesure

Date : 2026-10-27. Cible : minSdk 24, targetSdk 29, MT8167 (4 x Cortex-A35 1,3 GHz, PowerVR GE8300), 2-3 Go, 1280x800. Vues XML, pas Compose. Dépendances actuelles : appcompat, lifecycle-runtime-ktx, coroutines.

Légende : ⚠️ = non confirmé par une source lue (ou source partielle). Les pages `developer.android.com/reference/...` (Java et Kotlin) sont trop longues pour WebFetch : la navigation occupe la lecture. Quand la classe n'était pas atteignable, la source retenue est le code AOSP `android-10.0.0_r47` (API 29), lu sur `raw.githubusercontent.com/aosp-mirror/platform_frameworks_base`.

## Versions relevées

| Lib | Version stable la plus récente | Source |
|---|---|---|
| androidx.graphics:graphics-shapes | 1.1.0 (métadonnées Maven : 2025-10-22) | https://dl.google.com/dl/android/maven2/androidx/graphics/graphics-shapes/maven-metadata.xml |
| androidx.metrics:metrics-performance (JankStats) | 1.0.0 | https://dl.google.com/dl/android/maven2/androidx/metrics/metrics-performance/maven-metadata.xml |
| com.airbnb.android:lottie | 6.7.1 (Maven Central) | https://repo1.maven.org/maven2/com/airbnb/android/lottie/maven-metadata.xml |
| app.rive:rive-android | 11.15.0 (Maven Central, 2026-10-22) | https://repo1.maven.org/maven2/app/rive/rive-android/maven-metadata.xml |
| androidx.profileinstaller:profileinstaller | 1.4.1 (cité dans la doc) | https://developer.android.com/topic/performance/baselineprofiles/overview |
| Material Components Android | 1.14.0 (version citée par la doc MaterialContainerTransform) | https://developer.android.com/reference/com/google/android/material/transition/MaterialContainerTransform |

## 1. Animations sur RenderThread (vues)

| Technique | Ce qu'elle fait | API min | Thread | Coût bas de gamme | Source |
|---|---|---|---|---|---|
| `ViewAnimationUtils.createCircularReveal(view, cx, cy, r0, r1)` | Révélation circulaire d'une vue | 21 ⚠️ (valeur de mémoire, page Java non lue) | Javadoc : « It is also an asynchronous animation that automatically runs off of the UI thread. » Retourne `RevealAnimator`, qui `extends RenderNodeAnimator` (classe `@hide`) : l'animation agit sur le RenderNode. RenderThread déduit du type, pas écrit dans la doc ⚠️ | Faible. Usage unique : pas de pause ni de reprise. Un seul clip non rectangulaire par vue. Le clip circulaire prime sur `setClipToOutline`. | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-10.0.0_r47/core/java/android/view/ViewAnimationUtils.java ; https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-10.0.0_r47/core/java/android/animation/RevealAnimator.java |
| `RenderNodeAnimator` | Animation directe d'un RenderNode | - | RenderThread | - | Classe `@hide` : ne pas l'utiliser (API cachée). Lu via `RevealAnimator`. |
| `AnimatedVectorDrawable` (AVD) | Animer les propriétés d'un VectorDrawable (path, couleur, transformations) | 21 (snippet « Added in API level 21 » de la page Kotlin) ; `AnimatedVectorDrawableCompat` dans androidx.vectordrawable:vectordrawable-animated pour les versions antérieures | Javadoc : « Starting from API 25, AnimatedVectorDrawable runs on RenderThread (as opposed to on UI thread for ... ». Repli UI si le canvas est logiciel, ou via `forceAnimationOnUI()` (`@hide`). Pas de pause sur RT (TODO dans le source). | Faible : animation déportée sur RT à partir de l'API 25. Avant 25 : UI thread. | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-10.0.0_r47/graphics/java/android/graphics/drawable/AnimatedVectorDrawable.java ; https://developer.android.google.cn/reference/kotlin/android/graphics/drawable/AnimatedVectorDrawable |
| `ViewPropertyAnimator` | alpha, translation, scale, rotation, x/y | 12 ⚠️ (de mémoire) ; `withLayer()` 16 ⚠️ | UI thread. Un `ValueAnimator` tourne sur le UI thread et écrit dans `mView.mRenderNode` (`setAlpha`, `setTranslationX`, `setScaleX`…). Le dessin reste sur RT. | Un tick UI par frame (petit, non chiffré). `withLayer()` met la vue en `LAYER_TYPE_HARDWARE` pendant l'animation seulement. | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-10.0.0_r47/core/java/android/view/ViewPropertyAnimator.java ; https://developer.android.com/reference/android/view/ViewPropertyAnimator |

Conditions RT, côté ViewPropertyAnimator :
- Le calcul des valeurs reste sur le UI thread. Seule la composition est déportée.
- La doc hardware : « Calling the setter for any of these properties results in optimal invalidation and no redrawing of the targeted view » (avec hardware layer). Propriétés : alpha, x/y, translation, scale, rotation, pivot.
- Sans layer, alpha passe par un buffer hors écran : « rendered in an off-screen buffer which doubles the required fill-rate ».
- « Because hardware layers consume video memory, it is highly recommended that you enable them only for the duration of the animation and then disable them after the animation is done. »
- « do not draw more than 2.5 times the number of pixels on screen per frame ».

Source doc : https://developer.android.com/develop/ui/views/graphics/hardware-accel (page datée 2026-10-23).

Pistes pour la tablette :
- Préférer translation, scale, alpha, rotation (RT via RenderNode). Éviter d'animer les dimensions ou la mise en page : re-layout sur le UI thread ⚠️ (déduction, non lue dans une doc).
- Révélation circulaire : un seul effet d'ouverture, à usage unique. Coût RT.
- AVD pour les icônes.

## 2. Transitions d'éléments partagés, container transform

| Technique | Ce qu'elle fait | API min | Thread / coût | Source |
|---|---|---|---|---|
| `MaterialContainerTransform` (`com.google.android.material.transition`) | Transforme un conteneur en un autre (forme, taille, contenu) | `@RequiresApi(LOLLIPOP)` sur la classe (source master). Variante AndroidX : Fragments et Views, pas Activities. Variante `.platform` : Activities et Windows, « may have different behavior across API Levels ». | Doc muette sur le coût. Overlay drawable qui dessine un masque. Ombres : « may cause performance issues » avant API 28 (javadoc `setElevationShadowEnabled`). Chemin de repli `drawPath` : « less performant ». Mesure requise ⚠️. | https://github.com/material-components/material-components-android/blob/master/docs/theming/Motion.md ; https://developer.android.com/reference/com/google/android/material/transition/MaterialContainerTransform ; source : https://raw.githubusercontent.com/material-components/material-components-android/master/lib/java/com/google/android/material/transition/platform/MaterialContainerTransform.java |
| Usage en vues | `setStartView(v)` / `setEndView(v)`, puis `TransitionManager.beginDelayedTransition(...)` | - | Options : `setFadeMode` (`FADE_MODE_IN` par défaut, `FADE_MODE_THROUGH`), `setPathMotion(MaterialArcMotion())`, `setElevationShadowEnabled(false)` pour économiser les ombres sur bas de gamme ⚠️ (conseil, non mesuré) | https://github.com/material-components/material-components-android/blob/master/docs/theming/Motion.md |
| `ChangeBounds` / `ChangeTransform` (framework transitions) | ChangeBounds : « Moves and resizes views. ». ChangeTransform : « Captures scale and rotation of views before and after the scene change and animates those changes ». | Non indiqué ⚠️ | Coût et perf non documentés. Limites : `TextView` redimensionné affiche le texte à sa nouvelle place avant la fin. `AdapterView` (ListView) incompatible, peut figer. `SurfaceView` hors sync. | https://developer.android.com/develop/ui/views/animations/transitions |
| `TransitionManager.beginDelayedTransition(root, ChangeBounds())` | Enregistre l'état, anime au prochain redessin | - | Mise en page en UI thread ⚠️ | https://developer.android.com/develop/ui/views/animations/transitions |
| Alternative maison : capture bitmap + échelle | Snapshot de la vue, puis animation de translation et d'échelle du bitmap | - | Pas de source. Calcul : un bitmap plein écran 1280x800 en ARGB_8888 fait 1280 x 800 x 4 octets, soit environ 4,1 Mo. Capture = dessin logiciel sur CPU ⚠️. Prendre un snapshot réduit (1/2 ou 1/4). | calcul propre |

Recommandation : pour « carte vers détail » dans une même Activity, MaterialContainerTransform (Views) est le chemin documenté. Pour un simple déplacement ou changement de taille, ChangeBounds suffit. La capture bitmap est un plan B si Material est trop lourd, à mesurer sur la tablette.

## 3. Morphing de formes et d'icônes

| Technique | Ce qu'elle fait | Contraintes | Thread / coût | Source |
|---|---|---|---|---|
| AVD `pathData` (morph) | Interpole deux chemins vectoriels | Les chemins doivent avoir « the same number of commands and the same number of parameters for each command » (doc). Javadoc : « the same commands, in the same order ». `android:valueType="pathType"` requis (exemple doc). Chemins incompatibles = erreur au runtime (⚠️ selon un outil tiers, non officiel). | RT à partir de l'API 25 | https://developer.android.com/develop/ui/views/animations/drawable-animation ; https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-10.0.0_r47/graphics/java/android/graphics/drawable/AnimatedVectorDrawable.java |
| `androidx.graphics:graphics-shapes` 1.1.0 | `RoundedPolygon` (étoile, rectangle, pill, etc.) et `Morph(start, end)` avec progression de 0 à 1 | Kotlin (Kotlin 2.0 demande KGP 2.0+). Changement 1.1.0-alpha01 : progression sur la longueur des courbes. | `Morph.toPath(progress, path)` renvoie un `android.graphics.Path` (⚠️ vu dans le guide Compose via un snippet de recherche, non lu en entier). Utilisable en vues : oui en principe, via `canvas.drawPath` dans `onDraw` ⚠️ (non testé). Calcul de Path à chaque frame sur UI thread ⚠️ (déduction). | https://developer.android.com/jetpack/androidx/releases/graphics ; https://developer.android.com/develop/ui/compose/graphics/draw/shapes?hl=fr ; https://developer.android.com/reference/kotlin/androidx/graphics/shapes/package-summary |

- minSdk de graphics-shapes : ⚠️ non trouvé dans les sources lues (release notes, guide, page package, `build.gradle` androidx sans valeur visible). Vérifier le POM ou l'AAR avant ajout.
- Formes M3 Expressive : ⚠️ non confirmées par les sources lues. Le guide Compose les montre, pas la page vues.

## 4. Lottie, AVD, Rive

| Critère | AVD | Lottie | Rive |
|---|---|---|---|
| Dépendance | Aucune (framework), Compat via `androidx.vectordrawable` | `com.airbnb.android:lottie` 6.7.1 (Maven). README : « Lottie 2.8.0 and above only supports projects that have been migrated to androidx » | `app.rive:rive-android` 11.15.0 (Maven). Exemple doc : 11.9.2 + `androidx.startup:startup-runtime:1.1.1`. Init C++ runtime : « Rive needs to link and initialize its C++ runtime » |
| Poids APK | ~0 Ko ajouté ⚠️ (inférence) | ⚠️ non mesuré | ⚠️ non mesuré |
| Coût CPU | Faible : chemins et propriétés | Dépend de l'animation. `RenderMode.AUTOMATIC` passe en logiciel si API ≤ 25, si dash pattern avant API 28, ou si plus de 4 masks/mattes. Sinon matériel. | ⚠️ non chiffré. API Compose : thread dédié (« owns a thread for Rive operations »). API View legacy : `RiveAnimationView`, modèle de threads non décrit. |
| RenderThread | Oui, API 25+ | ⚠️ thread de rendu non lu ; mode HARDWARE par défaut, sauf les cas ci-dessus | ⚠️ |
| Vues XML | Oui | `LottieAnimationView` ⚠️ (non lu : le site lottie.airbnb.tech n'a pas livré son contenu) | `RiveAnimationView` ; API « legacy », maintenue « for some time » |
| minSdk | 21 (AVD) ⚠️ | ⚠️ non lu | ⚠️ non lu |

Sources : https://raw.githubusercontent.com/airbnb/lottie-android/master/README.md ; https://raw.githubusercontent.com/airbnb/lottie-android/master/lottie/src/main/java/com/airbnb/lottie/RenderMode.java ; https://rive.app/docs/runtimes/android/android

Lecture : sur une tablette Android 10 (API 29), Lottie en mode AUTOMATIC reste matériel sauf cas particuliers. Les lots Android 7 et 8 (minSdk 24) basculent en logiciel, car la règle AUTOMATIC cible API ≤ 25. Pour des icônes simples, AVD suffit. Lottie ne se justifie que pour une animation complexe fournie par un designer, après mesure de l'APK et du frame time.

## 5. Polices variables (axe `wght`)

| Point | Constat | Source |
|---|---|---|
| API | `Paint.setFontVariationSettings(String)` : API 26 ⚠️ (de mémoire, @since non lu) | https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-10.0.0_r47/graphics/java/android/graphics/Paint.java |
| Syntaxe | Tag d'axe de 4 caractères ASCII, entre guillemets simples ou doubles. Ex. `"'wght' 600"`. Axe absent : réglage ignoré. Format invalide : `IllegalArgumentException`. | javadoc (même source) |
| Mécanisme | Le code crée un nouveau `Typeface` à chaque appel (`setTypeface`). Animé à chaque frame : une allocation par frame ⚠️ (lu dans le source, pas mesuré). | même source |
| Re-layout, cache de glyphes | Ni la javadoc ni le source lus ne le disent. ⚠️ | - |

Pistes : n'animer qu'un libellé court, pas un texte long ni une liste. Mesurer avant/après avec JankStats (voir section 8). Une alternative sans coût de police : deux graisses discrètes avec deux `Typeface` préchargées.

## 6. Capteurs

Source unique : https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview

| Capteur | Constante | Ce que dit la doc | Permission |
|---|---|---|---|
| Lumière | `TYPE_LIGHT` (matériel) | « Measures the ambient light level (illumination) in lx. » Usage cité : « Controlling screen brightness » | Aucune |
| Proximité | `TYPE_PROXIMITY` (matériel) | « typically used to determine whether a handset is being held up to a person's ear » | Aucune |
| Accéléromètre | `TYPE_ACCELEROMETER` (matériel) | « acceleration force in m/s2 ... on all three physical axes ..., including the force of gravity » | Aucune |
| Gravité | `TYPE_GRAVITY` | « Motion detection (shake, tilt, etc.) » | Aucune |

- API : `SensorManager.getDefaultSensor(type)`, `registerListener(listener, sensor, delay)`, `unregisterListener`.
- Délais : `SENSOR_DELAY_NORMAL` 200 000 µs (défaut), `UI` 60 000 µs, `GAME` 20 000 µs, `FASTEST` 0 µs. Le délai est « only a suggested delay ».
- Limite : `registerListener()` plafonne à 200 Hz. `HIGH_SAMPLING_RATE_SENSORS` seulement pour cible API 31+ (hors périmètre ici).
- Consommation : « Using a larger delay imposes a lower load on the processor and therefore uses less power. » Désinscrire dans `onPause`. Le système ne coupe pas les capteurs à l'écran éteint. En arrière-plan (Android 9+), les capteurs continus ne livrent plus d'événements.
- Présence sur MT8167 : ⚠️ non vérifiable ici. Tester `getDefaultSensor(TYPE_LIGHT) != null` par adb ou dans l'appli. Proximité souvent absente d'une tablette ⚠️.
- Jour/nuit : `TYPE_LIGHT` pour la luminosité. Le projet a déjà un horaire 22 h - 7 h (`device/Screen`) : le choix entre horaire et capteur relève d'une décision produit ⚠️.
- Posée/prise : `TYPE_GRAVITY` ou `TYPE_ACCELEROMETER`. La doc ne donne pas d'algorithme : ⚠️ à construire et à valider sur la tablette.

## 7. Son et haptique

### SoundPool

Source : https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-10.0.0_r47/media/java/android/media/SoundPool.java

- Classe non dépréciée. Le constructeur à 3 arguments est `@deprecated` : utiliser `SoundPool.Builder`. `AudioAttributes` et Builder : API 21 ⚠️ (de mémoire).
- Javadoc : « In addition to low-latency playback, SoundPool can also manage the number of audio streams being rendered at once. » La décompression se fait avant la lecture « without having to suffer the CPU load and latency of decompressing during playback ». Donc charger tôt.
- `maxStreams` : Builder à 1 par défaut, minimum 1. Au-delà, un flux précédent est arrêté.
- Chargement : `OnLoadCompleteListener` « Called when a sound has completed loading ». `load()` asynchrone : implicite ⚠️.
- Thread des callbacks : Looper du thread courant, sinon principal (javadoc `setOnLoadCompleteListener`).
- Latence en millisecondes : aucune valeur dans la doc. ⚠️ à mesurer sur la tablette.

### Haptique

- Principes : https://developer.android.com/develop/ui/views/haptics/haptics-principles
  - « When it comes to haptic feedback on mobile devices, less is more. »
  - « Given the choice of buzzy haptics or no haptics for touch feedback, choose no haptics. »
  - « Avoid using the legacy one-shot vibrations… »
  - Événements très fréquents (défilement) : très subtils. Événements importants : plus forts.
- Ordre de préférence (doc) : 1) `HapticFeedbackConstants` si l'action y correspond (`view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)`) ; 2) `VibrationEffect` prédéfini ou `Composition` ; 3) éviter `createOneShot` et `vibrate(long)`.
- `VibrationEffect.createPredefined(int)` : API 29 ⚠️ (de mémoire, page non lue). Constantes : `EFFECT_CLICK`, `EFFECT_DOUBLE_CLICK`, `EFFECT_TICK`, `EFFECT_HEAVY_CLICK` ⚠️ valeurs et API de `EFFECT_THUD` / `EFFECT_POP` non vérifiées.
- Support : `Vibrator.areAllEffectsSupported(...)` renvoie `VIBRATION_EFFECT_SUPPORT_YES/NO/UNKNOWN` ⚠️ (non lu). Prévoir un repli.
- Tablette : la présence d'un moteur de vibration n'est pas documentée pour ce modèle. Tester `Vibrator.hasVibrator()` ⚠️.
- API 29 pour les constantes `HapticFeedbackConstants` : ⚠️ non vérifié.

## 8. Mesurer et tenir 60 i/s

| Outil | Ce qu'il fait | API / prérequis | Coût | Source |
|---|---|---|---|---|
| JankStats (`androidx.metrics:metrics-performance` 1.0.0) | Détecte les frames en jank, données par frame | Compatible minSdk 24 : FrameMetrics (API 24+), OnPreDrawListener avant | ⚠️ non chiffré | https://developer.android.com/topic/performance/jankstats |
| `Window.addOnFrameMetricsAvailableListener` | Métriques natives par frame | API 24 (cohérent avec la doc JankStats) ⚠️ page non lue | ⚠️ | https://developer.android.com/topic/performance/jankstats |
| `dumpsys gfxinfo <pkg> framestats` | Historique détaillé par frame en ligne de commande | Commande de mémoire ⚠️, non confirmée par la page lue (`profile-gpu` ne la cite pas) | - | https://developer.android.com/topic/performance/rendering/profile-gpu |
| Perfetto / System Trace | Trace système (CPU, threads, jank) | Perfetto : Android 10+ (doc). `ui.perfetto.dev` | Perfetto ne détaille pas le code de l'appli : utiliser CPU Profiler d'Android Studio pour ça | https://developer.android.com/topic/performance/tracing |
| Baseline Profiles | Précompile les chemins de code au premier lancement | AGP 8.0 minimum ; Macrobenchmark `androidx.benchmark:benchmark-macro-junit4:1.5.0` ; `profileinstaller:1.4.1` | Voir ci-dessous | https://developer.android.com/topic/performance/baselineprofiles/overview |
| `Choreographer.postFrameCallback` | Callback à chaque frame | API 16 ⚠️ de mémoire, page non lue | - | - |

Baseline Profiles, points sourcés :
- « Baseline Profiles improve code execution speed by about 30% from the first launch ». Ce chiffre n'a pas de source dans la page. La page ajoute : « Many apps measure performance boosts of about 30% after optimizing. » Table AGP 8.2 : couverture +30 %, performance +15 %. Startup Profiles : +15 % supplémentaire (AGP 8.2). Aucun chiffre pour le jank.
- Hors Play Store : ProfileInstaller installe le profil au premier lancement (doc, phrase valable pour Android 7 à 8.1). « Non-Google-Play-Store app distribution channels might not support using Baseline Profiles at installation. Users ... don't see the benefits until background dexopt runs—which is likely overnight. »
- Partage interne Play : non supporté ; piste de test interne : supportée.
- Les Views : la page cite Compose en exemple. Application aux Views : ⚠️ déduction (le profil couvre le code de l'appli), non documentée.
- Gain réel sur la MT8167 : aucun chiffre trouvé. Mesurer avec Macrobenchmark avant et après.

Ordre de mesure conseillé : `dumpsys gfxinfo` (⚠️ commande à valider) ou JankStats en debug, puis Perfetto pour l'analyse, puis Baseline Profile si les jank viennent du démarrage ou du premier passage d'écran.

## Non confirmé (⚠️), récapitulatif

- minSdk de graphics-shapes, Rive, Lottie, Material 1.14.0 (non lus).
- Poids APK de Lottie, Rive, AVD (non mesurés).
- Coût CPU de Lottie et Rive sur bas de gamme (non chiffré).
- API levels de ViewAnimationUtils (21), ViewPropertyAnimator (12, 16), VibrationEffect.createPredefined (29), HapticFeedbackConstants, Paint.setFontVariationSettings (26), Choreographer (16) : valeurs de mémoire, pages non lues.
- Coût des transitions (ChangeBounds, MaterialContainerTransform, capture bitmap) : aucun chiffre dans la doc.
- Présence du capteur de lumière, proximité, vibreur sur MT8167 : à tester sur la tablette.
- Latence réelle de SoundPool : aucune valeur dans la doc.
- Effet du re-layout et du cache de glyphes avec `setFontVariationSettings` : non documenté.
- `dumpsys gfxinfo framestats` : commande non confirmée par la page lue.

## Sources lues

- Hardware acceleration et layers : https://developer.android.com/develop/ui/views/graphics/hardware-accel
- ViewPropertyAnimator (source AOSP android-10.0.0_r47) : https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-10.0.0_r47/core/java/android/view/ViewPropertyAnimator.java
- ViewAnimationUtils (source AOSP android-10.0.0_r47) : https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-10.0.0_r47/core/java/android/view/ViewAnimationUtils.java
- RevealAnimator (source AOSP android-10.0.0_r47) : https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-10.0.0_r47/core/java/android/animation/RevealAnimator.java
- AnimatedVectorDrawable (source AOSP android-10.0.0_r47) : https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-10.0.0_r47/graphics/java/android/graphics/drawable/AnimatedVectorDrawable.java
- Animation drawable (règle pathData) : https://developer.android.com/develop/ui/views/animations/drawable-animation
- Types d'animations : https://developer.android.com/develop/ui/views/animations/overview (page Compose mise en avant, contenu partiel)
- MaterialContainerTransform : https://github.com/material-components/material-components-android/blob/master/docs/theming/Motion.md ; https://raw.githubusercontent.com/material-components/material-components-android/master/lib/java/com/google/android/material/transition/platform/MaterialContainerTransform.java
- Transitions framework : https://developer.android.com/develop/ui/views/animations/transitions
- graphics-shapes : https://developer.android.com/jetpack/androidx/releases/graphics
- Lottie : https://raw.githubusercontent.com/airbnb/lottie-android/master/README.md ; https://raw.githubusercontent.com/airbnb/lottie-android/master/lottie/src/main/java/com/airbnb/lottie/RenderMode.java
- Rive : https://rive.app/docs/runtimes/android/android
- Paint (source AOSP android-10.0.0_r47) : https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-10.0.0_r47/graphics/java/android/graphics/Paint.java
- SoundPool (source AOSP android-10.0.0_r47) : https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-10.0.0_r47/media/java/android/media/SoundPool.java
- Haptique : https://developer.android.com/develop/ui/views/haptics/haptics-principles
- Capteurs : https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview
- JankStats : https://developer.android.com/topic/performance/jankstats
- Profile GPU : https://developer.android.com/topic/performance/rendering/profile-gpu
- Tracing : https://developer.android.com/topic/performance/tracing
- Baseline Profiles : https://developer.android.com/topic/performance/baselineprofiles/overview
