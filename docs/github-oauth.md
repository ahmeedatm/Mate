# GitHub par connexion au compte (widgets GitHub et Actions)

Sur la tablette, le widget **GitHub** montre les pull requests de l'utilisateur (« Mes PR » : écrites ou assignées ; « À relire » :
relecture demandée) et fusionne celles qui sont prêtes ; le widget **Actions** montre le dernier run GitHub Actions de la branche
par défaut des dépôts de ces PR (8 au plus). Calqués sur GitLab ([gitlab-oauth.md](gitlab-oauth.md)) : mêmes vues sur la tablette,
mêmes réponses du compagnon (`project` = `propriétaire/dépôt`, `iid` = numéro de la PR, pipeline = contrôles du dernier commit).

## Connexion : « device flow »

GitHub ne permet pas à une appli OAuth de se passer de secret avec PKCE : pour une appli de bureau publique, sa voie est le
**device flow** (RFC 8628). Parcours : widget GitHub › carte **Connecter GitHub** › la page du compagnon s'ouvre sur l'ordinateur
et affiche un **code** › « Ouvrir GitHub » (https://github.com/login/device) › saisir le code › **Authorize** › le widget se
remplit seul (relu toutes les 3 s en attendant). Aucune adresse de retour : le compagnon interroge GitHub jusqu'à l'autorisation
(15 min au plus, puis « Le code a expiré »).

## Ce que fait le propriétaire de l'organisation GitHub (une fois)

**Settings** › **Developer settings** › **OAuth Apps** › **New OAuth App** :

| Champ | Valeur |
|---|---|
| Application name | `UnoMate` |
| Homepage URL | `https://mate.integration.unowhy.dev` |
| Authorization callback URL | `http://127.0.0.1:3000/` (exigée par le formulaire, pas utilisée par le device flow) |
| **Enable Device Flow** | **cochée** |

Puis copier le **Client ID**. Le **secret n'est pas utilisé** : ne pas en générer. Une organisation qui restreint les applis OAuth
doit approuver UnoMate (Settings › Third-party access).

Coller l'ID (public) dans `backend/config/accounts.json` ; il est livré avec le logiciel :

```json
"github": { "clientId": "<Client ID>", "scope": "repo" }
```

Relu à chaque appel. Pour un essai : `GITHUB_CLIENT_ID` dans `.env`. ID vide : pas de bouton ; les widgets n'apparaissent que si
`GITHUB_TOKEN` est réglé.

## Portées

GitHub n'a pas de portée « lecture seule » des dépôts privés pour une appli OAuth : la portée par défaut est **`repo`**, qui permet
aussi de fusionner. `"scope": "public_repo"` limite aux dépôts publics (fusion comprise) ; une portée sans `repo` ni `public_repo`
(par exemple `read:user`) ne lit que les dépôts publics et **ne fusionne pas** : `canMerge: false`, la tablette montre « Fusionner
sur l'ordi », `POST /github/prs/merge` répond 409 « La connexion GitHub est en lecture seule : fusionne sur l'ordinateur » sans
appeler GitHub. Le compagnon garde la portée réellement accordée (champ `scope` de la réponse du jeton).

## Comportement

- **Jeton** : celui d'une appli OAuth n'expire pas ; gardé dans `.data/github.json` (0600) de l'ordinateur, jamais sur la tablette,
  ni dans une réponse, ni dans un journal. Le code d'appareil (secret du device flow) reste en mémoire ; seul le code à saisir est
  montré par la page locale. Révoqué sur GitHub (401) : le compte passe à « à reconnecter » (`expired: true`).
- **`GITHUB_TOKEN` du `.env`** reste en secours (jeton personnel). Le compte connecté prime pour lire ; pour fusionner : le compte
  s'il a `repo` (ou `public_repo`), sinon `GITHUB_TOKEN`.
- **Pas connecté** : `GET /github/prs` répond 200 `{ "connected": false, "expired": false, "canMerge": false, "mine": [], "review": [] }`,
  `GET /github/actions` 200 `{ "connected": false, "expired": false, "pipelines": [] }` (jamais 401 : accès refusé = 502).
- Lecture : une requête GraphQL (`author:@me`, `assignee:@me`, `review-requested:@me`), gardée 20 s ; runs : `GET
  /repos/:dépôt/actions/runs?branch=<branche par défaut>&per_page=1`, jobs en échec (3 au plus) d'un run échoué.
- Fusion : `POST /github/prs/merge { project, iid, sha }` → `PUT /repos/:dépôt/pulls/:n/merge { sha }` : GitHub refuse (409) si la
  tête a bougé. Ouverture : `POST /github/prs/open`, `POST /github/actions/open` (adresses reconstruites par le compagnon).
- `/deck/features` : `github` vrai si `GITHUB_TOKEN`, compte connecté ou ID d'appli réglé (jamais en démo).
- Routes de la page locale (sans jeton d'appairage, 127.0.0.1 seulement) : `GET /oauth/github/start` (demande un code, revient sur
  `/?section=github`), `DELETE /local/accounts/github` ; état dans `github` de `GET /local/status` (`login` : code en attente ou
  dernier résultat). La tablette ouvre la connexion par `POST /local/open { "section": "github" }`.
- Code : `backend/src/github-auth.ts` (connexion), `backend/src/github.ts` (PR, fusion, Actions), `routes/github.ts` ; tablette :
  `net/GitHubApi`, `github/Forge` ; vues partagées avec GitLab (`GitLabView`, `PipelinesView`, `GitLabConnectCard`, paramètre `forge`).
- Tests : `backend/test/github-api.test.ts` (faux GitHub `test/fake-github.ts` : device flow, GraphQL, fusion, Actions),
  `backend/test/github.test.ts`, `backend/e2e/github.spec.ts` (page locale).
