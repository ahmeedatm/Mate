# GitLab par jeton personnel collé (widgets GitLab et Pipelines)

Sur la tablette, les widgets **GitLab** et **Pipelines** montrent les MR et les pipelines de l'utilisateur, et fusionnent les MR prêtes
(si le jeton le permet : voir « Portées »). Le compte se connecte **une fois dans la page du compagnon** (http://127.0.0.1:3000/), en
collant un jeton personnel GitLab. Aucune appli OAuth à créer sur GitLab.

Parcours : widget GitLab › carte **Connecter GitLab** › la page de l'ordinateur s'ouvre sur la carte GitLab › **Créer un jeton** (page de
création de jeton de GitLab, nom `Mate` et portée `api` déjà remplis) › créer le jeton, le copier › le coller dans le champ › **Enregistrer** :
le nom du compte s'affiche (« GitLab · camille ») et les widgets se remplissent seuls (relus toutes les 3 s en attendant).
« Déconnecter GitLab » : même carte.

Le lien « Créer un jeton » est `<GITLAB_URL>/-/user_settings/personal_access_tokens?name=Mate&scopes=api`. `GITLAB_URL`
(défaut `https://gitlab.com`) désigne l'instance.

## Portées

| Portée | Ce qu'elle permet |
|---|---|
| `read_api` | lire les MR et les pipelines, les ouvrir sur l'ordinateur ; **pas** de fusion |
| `api` | tout ce qui précède, plus fusionner depuis la tablette |

À l'enregistrement, le compagnon lit les portées du jeton (`GET /api/v4/personal_access_tokens/self`, GitLab 15.5 et plus) :

- `api` : lecture et fusion ;
- `read_api` seul : `canMerge` faux, la tablette montre « Fusionner sur l'ordi » et `POST /gitlab/mrs/merge` répond 409 « La connexion
  GitLab est en lecture seule : fusionne sur l'ordinateur » sans appeler GitLab ;
- ni `api` ni `read_api` : jeton refusé, rien n'est enregistré ;
- GitLab ne donne pas les portées : `api` supposé (la fusion dira si c'est faux).

## Comportement

- **Vérification** : le jeton est essayé auprès de GitLab (`GET /api/v4/user`, en-tête `PRIVATE-TOKEN`) avant d'être gardé. Refusé :
  « GitLab refuse ce jeton : vérifie qu'il est complet et qu'il n'a pas expiré », rien d'enregistré. GitLab injoignable : « GitLab ne répond pas ».
- **Stockage** : `.data/gitlab.json` (droits 0600) de l'ordinateur, pris en compte tout de suite, sans redémarrage. Rien ne va sur la
  tablette, ni dans une réponse, ni dans un journal, ni dans la page (le champ se vide après l'envoi).
- **Jeton refusé plus tard** (révoqué, expiré) : la lecture répond 502 (jamais 401), puis `expired: true` ; la page affiche « jeton refusé »
  et réclame un nouveau jeton, la tablette renvoie vers elle.
- **`GITLAB_TOKEN` du `.env`** reste en secours (portée `api`). Le jeton collé prime pour lire ; pour fusionner, il faut qu'il ait `api`, sinon
  `GITLAB_TOKEN` sert.
- **Ancien compte OAuth** (`refreshToken` dans `.data/gitlab.json`) : ignoré. La carte demande de coller un jeton.
- **Pas connecté** : `GET /gitlab/mrs` répond 200 `{ "connected": false, "expired": false, "canMerge": false, "mine": [], "review": [] }`
  (jamais 401, qui voudrait dire « tablette à réappairer »).
- **Widgets toujours proposés** : `/deck/features` → `gitlab` vrai sur tout ordinateur piloté (hors démo), pour que la carte « Connecter
  GitLab » soit trouvable.
- Routes (page locale, sans jeton d'appairage, depuis 127.0.0.1 seulement) : `PUT /local/accounts/gitlab { token }` (200 `{ ok, username }`,
  400 refusé, 502 injoignable), `DELETE /local/accounts/gitlab`. La tablette ouvre la carte par `POST /local/open { "section": "gitlab" }`
  (`/?section=gitlab`).
- Code : `backend/src/gitlab-auth.ts` (jeton), `backend/src/gitlab.ts` (MR et fusion), carte dans `backend/src/local-page.ts`.

## Essayer sans GitLab (émulateur)

`backend/test/gitlab-demo.ts` lance un compagnon relié à un faux GitLab (API, GraphQL, fusion, portées du jeton) et un port de pilotage
qui joue l'utilisateur :

```bash
cd backend && node --import tsx test/gitlab-demo.ts   # compagnon 3930, faux GitLab 3931, pilotage 3932
curl -XPOST localhost:3932/login                      # colle le jeton de démo dans la page locale
```

`POST /revoke` (GitLab révoque le jeton : « jeton refusé »), `GET /seen` (requêtes reçues, jeton masqué), `GET /opened` (adresses que le
compagnon a ouvertes). Les tests : `backend/test/gitlab-token.test.ts` (vérification, stockage, portées, jeton refusé, secours `.env`, jeton
jamais exposé), `backend/e2e/gitlab.spec.ts` (page locale dans un navigateur).
