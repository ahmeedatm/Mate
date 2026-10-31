# Claude commun de l'équipe

Le dossier `.claude/` est versionné : tout le monde (Ahmed, Eliot, Bruno) travaille avec Claude Code
selon le même circuit, sans rien installer. Le contexte et les conventions restent dans `CLAUDE.md` ;
ici, ce que `.claude/` ajoute et comment s'en servir.

## Ce qui existe

- `.claude/settings.json` : interdit le force-push et le push direct sur `master`, et branche le
  contrôle mécanique sur chaque `Edit`, `Write` et `MultiEdit`.
- `.claude/hooks/controle.mjs` (+ `controle.test.mjs`) : refuse les couleurs, textes et polices écrits
  en dur dans `app/src/main/`. Il ne regarde que les lignes ajoutées.
- `.claude/agents/` : `gardien-perimetre`, `relecteur-android`, `relecteur-backend`,
  `relecteur-design` (lecture seule) et `implementer` (exécute une tâche précise en TDD).
- `.claude/skills/` : `nouvelle-feature` (le circuit), `feature-android`, `feature-backend`,
  `ecran-design`, `relecture`. Claude les charge seul selon la demande, rien à taper.
- `.claude/settings.local.json` : réglages personnels, ignoré par git.

## Le circuit d'une demande

1. `nouvelle-feature` : Claude reformule la demande en une phrase et une exclusion ; on valide.
2. Branche `<type>/<sujet>` depuis `origin/master`, commit d'ouverture et PR brouillon.
3. Skill de zone, puis test rouge d'abord et code minimal (TDD).
4. `relecture` : contrôle mécanique, puis les agents adaptés au diff, en parallèle.
5. PR prête : « Comment tester », « Testé sur », captures par thème pour un écran.
6. CI verte, puis fusion. Approbation d'une autre personne seulement si la PR porte l'étiquette
   `impact-equipe` (elle change un réglage par défaut ou une habitude des autres). Une demande = une branche = un sujet.

## Deux niveaux de sévérité

1. Erreurs mécaniques (couleur, texte ou police écrits en dur) : le contrôle les refuse tout seul, par
   le hook à chaque `Edit` / `Write` et par `--diff` dans la `relecture`.
2. Le reste (goût, périmètre, qualité, bugs) : les relecteurs le signalent avec les étiquettes
   `bloquant`, `important` ou `mineur`, et l'humain décide. Par défaut, Claude corrige les `bloquant`
   avant de marquer la PR prête, sauf si l'humain en décide autrement ; ce qui sort de la demande
   devient une note « à faire dans une autre branche ».

## Faux positif du contrôle

Ajouter `controle: permis <raison>` sur la ligne concernée, et le dire dans la PR.

## Lancer le contrôle à la main

```
node .claude/hooks/controle.mjs --diff            # lignes ajoutées depuis origin/master
node .claude/hooks/controle.mjs --diff <base>     # autre base
node --test .claude/hooks/                        # tests du contrôle
```

Sortie 0 : rien à signaler ; 1 : problème trouvé ; 2 : git n'a pas pu lire le diff (`git fetch origin`).

## Qui contrôle `.claude/`

`.github/CODEOWNERS` réserve `/.claude/` à Ahmed : toute modification des règles, agents, skills ou du
contrôle passe par sa relecture. Une idée d'amélioration : ouvrir une PR, elle sera relue.
