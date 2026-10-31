---
name: feature-backend
description: À charger avant d'écrire ou de modifier le compagnon de l'ordinateur de Mate (dossier `backend/` : Node 22, TypeScript, Fastify, pilotes Mac et Windows, outils MCP), après `nouvelle-feature`. Donne la structure à respecter, les tests à écrire, le chemin de sécurité et les commandes de vérification sur Mac et Windows.
---

# feature-backend

Conventions et règles de travail : `CLAUDE.md`, § Conventions du code (bloc « Backend ») et
§ Règles de travail. Détail de la fonction touchée : `docs/architecture.md`, § Détail par fonction.
Sécurité du jeton et de l'appairage : `docs/securite-appairage.md`, à lire avant toute route.
Ne rien recopier d'ici dans la PR : renvoyer à ces documents.

## Structure

- Un plugin Fastify par domaine dans `backend/src/routes/`, enregistré par `src/server.ts`
  (démarrage, jeton, erreurs, enregistrement des plugins : pas de logique métier dedans).
- Tout accès au système (lancer une appli, touches, fenêtres, son) passe par `src/os.ts`, seul point
  d'entrée ; `src/mac/` et `src/win/` ne sont jamais appelés depuis une route. Toute fonction
  ajoutée ou changée dans `os.ts` est reportée dans le faux `backend/test/fake-os.ts`.
- Une fonction qui n'existe que sur un système répond clairement sur l'autre (501 ou `null`).
- Types d'écrans et de fenêtres partagés : `src/screens.ts`.

## Sécurité

- Toute route exige le jeton d'appairage, sauf `/health`, `/pair/*` et les routes locales qui
  n'écoutent que 127.0.0.1. Ne jamais ouvrir au WiFi une route sans jeton.
- Entrées validées par un schéma (corps, paramètres, requête). Jamais d'URL ni de commande construite
  depuis une donnée de la tablette sans contrôle (la tablette envoie un identifiant, le backend
  reconstruit l'URL).
- Aucun secret, jeton ni contenu sensible en journal. Erreur technique = une phrase en français
  (`setErrorHandler`) ; un refus d'un service tiers ne devient pas un 401 (réservé au jeton).

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
