# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

Ce fichier sert aux pages web servies par le compagnon de l'ordinateur (`backend/`). L'appli de la
tablette est native Android (vues XML, Kotlin) : sa finition passe par la skill
`.claude/skills/design-companion`, pas par ce fichier.

## Users

- **L'utilisateur**, qui a un Mac ou un PC Windows et une tablette 4.2 posée à côté, au bureau ou à
  la maison : joueur, amateur de domotique casual, ou simple curieux. Sur l'ordinateur, il appaire la
  tablette, règle la tablette (thème, écran, son), relie son agenda et GitLab, puis revient
  rarement sur la page.
- **Celui qui dépanne**, qui installe le compagnon pour un proche ou un collègue et vérifie que tout
  marche (pare-feu, Accessibilité Mac, mise à jour).

## Product Purpose

Mate transforme une tablette 4.2 en écran de contrôle tactile relié en WiFi à un Mac ou un
PC Windows : piloter l'ordinateur, ses applis et ses fenêtres, voir son agenda, ses tâches, ses MR,
lancer des macros, sur le modèle du Stream Deck. Le compagnon de l'ordinateur exécute ce que la
tablette demande. Sa page locale est le panneau de configuration côté ordinateur.

Succès : la tablette est appairée en moins d'une minute, l'utilisateur sait toujours si le compagnon
et la tablette marchent, et il règle depuis l'ordinateur ce qui est pénible au doigt.

## Positioning

Un accessoire personnalisable, polyvalent et robuste, qui réutilise une tablette existante au lieu
d'un nouveau matériel. Pas de positionnement commercial.

## Operating Context

- Page ouverte dans le navigateur de l'ordinateur sur `http://127.0.0.1:<port>/`, sans jeton, en
  local seulement. Relancer l'appli « Mate » la rouvre.
- Moments d'usage : première installation et appairage (code à 6 chiffres affiché par la tablette,
  valable 2 minutes), réglages ponctuels, dépannage quand la tablette ne trouve plus l'ordinateur.
- La tablette peut ouvrir la page sur une section précise (`?section=agenda`, `gitlab`).
- Mac et Windows, mode clair et sombre du système.

## Capabilities and Constraints

- Sections actuelles : état du compagnon et de la tablette, adresse, appairage, réglages de la
  tablette connectée (thème, luminosité, volume, nuit, Ne pas déranger, mise à jour), agenda
  (comptes Microsoft / Google, lien ICS), GitLab (connexion OAuth), « Cette appli » (lancement au
  démarrage, pare-feu, mise à jour, extension, quitter, tout oublier),
  Accessibilité Mac.
- Visée (30/10/2026) : devenir un panneau de configuration complet, dont la configuration des
  profils de deck depuis l'ordinateur (travail mené à part, worktree `config-profils-deck-pc`).
- Autres pages web : visualiseur `/camera` (la tablette sert de caméra de l'ordinateur), retours
  OAuth (redirigent vers la page locale).
- HTML écrit dans une chaîne TypeScript, sans framework, sans bundler, sans dépendance, sans
  ressource chargée depuis Internet : la page marche hors ligne. Texte injecté par `textContent`.
- Tout changement visible a son test Playwright dans `backend/e2e/`.

## Brand Commitments

- Nom : **Mate** (badge « Mate »).
- La page doit sembler venir du même produit que la tablette : tokens de la DA « Console douce »
  (thèmes Console et Console jour, voir `DESIGN.md`) et police communs avec l'appli
  (`app/src/main/res/values/palettes.xml`, `backend/src/web-style.ts`).
- Icône Mate « visage » (`docs/ui-ux/da/icone-visage.svg`) sur les pages web du compagnon ; icône de l'appli (`docs/ui-ux/da/icone.svg`) ailleurs.
- Textes en français, tutoiement, mêmes mots que sur la tablette (« Réglages › Ordinateurs ›
  Ajouter », « Saisir l'adresse »).

## Evidence on Hand

- DA : `DESIGN.md` et `docs/ui-ux/da/`.
- Critique précédente de la page : `docs/team/design-companion/premiere-passe.md`.
- Aucun témoignage, client, chiffre ni prix dans le dépôt : ne pas en inventer.

## Product Principles

1. L'état d'abord : on sait en un regard si le compagnon tourne et si la tablette est reliée.
2. Une étape à la fois : l'appairage ne se perd pas au milieu des réglages.
3. Même produit que la tablette : mêmes couleurs, mêmes mots, même calme.
4. Rien ne ment : un compagnon arrêté ou une tablette absente se voit, les actions impossibles
   sont désactivées.
5. Hors ligne et léger : rien ne vient d'Internet.

## Accessibility & Inclusion

Accessibilité : contraste du texte 4,5:1 au moins dans les deux
modes, navigation au clavier avec focus visible, structure de titres lisible par un lecteur d'écran.
