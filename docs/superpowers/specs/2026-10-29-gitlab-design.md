# GitLab dans le Deck — conception

29/10/2026 · Ahmed · branche `plugin-gitlab` (depuis `origin/main` 940bfd5) · carte COMP-37 du board

## Pourquoi

Voir depuis la tablette, sans ouvrir GitLab, les MR qui demandent une action : les siennes et celles à relire, avec
leur état (pipeline, approbations, fusion possible), et fusionner une MR prête. Modèle : le panneau MR d'Orca.

## Objectifs

- Un onglet « GitLab » dans le Deck, après « Agents ».
- Deux colonnes : **Mes MR** (l'utilisateur du jeton est assigné) et **À relire** (il est relecteur). MR ouvertes,
  tous projets confondus. Une MR présente dans les deux listes apparaît dans les deux.
- Une carte par MR : `projet!numéro`, titre, auteur, brouillon, pipeline (réussi, en cours, échoué, annulé, aucun),
  approbations (`1/2` et qui a approuvé), état de fusion en clair.
- Toucher une carte : la MR s'ouvre dans le navigateur de l'ordinateur.
- Bouton « Fusionner » sur la carte, actif seulement si GitLab dit `MERGEABLE`, confirmé par appui maintenu
  (`HoldButton`). Refus de GitLab = phrase en français sur la carte.
- Relecture toutes les 30 s tant que l'onglet est affiché ; rien en arrière-plan.
- Mac et Windows.

## Non-objectifs

Approuver, commenter, relancer un pipeline, fusion automatique quand le pipeline passe, notifications ou lumière sur
la tablette, widget de profil. Possibles plus tard.

## Anti-objectifs

- Jeton GitLab sur la tablette, dans un intent, un journal ou une réponse HTTP.
- Fusionner autre chose que ce que la carte affiche (commit poussé entre l'affichage et l'appui).

## Choix techniques (Claude)

- **Accès** : `GITLAB_URL` (défaut `https://gitlab.unowhy.dev`) et `GITLAB_TOKEN` (portée `api`, nécessaire à la
  fusion) dans `backend/.env`, sur le modèle de `jira.ts`. Pas de `glab` : le lancement automatique ne voit pas la
  variable du shell et `glab` n'existe pas sur Windows.
- **Lecture** : une seule requête GraphQL (`currentUser { assignedMergeRequests, reviewRequestedMergeRequests }`,
  `state: opened`, `first: 50`) avec `iid`, `title`, `draft`, `webUrl`, `detailedMergeStatus`, `diffHeadSha`,
  `approvalsRequired`, `approvalsLeft`, `approvedBy`, `author`, `project.fullPath`, `headPipeline.status`. Vérifiée le
  29/10 sur GitLab 18.10.8-ee avec le compte aatmane. Résultat gardé 20 s (plusieurs tablettes, onglet rouvert).
- **Fusion** : REST `PUT /projects/:fullPath/merge_requests/:iid/merge` avec `sha` = `diffHeadSha` affiché. GitLab
  refuse (409) si la MR a bougé. Le cache est vidé après une fusion.
- **Backend** : `backend/src/gitlab.ts` (client, mise en forme, libellés d'état), plugin `backend/src/routes/gitlab.ts`
  (`GET /gitlab/mrs`, `POST /gitlab/mrs/merge` `{ project, iid, sha }`), `gitlab` dans `GET /deck/features` (vrai si
  le jeton est réglé). Ouverture de la MR : `POST /gitlab/mrs/open` `{ project, iid }`, l'URL est reconstruite côté
  backend (jamais une URL fournie par la tablette).
- **Tablette** : `net/GitLabApi.kt` (`client.gitlab`), `ui/GitLabView.kt` sur le modèle d'`AgentsView` (vue autonome,
  `attach`/`setActive`, posable plus tard dans un widget de profil V2), onglet dans `DeckFragment` comme Agents. Textes
  dans `strings.xml`, couleurs `?attr/c_…`.
- **Tests** : `backend/test/gitlab.test.ts` (fetch simulé : mise en forme, libellés, fusion avec `sha`, refus 405/409,
  jeton absent), contrôle dans `backend/qa/sanity.ts`, logique pure Android (libellés, activation du bouton) dans
  `app/src/test/`.

## Laissé au constructeur

Disposition fine de la carte, libellés exacts des états, ordre des cartes (mon choix : plus récentes en haut).
