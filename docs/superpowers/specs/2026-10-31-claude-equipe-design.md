# Un Claude commun à l'équipe Mate : skills, agents, contrôles et circuit de dev

Date : 31/10/2026. Auteur de la demande : Ahmed. Destination finale : `docs/superpowers/specs/2026-10-31-claude-equipe-design.md` du dépôt GitHub `ahmeedatm/Mate`.

## Intention

L'équipe (Ahmed, Eliot `EliotFreifer`, Bruno `crimson-king-5`) code Mate avec Claude Code. Chacun a sa config perso ; le dépôt n'a aucun dossier `.claude`. Résultat constaté dans l'historique git (846 commits) :

- **Dérive de périmètre** : des branches mélangent plusieurs sujets (`fix/notifications-illisibles` contenait « notifications, bandeau, écran de démarrage, mot de réveil, crayon sur l'Accueil » ; `fix/retouches-ui`, `fix/raccourcis-ecrans-modales` idem). Des ajouts que personne n'avait demandés.
- **Bugs fusionnés** : commits de 20 à 60 fichiers, impossibles à relire ; branches `fix/…-restes`, `fix/…-sure` juste derrière une grosse feature.
- **Écrans hors DA** : des écrans faits par Claude qui ne collent pas à la DA du projet.
- `CLAUDE.md` fait 61 Ko : tout y est, donc rien n'y ressort.

Le but : quiconque ouvre Claude Code dans le dépôt obtient le même Claude « propre », reçu par `git pull`, sans rien installer de plus.

## Ce qui compte comme réussi

- Une demande = une branche = un sujet. Claude refuse d'empiler un 2e sujet dans la même branche et propose d'en ouvrir une autre.
- Toute feature passe par : branche → test qui échoue → code → relecture par les agents spécialisés → MR/PR au format du dépôt.
- Les erreurs mécaniques ne peuvent pas passer (contrôle bloquant).
- Un écran nouveau ou modifié est vérifié dans **tous** les thèmes existants avant la PR.

## Non-buts

- Pas de dépendance à la config perso d'Ahmed (`~/.claude/workflows/`, ses hooks, ses règles globales, Orca, NAS, usine). Uniquement des pièces standard de Claude Code versionnées dans le dépôt.
- Pas de nouvelle CI ni de changement de `.github/workflows/` dans ce chantier ; seule exception : la ligne CODEOWNERS de la décision 8.
- Pas de réécriture du code de l'app.

## Anti-buts (échec même si ça « marche »)

- Des règles qui nomment un thème ou une couleur précise (« vert acide », « Console ») : elles casseraient au prochain thème.
- Un contrôle bloquant si bavard ou si faux qu'on le désactive.
- Des agents qui héritent du modèle le plus cher de la session de chacun.
- Dupliquer le contenu de `CLAUDE.md` dans les skills : une seule source par règle.

## Contraintes

- Pièces Claude Code standard : `.claude/settings.json` (hooks, permissions), `.claude/skills/<nom>/SKILL.md`, `.claude/agents/<nom>.md`. Tout versionné.
- Chaque agent fixe son `model` dans son en-tête (`sonnet` par défaut, `haiku` pour chercher) ; aucun n'est en `inherit`.
- Textes en français, comme le reste du projet.
- Le dépôt garde ses conventions : `CLAUDE.md`, `DESIGN.md`, `PRODUCT.md`, `docs/ui-ux/da/` (maquettes), `docs/developpement.md`, modèle de PR `.github/pull_request_template.md`, nommage des PR (titre en français sans préfixe technique, branche `feat|fix|docs|test|ci/…`), tests : `backend/test/` (comportement backend), `backend/e2e/` (pages web), `app/src/test/` (logique Android) ; `npm run format`, `./gradlew ktlintFormat` avant de pousser.
- Thèmes : la liste vit dans `app/src/main/kotlin/app/mate/ui/Themes.kt` (aujourd'hui Console, Console jour, Contraste élevé) ; couleurs par attributs `?attr/c_…` (`attrs.xml`, `palettes.xml`), polices par rôle `c_font_*`, jamais en dur.

## Ce qu'Ahmed a décidé

1. Un `.claude/` commun dans le dépôt, utilisé par tous les membres.
2. Des skills pour ajouter des features en respectant les règles du projet.
3. Des agents spécialisés, dont des relecteurs.
4. Un circuit de dev propre avec ces agents.
5. Le design est « très important » : il a ses propres configs.
6. **Raisonner en thème** : le design comme le code respectent le caractère de chaque thème, y compris les thèmes ajoutés plus tard. Les règles et les relecteurs lisent la liste des thèmes dans le code au moment de vérifier, ils ne la recopient pas.
8. **Seul Ahmed contrôle `.claude/`** : ligne `/.claude/ @ahmeedatm` dans `.github/CODEOWNERS` (changement de `.github/`, approuvé par Ahmed, PR à part ou dans la même PR qu'il approuve) ; toute modification de `.claude/` demande son approbation.
7. **Deux niveaux de sévérité** : erreurs mécaniques bloquées par un contrôle automatique ; le reste (goût, périmètre, qualité) signalé par les relecteurs, l'humain tranche.

## Le contenu proposé (forme laissée au builder dans ce cadre)

### Skills (`.claude/skills/`)

- `nouvelle-feature` : point d'entrée de toute demande de fonctionnalité. Reformule la demande en une phrase et la liste de ce qu'elle n'inclut pas ; crée la branche au bon nom ; renvoie vers la skill de zone ; impose TDD ; termine par la relecture puis la PR au modèle du dépôt. Refuse un 2e sujet dans la branche.
- Skills de zone, qui pointent vers les sections de `CLAUDE.md` / `docs/` au lieu de les recopier :
  - `feature-android` (vues XML, un Fragment par mode, `strings.xml`, `errorText`, dépendances limitées, test JVM, essai sur l'émulateur `companion_api29`) ;
  - `feature-backend` (plugin Fastify par domaine, `os.ts` seul point d'entrée système, test dans `backend/test/`, contrôle dans `backend/qa/sanity.ts`, outil MCP si nouvel ordre) ;
  - `ecran-design` : avant tout écran, lire `DESIGN.md` + maquettes ; attributs du thème uniquement ; captures dans chaque thème de `Themes.kt` (`adb exec-out screencap -p`) ; accessibilité (contentDescription, contrastes du thème Contraste) ; pas de look « IA générique ».
- `relecture` : lance les agents relecteurs adaptés au diff (Android, backend, design, périmètre) en parallèle et rassemble leurs remarques par gravité.

### Agents (`.claude/agents/`)

- `gardien-perimetre` : compare le diff à la demande d'origine (titre/description de PR ou phrase de la skill) ; liste tout ce qui n'était pas demandé. Lecture seule.
- `relecteur-android` : conventions Android du projet, bugs, tests manquants.
- `relecteur-backend` : conventions backend, sécurité (jeton, 127.0.0.1, entrées), tests.
- `relecteur-design` : diff d'interface contre `DESIGN.md`, maquettes et chaque thème de `Themes.kt` ; regarde les captures si fournies.
- Tous en lecture seule, `model` fixé.

### Contrôle bloquant (`.claude/settings.json`, hook)

Vérifie les erreurs mécaniques sur les fichiers modifiés, au minimum : couleur en dur dans le code ou les layouts Android (hors `palettes.xml`/`colors.xml`), texte affiché en dur hors `strings.xml`, `@font/` en dur. Il bloque avec un message qui dit quoi faire. Le moment exact (après chaque édition, ou avant commit) et la liste finale des règles sont au builder, sous l'anti-but « pas bavard, pas faux ».

### `CLAUDE.md`

Allégé : il garde le cœur (projet, contraintes matérielles, règles de travail, où trouver quoi) et renvoie vers les skills et `docs/` pour le détail. Rien ne doit être perdu : ce qui sort de `CLAUDE.md` va dans un document existant ou une skill.

## Existant à reprendre

`config-claude/` (versionné) contient déjà une config : `install.sh` qui copie `projet/` dans un `.claude/` **non versionné** (skill `design-companion` + `references/` android, états, anti-motifs, web ; `settings.local.json` qui interdit push direct et force push sur `master`) et `global/` (agent `implementer` en sonnet, `wait-until.sh`, style Concis). Le nouveau `.claude/` versionné part de là : `design-companion` sert de base à la skill design (en retirant tout compte de thèmes en dur, ex. « 4 thèmes »), l'agent `implementer` passe dans `.claude/agents/`, les interdits de push passent dans `.claude/settings.json`. **Décision d'Ahmed : `config-claude/` est supprimé en entier** (y compris `install.sh`, le style Concis et `wait-until.sh`), une fois le bon repris ; ses mentions dans la doc (`docs/developpement.md`, `CLAUDE.md`, `.github/copie-github.md` si besoin) sont retirées.

## Choix faits par Claude (à vérifier par Ahmed)

- **Demande d'origine** : dès la branche créée, `nouvelle-feature` ouvre une PR brouillon dont la section « Ce qui change » du modèle porte la demande et ce qu'elle n'inclut pas. C'est la référence du `gardien-perimetre`, partagée entre sessions et membres.
- **Contrôle bloquant sur les lignes ajoutées seulement** (diff), pas sur le fichier entier : les ~6 couleurs et ~9 textes en dur déjà présents sont tolérés et ne bloquent pas l'édition de leurs fichiers ; ils ne sont pas corrigés dans ce chantier.
- **Script du contrôle en Node** (déjà requis par le backend) : marche sur Mac et Windows. Lisible, sans réseau.
- **Captures par thème** : si un émulateur ou la tablette est branché (`adb devices`), la skill design prend elle-même les captures dans chaque thème de `Themes.kt` ; sinon elle le dit et demande à l'humain. Captures jointes dans la PR. `relecteur-design` sans capture signale le manque, ne bloque pas (niveau 2).
- **`CLAUDE.md` périmé** (règles GitLab, MR, pipeline : `.gitlab-ci.yml` n'existe plus, le dépôt vit sur GitHub) : corrigé ou supprimé, jamais recopié dans une skill. « Rien de perdu » vaut pour ce qui est encore vrai.

## Laissé au builder

- Noms exacts, découpage et texte des skills et agents.
- Forme technique du contrôle bloquant (script, moment du déclenchement), tant qu'il respecte les anti-buts.
- Comment découper `CLAUDE.md` sans perte.
- Une courte page `docs/claude.md` (ou section de `docs/developpement.md`) qui explique à l'équipe ce qui existe et comment ça s'enchaîne.
