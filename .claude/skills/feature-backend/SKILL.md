---
name: feature-backend
description: À charger avant d'écrire ou de modifier le compagnon de l'ordinateur de Mate (dossier `backend/` : Node 22, TypeScript, Fastify, pilotes Mac et Windows, outils MCP), après `nouvelle-feature`. Donne la structure à respecter, les tests à écrire, le chemin de sécurité et les commandes de vérification sur Mac et Windows.
---

# feature-backend

Conventions et règles de travail : `CLAUDE.md`, § Conventions du code (bloc « Backend ») et
§ Règles de travail. Détail de la fonction touchée : `docs/architecture.md`, § Détail par fonction.
Sécurité du jeton et de l'appairage : `docs/securite-appairage.md`, à lire avant toute route.
Ne rien recopier d'ici dans la PR : renvoyer à ces documents.

## À vérifier

Règles et raisons : `CLAUDE.md` § Conventions du code (bloc « Backend »), `docs/securite-appairage.md`
et `docs/architecture.md` § Détail par fonction. Ce qu'on contrôle :

- un plugin Fastify par domaine dans `backend/src/routes/`, enregistré par `src/server.ts` ;
- tout accès au système par `src/os.ts` seulement, et son faux `backend/test/fake-os.ts` mis à jour ;
- une fonction propre à un système répond clairement sur l'autre (501 ou `null`) ;
- jeton exigé sur chaque route (exceptions et routes locales : `docs/securite-appairage.md`) ;
- entrées validées par un schéma, aucune URL ni commande construite depuis une donnée de la tablette ;
- aucun secret en journal, erreurs techniques remplacées par une phrase.

## Tests (rouge d'abord, vu échouer)

- Nouveau comportement : un test dans `backend/test/`, contre `backend/test/server.ts`.
- Page web servie par le backend : un test Playwright dans `backend/e2e/`.
- Nouvelle feature : un contrôle dans `backend/qa/sanity.ts` (lecture seule si possible).
- Nouvel ordre vers la tablette ou nouveau réglage de la page locale : tous les maillons de
  `CLAUDE.md` (type dans `tablet.ts`, schéma dans `routes/tablet.ts`, branche dans
  `RemoteChannel.execute` côté Android) et un outil dans `src/mcp-tools.ts` (suppression marquée
  `destructiveHint`). Charger aussi `feature-android` pour la branche Android.

## Commandes (Mac et Windows)

```
cd backend
npm run format
npm run lint
npm run coverage
```

Compagnon de test à côté du vrai (autres données, autre port) : voir `docs/developpement.md`,
§ Boucle de développement. Ne pas arrêter ni modifier le compagnon d'un autre. Sous Windows, passer
les variables d'environnement par le shell utilisé (`$env:PORT=3021` en PowerShell).

## Installation, Mac, Windows

`src/mac/`, `src/win/`, `os.ts`, `service.ts` ou les scripts d'installation touchés : les tests
d'installation à la main de `docs/developpement.md`, § Branches, PR et CI
(`backend/qa/install-mac.sh`, `install-windows.ps1`) sont à faire sur le système concerné, puis à
cocher dans « Testé sur ». Sans la machine : le dire dans la PR, ne pas cocher.

## Fin

Mettre à jour `docs/architecture.md` (§ Détail par fonction) si le comportement décrit change, puis
charger la skill `relecture`.
