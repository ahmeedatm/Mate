# Deck V2 : barre fixe et profils — plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remplacer le menu caché par une barre fixe à gauche qui mène aux profils Accueil, Deck et Réunion (ce dernier seulement pendant un appel), puis retirer l'ancien mode Deck sans perte.

**Architecture:** Les profils d'Ahmed (`HomeLayout`, pages de widgets) reçoivent des rôles (Accueil, Deck, Réunion). Une logique pure (`RailModel`) dit quoi afficher dans la barre ; une vue (`ProfileRail`) remplace `MenuPanel` et la barre de profils de `HomeFragment`. Le backend pousse un ordre `meeting` sur le canal `/tablet` ; la tablette suit l'appel avec `CallSession` (logique pure) et bascule sur le profil Réunion.

**Tech Stack:** Kotlin, vues XML, `androidx.dynamicanimation` (déjà là), JUnit (`app/src/test`, `FakeContext`) ; backend Node 22 / TypeScript / Fastify, `node --test` avec `test/fake-os.ts`.

**Spec:** `docs/superpowers/specs/2026-10-29-deck-v2-barre-profils-design.md` (lire avant toute tâche).

## Global Constraints

- Cible : Android 10 (API 29), `minSdk 24` ; aucune nouvelle dépendance ; vues XML, pas de Compose.
- Matériel : 1280 × 800 à 160 dpi (1 dp = 1 px), 55 Hz = 18,2 ms par image ; vérifier sur la vraie tablette (`adb -s 77332110160001681`).
- Couleurs en `?attr/c_…` / `themeColor(context, R.attr.c_…)`, polices par rôle `c_font_*`, jamais en dur ; rouge (`c_accent`) = « maintenant » seulement.
- Animations de navigation : ressorts de `ui/Look.kt` avec `always = true` (le kiosque met `animator_duration_scale` à 0 et fige tout `ViewPropertyAnimator`) ; translation, échelle, alpha seulement ; pas de boucle.
- Textes de l'UI en français dans `res/values/strings.xml` ; erreurs via `errorText(e)`.
- Barre : 96 dp de large ; pastille de sélection 56 × 32 dp, fond `c_surface_raised` ; repos = opacité 0,4, libellés effacés.
- Transition d'écran : sortie ~150 ms (alpha), entrée 24 dp dans le sens du déplacement dans la barre + alpha (~300 ms) ; appui 0,94.
- Nouveau comportement backend = un test dans `backend/test/` ; logique pure Android = un test dans `app/src/test/`.
- Avant de pousser : `cd backend && npm run format && npm run lint && npm run coverage` ; `./gradlew ktlintFormat ktlintCheck testDebugUnitTest lintDebug` (avec `GRADLE_USER_HOME=~/.gradle-companion`, `JAVA_HOME=/opt/homebrew/opt/openjdk@21`).
- Branche `feat/deck-v2-barre` depuis `main`, une MR par groupe de tâches ; **commits seulement avec l'accord de l'utilisateur** (règle du projet).

## Review Focus

1. Un appel commence pendant une saisie ou en mode Modifier : l'entrée Réunion apparaît, l'écran ne bascule pas (test : Task 7).
2. L'ordinateur change ou la connexion tombe pendant un appel : Réunion ne reste pas affichée indéfiniment (test : Task 7).
3. Profil Deck supprimé : le mode `deck` (canal, voix, extension Claude) mène à l'Accueil, sans plantage (test : Task 1).
4. Plus de profils que la barre n'en tient : pas de débordement, entrée « Plus » (test : Task 2).
5. Disposition enregistrée avant les rôles (v1 sans noms, v2 avec `game`) : la migration ajoute Deck juste après l'Accueil sans décaler à tort `gameProfile` (test : Task 1).

---

### Task 1 : Rôles des profils et migration

**Files:**
- Modify: `app/src/main/kotlin/com/unowhy/unomate/ui/home/WidgetModel.kt` (`HomeLayout`, `Template`)
- Modify: `app/src/main/res/values/strings.xml` (`profile_home`, `profile_deck`, `profile_call`, descriptions des modèles)
- Test: `app/src/test/kotlin/com/unowhy/unomate/ui/home/ProfilesTest.kt`

**Interfaces:**
- Produces : dans `HomeLayout`, `var homeProfile: Int = 0`, `var deckProfile: Int = -1`, `var callProfile: Int = -1` (JSON `"home"`, `"deck"`, `"call"`, comme `"game"`) ; `fun ensureRoles(deckName: String, callName: String): Boolean` (vrai si la disposition a changé) ; `fun canRemove(index: Int): Boolean` (faux pour Accueil et Réunion) ; `fun profilesInOrder(inCall: Boolean): List<Int>` (pages dans l'ordre, sans `callProfile` hors appel) ; `fun deckTarget(): Int` (= `deckProfile`, ou `homeProfile` s'il n'y en a pas). Modèles `Template.DECK_CADRAN` et `Template.CALL_CADRAN` (`cadran = true`).
- `moveProfile` / `removeProfile` tiennent à jour les quatre index (`game`, `home`, `deck`, `call`) comme ils le font pour `gameProfile`.

- [ ] **Step 1 : tests qui échouent** (dans `ProfilesTest`)
  - `ensureRoles sur une disposition d'un seul profil ajoute Deck en 2e et Reunion en dernier` : `names` = `[null, "Deck", "Réunion"]`, `deckProfile == 1`, `callProfile == 2`, renvoie `true` ; second appel renvoie `false`.
  - `ensureRoles decale le profil de jeu place apres l'Accueil` : 2 profils, `gameProfile = 1` → après, `gameProfile == 2`, widgets de l'ancienne page 1 sur la page 2.
  - `roles survivent a l'enregistrement` (save/load par `FakeContext`, clé `home_layout_cadran`).
  - `Accueil et Reunion ne se suppriment pas` : `canRemove(homeProfile)` et `canRemove(callProfile)` faux ; `removeProfile(callProfile)` renvoie `false`.
  - `supprimer le Deck renvoie deckTarget vers l'Accueil` : après `removeProfile(deckProfile)`, `deckProfile == -1`, `deckTarget() == homeProfile`.
  - `profilesInOrder cache Reunion hors appel` : `profilesInOrder(false)` sans `callProfile`, `profilesInOrder(true)` avec, en dernier.
  - `deplacer un profil suit les roles`.
- [ ] **Step 2 :** `./gradlew testDebugUnitTest --tests '*ProfilesTest*'` → échec de compilation (membres absents).
- [ ] **Step 3 : implémenter** dans `HomeLayout` et `Template`. `ensureRoles` insère Deck à l'index `homeProfile + 1` (pages suivantes décalées, widgets compris) avec `Template.DECK_CADRAN.widgets(page)`, et ajoute Réunion en fin avec `CALL_CADRAN`. Contenu des modèles, sur la grille 4 × 4 Cadran (`RowProfile`) :
  - `DECK_CADRAN` : `APPS` en haut sur toute la largeur (ajouter `WidgetSize.W` à `APPS` si besoin), `KEYS` en `L` en bas à gauche, `MEDIA` en `M` et `AUDIO` en `M` à droite.
  - `CALL_CADRAN` : `CALL` (Task 8) en `XL` en haut, `NEXT` en `M`, `AUDIO` en `M` en bas.

  Tant que la Task 8 n'est pas faite, `CALL_CADRAN` prend `MEETING`. `Template.defaultFor(cadran = true)` construit les trois profils (Accueil = `WORK_CADRAN`).
- [ ] **Step 4 :** relancer → PASS ; `./gradlew ktlintCheck`.
- [ ] **Step 5 :** commit (si autorisé) `Profils : rôles Accueil, Deck, Réunion et migration`.

### Task 2 : RailModel, ce que la barre affiche

**Files:**
- Create: `app/src/main/kotlin/com/unowhy/unomate/ui/RailModel.kt`
- Test: `app/src/test/kotlin/com/unowhy/unomate/ui/RailModelTest.kt`

**Interfaces:**
- Consumes : `HomeLayout.profilesInOrder(inCall)`, `HomeLayout.callProfile` (Task 1).
- Produces :
  ```kotlin
  sealed interface RailEntry {
      data class Profile(val page: Int, val isCall: Boolean) : RailEntry
      data object More : RailEntry          // ouvre ProfilesDialog
      data class Tool(val mode: Mode) : RailEntry // FOCUS, COMPUTER, CAMERA
  }
  object RailModel {
      const val MAX_PROFILES = 5
      fun entries(layout: HomeLayout, inCall: Boolean): List<RailEntry>
      fun selected(entries: List<RailEntry>, mode: Mode, page: Int): Int // index dans entries, -1 si aucun
  }
  ```
  Réglages, Parler, notifications et ordinateur ne sont pas des entrées : ils ont leur place fixe dans la vue.

- [ ] **Step 1 : tests qui échouent**
  - `trois profils hors appel` : `[Profile(0), Profile(1), Tool(FOCUS), Tool(COMPUTER), Tool(CAMERA)]` (Réunion absente).
  - `en appel Reunion suit les profils, marquee isCall`.
  - `au dela de MAX_PROFILES une entree More remplace les derniers` : 7 profils → 4 `Profile` + `More`.
  - `en appel avec beaucoup de profils Reunion reste visible` : Réunion garde sa place, `More` prend la précédente.
  - `selected` : mode `HOME` + page 1 → index de `Profile(1)` ; mode `FOCUS` → index de `Tool(FOCUS)`.
- [ ] **Step 2 :** `--tests '*RailModelTest*'` → FAIL.
- [ ] **Step 3 :** implémenter `RailModel`.
- [ ] **Step 4 :** PASS.
- [ ] **Step 5 :** commit `Barre : modèle des entrées`.

### Task 3 : ProfileRail remplace le menu caché et la barre de profils

**Files:**
- Create: `app/src/main/kotlin/com/unowhy/unomate/ui/ProfileRail.kt`, `app/src/main/res/layout/view_profile_rail.xml`
- Modify: `app/src/main/res/layout/activity_main.xml` (barre à gauche du conteneur des écrans, retrait de `menu_host`), `MainActivity.kt` (lignes ~120-135 : `nav = ProfileRail(...)` pour tous les thèmes ; retrait de `menu`), `ui/HomeFragment.kt` + `res/layout/fragment_home.xml` (retrait de la barre de profils `home_profile_*` ; `showProfile(index)` reste public), `ui/Navigation.kt` (ajouter `fun showProfiles(layout: HomeLayout, page: Int, inCall: Boolean)`)
- Delete: `ui/MenuPanel.kt`, `ui/DrawerGesture.kt`, `ui/Rail.kt`, `res/layout/view_menu_panel.xml`, `item_menu.xml`, et les tests de `DrawerGesture` s'il y en a
- Test: tests instrumentés touchés par le menu (`app/src/androidTest/…`), mis à jour

**Interfaces:**
- Consumes : `RailModel.entries/selected` (Task 2), `HomeLayout` (Task 1), `Navigation` existant.
- Produces : `class ProfileRail(activity: Activity, onMode: (Mode) -> Unit, onProfile: (Int) -> Unit, onProfiles: () -> Unit) : Navigation`. `onProfiles` ouvre `ProfilesDialog` (appui long sur une entrée de profil, ou `More`). `HomeFragment` appelle `(activity as MainActivity).onProfileShown(page)` à chaque changement de profil, balayage compris ; la barre suit.

- [ ] **Step 1 :** vue de la barre selon la spec, section La barre : Parler (56 dp, appui long = commande écrite, comme `VoiceControl` aujourd'hui), entrées de `RailModel`, filet, pied (cloche des notifications déplacée depuis l'Accueil, ordinateur avec point d'état, Réglages). Focus affiche `focusLabel()` en `c_accent` quand `focusRemaining() != null`. Pastille des tâches sur l'Accueil (`setPendingTasks`).
- [ ] **Step 2 :** pastille de sélection unique qui glisse en `translationY` par `Look.springTo(..., SPATIAL_DEFAULT)` (avec `always = true` : ajouter le paramètre à `springTo` s'il manque).
- [ ] **Step 3 :** câbler `MainActivity` : `show(Mode.HOME)` + `HomeFragment.showProfile(page)` pour une entrée de profil ; `nav.select` met la pastille à jour. Le balayage de `HomeFragment` parcourt `layout.profilesInOrder(inCall)` : Réunion en est absente hors appel. Dans `ProfilesDialog`, l'entrée Réunion n'a ni « Supprimer » ni « Monter », et son « Modifier » l'affiche hors appel avec un bandeau `call_profile_preview` (« Aperçu : ce profil s'affiche pendant un appel »).
- [ ] **Step 4 :** `./gradlew assembleDebug` puis installer sur la tablette et vérifier à la main :
  - les trois profils sont à un toucher ;
  - le balayage met la barre à jour ;
  - l'appui long ouvre la liste des profils ;
  - le Focus lancé s'affiche en rouge.

  Capture : `adb exec-out screencap -p`.
- [ ] **Step 5 :** `./gradlew ktlintCheck testDebugUnitTest lintDebug` et `scripts/test-android.sh` (WelcomeTest, HomeTest, DeckTest) → verts ; corriger les tests qui ouvraient `menu_handle`.
- [ ] **Step 6 :** commit `Barre fixe à gauche : remplace le menu caché et la barre de profils`.

### Task 4 : Transitions d'écran, appui et repos

**Files:**
- Modify: `ui/Look.kt` (`fun sharedAxis(out: View?, into: View, fromBelow: Boolean)`), `MainActivity.show()` (~ligne 544), `ui/HomeFragment.kt` (changement de profil), `ui/ProfileRail.kt` (repos)
- Test: `app/src/test/kotlin/com/unowhy/unomate/ui/RailModelTest.kt` (sens du déplacement)

**Interfaces:**
- Produces : `RailModel.direction(from: Int, to: Int): Int` (+1 si `to` est plus bas dans la barre, -1 sinon, 0 si égal) ; `ProfileRail.setResting(resting: Boolean)` appelé par le même déclencheur que le repos de l'Accueil.

- [ ] **Step 1 : test** `direction` : 0 → 2 = +1, 2 → 0 = -1, 1 → 1 = 0.
- [ ] **Step 2 :** FAIL, puis implémenter `direction`, PASS.
- [ ] **Step 3 :** implémenter `Look.sharedAxis`. Sortie en alpha 1 → 0 sur un ressort `EFFECTS_FAST`. Entrée en `translationY` de ±24 dp → 0 (`SPATIAL_DEFAULT`) et alpha 0 → 1 (`EFFECTS_DEFAULT`). Tout en `always = true`, calque matériel pendant l'animation. Brancher sur `MainActivity.show()` et sur le changement de profil.
- [ ] **Step 4 :** repos : alpha de la barre 1 → 0,4 et libellés 1 → 0 (`EFFECTS_DEFAULT`), retour au premier toucher. Mode Modifier de `WidgetGrid` : les widgets passent à l'échelle 0,94 (`SPATIAL_DEFAULT`), les poignées apparaissent en fondu, sans tremblement ; retour à 1 en sortant.
- [ ] **Step 5 :** mesurer sur la tablette : `adb shell dumpsys gfxinfo com.unowhy.companion reset`, 10 changements d'écran, puis `dumpsys gfxinfo com.unowhy.companion`. Attendu : 90e centile ≤ 18 ms. Noter le chiffre dans la MR.
- [ ] **Step 6 :** commit `Navigation : transitions par la barre et repos`.

### Task 5 : Backend, commandes `mute-mic` et `dnd`, champ `icon`, deck Bureau

**Files:**
- Modify: `backend/src/screens.ts:108` (`SystemCommand`), `src/os.ts` (`systemCommand`, ~ligne 417), `src/perso.ts:58`, `src/mcp.ts:271`, `src/deck.ts` (`glyph` → plus `icon?: string`, ~lignes 55-67 et 246), `config/deck.example.json`, `config/deck.example.windows.json`, `test/fake-os.ts`
- Test: `backend/test/deck.test.ts`, `backend/test/deck-api.test.ts`, `backend/qa/sanity.ts` (contrôle du deck de départ : icônes présentes)

**Interfaces:**
- Produces :
  - `SystemCommand` gagne `"mute-mic" | "dnd"` ;
  - les tuiles de `GET /deck` portent `icon?: string` ;
  - jeu d'icônes accepté : `lock`, `screenshot`, `dnd`, `mic_off`, `videocam_off`, `clipboard`, `windows`, `dark_mode`, `bedtime`, `play_pause`, `skip_next`, `skip_previous`, `volume_up`, `volume_off`, `present`, `hand`, `call_end`, `keyboard`, `link`, `apps`. Un nom inconnu est refusé à l'écriture d'une touche perso et ignoré à la lecture de `deck.json`.

- [ ] **Step 1 : tests qui échouent**
  - `system mute-mic bascule la sourdine du micro` : faux pilotage `micMuted: false` → après l'action, `setAudio` appelé avec `{ micMuted: true }`.
  - `system dnd bascule Ne pas deranger` : appelle le pilotage DND avec l'inverse de l'état lu.
  - `GET /deck renvoie icon` pour une tuile qui en a une.
  - `une touche perso refuse une icone inconnue` (400).
- [ ] **Step 2 :** `cd backend && node --import tsx --test test/deck.test.ts test/deck-api.test.ts` → FAIL.
- [ ] **Step 3 :** implémenter. `mute-mic` passe par `getAudio()` / `setAudio({ micMuted })`, `dnd` par le pilotage de `src/dnd.ts`. Ajouter les exports manquants à `test/fake-os.ts`. Ajouter les tuiles Bureau au deck de départ (Verrouiller `lock`, Capture `screenshot`, Ne pas déranger `dnd`, Couper le micro `mic_off`, Mode sombre `dark_mode`, Veille écran `bedtime`), avec `icon` et sans `glyph`.
- [ ] **Step 4 :** `npm run format && npm run lint && npm run coverage` → vert.
- [ ] **Step 5 :** commit `Deck : commandes micro et Ne pas déranger, icônes des tuiles`.

### Task 6 : Tablette, icônes des touches et touches « tablette »

**Files:**
- Create: `app/src/main/kotlin/com/unowhy/unomate/ui/home/TileIcons.kt`, les vecteurs `res/drawable/ic_tile_*.xml` qui manquent (Material Symbols Rounded, Apache 2.0)
- Modify: `net/DeckApi.kt:12,72` (`DeckTile.icon: String?`), `ui/home/TileGridView.kt` (icône prioritaire sur `glyph`), `ui/home/KeysWidget.kt`
- Test: `app/src/test/kotlin/com/unowhy/unomate/ui/home/TileIconsTest.kt`, `app/src/test/kotlin/com/unowhy/unomate/net/…` (parsing de `icon`)

**Interfaces:**
- Consumes : champ `icon` (Task 5).
- Produces : `object TileIcons { fun drawable(name: String?): Int? }` (null si inconnu) ; touches « tablette » `Presse-papiers` et `Fenêtres`, qui ouvrent `ClipboardView` et le widget `OPEN` en grand (`DeckOverlays`), sans appel à l'ordinateur.

- [ ] **Step 1 : tests** : `drawable("lock")` non nul ; `drawable("inconnu")` et `drawable(null)` nuls ; chaque nom de la liste de la Task 5 a un vecteur ; une tuile JSON avec `"icon":"dnd"` donne `DeckTile.icon == "dnd"`.
- [ ] **Step 2 :** FAIL → implémenter → PASS.
- [ ] **Step 3 :** vérifier sur la tablette que le widget Touches du profil Deck n'affiche plus d'emoji pour les touches Bureau.
- [ ] **Step 4 :** commit `Touches : icônes Material et touches de la tablette`.

### Task 7 : Ordre `meeting` et suivi de l'appel

**Files:**
- Create: `backend/src/meeting-watch.ts`, `backend/test/meeting-watch.test.ts`, `app/src/main/kotlin/com/unowhy/unomate/ui/CallSession.kt`, `app/src/test/kotlin/com/unowhy/unomate/ui/CallSessionTest.kt`
- Modify: `backend/src/tablet.ts:20-32` (type), `src/routes/tablet.ts` (schéma), `src/server.ts:152` (démarrage), `src/mcp.ts` (aucun outil : ordre interne), `remote/RemoteChannel.kt:111` (branche `"meeting"`), `MainActivity.kt` (`showMeetingControls` → `CallSession`, `onComputerChanged` → `end`), `ui/ProfileRail.kt` (apparition de l'entrée)

**Interfaces:**
- Backend :
  ```ts
  export type MeetingEvent = { state: "start" | "end"; app: string };
  export function startMeetingWatch(
    notify: (e: MeetingEvent) => void,
    deps?: { intervalMs?: number; connected?: () => boolean; liveApps?: () => Promise<string[]> },
  ): () => void; // arrête la veille
  ```
  Par défaut : `intervalMs` 3000 ; `connected` = `tabletStatus().connected` ; `liveApps` = même calcul que `apps` de `GET /deck/live` (à extraire dans une fonction partagée de `routes/deck.ts` ou `deck.ts`). `TabletCommand` gagne `{ type: "meeting"; state: "start" | "end"; app: string }`.
- Tablette :
  ```kotlin
  class CallSession(private val now: () -> Long) {
      fun start(app: String, endAtMs: Long? = null) // endAtMs : fin de l'événement pour « Rejoindre » sans détection
      fun end()
      fun tick(): Boolean                      // vrai si l'appel vient de finir par endAtMs
      val active: Boolean
      fun shouldSwitch(typing: Boolean, fullScreen: Boolean): Boolean
      fun returnTo(currentPage: Int, callPage: Int, previousPage: Int): Int? // page où revenir, null = rester
  }
  ```

- [ ] **Step 1 : tests backend**
  - `start puis end sur changement de liveApps, une seule fois chacun` ;
  - `rien n'est envoye sans tablette connectee` ;
  - `la veille s'arrete` (la fonction renvoyée coupe le minuteur) ;
  - `le schema de /tablet/command accepte meeting`.

  Utiliser des `deps` injectés, pas de vrai minuteur long.
- [ ] **Step 2 : tests tablette**
  - `shouldSwitch faux pendant une saisie ou en plein ecran` ;
  - `tick finit l'appel a endAtMs` ;
  - `returnTo revient au profil d'avant si l'utilisateur est reste sur Reunion` ;
  - `returnTo null s'il est parti ailleurs` ;
  - `end apres changement d'ordinateur rend active faux`.
- [ ] **Step 3 :** FAIL des deux côtés → implémenter.
  - « Rejoindre » sur la tablette appelle `start(app, finDeLEvenement)`.
  - L'ordre `meeting` appelle `start` ou `end`.
  - `MainActivity` affiche l'entrée Réunion (`ProfileRail` : apparition en échelle 0,6 → 1 et alpha, ressorts `always`), puis bascule si `shouldSwitch`.
  - À la fin, `returnTo`, puis repli de l'entrée.
- [ ] **Step 4 :** `npm run lint && npm run coverage` et `./gradlew testDebugUnitTest` → vert. Sur Mac : lancer un appel Teams ou Discord, vérifier l'arrivée et le départ de Réunion sur la tablette.
- [ ] **Step 5 :** commit `Réunion : l'ordinateur prévient la tablette, profil d'appel automatique`.

### Task 8 : Widget d'appel générique `CALL`

**Files:**
- Create: `app/src/main/kotlin/com/unowhy/unomate/ui/home/GenericCallWidget.kt`
- Modify: `ui/home/WidgetModel.kt` (`WidgetType.CALL("call", …, listOf(WidgetSize.XL, WidgetSize.L), true)`), `ui/home/HomeWidgets.kt` (fabrique), `ui/home/CallWidget.kt` (logique partagée si besoin), `Template.CALL_CADRAN` (`CALL` en `XL`), `strings.xml`
- Test: `app/src/test/kotlin/com/unowhy/unomate/ui/home/WidgetModelTest.kt` (type et tailles), `CallSessionTest` (choix de l'appli affichée)

**Interfaces:**
- Consumes : `CallSession.active` et l'appli en cours (Task 7), pages `DeckPage` avec `app` / `meeting` (comme `CallWidget`).
- Produces : `CallSession.Companion.pageFor(app: String, pages: List<DeckPage>): DeckPage?` (fonction pure) ; `CALL` affiche la page de l'appli en cours (Teams = page `meeting: true`, Discord = page `app: "Discord"`). Sans page (Meet, Zoom, Webex) : touches `mute-mic`, son de l'ordinateur, « Ouvrir l'appli ». Hors appel (aperçu) : état vide avec le texte `call_widget_preview`.

- [ ] **Step 1 : tests** : `WidgetType.of("call") == CALL` ; tailles `XL` et `L` ; `CallSession.pageFor(app, pages)` renvoie la page Teams, la page Discord, ou null pour `"Zoom"`.
- [ ] **Step 2 :** FAIL → implémenter → PASS.
- [ ] **Step 3 :** sur la tablette, avec le backend de démo (`DEMO_OS=mac node --import tsx test/widgets-demo.ts`, émulateur par `10.0.2.2:3920`) : profil Réunion en appel Teams, puis en appel Discord, puis en aperçu.
- [ ] **Step 4 :** commit `Widget d'appel générique pour le profil Réunion`.

### Task 9 : Parité, puis retrait du mode Deck

**Files:**
- Modify: `Mode.kt` (retrait de `DECK`, `Mode.of("deck")` → `HOME`), `MainActivity.kt` (`showMode`, `showAgents`, `showAppControls`, `showMeetingControls` → profil Deck / Réunion), `voice/VoiceCommands.kt` + `voice/VoiceControl.kt` (« deck », pages d'appli → profil Deck), `remote/RemoteChannel.kt` (`mode: deck` → `HomeLayout.deckTarget()`), `backend/src/tablet.ts:14` (`MODES` garde `"deck"`, sens = profil Deck), docs de l'outil MCP du mode
- Delete: `ui/DeckFragment.kt`, `ui/TabStrip.kt` et les vues propres au fragment, si plus rien ne les utilise (`OpenWindowsView`, `MixerView`, etc. restent : les widgets s'en servent)
- Test: `app/src/test/kotlin/com/unowhy/unomate/ModeTest.kt`, tests de `voice/`, `DeckTest` (androidTest, réécrit contre le profil Deck)

- [ ] **Step 1 : inventaire écrit dans la MR.** Pour chaque onglet et chaque type de page de `deck.example*.json` (Apps, Ouvertes, Pavé, Son, Agents, Média, Raccourcis, Perso, Discord, Réunion, Word, Excel, PowerPoint, Outlook), noter le widget qui le remplace et le vérifier à l'écran. **Un manque arrête cette tâche** : le signaler et le traiter avant de continuer.
- [ ] **Step 2 : tests**
  - `Mode.of("deck")` → `HOME` ;
  - l'ordre `mode: deck` affiche la page `deckTarget()` ;
  - la voix « deck » mène au profil Deck ;
  - la voix d'une page d'appli (« Discord ») mène au profil qui contient le widget correspondant, sinon au Deck.
- [ ] **Step 3 :** FAIL → retirer `DeckFragment` et `Mode.DECK`, rediriger les appels → PASS.
- [ ] **Step 4 :** `./gradlew ktlintCheck testDebugUnitTest lintDebug`, `scripts/test-android.sh`, `cd backend && npm run lint && npm run coverage` → vert.
- [ ] **Step 5 :** commit `Deck V2 : le mode Deck devient le profil Deck`.

### Task 10 : Documentation

**Files:**
- Modify: `CLAUDE.md` (navigation, profils et rôles, plus de `DeckFragment` ni de `MenuPanel`, ordre `meeting`, `CallSession`, `CALL`, `icon`), `docs/etat-du-projet.md` (Deck V2 : lots faits, à vérifier sur la tablette), `docs/ui-ux/da/README.md` (principe 5 : la barre est toujours là et s'efface au repos ; ligne « Menu qui suit le doigt » remplacée), `docs/architecture.md`, `README.md` (modes), `docs/cible-et-positionnement.md` (une ligne : cible B2B bureau, décision du 29/10)

- [ ] **Step 1 :** mettre à jour, puis `npm run format` dans `backend/` (Prettier vérifie aussi le Markdown du dépôt s'il est dans son périmètre ; sinon rien).
- [ ] **Step 2 :** commit `Docs : Deck V2, barre fixe et profils`.
