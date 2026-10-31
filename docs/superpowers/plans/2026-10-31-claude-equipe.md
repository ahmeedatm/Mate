# Claude commun de l'équipe Mate : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** un dossier `.claude/` versionné (contrôle bloquant, agents relecteurs, skills de circuit et de design) que toute l'équipe reçoit par `git pull`, un `CLAUDE.md` allégé sans perte, `config-claude/` supprimé.

**Architecture:** pièces standard de Claude Code seulement. `.claude/settings.json` porte un hook `PreToolUse` (Edit/Write/MultiEdit) qui lance un script Node sans dépendance ; le script ne regarde que les lignes ajoutées. Les skills renvoient aux sections de `CLAUDE.md` et `docs/` au lieu de les recopier ; les agents sont en lecture seule avec `model` fixé.

**Tech Stack:** Node 22 (`node:test`, pas de dépendance), Markdown (SKILL.md, agents), JSON (settings).

**Spec:** `docs/superpowers/specs/2026-10-31-claude-equipe-design.md`

## Global Constraints

- Textes en français, accents compris.
- Chaque agent fixe `model:` dans son en-tête : `sonnet` (ou `haiku` pour un agent qui ne fait que chercher) ; jamais `inherit` ni absent.
- Aucune règle, skill ou agent ne nomme un thème ou une couleur précise (« Console », « vert acide », « 3 thèmes ») : la liste des thèmes se lit dans `enum class AppTheme` de `app/src/main/kotlin/app/mate/ui/Themes.kt` au moment de vérifier.
- Une seule source par règle : une skill renvoie à la section de `CLAUDE.md` / `docs/…` qui porte la règle, elle ne la recopie pas.
- Aucune dépendance à la config perso d'un membre (`~/.claude/…`, Orca, NAS, usine).
- Pas de changement de `.github/workflows/` ; seul `.github/CODEOWNERS` change (ligne `/.claude/ @ahmeedatm`).
- Équipe active : Ahmed (`ahmeedatm`), Eliot (`EliotFreifer`), Bruno (`crimson-king-5`). Aucun autre auteur cité.
- Nommage du dépôt : branche `^(feat|fix|docs|test|ci)/[a-z0-9]+(-[a-z0-9]+)*$` (`.github/nommage.sh`), titre de PR en français sans préfixe technique, corps = `.github/pull_request_template.md`.
- Pas de réécriture du code de l'app ; les couleurs et textes en dur déjà présents ne sont pas corrigés.

## Review Focus

- Un fichier qui contient déjà une couleur en dur (`ui/BreathingView.kt:80`, `ui/RollingText.kt:70`) est modifié ailleurs : le contrôle doit laisser passer (test `laisse passer une ligne déjà présente`, tâche 1).
- Chemins Windows (`C:\…\app\src\main\res\layout\x.xml`) : le contrôle doit les reconnaître comme les chemins Mac (test `chemins Windows`, tâche 1).
- Entrée du hook illisible ou outil inconnu : le contrôle ne bloque jamais par erreur (sortie 0) (test `entrée illisible`, tâche 1).
- Texte construit par gabarit (`"${context.getString(R.string.x)} : ${y}"`, `ui/home/ProfilesDialog.kt:253`) ou chiffres seuls (`android:text="1"`, `view_welcome.xml`) : pas un texte en dur (tests `gabarit Kotlin` et `chiffres seuls`, tâche 1).
- Icône vectorielle Material ajoutée (`android:fillColor="#FF000000"` dans `res/drawable/ic_*.xml`) : pas bloquée, l'icône est teintée par le thème (test `icône vectorielle`, tâche 1).

---

### Task 1: Contrôle bloquant

**Files:**
- Create: `.claude/hooks/controle.mjs`
- Create: `.claude/hooks/controle.test.mjs`
- Create: `.claude/settings.json`

**Interfaces:**
- Produces: `findProblems(filePath: string, addedLines: string[]) => Array<{ rule: string, line: string, fix: string }>` (export nommé) ; `addedLines(before: string, after: string) => string[]` (lignes de `after` absentes de `before`, compte multiple : une ligne présente 1 fois avant et 2 fois après compte 1 fois) ; CLI : sans argument = hook (JSON sur stdin) ; `--diff [base]` (défaut `origin/master`) = lignes ajoutées de `git diff <base>...HEAD` plus l'arbre de travail, sortie 1 si problème.
- Règles (`rule`), seulement sous `app/src/main/` (chemin normalisé en `/`) :
  - `couleur-en-dur` : XML de `res/layout*/`, `res/drawable*/`, `res/values*/` (sauf `palettes.xml`, `colors.xml`) contenant `"#[0-9A-Fa-f]{3,8}"`, sauf attributs `android:fillColor` / `android:strokeColor` dans `res/drawable*/` ; Kotlin `.kt` contenant `Color.parseColor("`, `Color.rgb(<chiffre>`, `Color.argb(<chiffre>`, `Color.(BLACK|WHITE|RED|GREEN|BLUE|GRAY|DKGRAY|LTGRAY|YELLOW|CYAN|MAGENTA)\b`, ou un littéral `0x[Ff]{2}[0-9A-Fa-f]{6}`. Correction proposée : `?attr/c_…` en XML, `themeColor(context, R.attr.c_…)` en Kotlin, valeur ajoutée dans `palettes.xml` pour chaque thème de `Themes.kt`.
  - `texte-en-dur` : XML de `res/layout*/` avec `android:(text|hint|contentDescription|title)="X"` où X ne commence pas par `@` ou `?` et contient une lettre ; Kotlin avec `(text|hint|contentDescription|title) = "…"`, `setText("…")`, `setTitle("…")`, `setMessage("…")`, `makeText(…, "…")` dont la chaîne, une fois retirés `${…}` et `$nom`, contient une lettre. Correction : `res/values/strings.xml` puis `@string/…` / `getString(R.string.…)`.
  - `police-en-dur` : `@font/` dans `res/**.xml` sauf `res/font/`, `values*/fonts.xml`, `values*/palettes.xml` ; `R.font.` dans un `.kt`. Correction : attribut de rôle `?attr/c_font_…` / `themeFont(context, R.attr.c_font_…)`.
  - Échappatoire : une ligne qui contient `controle: permis` est ignorée (raison écrite à côté, vue en relecture).
- Hook : lit `{ tool_name, tool_input }` ; `Edit` → `addedLines(old_string, new_string)` ; `MultiEdit` → idem pour chaque `edits[]` ; `Write` → `addedLines(<fichier actuel ou "">, content)`. Problème → message sur stderr, sortie 2. Rien, ou entrée illisible, ou fichier hors `app/src/main/` → sortie 0, rien d'écrit.
- Message (exact, une ligne par problème, puis la dernière ligne) :
  `Contrôle Mate : <règle> dans <chemin relatif> : « <ligne coupée à 100 car.> ». <correction>`
  `Le contrôle ne regarde que les lignes ajoutées. Faux positif : ajouter « controle: permis <raison> » sur la ligne et le dire dans la PR.`

- [ ] **Step 1: Write the failing tests** (`node:test`, `assert`) : `bloque une couleur ajoutée dans un layout` (`android:background="#FF0000"` dans `app/src/main/res/layout/x.xml` → 1 problème `couleur-en-dur`) ; `bloque Color.parseColor littéral` ; `laisse passer Color.parseColor(variable)` ; `laisse passer une ligne déjà présente` (`addedLines("a\nval white = 0xFFFFFFFF.toInt()", "a\nb\nval white = 0xFFFFFFFF.toInt()")` → `["b"]`) ; `icône vectorielle` (`android:fillColor="#FF000000"` dans `res/drawable/ic_x.xml` → 0) ; `palettes.xml permis` ; `bloque un texte en dur dans un layout` ; `chiffres seuls` (`android:text="1"` → 0) ; `gabarit Kotlin` (`contentDescription = "${a} : ${b}"` → 0) ; `bloque setText littéral` ; `bloque @font/ dans un layout` ; `fonts.xml permis` ; `hors app/src/main ignoré` (`app/src/test/X.kt`, `backend/src/x.ts`) ; `chemins Windows` (`C:\\w\\app\\src\\main\\res\\layout\\x.xml`) ; `controle: permis` ; CLI : `hook Edit bloquant sort en 2 avec le message` (spawn `node controle.mjs` avec JSON Edit sur stdin) ; `entrée illisible` (stdin `pas du json` → sortie 0, stderr vide).

- [ ] **Step 2: Run** `node --test .claude/hooks/` — Expected: FAIL (module absent).

- [ ] **Step 3: Implement** `.claude/hooks/controle.mjs` (ESM, sans dépendance, < 200 lignes, règles dans un tableau de constantes en tête du fichier, commentaire d'en-tête en français : ce qu'il vérifie, pourquoi seulement les lignes ajoutées, comment le lancer à la main).

- [ ] **Step 4: Run** `node --test .claude/hooks/` — Expected: PASS, 0 échec.

- [ ] **Step 5: Write `.claude/settings.json`**

```json
{
  "permissions": {
    "deny": [
      "Bash(git push --force*)", "Bash(git push -f*)", "Bash(git push * --force*)", "Bash(git push * -f*)",
      "Bash(git push origin master*)", "Bash(git push origin HEAD:master*)"
    ]
  },
  "hooks": {
    "PreToolUse": [
      { "matcher": "Edit|Write|MultiEdit",
        "hooks": [{ "type": "command", "command": "node \"$CLAUDE_PROJECT_DIR/.claude/hooks/controle.mjs\"", "timeout": 10 }] }
    ]
  }
}
```

- [ ] **Step 6: Verify** `node .claude/hooks/controle.mjs --diff origin/master` sort en 0 sur la branche (rien d'ajouté dans `app/`), puis `git diff --stat` ne touche que `.claude/`.

- [ ] **Step 7: Commit** `git add .claude && git commit -m "feat: contrôle bloquant des couleurs, textes et polices en dur"`

### Task 2: CLAUDE.md allégé, sans perte

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/architecture.md` (nouvelle section à la fin)
- Create: `scripts/claude-md-sans-perte.mjs` (temporaire, supprimé à l'étape 5)

**Interfaces:**
- Produces: `docs/architecture.md`, section `## Détail par fonction` (titre exact), qui reçoit **mot pour mot** les puces de « Conventions du code » de `CLAUDE.md` décrivant une fonction (Vision, Deck V2, Ordinateurs, Agents Claude, Jira, Je m'absente, Onglet Son, Mode Caméra, Mac Accessibilité, Verrou, Commande vocale, Réunions Teams, Alerte réunion, Plugins, Accessibilité, Contrat API, Extension MCP, le long paragraphe Android de l'interface, le long paragraphe « Le backend tourne sur l'ordinateur piloté »), chacune en sous-section `### <nom>`.
- `CLAUDE.md` garde, dans cet ordre : Le projet, Contraintes techniques connues, Architecture client léger, Points à vérifier (MDM inclus), Décidé, Pas encore décidé, Règles de travail, `## Conventions du code` réduit aux règles transverses (une ligne chacune : Kotlin + vues XML + une Activity / Fragment par mode ; couleurs `?attr/c_…` et `themeColor`, polices `c_font_*`, jamais en dur, thèmes listés dans `Themes.kt` ; textes dans `res/values/strings.xml`, erreurs par `errorText(e)` ; dépendances limitées (appcompat, lifecycle-runtime-ktx, coroutines), HTTP par `HttpURLConnection` + `org.json`, un client par domaine dans `net/` ; AGP 9 Kotlin intégré, sources `src/main/kotlin` ; backend Node 22 + TypeScript (`tsx`) + Fastify, un plugin par domaine, `src/os.ts` seul point d'entrée système ; WiFi en HTTPS seulement, jeton jamais en clair hors 127.0.0.1 ; nouvel ordre tablette = type `tablet.ts` + schéma `routes/tablet.ts` + outil `mcp-tools.ts` + branche `RemoteChannel.execute` ; noms `Computer*` vs `Mac`), Environnement local, puis une section `## Où trouver le reste` : `docs/architecture.md` (§ Détail par fonction), `docs/developpement.md`, `docs/claude.md` (circuit Claude, skills, agents, contrôle), `DESIGN.md`, `docs/ui-ux/da/`, `docs/etat-du-projet.md`.
- Règle de travail « Ne rien commit sans demande explicite » devient : « Ne rien commit ni pousser sans demande explicite. Demander une feature par la skill `nouvelle-feature` vaut accord pour sa branche, ses commits et sa PR brouillon. »
- Rien de périmé ne passe dans les skills ; une mention de `config-claude/` est retirée.

- [ ] **Step 1: Write the check** `scripts/claude-md-sans-perte.mjs <ancien CLAUDE.md> ` : pour chaque ligne non vide de l'ancien fichier (hors titres `#`), vérifie qu'elle figure telle quelle dans le nouveau `CLAUDE.md` ou dans `docs/architecture.md` ou `docs/developpement.md` ; sinon l'affiche ; sortie 1 si au moins une manque. Les lignes réécrites exprès (règle « Ne rien commit… », lignes transverses résumées) sont listées dans un tableau `REECRITES` du script avec l'endroit qui les porte.

- [ ] **Step 2: Run** `git show HEAD:CLAUDE.md > /tmp/claude-ancien.md && node scripts/claude-md-sans-perte.mjs /tmp/claude-ancien.md` — Expected: PASS trivialement (rien n'a bougé), sert de témoin.

- [ ] **Step 3: Déplacer** les puces dans `docs/architecture.md` § Détail par fonction (copier-coller exact) et réduire `CLAUDE.md` comme décrit.

- [ ] **Step 4: Run** le script — Expected: sortie 0 ; `wc -c CLAUDE.md` < 20000.

- [ ] **Step 5: Supprimer** `scripts/claude-md-sans-perte.mjs` et commit `git add CLAUDE.md docs/architecture.md && git commit -m "docs: CLAUDE.md allégé, détail des fonctions dans architecture.md"`

### Task 3: Agents

**Files:**
- Create: `.claude/agents/gardien-perimetre.md`, `.claude/agents/relecteur-android.md`, `.claude/agents/relecteur-backend.md`, `.claude/agents/relecteur-design.md`, `.claude/agents/implementer.md` (repris de `config-claude/global/agents/implementer.md`)

**Interfaces:**
- Consumes: § `## Conventions du code` et `## Où trouver le reste` de `CLAUDE.md` (tâche 2) ; `node .claude/hooks/controle.mjs --diff` (tâche 1).
- Produces: noms d'agents utilisés par les skills de la tâche 5. En-tête de chacun : `name`, `description` (quand l'appeler), `tools: Read, Grep, Glob, Bash` (lecture seule ; `implementer` : `Read, Edit, Write, Grep, Glob, Bash`), `model: sonnet`.
- Format de retour commun aux 4 relecteurs (exact) : liste de remarques, chacune `[bloquant|important|mineur] fichier:ligne — problème. Correction attendue.` ; puis `Rien d'autre.` ou `⚠️ pas vérifié : …`. Jamais d'éloge ni de résumé du diff.
- `gardien-perimetre` : lit la demande dans la section « Ce qui change » de la PR (`gh pr view --json body,title`) ou la phrase fournie par l'appelant ; lit `git diff origin/master...HEAD --stat` puis le diff ; liste chaque changement absent de la demande ou présent dans « Ce que ça n'inclut pas ». Lecture seule, n'écrit pas sur GitHub.
- `relecteur-android` : conventions de `CLAUDE.md` § Conventions du code (Android) et § Détail par fonction de `docs/architecture.md` pour la zone touchée ; bugs (cycle de vie, fil principal, fuite de vue), tests JVM manquants dans `app/src/test/` pour la logique pure, textes dans `strings.xml`, `errorText`, dépendance ajoutée.
- `relecteur-backend` : plugin Fastify par domaine, `os.ts` seul point d'entrée (et son faux `test/fake-os.ts`), sécurité (jeton d'appairage, routes sans jeton seulement sur 127.0.0.1, validation des entrées par schéma, jamais de secret ni de `tool_input` en journal), test dans `backend/test/`, contrôle dans `backend/qa/sanity.ts` pour une nouvelle feature, outil MCP pour un nouvel ordre ou réglage.
- `relecteur-design` : lit la liste des thèmes dans `enum class AppTheme` de `Themes.kt`, `DESIGN.md` (pages web), `docs/ui-ux/da/README.md` et ses captures, `.claude/skills/ecran-design/references/anti-motifs.md` ; vérifie attributs du thème seulement, polices par rôle, accessibilité (`contentDescription`, contrastes vérifiés par `ConsolePaletteTest`/`ContrastPaletteTest` si `palettes.xml` change), une valeur par thème pour tout attribut ajouté ; regarde les captures fournies (une par thème) ; sans capture : remarque `important` « captures par thème manquantes », jamais bloquant.

- [ ] **Step 1: Write** les 5 fichiers (chacun < 80 lignes, français, sans nom de thème ni de couleur).
- [ ] **Step 2: Verify** `grep -L '^model: \(sonnet\|haiku\)$' .claude/agents/*.md` → aucune sortie ; `grep -rniE 'console jour|vert acide|contraste élevé|[0-9] thèmes' .claude/agents` → aucune sortie.
- [ ] **Step 3: Commit** `git commit -m "feat: agents relecteurs du projet"`

### Task 4: Skill design par thème

**Files:**
- Create: `.claude/skills/ecran-design/SKILL.md` (à partir de `config-claude/projet/skills/design-companion/SKILL.md`)
- Create: `.claude/skills/ecran-design/references/{android,etats,anti-motifs,web}.md` (copies de `config-claude/projet/skills/design-companion/references/`, comptes de thèmes et noms de thèmes retirés)
- Modify: `docs/developpement.md` (ligne « Passe qualité d'un écran », section Avec Claude Code : `design-companion` → `ecran-design`, plus de `config-claude/`)

**Interfaces:**
- Consumes: agent `relecteur-design` (tâche 3).
- Produces: skill `ecran-design`, appelée par `feature-android` et `nouvelle-feature` dès qu'un écran est créé ou modifié.
- `description` : à utiliser avant de créer ou modifier un écran, une vue, un layout, un widget ou une page web du compagnon, et pour une passe qualité (`critique`, `etats`, `polish`, `distill`, `harden`, `animate`).
- Contenu ajouté à la base : « Avant tout écran » (lire `DESIGN.md` pour le web, `docs/ui-ux/da/README.md` + captures, l'écran voisin le plus proche ; lire `enum class AppTheme` de `Themes.kt` pour la liste des thèmes) ; « Raisonner en thème » (chaque thème a son caractère, lu dans ses valeurs de `palettes.xml` ; tout attribut ajouté a une valeur dans chaque palette ; jamais de couleur, police ou thème nommé en dur) ; « Captures par thème » : si `adb devices` liste un appareil, pour chaque entrée de `AppTheme` : changer de thème (APK debug : `adb shell am force-stop app.mate` puis écrire la valeur dans `shared_prefs/companion.xml` clé `theme` par `adb shell run-as app.mate`, ou ordre `settings` `{"theme": "<NOM>"}` par `PUT /local/tablet/settings` du compagnon), relancer (`am start -n app.mate/app.mate.MainActivity`), `adb exec-out screencap -p > captures/<écran>-<thème>.png`, joindre à la PR ; sinon le dire et demander à l'humain ; « Accessibilité » (`contentDescription`, cibles ≥ 48 dp, thème à contraste renforcé s'il existe dans `AppTheme`) ; la commande `critique` utilise `relecteur-design` comme évaluateur séparé (plus de `code-scout`).
- Retirés de la base : « 4 thèmes », mention de `direction-artistique` non fournie remplacée par « se signale à l'humain ».

- [ ] **Step 1: Write** les fichiers.
- [ ] **Step 2: Verify** `grep -rniE 'console|vert acide|contraste élevé|[0-9] thèmes|code-scout' .claude/skills/ecran-design` → aucune sortie hors noms de fichiers ou de jetons génériques (`c_…`) ; `grep -n 'config-claude' docs/developpement.md` → aucune sortie.
- [ ] **Step 3: Commit** `git commit -m "feat: skill de design d'écran par thème"`

### Task 5: Skills du circuit

**Files:**
- Create: `.claude/skills/nouvelle-feature/SKILL.md`, `.claude/skills/feature-android/SKILL.md`, `.claude/skills/feature-backend/SKILL.md`, `.claude/skills/relecture/SKILL.md`

**Interfaces:**
- Consumes: agents de la tâche 3, `ecran-design` (tâche 4), `node .claude/hooks/controle.mjs --diff` (tâche 1), sections de `CLAUDE.md` (tâche 2).
- `nouvelle-feature` (description : toute demande de fonctionnalité ou de correction dans Mate, avant d'écrire du code) : 1. reformuler en une phrase + « ce que ça n'inclut pas », faire valider ; 2. si la branche courante n'est pas `master` et porte déjà un autre sujet (PR ouverte au titre différent, ou commits hors sujet) : refuser d'empiler, proposer une nouvelle branche ; 3. branche `<type>/<sujet>` vérifiée par `.github/nommage.sh "<branche>" "<titre>"` ; 4. commit vide `git commit --allow-empty -m "Début : <phrase>"`, push, `gh pr create --draft --title "<titre>" --body` au modèle `.github/pull_request_template.md`, la phrase et « N'inclut pas : … » dans « Ce qui change » (sans `gh` : afficher le corps à coller sur GitHub) ; 5. skill de zone (`feature-android`, `feature-backend`, `ecran-design` si un écran change) ; 6. TDD : test rouge lancé et vu échouer avant le code ; 7. `npm run format` / `./gradlew ktlintFormat`, tests de la zone ; 8. skill `relecture` ; corriger les `bloquant` ; 9. corps de PR rempli (Comment tester, Testé sur, captures par thème), `gh pr ready`. Un 2e sujet demandé en cours de route : le noter pour une autre branche, ne pas le faire.
- `feature-android` : renvoie à `CLAUDE.md` § Conventions du code et à `docs/architecture.md` § Détail par fonction de la zone ; une vue XML, un Fragment par mode, textes dans `strings.xml`, `errorText`, pas de dépendance nouvelle sans accord, test JVM dans `app/src/test/` (contexte `FakeContext`), `./gradlew ktlintFormat ktlintCheck testDebugUnitTest`, essai sur l'émulateur `companion_api29` ou la tablette (`adb install -r app/build/outputs/apk/debug/app-debug.apk`), contraintes de `docs/tablette-4.2.md`.
- `feature-backend` : un plugin Fastify par domaine dans `backend/src/routes/`, pilotage seulement par `src/os.ts` (et ajout au faux `test/fake-os.ts`), test dans `backend/test/` contre `test/server.ts`, page web → test `backend/e2e/`, contrôle dans `backend/qa/sanity.ts`, nouvel ordre ou réglage → outil `mcp-tools.ts`, sécurité de `docs/securite-appairage.md`, `cd backend && npm run format && npm run lint && npm run coverage` ; installation, `src/mac/`, `src/win/`, `os.ts`, `service.ts` touchés → tests d'installation à la main de `docs/developpement.md` § Branches, PR et CI.
- `relecture` (description : avant de marquer une PR prête ou de dire « fini ») : `node .claude/hooks/controle.mjs --diff origin/master` d'abord ; puis, selon `git diff origin/master...HEAD --name-only`, lancer **dans le même message** `gardien-perimetre` (toujours), `relecteur-android` (`app/`), `relecteur-backend` (`backend/`), `relecteur-design` (`res/layout*`, `res/drawable*`, `res/values*`, `ui/`, `backend/src/local-page.ts`, `web-style.ts`, `camera.ts`, `assets/web`), avec la demande d'origine et la liste des captures ; rassembler par gravité `bloquant` puis `important` puis `mineur`, sans doublon ; l'humain tranche ce qui n'est pas `bloquant`.

- [ ] **Step 1: Write** les 4 skills (frontmatter `name`, `description` ; chacune < 120 lignes ; aucune recopie de règle de `CLAUDE.md`, des renvois).
- [ ] **Step 2: Verify** `grep -rniE 'console|vert acide|contraste élevé|[0-9] thèmes|gitlab-ci|\bMR\b' .claude/skills` → aucune sortie (hors tâche 4 déjà vérifiée).
- [ ] **Step 3: Commit** `git commit -m "feat: skills du circuit de dev"`

### Task 6: Doc, CODEOWNERS, suppression de config-claude

**Files:**
- Create: `docs/claude.md`
- Modify: `docs/developpement.md` § Avec Claude Code (lien vers `docs/claude.md`)
- Modify: `.github/CODEOWNERS` (ajout `/.claude/ @ahmeedatm` avec un commentaire d'une ligne)
- Delete: `config-claude/` entier

**Interfaces:**
- `docs/claude.md` (< 80 lignes) : ce qui existe (`.claude/settings.json`, contrôle, agents, skills), le circuit en 6 étapes (nouvelle-feature → branche + PR brouillon → zone + TDD → relecture → PR prête → CI + approbation), les deux niveaux de sévérité, l'échappatoire `controle: permis`, qui contrôle `.claude/` (CODEOWNERS : Ahmed), lancer le contrôle à la main et ses tests (`node --test .claude/hooks/`).

- [ ] **Step 1: Write / modify / delete.**
- [ ] **Step 2: Verify** `git grep -n 'config-claude' -- ':!docs/superpowers'` → aucune sortie ; `test ! -e config-claude`.
- [ ] **Step 3: Commit** `git commit -m "docs: guide du Claude commun, .claude/ réservé à Ahmed"`

### Task 7: Vérifications réelles et PR (session principale)

- [ ] `node --test .claude/hooks/` vert.
- [ ] Rouge : `claude -p --model haiku` dans le worktree demande d'ajouter `android:background="#FF0000"` à un layout → l'édition est refusée, `git diff` vide, message du contrôle cité. Vert : même chose sur une ligne sans couleur d'un fichier qui en contient déjà (`ui/BreathingView.kt`) → acceptée ; puis `git checkout` du fichier.
- [ ] `claude -p --model haiku` liste les skills et agents du projet.
- [ ] `grep '^model:' .claude/agents/*.md`.
- [ ] `.github/nommage.sh feat/claude-commun-equipe "<titre>"` conforme ; push, PR au modèle avec preuves ; CI.
