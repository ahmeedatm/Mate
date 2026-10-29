---
name: ship
description: Livre le travail en cours de unowhy-companion de bout en bout, sans demander - vérif locale selon les dossiers touchés, commit, push, MR GitLab, suivi du pipeline jusqu'au vert, publication de la mise à jour d'essai pour la tablette (et compagnon du Mac si backend/ change), puis fusion seulement après le go de l'utilisateur. Use PROACTIVELY - invoquer de soi-même dès qu'une étape est terminée et vérifiée, sans attendre de demande. Aussi via /ship [titre].
---

# /ship

Autorisé sans confirmation (voir `.claude/rules/autonomie-hackathon.md`). Projet GitLab :
`efreifer/unowhy-companion`, hôte `gitlab.unowhy.dev`.

## 1. Branche

- `git branch --show-current`. Sur `main` : `git switch -c <type>/<description-courte>` (le travail
  non commité suit).
- `git fetch --prune origin`. Si `origin/main` a avancé : `git rebase origin/main` avant le push
  (branche pas encore poussée) ou `git merge origin/main` (déjà poussée). Conflit : le résoudre.

## 2. Vérif locale (selon les fichiers touchés)

Fichiers : `git diff --name-only origin/main...HEAD` + `git status --porcelain`.

| Touché | Commande |
|---|---|
| `backend/` | `cd backend && npm run format && npm run lint && npm test` |
| `app/`, `*.gradle*` | `./gradlew ktlintFormat ktlintCheck testDebugUnitTest` |
| `backend/src/win/helper/`, `native.cs` | `cd backend && npm run build:helper` (exe gardés dans le dépôt) |
| docs seules | rien |

Le format est bloquant en CI (`npm run lint` = prettier + typecheck, `ktlintCheck`). Toujours formater
**après** le rebase/merge de `origin/main` : `main` a été reformaté (MR !21), le code d'une branche
plus ancienne ne passe plus sans ça. Le reformatage va dans le commit de l'étape, pas dans un fichier
hors sujet. Gradle refuse `-XX:MaxPermSize` / `android.enableBuildCache` du `~/.gradle/gradle.properties`
du poste : lancer avec `GRADLE_USER_HOME=/tmp/gh-companion` (lien `wrapper` → `~/.gradle/wrapper`).

Rouge : corriger et relancer. Pas de push tant que ce n'est pas vert.

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

## 5. Suivre le pipeline

```bash
pid=$(glab api "projects/efreifer%2Funowhy-companion/merge_requests/<iid>" | jq -r .head_pipeline.id)
~/.claude/scripts/wait-until.sh --timeout 1800 --interval 30 -- \
  ~/.claude/scripts/gitlab-status.sh pipeline "$pid" efreifer/unowhy-companion
```

En arrière-plan (`run_in_background: true`), puis continuer le travail suivant.
- `success` : étape 6.
- `failed` : `glab ci trace <job-id>`, corriger, recommencer à l'étape 2.

## 6. Publier l'essai pour le test

Pipeline de la MR vert, depuis le worktree de la branche, en arrière-plan (`run_in_background: true`, 5 min
environ) :

```bash
@REPO@/.claude/skills/ship/essai.sh
```

Il pousse `essai/mr-<iid>` (le titre de la MR part en variable de pipeline `MR_TITLE`), lance le job manuel
`essai-android` et attend `essai-updates`, qui dépose l'essai dans le paquet `companion-updates/essais` : une entrée
par MR, avec l'archive du backend (Mac et Windows) si `backend/` diffère de `main`. Il ne touche ni à la tablette ni
au compagnon du Mac. Dernière ligne utile : `essai X.Y.Z-essai.N de la MR !<iid> publié (backend : oui|non)`.

- Plusieurs essais coexistent (une entrée par MR) : republier la même MR remplace seulement la sienne. Le choix se
  fait sur la tablette, Réglages > Mises à jour.
- Prérequis, à dire à l'utilisateur au premier essai : la tablette et ses ordinateurs doivent avoir cette version
  (liste des essais, bascule du backend) en stable avant de pouvoir choisir une MR. Mettre à jour le PC d'Eliot
  seulement avec l'accord de l'utilisateur.
- Sortie non nulle : lire le message (job en échec : `glab ci trace <id>`), corriger, reprendre à l'étape 2.
- Docs seules, CI seule, `.claude/` : pas d'essai, étape 7 en le disant.
- L'essai n'est pas device owner : Mises à jour, WiFi et carte Kiosque ne s'y testent pas. Si la tâche les touche,
  le dire ; test par adb seulement avec une tablette en APK debug (`deploy-tablette.sh`).
- Backend d'une MR : ordinateur installé par paquet seulement (lancé depuis le dépôt, la tablette affiche un refus
  clair). L'ordinateur garde son backend d'origine et le retrouve avec « Revenir à la stable ».

## 7. Retours de l'utilisateur, puis fusion

Ce message est tout le message final, puis s'arrêter et attendre. Rien avant ni après : pas de résumé du
travail, pas de liste de fichiers, pas de compte rendu (étape 8), pas de pipeline. Le détail est dans la MR.

```
C'est fini. Essai X.Y.Z-essai.N à installer (MR !<iid>).
Installer : Réglages > Mises à jour > Rechercher, section « Essais de MR », « Mettre à jour » sur la ligne « !<iid> … », puis « Ouvrir l'essai » (backend de l'ordinateur basculé seul si la MR le touche).
Tester : <1 à 3 lignes : où aller, quel geste, ce qui doit se passer>
```

Une ligne `⚠️` en plus seulement si quelque chose ne se teste pas dans l'essai (device owner, ordinateur lancé depuis le dépôt).

- **Retour** : corriger sur la même branche, reprendre à l'étape 2 (nouvel essai), même message. Pas de nouvelle
  salve de questions, sauf si le retour change le besoin.
- **Go** (« go », « ok », « c'est bon », « fusionne ») : vérifier pipeline vert et pas de conflit, puis
  `glab mr merge <iid> --yes` (sans `--auto-merge`), retirer l'essai de la MR
  (`@REPO@/.claude/skills/ship/essai.sh --retire <iid>` : branche `essai/mr-<iid>` supprimée et entrée du manifest
  retirée, `GITLAB_TOKEN` dans l'environnement), revenir sur `main` à jour.
- **Abandon** : retirer l'essai de la même façon. Sur la tablette, « Revenir à la stable » dans l'essai remet le
  backend d'origine de l'ordinateur.

## 8. Compte rendu

Après la fusion, l'abandon, ou quand il n'y a pas d'essai (étape 6) : une ligne, URL de la MR, statut du
pipeline, fusionnée ou non. En attente de test, c'est le message de l'étape 7 qui sert de compte rendu.
