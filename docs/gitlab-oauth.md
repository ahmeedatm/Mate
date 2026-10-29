# GitLab par connexion au compte (onglet GitLab du Deck)

Sur la tablette, l'onglet **GitLab** (Deck) montre les MR de l'utilisateur et fusionne celles qui sont prêtes. Le compte GitLab
se **connecte une fois dans le navigateur de l'ordinateur**, comme l'agenda ([agenda-comptes.md](agenda-comptes.md)) : plus de
jeton à créer et à copier dans `.env`.

Parcours : onglet GitLab › carte **Connecter GitLab** › la page d'autorisation de `gitlab.unowhy.dev` s'ouvre sur l'ordinateur ›
**Autoriser** › l'onglet se remplit seul (relu toutes les 3 s en attendant). « Déconnecter GitLab » : page du compagnon
(http://127.0.0.1:3000/), carte **GitLab**.

## Ce que l'admin GitLab crée (une fois, pour toute l'instance ou le groupe)

**Admin** › **Applications** › **Nouvelle application** (ou, au niveau d'un groupe : **Paramètres** › **Applications**) :

| Champ | Valeur |
|---|---|
| Nom | `Unowhy Companion` |
| URI de redirection (une par ligne) | `http://127.0.0.1:3000/oauth/gitlab/callback` |
| | `http://localhost:3000/oauth/gitlab/callback` |
| Confidentielle | **décochée** (appli de bureau : code d'autorisation + PKCE, pas de secret) |
| Approuvée (*Trusted*) | décochée (l'utilisateur voit l'écran d'autorisation) |
| Portée (*Scopes*) | **`api`** seulement (lecture des MR, fusion) |

Un autre port que 3000 (`PORT` dans `.env` du compagnon) : ajouter les deux adresses avec ce port. GitLab compare l'adresse de retour
caractère par caractère.

Puis **Enregistrer** et copier l'**ID de l'application** (*Application ID*). Le **secret n'est pas utilisé** : ne pas le diffuser.

## Ce que fait Unowhy ensuite

Coller l'ID (public) dans `backend/config/accounts.json` ; il est livré avec le logiciel :

```json
"gitlab": { "clientId": "<ID de l'application>" }
```

Le fichier est relu à chaque appel : pas de redémarrage. Pour un essai sans toucher au fichier : `GITLAB_CLIENT_ID` dans `.env`.
`GITLAB_URL` (défaut `https://gitlab.unowhy.dev`) désigne l'instance.

ID vide : pas de bouton de connexion, l'onglet n'apparaît que si `GITLAB_TOKEN` est réglé.

## Comportement

- **Jetons** : jeton d'accès (2 h) en mémoire ; jeton de renouvellement (tournant : chaque renouvellement donne un nouveau jeton et
  invalide l'ancien) dans `.data/gitlab.json` (droits 0600) de l'ordinateur. Rien ne va sur la tablette, ni dans une réponse, ni dans
  un journal. Renouvelé seul, un renouvellement à la fois.
- **`GITLAB_TOKEN` du `.env`** reste en secours (jeton personnel, portée `api`). Le compte connecté prime. Compte à reconnecter et
  `GITLAB_TOKEN` réglé : le jeton sert.
- **Pas connecté** : `GET /gitlab/mrs` répond 200 `{ "connected": false, "expired": false, "mine": [], "review": [] }` (jamais 401,
  qui voudrait dire « tablette à réappairer »). `expired: true` : GitLab a refusé le renouvellement (accès retiré dans GitLab,
  appli révoquée) : la tablette propose de reconnecter.
- `/deck/features` : `gitlab` vrai si `GITLAB_TOKEN`, compte connecté ou ID d'appli réglé.
- Routes (page locale, sans jeton d'appairage, depuis 127.0.0.1 seulement) : `GET /oauth/gitlab/start`, `GET /oauth/gitlab/callback`,
  `DELETE /local/accounts/gitlab`. La tablette ouvre la connexion par `POST /local/open { "section": "gitlab" }`.
- Code : `backend/src/gitlab-auth.ts` (OAuth), `backend/src/gitlab.ts` (MR et fusion).

Si ça bloque :

| Message | Cause | Solution |
|---|---|---|
| « The redirect URI included is not valid » sur la page GitLab | l'adresse de retour n'est pas dans l'application | ajouter exactement l'adresse affichée (port compris) |
| « The requested scope is invalid » | l'application n'a pas la portée `api` | cocher `api` dans l'application |
| « Client authentication failed » au retour | application créée **confidentielle** | décocher *Confidentielle* |
| carte GitLab absente de la page, onglet absent | ID vide et pas de `GITLAB_TOKEN` | renseigner `gitlab.clientId` |

## Essayer sans GitLab (émulateur)

`backend/test/gitlab-demo.ts` lance un compagnon relié à un faux GitLab (page d'autorisation, `/oauth/token` avec PKCE et jeton de
renouvellement tournant, GraphQL, fusion) et un port de pilotage qui joue le navigateur et l'utilisateur :

```bash
cd backend && node --import tsx test/gitlab-demo.ts   # compagnon 3930, faux GitLab 3931, pilotage 3932
curl -XPOST localhost:3932/login                      # autorise la connexion comme le ferait le navigateur
```

`POST /revoke` (GitLab révoque tout : « à reconnecter »), `POST /expire` (jeton d'accès périmé : renouvellement), `GET /seen`
(requêtes reçues), `GET /opened` (adresses que le compagnon a ouvertes). Les tests : `backend/test/gitlab-oauth.test.ts`
(flux, PKCE, state, renouvellement, déconnexion, jeton jamais exposé), `backend/e2e/gitlab.spec.ts` (page locale dans un navigateur).
