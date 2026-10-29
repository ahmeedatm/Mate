# Ouverture : le compagnon est programmable

Pour les développeurs (démo Léo). Trois portes vers la même mécanique : le deck de l'ordinateur. Une touche = une liste d'étapes
(`runAction`, `backend/src/deck.ts`), qu'on la déclare dans un fichier, par l'API HTTP ou en le demandant à Claude.

| Porte | Où | Pour qui |
|---|---|---|
| `deck.json` | `backend/config/deck.json` | l'équipe, versionnable à la main |
| Touches perso | `backend/.data/keys.json`, API `/deck/keys/:slot`, outil MCP `deck_touche_affecter` | l'utilisateur, ses scripts, Claude |
| API HTTP | `backend/src/routes/` | tout ce qui sait faire un `curl` |

La tablette n'envoie jamais d'action : seulement l'identifiant d'une touche (`POST /deck/run`). Seules les actions déclarées dans
`deck.json` ou dans les touches perso peuvent donc s'exécuter.

## 1. `deck.json`

`backend/config/deck.json` n'est pas versionné. Sans lui, le compagnon lit `deck.example.json` (Mac) ou
`deck.example.windows.json`. `DECK_FILE=/chemin/deck.json` pour un autre fichier. Une page par entrée de `pages`, un onglet par page
sur la tablette ; l'identifiant d'une touche est `<page>-<touche>` (`0-2` = page 0, touche 2).

```json
{
  "pages": [
    {
      "title": "Dev",
      "tiles": [
        { "label": "VS Code", "action": { "type": "app", "name": "Visual Studio Code" } },
        { "label": "Local", "glyph": "🌐", "action": { "type": "url", "url": "http://localhost:3000" } },
        {
          "label": "Tests",
          "glyph": "🧪",
          "color": "#2E2727",
          "action": {
            "type": "macro",
            "steps": [
              { "type": "app", "name": "iTerm" },
              { "type": "wait", "ms": 400 },
              { "type": "text", "text": "npm test" },
              { "type": "keys", "keys": "return" }
            ]
          }
        }
      ]
    }
  ]
}
```

Types d'étapes (`Step` dans `deck.ts`) : `app`, `url`, `keys`, `shortcut` (Mac), `media`, `system`, `text`, `wait`, `window`, et `press`
(appuyer sur un bouton d'une fenêtre, `deck.json` seulement). `macro` enchaîne des étapes dans l'ordre. Une `url` doit avoir un schéma
autorisé (`http(s)`, `mailto`, `msteams`, `slack`, `vscode`…), sinon la touche répond une erreur.

Les pages à clé spéciale (`apps`, `media`, `meeting`, `app`) sont gérées par la tablette : voir les commentaires de `DeckConfig`.

## 2. Touches perso, en JSON

Page « Perso » du deck : des emplacements vides qu'on remplit depuis la tablette, l'API ou Claude. Format d'une touche
(`backend/src/perso.ts`) :

```json
{
  "label": "Mode code",
  "glyph": "💻",
  "color": "#1F3A5F",
  "steps": [
    { "type": "window", "app": "Visual Studio Code", "layout": "max" },
    { "type": "window", "app": "Microsoft Teams", "layout": "left" },
    { "type": "keys", "keys": "cmd+shift+m", "app": "Microsoft Teams" }
  ]
}
```

- `label` 40 caractères au plus, `glyph` 8, `color` en `#RRGGBB`, de 1 à 20 étapes.
- Plusieurs étapes = macro, sans écrire `macro`. `press` n'existe pas ici.
- `window` : `layout` = `keep`, `left`, `right` ou `max` ; `display` = libellé d'écran (`GET /deck/catalog`).
- `keys` : `cmd+shift+m`, `ctrl+up`, `f5`, `return`… (sur Windows `cmd` = Ctrl).
- Les champs en trop sont retirés sans erreur.
- Le fichier `backend/.data/keys.json` garde `{"slots": [...]}` ; l'emplacement 0 devient la touche `k-0`.

## 3. API HTTP

Le compagnon écoute sur `http://127.0.0.1:3000` (`PORT` pour changer). Toute route sauf `GET /health` demande
`Authorization: Bearer <jeton>`. Le jeton est le champ `token` de `backend/.data/identity.json` (ou `COMPANION_TOKEN` du `.env`).
En WiFi c'est du HTTPS avec un certificat auto-signé, d'où `curl -k`.

```bash
TOKEN=$(jq -r .token backend/.data/identity.json)
H="Authorization: Bearer $TOKEN"; B=http://127.0.0.1:3000

curl $B/health                                   # identité, sans jeton
curl -H "$H" $B/deck                             # pages et touches, ids compris
curl -H "$H" $B/deck/catalog                     # applis installées, écrans, système

# Remplir la touche perso 0 (emplacements numérotés depuis 0)
curl -X PUT $B/deck/keys/0 -H "$H" -H "Content-Type: application/json" \
  -d '{"label":"Mode code","glyph":"💻","color":"#1F3A5F","steps":[{"type":"window","app":"Visual Studio Code","layout":"max"},{"type":"window","app":"Microsoft Teams","layout":"left"}]}'

curl -H "$H" $B/deck/keys/0                      # {"key": {...}}
curl -X POST $B/deck/run -H "$H" -H "Content-Type: application/json" -d '{"id":"k-0"}'   # l'exécute sur l'ordinateur
curl -X DELETE -H "$H" $B/deck/keys/0            # vide l'emplacement
```

Réponses : `{"ok":true}`, `404 {"error":"Tuile inconnue"}` pour un id absent, `401` sans jeton.

Piloter la tablette elle-même :

```bash
curl -X POST $B/tablet/command -H "$H" -H "Content-Type: application/json" -d '{"type":"mode","mode":"deck"}'
curl -X POST $B/tablet/command -H "$H" -H "Content-Type: application/json" -d '{"type":"message","text":"Build terminé","seconds":10}'
```

`type` : `mode`, `message`, `document`, `focus`, `noise`, `screenshot` (schéma dans `routes/tablet.ts`). Sans tablette connectée :
`502 Tablette non connectée`. Depuis l'ordinateur, sans jeton, la page locale règle aussi la tablette
(`PUT /local/tablet/settings`, par exemple `{"theme":"CONTRASTE"}`).

Une route par domaine dans `backend/src/routes/` : `deck`, `system` (son), `calendar`, `tasks`, `docs`, `windows`, `agents`, `jira`…

## 4. Outil MCP

`backend/src/mcp.ts` est un serveur MCP en stdio, déclaré dans `.mcp.json` à la racine sous le nom `tablette`. Il relaie vers le
compagnon déjà démarré : tout client MCP (Claude Code, Claude Desktop) pilote alors la tablette. Première fois : `cd backend && npm ci`,
puis lancer le compagnon (`npm run dev` ou le service).

Outils : `tablette_etat`, `tablette_capture`, `tablette_afficher_mode`, `tablette_message`, `tablette_document`, `tablette_focus`,
`tablette_ambiance`, `tablette_demander`, `docs_lister`, `taches_lister`, `tache_ajouter`, `tache_cocher`, `agenda_ajouter`,
`deck_touches_lister`, `deck_touche_affecter`, `deck_touche_effacer`, `agents_lister`.

Programmer une touche en une phrase à Claude : « Affecte la touche 1 du deck : VS Code en grand, Teams à gauche ». Il appelle :

```json
{
  "name": "deck_touche_affecter",
  "arguments": {
    "numero": 1,
    "libelle": "Mode code",
    "symbole": "💻",
    "couleur": "#1F3A5F",
    "etapes": [
      { "type": "window", "app": "Visual Studio Code", "layout": "max" },
      { "type": "window", "app": "Microsoft Teams", "layout": "left" }
    ]
  }
}
```

⚠️ L'outil numérote à partir de 1, l'API à partir de 0 : `numero: 1` écrit `PUT /deck/keys/0` (touche `k-0`).

## Démo

1. `GET /deck` : montrer les pages et les ids.
2. Éditer `deck.json` (ajouter la macro « Tests »), recharger le deck sur la tablette.
3. `PUT /deck/keys/0` en `curl` : la touche apparaît dans Perso.
4. Dans Claude Code : « affecte la touche 2 du deck… » puis `tablette_afficher_mode` sur `deck`.
5. `POST /deck/run` depuis un script : la touche s'exécute sur l'ordinateur, comme au doigt.

## Vérifié

Le 29/10/2026, sur un compagnon de test isolé (Mac, `HOST=127.0.0.1`, port et dossier de données à part, `deck.json` d'essai) :
`GET /health`, `GET /deck` (avec et sans jeton), `PUT/GET/DELETE /deck/keys/:slot`, `POST /deck/run` (touche `wait` seulement),
`GET /deck/catalog`, la validation de `/tablet/command`, le handshake MCP et les appels `deck_touche_affecter` et
`deck_touches_lister`.

⚠️ Non vérifié : l'exécution réelle des étapes `window`, `keys`, `app`, `text` et `url` des exemples (elles pilotent l'ordinateur),
Windows, et la remise d'un ordre à une vraie tablette (`/tablet/command` a répondu 502, aucune tablette connectée).
