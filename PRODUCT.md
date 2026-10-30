# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

Ce fichier sert aux pages web servies par le compagnon de l'ordinateur (`backend/`). L'appli de la
tablette est native Android (vues XML, Kotlin) : sa finition passe par la skill
`.claude/skills/design-companion`, pas par ce fichier.

## Users

- **Le pro**, employé qui travaille sur Mac ou PC Windows avec une tablette Mate posée sur son
  bureau. Sur l'ordinateur, il appaire la tablette, règle la tablette (thème, écran, son), relie son
  agenda et GitLab, puis revient rarement sur la page.
- **L'IT ou l'admin**, qui installe le compagnon sur des postes pour d'autres et vérifie que tout
  marche (pare-feu, Accessibilité Mac, mise à jour).

## Product Purpose

Mate transforme une tablette 4.2 en écran de contrôle tactile relié en WiFi à un Mac ou un
PC Windows : piloter l'ordinateur, ses applis et ses fenêtres, voir son agenda, ses tâches, ses MR,
lancer des macros, sur le modèle du Stream Deck. Le compagnon de l'ordinateur exécute ce que la
tablette demande. Sa page locale est le panneau de configuration côté ordinateur.

Succès : la tablette est appairée en moins d'une minute, l'utilisateur sait toujours si le compagnon
et la tablette marchent, et il règle depuis l'ordinateur ce qui est pénible au doigt.

## Positioning

Un accessoire de productivité personnalisable, polyvalent et robuste, destiné d'abord aux
professionnels (B2B), vendu seul ou avec un PC Unowhy. Il réutilise des tablettes existantes
(18 000 à valoriser) au lieu d'un nouveau matériel. B2C (indépendants, télétravail) en complément.

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
  démarrage, pare-feu, ancienne installation, mise à jour, extension, quitter, tout oublier),
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
- La page doit sembler venir du même produit que la tablette : tokens Cadran (jour / nuit, rouge
  Unowhy `#CA0538` = « maintenant ») et police Outfit, communs avec l'appli
  (`app/src/main/res/values/palettes.xml`, `backend/src/web-style.ts`).
- Icône Mate « visage » (`docs/ui-ux/da/icone-visage.svg`) sur les pages web du compagnon ; icône Cadran (`docs/ui-ux/da/icone.svg`) ailleurs.
- Textes en français, tutoiement, mêmes mots que sur la tablette (« Réglages › Ordinateurs ›
  Ajouter », « Saisir l'adresse »).

## Evidence on Hand

- DA Cadran : `docs/ui-ux/da/` (README, maquettes, captures, vidéo).
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

Accessibilité obligatoire pour vendre (COMP-50) : contraste du texte 4,5:1 au moins dans les deux
modes, navigation au clavier avec focus visible, structure de titres lisible par un lecteur d'écran.
