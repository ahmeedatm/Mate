# Site vitrine : skills de design et refonte du 30/10/2026

Refonte de `site/` en gardant la DA Cadran (`docs/ui-ux/da/README.md`). Ce fichier liste les skills de design
installées pour Claude, dit lesquelles ont servi, et garde le journal des passes.

## Inventaire des skills de design

| Famille | Skills | Ce qu'elles font | Ici |
|---|---|---|---|
| Direction artistique | `direction-artistique` | DA produit sur de vrais design systems (M3, HIG) : tokens jour/nuit, mouvement, icône | Déjà passée : Cadran en vient. Pas relancée, la DA est imposée |
| Direction artistique | `design-taste-frontend` | Direction de composition tirée d'une bibliothèque de 247 sites, règles maison mesurées par un navigateur (`lint.mjs`, `inspect.mjs`), boucle de critique sur captures | **Passe 1** |
| Direction artistique | `high-end-visual-design`, `minimalist-ui`, `industrial-brutalist-ui`, `frontend-design`, `genjutsu` | Styles prêts à poser, ou équipe d'agents pour un site entier | Non : imposent leur propre look à la place de Cadran |
| Choix d'un design system | `design-system-selector`, `visual-design-system`, `atlassian-design-system`, `ui-ux-pro-max` (et ses sous-skills `design`, `design-system`, `brand`, `ui-styling`, `banner-design`, `slides`) | Choisir un système (Material, Carbon, Atlassian…) ou une palette selon le thème | Non : le système est déjà choisi (Cadran sur M3) |
| Conception, revue | `impeccable` (critique, audit, polish, layout, typeset, colorize, animate, live, generate…) | Revue de design notée (Nielsen), détecteur d'anti-patterns, retouches | **Passe 2** : critique à deux agents isolés + détecteur |
| Revue transverse | `better-interface` → `better-accessibility`, `better-layout`, `better-writing`, `better-typography`, `better-colors`, `better-ui` | Revue classée par impact, déclencheurs bloquants (nom accessible, focus, 320 px, contraste, mouvement réduit) | **Passe 3**, sur les déclencheurs mesurés au rendu |
| Revue de changement | `interface-review` | Même revue, limitée à un diff ou une MR | À lancer par toi sur la MR |
| Mouvement | `review-animations`, `improve-animations`, `animate`, `find-animation-opportunities`, `animation-performance`, `animation-vocabulary`, `motion-design`, `emil-design-eng`, `apple-design` | Revue et écriture du mouvement (courbes, durées, interruption, GPU, mouvement réduit) | **Passe 4** : `review-animations` |
| Mise à l'épreuve | `break-ui`, `break` | Données extrêmes sur un composant | Non : page statique sans données variables ; 320 px et zoom 200 % vérifiés en passe 3 |
| Variantes | `prototype`, `variant`, `impeccable live` / `generate` | Plusieurs variantes d'un élément à choisir dans la vraie page | Non lancées : à faire si tu veux comparer d'autres héros |
| Depuis une maquette | `build-design`, Figma MCP | Coder depuis Figma ou une image | Non : pas de maquette |
| Bibliothèques | `pick-ui-library`, `ask-sonner` | Choisir des composants React | Non : site statique sans framework |
| Vidéo | `hyperframes`, `hyperframes-cli`, `hyperframes-registry` | Films en HTML rendus en vidéo | Indirect : la boucle du site vient du film `video/demo` fait avec |
| Analyse | `explain-interface`, `reverse-engineer-anything` | Expliquer comment un site est fait | Non |
| Projet | `design-companion` | Finition de l'appli Android (vues XML) | Non : hors site |

## Journal des passes

**Avant** : gate de `design-taste-frontend` à 5 échecs (pas de point focal au premier écran, titre sur 3 lignes,
filets, page figée, étiquettes au-dessus des titres) et 6 rangées de cartes identiques.

**Passe 1, `design-taste-frontend`.** Direction tirée parmi trois : « la page est l'objet » (graine 2106245378),
palette et typo gardées de Cadran. Idée : la ligne du jour de Cadran pilote la vraie coque de la tablette 4.2 ; six
moments d'une journée, point rouge à l'heure réelle du visiteur, chaque moment change l'écran (boutons radio et
`:has()`, sans script). Sections reprises de la fiche : feuilles de couleur empilées, registre aligné sur la virgule,
lettre avec talon, carte-réponse de téléchargement, questions en texte courant, dos de carte festonné.
Écarts à la fiche : étapes en « trois phrases » (trois étapes et aucune photo, la rangée glissée en demandait six à
neuf) ; mouvement signature « pied en rideau » remplacé par le battement de la minute de Cadran (le rideau faisait
échouer le contrôle de texte couvert du gate). Gate : 0 échec.

**Passe 2, `impeccable critique`.** Revue isolée 26/36, détecteur 19 trouvailles (faux positifs ou choix assumés :
images du fondu cachées, couleurs des thèmes de démo). Corrigé : IP52 et IK06 attribués à la fiche produit (non
mesurés, `docs/cible-et-positionnement.md`), « 10 points » retiré ; ligne du jour remontée au premier écran ; captures
des feuilles montrées entières ; lecteur d'écran (légende annoncée, images inactives cachées, noms des moments gardés
sur téléphone) ; bouton pause de la vidéo ; téléphone : message et lien par e-mail au lieu de télécharger un exe ;
perforations en SVG au lieu de dégradés ; point « maintenant » masqué hors de 8 h – 20 h.

**Passe 3, `better-interface`.** Mesuré au rendu (Playwright) : 320 px sans débordement, zoom 200 % (720 px) sans
débordement, tout contrôle nommé, focus visible, flèches du clavier sur les moments, contrastes ≥ 4,5:1 jour et nuit,
mouvement réduit respecté. Corrigé : zones de toucher des moments qui se chevauchaient de 5 px à 320 px.

**Passe 4, `review-animations`.** Corrigé : la ligne du jour et le minuteur du code animaient `width` et `left`
(passés en `scaleX` et `translateX` en unités de conteneur) ; survol des moments réservé aux pointeurs fins ; relâché
des boutons ramené à 300 ms ; mouvement réduit qui garde le fondu de l'écran sans déplacement.

**Retour du 30/10 : bandeau et texte.** Bandeau collant (ton de surface au défilement, lien de la section visible
marqué, feuilles empilées décalées dessous). Texte réécrit avec `better-writing` : vouvoiement (site B2B ; l'appli garde
le tutoiement), titre et légendes concrets, jargon retiré (« pipelines », « agents de code »), arguments B2B (achat,
sécurité, déploiement, postes aménagés, prise en main). Deux affirmations fausses corrigées : « seule la météo
interroge un service extérieur » (la voix passe par Google, les mises à jour par Unowhy, `docs/confidentialite.md`) et
« agenda relié à Microsoft ou Google » (connexion de comptes inactive, `backend/config/accounts.json` vide : lien ICS
seulement).

## Reste ouvert

- « Équiper une équipe » et « Contacter Unowhy » mènent à `unowhy.com` : il manque une adresse ou une page de contact commercial.
- La coque porte la plaque « SQOOL » : c'est la vraie tablette ; à valider côté marque.
- Avertissements assumés du gate : fonds crème (couleurs réelles des thèmes Registre et Maison) et titres des feuilles
  empilées qui restent à l'écran (onglets, voulu par la composition).
