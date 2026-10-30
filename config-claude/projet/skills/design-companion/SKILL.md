---
name: design-companion
description: Passe qualité UX/UI écran par écran du Mate (appli Android en vues XML sur la tablette 4.2, pages web servies par le backend). Commandes critique, polish, distill, harden, animate, etats. Utiliser pour relire, finir ou durcir un écran existant, chercher les anti-motifs « look IA », inventorier les états (sans ordinateur, injoignable, vide, erreur, kiosque), vérifier contrastes, cibles tactiles et animations sur ce matériel. Ne crée pas de direction artistique.
argument-hint: "<critique|polish|distill|harden|animate|etats> <écran ou fichier>"
---

# design-companion

Sources, reprises en intention et adaptées (pas de copie) :
- [pbakaus/impeccable](https://github.com/pbakaus/impeccable), Apache 2.0 : critique, polish, distill,
  quieter, harden, animate, layout, typeset, colorize, audit natif, craft floor, règles du détecteur,
  relecteur de fin séparé de l'auteur.
- [ghaida/intent](https://github.com/ghaida/intent), CC0 : fortify (inventaire d'états), evaluate
  (heuristiques, anti-motifs), transpose (kiosque), articulate (microcopie), include (accessibilité).

## Périmètre

Cette skill relit et finit des écrans **dans la DA existante**. Elle ne choisit ni palette, ni police,
ni concept : c'est une autre skill (`direction-artistique`, non fournie ici). Un problème de DA (thème raté, identité
floue) se signale et part là-bas ; on ne le corrige pas en douce pendant un polish.

Hors périmètre : Compose, Material You dynamique (Android 10), outillage web d'impeccable
(live, generate, detect automatique), PRODUCT.md / DESIGN.md génériques, stratégie, recherche.

## Avant toute commande

1. Lire la référence de la surface : `references/android.md` (appli) ou `references/web.md`
   (pages du backend). Toujours `references/anti-motifs.md`.
2. Lire le code réel de l'écran et ses voisins (même package `ui/`, mêmes styles). Le dépôt fait
   foi : `CLAUDE.md` liste les conventions (thèmes, `Look.kt`, `errorText`, `showForTyping`).
3. Regarder le rendu, pas seulement le code : capture émulateur ou navigateur (protocole dans les
   références). Une capture par état utile, pas une après chaque retouche.

Un émulateur ou un backend déjà lancé peut appartenir à une autre session : en lancer un à soi
(autre AVD, autre port) plutôt que de toucher au sien.

## Commandes

| Commande | But | Modifie le code |
|---|---|---|
| `critique <écran>` | Diagnostic noté, priorisé | non |
| `etats <écran>` | Inventaire des états manquants | non |
| `polish <écran>` | Finition avant livraison, sans changer le concept | oui |
| `distill <écran>` | Retirer ce qui ne sert pas la tâche | oui |
| `harden <écran>` | Textes longs, erreurs, réseau, kiosque | oui |
| `animate <écran>` | Mouvement utile, dans le budget de la tablette | oui |

Sans argument : proposer la commande qui colle à la demande, ne rien lancer.

### critique

Deux évaluations séparées, puis une synthèse. L'auteur d'un écran ne le critique pas seul.

1. **A, relecture design** (agent séparé de celui qui a codé l'écran, par exemple `code-scout`
   pour lire et la session principale pour juger les captures) : pour chaque écran, le parcours
   principal en 4 questions par étape (l'utilisateur sait-il quoi faire, voit-il l'action,
   comprend-il ce qu'elle fait, voit-il le résultat ?), les 10 heuristiques de Nielsen avec un
   verdict en mots (tient, tendu, cassé), la charge cognitive (plus de 4 choix visibles à un point
   de décision = signal).
2. **B, contrôle mécanique**, sans voir A : checklist de `references/anti-motifs.md`, contrastes
   calculés depuis `palettes.xml` (ou le CSS de la page), tailles de texte et de cibles lues dans
   le XML, rendu à `font_scale 1.3`, 4 thèmes.
3. **Synthèse** : ce qui tient, puis 3 à 6 problèmes classés P0 à P3, chacun avec : quoi, où
   (`fichier:ligne` ou capture), pourquoi ça gêne, le changement attendu, la commande qui le traite.
   P0 bloque la tâche ou ment sur l'état ; P1 gêne fort ou viole une règle de plateforme ; P2 gêne
   peu ; P3 finition. Peu de P3.

Sortie : `docs/team/design-companion/<écran>-critique.md` avec les captures à côté. Pas de note
globale sur 40 : le nombre de problèmes par niveau suffit.

### etats

Remplir le tableau de `references/etats.md` pour l'écran : une ligne seulement par état que l'écran
ne gère pas encore ou gère mal. Pour chaque ligne : ce qu'on voit, ce qu'on peut faire, comment on
s'en sort. Provoquer les états pour de vrai (backend arrêté, données vides, texte long) plutôt que
de les deviner.

### polish

Ordre : tâche cassée ou état mensonger, puis états manquants, puis hiérarchie et cohérence avec les
écrans voisins, puis visuel et mouvement, puis nettoyage.

- Classer chaque écart : attribut de thème manquant, composant maison qui devrait être un style
  existant (`Card`, `Chip`, `IconButton`, `PrimaryButton`), écart de parcours, défaut local.
  Corriger au niveau le plus bas qui règle la cause.
- Même rôle = même style partout (titres `TitleView`, boutons, chips). Mêmes mots pour la même action.
- Finir le chemin entier, pas un coin parfait et le reste en l'état.
- Ne pas changer le concept. S'il est faux, le dire et renvoyer vers la DA.

### distill

Trouver la tâche principale de l'écran (une seule). Puis retirer ou replier : actions secondaires
en concurrence, information dite deux fois, conteneurs qui ne groupent rien, cartes dans des cartes,
textes d'aide qui répètent le titre. Garder ce dont l'utilisateur a besoin pour décider. Lister ce
qui a été retiré et où on le retrouve.

### harden

Provoquer : nom d'ordinateur long, tâche de 200 caractères, 0 / 1 / 50 éléments, emoji, backend
arrêté, réponse lente, 401, Accessibilité Mac refusée, clavier ouvert, double appui. Pour chacun :
le texte reste lisible (retour à la ligne ou coupure avec `ellipsize`), l'erreur passe par
`errorText(e)`, l'action ne part pas deux fois, rien ne reste figé. États : `references/etats.md`.

### animate

Chercher seulement les endroits où le mouvement explique quelque chose : retour d'appui, changement
d'état, arrivée d'un mode, élément ajouté ou retiré. Un seul moment travaillé par écran au plus.
Respecter le budget chiffré de `references/android.md`. Vérifier sur la vraie tablette (l'émulateur
ne dit rien des performances) et avec les animations désactivées.

## Fin de passe

- Relecture finale par un agent qui n'a pas écrit le code : il reçoit les captures, la demande, la
  référence, et rend une liste ordonnée de corrections (8 au plus) ou « rien à corriger ». Pas
  d'éloge, pas de résumé.
- Une modification de code suit les règles du dépôt : tests (`app/src/test/`, `backend/e2e/`),
  `ktlintFormat` / `npm run format`, puis `/ship`.
