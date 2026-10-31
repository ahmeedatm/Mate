---
name: ship
description: Livre le travail en cours de unowhy-companion de bout en bout, sans demander - vérif locale rapide (format + typecheck), commit, push, MR GitLab, publication de la mise à jour d'essai pour la tablette (et compagnon du Mac si backend/ change) sans attendre le pipeline, puis fusion seulement après le go de l'utilisateur et le pipeline vert. Use PROACTIVELY - invoquer de soi-même dès qu'une étape est terminée et vérifiée, sans attendre de demande. Aussi via /ship [titre].
---

# /ship

Autorisé sans confirmation (voir `.claude/rules/autonomie-hackathon.md`). Projet GitLab :
`efreifer/unowhy-companion`, hôte `gitlab.unowhy.dev`.

## 1. Branche

- `git branch --show-current`. Sur `main` : `git switch -c <type>/<description-courte>` (le travail
  non commité suit).
- `git fetch --prune origin`. Si `origin/main` a avancé : `git rebase origin/main` avant le push
  (branche pas encore poussée) ou `git merge origin/main` (déjà poussée). Conflit : le résoudre.

## 2. Vérif locale rapide (selon les fichiers touchés)

Fichiers : `git diff --name-only origin/main...HEAD` + `git status --porcelain`.

| Touché | Commande |
|---|---|
| `backend/` | `cd backend && npm run format && npm run lint` |
| `app/`, `*.gradle*` | `./gradlew ktlintFormat ktlintCheck` |
| `backend/src/win/helper/`, `native.cs` | `cd backend && npm run build:helper` (exe gardés dans le dépôt) |
| docs seules | rien |

Le format est bloquant en CI (`npm run lint` = prettier + typecheck, `ktlintCheck`). Toujours formater
**après** le rebase/merge de `origin/main` : `main` a été reformaté (MR !21), le code d'une branche
plus ancienne ne passe plus sans ça. Le reformatage va dans le commit de l'étape, pas dans un fichier
hors sujet. Gradle refuse `-XX:MaxPermSize` / `android.enableBuildCache` du `~/.gradle/gradle.properties`
du poste : lancer avec `GRADLE_USER_HOME=/tmp/gh-companion` (lien `wrapper` → `~/.gradle/wrapper`).

Seulement format + typecheck : tests, lint Android et émulateur tournent en CI, pas en local. Rouge : corriger
et relancer. Pas de push tant que ce n'est pas vert.

## 3. Commit

- `git add` des fichiers du sujet seulement. Jamais `.env`, `backend/.data/`, `dist/`, APK.
- Un commit par étape autonome. Titre français sans préfixe, corps court sur le pourquoi.

## 4. Push et MR

```bash
git push -u origin HEAD
glab mr list --source-branch "$(git branch --show-current)" --output json
```

Pas de MR : la créer avec le template du dépôt rempli.

```bash
glab mr create --target-branch main --title "<titre>" --description "<desc>" \
  --assignee "$(glab api user | jq -r .username)" --remove-source-branch --yes
```

Jamais d'auto-merge : la fusion attend le go de l'utilisateur (étape 7).

Description = `.gitlab/merge_request_templates/Default.md` rempli : « Ce que fait la MR » en une ou
deux phrases, et dans « Testé sur » ne cocher que ce qui a vraiment été testé.

MR existante : le push suffit.

## 5. Publier l'essai pour le test, sans attendre le pipeline

Juste après le push, depuis le worktree de la branche, au premier plan (timeout Bash 600000, 3 à 5 min) :

```bash
@REPO@/.claude/skills/ship/essai.sh
```

Le pipeline `essai/mr-<iid>` ne dépend pas de celui de la MR : les deux tournent en même temps.

Il pousse `essai/mr-<iid>` (titre de la MR en variable `MR_TITLE`), lance le job manuel `essai-android`, attend
`essai-updates`, qui dépose l'essai dans le paquet commun `companion-updates/essais` (une entrée par MR, backend de la
MR compris si `backend/` change). Transition, tant que la stable de la tablette ne lit pas ce registre : l'essai est
aussi annoncé par le compagnon du Mac, qui passe sur la branche si `backend/` a changé. Dernière ligne utile :
`essai X.Y.Z-essai.N de la MR !<iid> publié (backend : oui|non)`.

- Une entrée par MR : publier une MR ne remplace plus l'essai d'une autre. Le Mac, lui, n'annonce et ne porte que le
  dernier publié (transition) : toujours dire quelle MR est sur la tablette.
- Sortie non nulle : lire le message (job en échec : `glab ci trace <id>`), corriger, reprendre à l'étape 2.
- Docs seules, CI seule, `.claude/` : pas d'essai, étape 7 en le disant.
- L'essai n'est pas device owner : Mises à jour, WiFi et carte Kiosque ne s'y testent pas. Si la tâche les touche,
  le dire ; test par adb seulement avec une tablette en APK debug (`deploy-tablette.sh`).

## 6. État du pipeline de la MR

Essai publié : lire l'état du pipeline de la MR, sans l'attendre s'il tourne encore.

```bash
glab api "projects/efreifer%2Funowhy-companion/merge_requests/<iid>" | jq -r .head_pipeline.status
```

- `success` ou `running` : étape 7.
- `failed` : étape 7 quand même, avec une ligne `⚠️ pipeline rouge (<job>)`. La correction se fait au retour
  suivant ou avant la fusion (`glab ci trace <job-id>`, corriger, reprendre à l'étape 2).

## 7. Retours de l'utilisateur, puis fusion

Ce message est tout le message final, puis s'arrêter et attendre. Rien avant ni après : pas de résumé du
travail, pas de liste de fichiers, pas de compte rendu (étape 8), pas de pipeline. Le détail est dans la MR.

```
C'est fini. Essai X.Y.Z-essai.N à installer (MR !<iid>).
Installer : Réglages > Mises à jour > Rechercher, « Mettre à jour » sur Mate essai, puis « Ouvrir l'essai ».
Tester : <1 à 3 lignes : où aller, quel geste, ce qui doit se passer>
```

Une ligne `⚠️` en plus seulement si quelque chose ne se teste pas dans l'essai (device owner, Mac à relancer) ou si le
pipeline de la MR est rouge.

- **Retour** : corriger sur la même branche, reprendre à l'étape 2 (nouvel essai), même message. Pas de nouvelle
  salve de questions, sauf si le retour change le besoin.
- **Go** (« go », « ok », « c'est bon », « fusionne ») : attendre le pipeline de la MR
  (`wait-until.sh --timeout 1800 -- gitlab-status.sh pipeline <id> efreifer/unowhy-companion`) ; rouge : corriger,
  reprendre à l'étape 2 sans republier d'essai. Vert et pas de conflit :
  `glab mr merge <iid> --yes` (sans `--auto-merge`), retirer l'essai (branche `essai/mr-<iid>` et entrée du
  registre) : `@REPO@/.claude/skills/ship/essai.sh --retire <iid>`, revenir sur
  `main` à jour.
- **Abandon** : retirer l'essai de la même façon ; si le Mac porte la branche, le remettre sur `main` :
  `git -C @REPO@ pull --ff-only` puis, depuis ce dossier,
  `BACKEND=1 APK=0 .claude/skills/ship/deploy-tablette.sh`. Sur la tablette, « Revenir à la stable » dans l'essai.

## 8. Compte rendu

Après la fusion, l'abandon, ou quand il n'y a pas d'essai (étape 5) : une ligne, URL de la MR, statut du
pipeline, fusionnée ou non. En attente de test, c'est le message de l'étape 7 qui sert de compte rendu.
