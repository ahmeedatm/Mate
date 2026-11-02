---
name: relecture
description: À charger avant de marquer une PR prête ou de dire "c'est fini" sur du code Mate. Lance le contrôle mécanique, puis les agents relecteurs adaptés au diff (périmètre, Android, backend, design) en parallèle, et rassemble leurs remarques par gravité. Aussi pour "relis ma branche" ou "vérifie avant la PR".
---

# relecture

Relecture de la branche courante contre `origin/master`. Les agents sont dans `.claude/agents/` ;
leurs règles sont dans leurs fichiers, pas ici. Tous sont en lecture seule.

## 1. Le contrôle mécanique

```
git fetch origin
node .claude/hooks/controle.mjs --diff origin/master
```

Sortie 0 : rien à signaler. Sortie 2 : diff illisible (base introuvable) : `git fetch origin` puis
relancer. Sortie 1 : chaque ligne est une couleur, un texte ou une police en dur
dans une ligne ajoutée. Les corriger (c'est du `bloquant`) puis relancer, avant d'appeler les agents.

## 2. Les agents, dans un seul message

`git diff origin/master...HEAD --name-only` donne les fichiers. Lancer d'un coup, **dans le même
message** (plusieurs appels Agent en parallèle, jamais l'un après l'autre) :

| Agent | Quand |
|-------|-------|
| `gardien-perimetre` | toujours |
| `relecteur-android` | un fichier sous `app/` |
| `relecteur-backend` | un fichier sous `backend/` |
| `relecteur-design` | un fichier sous `res/layout*`, `res/drawable*`, `res/values*`, un dossier `ui/`, ou parmi `backend/src/local-page.ts`, `web-style.ts`, `camera.ts`, `backend/assets/web` |

Chaque appel reçoit le même contexte, car un agent n'a pas la conversation :

- **La demande d'origine** : la section « Ce qui change » de la PR avec son « N'inclut pas : … »
  (`gh pr view --json title,body`), ou à défaut la phrase validée avec l'humain. Sans `gh` ni phrase,
  la demander à l'humain avant de lancer.
- Le chemin du dépôt (ou du worktree) à relire et la base `origin/master`.
- **La liste des captures** (chemins des images, une par thème) pour `relecteur-design`. Aucune
  capture : le lui dire, il le signalera.
- Ce qu'on sait déjà non vérifié (appareil absent, système non essayé).

Laisser les agents choisir leur modèle (fixé dans leur en-tête) : ne jamais passer `model` plus
cher. Ne pas relancer un agent pour le même changement tant que rien n'a bougé.

## 3. Rassembler

Les agents rendent des lignes `[bloquant|important|mineur] fichier:ligne — problème. Correction.`
Fusionner en une seule liste :

1. `bloquant`, puis `important`, puis `mineur`.
2. Sans doublon : la même remarque (même fichier et même problème) vue par plusieurs agents est
   gardée une fois, avec la gravité la plus haute et les agents qui l'ont vue.
3. Garder les lignes `⚠️ pas vérifié : …` des agents, regroupées à la fin.

Rendre la liste à l'humain telle quelle, sans éloge ni résumé du diff. Aucun problème : le dire en
une ligne et lister seulement les `⚠️`.

## 4. Suite

- `bloquant` : à corriger avant la PR prête, puis relancer le contrôle mécanique et les agents
  concernés.
- `important` et `mineur` : l'humain tranche. Corriger ce qu'il accepte, laisser le reste, et le dire
  dans la PR. Une remarque qui demande de sortir de la demande validée devient une note « à faire dans
  une autre branche », pas une modification de cette PR.
- Zéro `bloquant` et le diff touche `app/` ou `backend/` : rappeler l'étiquette `essai` si la PR doit
  être essayée sur une vraie tablette (`nouvelle-feature`, § 9).
