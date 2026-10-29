# Web : les pages servies par le backend

## Ce qui existe

| Page | Fichier | Rôle | Test |
|---|---|---|---|
| `GET /` page locale | `backend/src/local-page.ts` | nom, adresse, code d'appairage en grand, Accessibilité Mac, agenda (comptes, lien ICS) | `backend/e2e/local-page.spec.ts`, `oauth.spec.ts` |
| `GET /camera` visualiseur | `backend/src/camera.ts` | la tablette sert de caméra de l'ordinateur (MJPEG, Figer, Pivoter) | `backend/e2e/camera.spec.ts` |
| Extension de navigateur | `backend/extension/` | pas d'interface : envoie l'onglet courant au backend | aucun visuel |

Ouvertes sur l'ordinateur, sans jeton, en local seulement (`isLocalPage` dans `server.ts`).
Lues par quelqu'un qui installe ou dépanne : la page doit dire où on en est et quoi faire ensuite.

## Contraintes

- HTML écrit dans une chaîne TypeScript et renvoyé tel quel. Pas de framework, pas de bundler,
  pas de dépendance, pas de police ni d'image chargée depuis Internet (la page doit marcher hors
  ligne).
- CSS dans un `<style>` : variables `--bg`, `--card`, `--text`, `--dim`, `--accent`, `--ok`, `--ko`,
  clair et sombre par `prefers-color-scheme`. Une nouvelle couleur devient une variable, dans les
  deux modes.
- JS minimal dans la page : `fetch` vers `/local/*`, rafraîchissement par intervalle. Texte injecté
  par `textContent`, jamais `innerHTML` avec une donnée du serveur.
- Textes en français, tutoiement, comme l'appli. Mêmes mots que sur la tablette (« Réglages ›
  Ordinateurs › Ajouter », « Saisir l'adresse »).
- Tout changement visible a son test Playwright dans `backend/e2e/` (ce qui s'affiche selon l'état,
  pas le pixel).

## Anti-motifs web (le détecteur d'impeccable, en checklist manuelle)

Relire le CSS et le rendu avec cette liste. Une case cochée = une ligne dans la critique.

Couleur et surface
- [ ] Contraste du texte < 4,5:1 (grand texte < 3:1), dans les deux modes. Inclure les boutons :
  texte blanc sur `--accent` clair en mode sombre.
- [ ] Texte gris sur un fond coloré.
- [ ] Dégradé violet ou cyan sur fond sombre, halo coloré (ombre sans décalage), lueur radiale.
- [ ] Texte en dégradé.
- [ ] Bordure colorée épaisse sur un seul côté d'une carte ou d'une alerte.
- [ ] Ombre dure décalée sans flou ; bordure fine plus grande ombre floue sur chaque carte.
- [ ] Fond rayé ou quadrillé décoratif ; fond crème « de bon goût » par réflexe.

Structure et texte
- [ ] Cartes dans des cartes.
- [ ] Petite étiquette en capitales espacées au-dessus d'un titre ; numéros de section 01 / 02.
- [ ] Icône dans un carré arrondi au-dessus de chaque titre, en grille de cartes identiques.
- [ ] Même espacement partout (pas de rythme serré / large).
- [ ] Niveaux de titres sautés, ou un seul `h1` et le reste en `<b>` (structure invisible aux
  lecteurs d'écran).
- [ ] Lignes de plus de ~75 caractères, interligne < 1,3, texte justifié, corps < 14 px.
- [ ] Texte qui déborde de son conteneur ou touche le bord de la fenêtre.
- [ ] Même texte répété trois fois dans un même bloc.

Mouvement et contenu
- [ ] Rebond ou élastique ; point qui pulse sans donnée vivante derrière ; défilement automatique.
- [ ] Contenu invisible au repos (opacité 0 qui attend un script).
- [ ] Image cassée, erreur JavaScript au chargement (console du navigateur).

Ce que le navigateur dessine seul
- [ ] Anneau de focus supprimé (`outline: none`) sans remplaçant visible au clavier.
- [ ] Champ, bouton, `details` sans style de focus ou d'état désactivé.
- [ ] Favicon absent (erreur 401 / 404 en console sur chaque page).

Copie
- [ ] Mots creux de marketing, tirets cadratins en série, formules en « Pas X. Juste Y. ».
- [ ] Bouton qui ne dit pas son action (« OK », « Valider » seul).

## Vérifier

Le backend sur un port libre avec un `DATA_DIR` jetable, puis Playwright (MCP ou `npx playwright`) :

- 1280 et 800 px de large, clair et sombre (`emulateMedia({ colorScheme })`) ;
- chaque état de la page : premier lancement, code d'appairage affiché, tablette connectée,
  Accessibilité refusée (Mac), agenda relié, lien ICS refusé, **backend arrêté** pendant que la
  page est ouverte ;
- contrastes calculés par `getComputedStyle`, pas à l'œil ;
- navigation au clavier (Tab) : chaque contrôle atteint et visible.
