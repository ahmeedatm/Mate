# Recherche UI/UX : outils, directions, XML ou Compose

Recherche du 26/10/2026. But : une UI de produit fini, épurée, animée sans saccade sur la tablette 4.2
(MT8167, 4 × Cortex-A35 1,3 GHz, GPU PowerVR GE8300, 2-3 Go, Android 10, 1280×800).

## Recommandation

1. **Rester en vues XML.** Compose ajoute ~800 Ko à l'APK et un démarrage plus lent sans gain prouvé
   sur ce matériel ; nos animations tiennent avec `ViewPropertyAnimator` (déjà dans `ui/Look.kt`).
2. **Une DA en jetons** ([`da/README.md`](da/README.md), aujourd'hui Console douce) : tokens posés dans
   `palettes.xml` avec les outils de Material 3. Les attributs `?attr/c_…`
   existent déjà : on change des valeurs, pas l'architecture.
3. **Installer peu** : fait le 27/10/2026, voir la fin du document.

## Inventaire de l'existant (déjà installé)

| Outil | État | Utile ici pour |
|---|---|---|
| ui-ux-pro-max 2.11.0 (+ design, design-system, ui-styling, brand, banner-design, slides) | activé | règles UX, palettes, polices, fichier de règles Jetpack Compose. Sortie générique sur ce sujet (propose Orbitron + vert OLED) : garde-fou, pas source de style |
| visual-design-system (skill perso) | actif | maquettes HTML rapides (pages web du backend) |
| superpowers, context-engineering | activés | méthode, pas de design |
| everything-claude-code 1.7.0 | installé, **désactivé** | `frontend-patterns`, `liquid-glass-design` (iOS) : rien de propre à Android |
| context7 | installé, **désactivé** | doc à jour de Material Components / AndroidX |
| MCP Figma (claude.ai) | connecté | si une maquette Figma est faite |
| MCP Playwright | connecté | captures des pages web du backend |
| MCP `tablette` (projet) | échec au démarrage (`backend/node_modules` absent dans ce worktree) | pilotage de la tablette |
| adb + émulateurs `companion_api29`, `companion_test_api29` | présents | captures `screencap`, mesure `gfxinfo` |

## Outils trouvés (comparatif)

Dates = dernier push GitHub lu le 26/10/2026 via l'API.

| Outil | Quoi | Usage ici | Coût | Qualité / maintenance | Risque | Verdict |
|---|---|---|---|---|---|---|
| [android/skills](https://github.com/android/skills) (Google, Apache-2.0) | ~35 skills officiels Android | `android-profiler` (traces Perfetto, jank, démarrage), `r8-analyzer`, `edge-to-edge`, `agp-9-upgrade`, `migrate-xml-views-to-jetpack-compose` si un jour Compose | descriptions chargées en contexte (~35 lignes) | 9,8k ★, push 26/10/2026, skills datés 23/10/2026 | faible (markdown, Google) | **installer** |
| [frontend-design](https://github.com/anthropics/claude-plugins-official/tree/main/plugins/frontend-design) (Anthropic) | skill de direction esthétique web (~1 500 mots) | page locale, `/camera`, retour OAuth, page de l'installeur | 1 skill | marketplace officiel, push 23/10/2026 | nul (markdown) | **installer** |
| [ui-ux-pro-max](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill) 2.15.0 | mise à jour de l'existant | idem existant | aucun | 141k ★, release 13/08/2026, push 26/10/2026 | scripts Python locaux, déjà acceptés | **mettre à jour** |
| context7 (officiel) | doc à jour par MCP | API Material Components, dynamicanimation | 1 MCP distant | officiel | faible | **réactiver** |
| [material-3-skill](https://github.com/hamen/material-3-skill) | Material 3 / Expressive | Compose seulement | 1 skill | 1,5k ★, push 15/07/2026 | dossier `bin/` non inspecté | non (XML) |
| [compose-skill](https://github.com/aldefy/compose-skill) | expert Compose | Compose seulement | 1 skill | 597 ★, push 23/07/2026 | faible | non (XML) |
| [mobile-mcp](https://github.com/mobile-next/mobile-mcp) | MCP tap/swipe/capture Android et iOS | pilotage de l'émulateur | `npx` à chaque session | 10k ★, push 26/10/2026 | exécute du code npm (épingler la version) | non : adb + `screencap` suffisent, MCP `tablette` du projet |
| [android-mcp](https://github.com/CursorTouch/android-mcp) | alternative Python | idem | `uv` + Python 3.13 | 906 ★, push 25/10/2026 | moyen | non |
| kotlin-lsp (officiel) | serveur de langage Kotlin | navigation dans le code | binaire LSP | officiel | faible | hors sujet UI, optionnel |
| superdesign (officiel, tiers) | canevas de design en ligne | maquettes | compte externe | — | envoie le code au service | non |

Doc d'installation : [plugins](https://code.claude.com/docs/en/plugins/install.md) (portées `user` → `~/.claude/settings.json`, `project` → `.claude/settings.json` versionné, `local` → `.claude/settings.local.json`) et [sécurité des plugins](https://code.claude.com/docs/en/plugins/security.md).

## Direction artistique

Direction actuelle, **Console douce** : [`da/README.md`](da/README.md). Elle s'appuie sur Material Design 3
(couleur HCT, composants, ressorts) et les règles de finesse de l'Apple HIG.

Trois premières pistes (Console, Signal, Veille douce) ont été écartées le 26/10/2026. Elles
faisaient « générées » : couleurs et polices choisies à l'œil, pas de design system derrière.

## XML ou Compose

**Avis : rester en vues XML.**

- **Taille** : Sunflower (R8 complet) passe de 2 252 Ko en vues à 3 034 Ko en vues + Compose, 2 966 Ko en Compose seul ([compare-metrics](https://developer.android.com/develop/ui/compose/migrate/compare-metrics)). L'appli vise un APK léger, mis à jour en silence.
- **Démarrage** : Compose dépend des Baseline Profiles (⚠️ gain annoncé entre 15 % et 30 % selon les sources : +15 % sourcé pour AGP 8.2, le 30 % n'a pas été retrouvé). Installé par adb ou par notre `Updater`, l'APK n'est compilé qu'au dexopt en arrière-plan : les premiers lancements sur A35 seraient lents. Aucune mesure publique Compose vs vues sur ce bas de gamme n'a été trouvée.
- **Animation** : les deux passent par le RenderThread pour alpha/translation/échelle. Les ressorts existent en vues (`SpringAnimation`, et thème `motionSpring*` de Material Components 1.13.0, [Motion.md](https://raw.githubusercontent.com/material-components/material-components-android/master/docs/theming/Motion.md)). Material 3 Expressive complet (formes morphées, composants) n'existe qu'en Compose : c'est le seul vrai manque, et il est coûteux sur ce GPU.
- **Migration** : tout l'existant est en vues (Fragments, `WidgetGrid`, `ScreenMap`, `KeyEditor`). Un `ComposeView` incrémental est possible, mais paie toute la lib dès le premier écran.
- **À revoir si** un nouvel écran complexe arrive (éditeur, liste riche) et qu'une mesure sur la vraie tablette montre un démarrage acceptable.

## Animer à 60 i/s sur la tablette

| Technique | Coût | API 24 / 29 | Usage |
|---|---|---|---|
| `ViewPropertyAnimator` (alpha, translation, échelle, rotation) | faible, RenderThread | oui / oui | défaut (déjà dans `Look.kt`) |
| Calque matériel pendant l'anim (`withLayer()`) | mémoire GPU le temps de l'anim | oui / oui | groupes de vues qui bougent ensemble |
| `SpringAnimation` (androidx.dynamicanimation) | faible | oui / oui | appui, arrivée (direction C) |
| `AnimatedVectorDrawable` | RenderThread dès API 25 | oui / oui | icônes animées, avec parcimonie |
| Dégradés, 9-patch, `GradientDrawable` | très faible | oui / oui | halos, reliefs (remplace ombres et flou) |
| `elevation` / ombres | coûteux sur ce GPU | oui / oui | éviter |
| Animer largeur/hauteur, `requestLayout` par image | relayout CPU à chaque image | oui / oui | interdit |
| `TransitionManager`, `MotionLayout` | relayout, lourd | oui / oui | éviter |
| `RenderEffect` (flou, glow) | GPU | API 31+ | indisponible |

Mesure : `adb shell dumpsys gfxinfo app.mate reset`, faire le geste, puis `dumpsys gfxinfo app.mate` (images saccadées, 90e centile) ; [doc dumpsys](https://developer.android.com/tools/dumpsys), [rendu lent](https://developer.android.com/topic/performance/vitals/render). L'émulateur rend sur le GPU du Mac : il valide l'absence de relayout, pas les 60 i/s. Seule la vraie tablette tranche ; le skill `android-profiler` de Google lit les traces Perfetto.

## Polices sur Android

Polices variables : les axes ne sont pilotables qu'à partir d'API 26, et les fichiers sont lourds (Inter 877 Ko, Doto 457 Ko). Embarquer dans `res/font/` des instances **statiques** sous-ensemblées au latin (`pyftsubset`), 20 à 40 Ko par graisse. Pas de polices téléchargeables : elles passent par les services Google, absents en kiosque.

## Installé (27/10/2026)

| Outil | Portée | Pourquoi |
|---|---|---|
| `frontend-design@claude-plugins-official` | utilisateur (profil Orca et `~/.claude`) | pages web, tous projets |
| `ui-ux-pro-max` 2.11.0 → 2.15.0 | utilisateur | garde-fou UX |
| context7 (réactivé) | utilisateur | doc à jour des API |
| `android-skills@android-skills` 1.0.18 | **local** à ce dépôt (`.claude/settings.local.json`, non versionné) | ~6 300 tokens dans chaque session : réservé aux projets Android |
| skill `direction-artistique` | `~/.claude/skills` (tous projets) | la démarche de cette DA, outillée : charte, palette M3, polices, icônes, captures, film |

Non retenu : skills Compose (pas de Compose), mobile-mcp et android-mcp (adb suffit), superdesign (envoie le code dehors).
