---
name: feature-android
description: À charger avant d'écrire ou de modifier du code Android de Mate (dossier `app/` : Kotlin, vues XML, ressources, tests JVM), après `nouvelle-feature`. Rappelle où lire les conventions, comment tester et comment essayer sur l'émulateur ou la tablette. Pour l'aspect d'un écran, charger aussi `ecran-design`.
---

# feature-android

Les conventions Android ne sont pas ici : elles sont dans `CLAUDE.md`, § Conventions du code. Les
lire avant d'écrire, puis chercher le détail de la fonction touchée dans `docs/architecture.md`,
§ Détail par fonction (`grep -n '^###' docs/architecture.md`). Le code voisin fait foi : reprendre
ses helpers, ses styles et ses motifs plutôt que d'en créer.

## Avant d'écrire

- Matériel cible : `docs/tablette-4.2.md` (Android 10, 2 Go de RAM, tas de 128 Mo, écran 55 Hz,
  WebView 81). Toute décision se juge contre lui : rien de lourd, pas d'allocation dans `onDraw`.
- Chercher si le helper, la vue, la chaîne ou le client réseau existe déjà.
- Écran créé ou changé : charger `ecran-design`.

## Ce que le projet impose (détail dans `CLAUDE.md`)

- Vues XML, pas de Compose ; une Activity, un Fragment par mode.
- Textes affichés dans `res/values/strings.xml`, en français avec les accents ; jamais en dur.
- Erreurs affichées par `errorText(e)`, jamais `e.message` brut.
- Pas de dépendance nouvelle sans l'accord de l'équipe : le demander avec la raison, l'écrire dans la
  PR. Si elle est accordée, régénérer `assets/licences.txt` (voir `CLAUDE.md`).
- Réseau : un client par domaine dans `net/`, jeton jamais en clair hors 127.0.0.1.
- Couleurs et polices par attributs du thème (le contrôle `.claude/hooks/controle.mjs` bloque le reste).
- Un nouvel ordre venu de l'ordinateur : maillons listés dans `CLAUDE.md` (charger aussi `feature-backend`).

## Tests

Logique pure (calcul, décision, analyse de texte, plan de mise à jour) = un test JVM dans
`app/src/test/kotlin/app/mate/`, au même package que le code, avec `FakeContext` quand il faut un
contexte (voir `PrefsTest` ou `MeetingAlertModelTest`). Test rouge d'abord, lancé et vu échouer.

```
./gradlew ktlintFormat ktlintCheck testDebugUnitTest
```

Sous Windows : `gradlew.bat` au lieu de `./gradlew`. Un seul test : `--tests '*NomDuTest'`.
Tout ce qui ne se teste pas en JVM (affichage, geste) se prouve à l'essai, ci-dessous.

## Essayer l'appli

1. `adb devices`. Rien de branché : démarrer l'émulateur `companion_api29`, ou le dire à l'humain
   qui branche la tablette. Un appareil déjà présent peut appartenir à quelqu'un d'autre : viser un
   appareil précis avec `-s <série>`, jamais `installDebug` (il installe sur tous).
2. `./gradlew assembleDebug`, puis
   `adb -s <série> install -r app/build/outputs/apk/debug/app-debug.apk`.
3. Joindre l'émulateur à un compagnon : `adb reverse tcp:3000 tcp:3000`, adresse `127.0.0.1`
   (détail : `docs/developpement.md`, § Boucle de développement).
4. Refaire les gestes de « Comment tester » de la PR. Capture : `adb exec-out screencap -p` ;
   une capture noire = écran éteint, l'appli est en pause.

Pas d'appareil : le dire dans la PR (« Testé sur » reste décoché), ne pas affirmer que ça marche.

## Fin

`./gradlew ktlintFormat` avant de pousser, puis la skill `relecture`. Les renvois de documentation
à mettre à jour (`docs/architecture.md`, § Détail par fonction, si le comportement décrit change)
font partie du changement.
