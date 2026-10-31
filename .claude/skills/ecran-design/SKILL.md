---
name: ecran-design
description: À utiliser avant de créer ou modifier un écran, une vue, un layout, un widget ou une page web du compagnon Mate (appli Android en vues XML sur la tablette 4.2, pages servies par le backend), et pour une passe qualité (critique, etats, polish, distill, harden, animate). Raisonne par thème, fait une capture par thème, cherche les anti-motifs « look IA », inventorie les états (sans ordinateur, injoignable, vide, erreur, kiosque), vérifie contrastes, cibles tactiles et animations sur ce matériel. Ne crée pas de direction artistique.
argument-hint: "<critique|polish|distill|harden|animate|etats> <écran ou fichier>"
---

# ecran-design

Sources, reprises en intention et adaptées (pas de copie) :
- [pbakaus/impeccable](https://github.com/pbakaus/impeccable), Apache 2.0 : critique, polish, distill,
  quieter, harden, animate, layout, typeset, colorize, audit natif, craft floor, règles du détecteur,
  relecteur de fin séparé de l'auteur.
- [ghaida/intent](https://github.com/ghaida/intent), CC0 : fortify (inventaire d'états), evaluate
  (heuristiques, anti-motifs), transpose (kiosque), articulate (microcopie), include (accessibilité).

## Périmètre

Cette skill crée, relit et finit des écrans **dans la DA existante**. Elle ne choisit ni palette, ni
police, ni concept. Un problème de DA (thème raté, identité floue) se signale à l'humain, qui
tranche ; on ne le corrige pas en douce pendant un polish.

Hors périmètre : Compose, Material You dynamique (Android 10), outillage web d'impeccable
(live, generate, detect automatique), PRODUCT.md générique, stratégie, recherche.

## Avant tout écran

À faire pour toute création ou modification, avec ou sans commande.

1. Lire la direction artistique : `DESIGN.md` pour les pages web, `docs/ui-ux/da/README.md` et ses
   captures pour l'appli.
2. Lire l'écran voisin le plus proche (même package `ui/`, mêmes styles) : le dépôt fait foi,
   `CLAUDE.md` § `## Conventions du code` liste les règles (couleurs, polices, `Look.kt`,
   `errorText`, `showForTyping`).
3. Lire `enum class AppTheme` de `app/src/main/kotlin/app/mate/ui/Themes.kt` : c'est la liste des
   thèmes, à relire à chaque fois. Ne jamais la recopier de mémoire.
4. Lire la référence de la surface : `references/android.md` (appli) ou `references/web.md` (pages
   du backend), et toujours `references/anti-motifs.md`.

Un émulateur ou un backend déjà lancé peut appartenir à une autre session : en lancer un à soi
(autre AVD, autre port) plutôt que de toucher au sien.

## Raisonner en thème

- Chaque thème a son caractère. Le lire dans ses valeurs de `app/src/main/res/values/palettes.xml`
  (fond, surfaces, accent, texte, teintes `c_hue_*`), pas dans son nom.
- Tout attribut ajouté dans `attrs.xml` a une valeur dans chaque palette de `AppTheme`. Un attribut
  oublié dans une palette se voit seulement quand on bascule : le vérifier par les captures.
- Jamais de couleur, de police ni de thème nommé en dur dans une vue, un drawable ou une condition
  (`?attr/c_…`, `themeColor(context, R.attr.c_…)`, `c_font_*`). Pour les pages web, des variables
  CSS en miroir de `backend/src/web-style.ts`, clair et sombre.
- Un écran qui n'est correct que dans un thème n'est pas fini.

## Captures par thème

Chaque écran créé ou modifié a une capture par entrée de `enum class AppTheme`, lue dans le code à
ce moment-là. Dossier `captures/` (à ne pas commiter : joindre les images à la PR).

D'abord `adb devices`. Si aucun appareil n'est listé : le dire à l'humain et lui demander les
captures (émulateur `companion_api29` ou tablette), sans en inventer.

Sinon, pour chaque `NOM` d'`AppTheme` :

1. `adb shell am force-stop app.mate`.
2. Écrire la valeur du thème par `adb shell run-as app.mate` (APK debug) dans
   `shared_prefs/companion.xml`, clé `theme` : remplacer `<string name="theme">…</string>` par
   `<string name="theme">NOM</string>`, ou l'ajouter dans `<map>` ; si le fichier manque, le créer
   avec `<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name="theme">NOM</string></map>`.
   Autre voie, tablette connectée au compagnon : `PUT /local/tablet/settings` avec `{"theme": "NOM"}`
   (127.0.0.1), l'ordre `settings` change le thème sans toucher au fichier.
3. `adb shell am start -n app.mate/app.mate.MainActivity`, attendre que l'écran soit affiché.
4. Aller sur l'écran voulu, puis `adb exec-out screencap -p > captures/<écran>-<thème>.png`.

Pour les pages web : une capture clair et une sombre (Playwright, `references/web.md`).

Regarder chaque image : texte coupé, chevauchement, contraste faible, hiérarchie plate, élément qui
se décale d'un thème à l'autre. Les captures partent avec la demande à `relecteur-design`.

## Accessibilité

- `contentDescription` (ou texte) sur tout élément touchable ; icône seule = description + geste connu.
- Cibles tactiles ≥ 48 dp, 8 dp entre deux cibles (`references/android.md`).
- Contraste calculé depuis `palettes.xml`, pas à l'œil : texte ≥ 4,5:1, grand texte et icônes ≥ 3:1,
  dans chaque thème. Si `AppTheme` contient un thème à contraste renforcé, c'est lui qui fixe le plus
  haut seuil de lisibilité : il doit rester atteint après la modification.
- Un état ne repose jamais sur la seule couleur. Rendu à `font_scale 1.3` sans rien de coupé.

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

1. **A, relecture design** par l'agent `relecteur-design`, évaluateur séparé de celui qui a codé
   l'écran. Lui donner les captures par thème, la demande et les références. Il rend ses remarques ;
   la session principale ajoute le jugement du parcours : en 4 questions par étape (l'utilisateur
   sait-il quoi faire, voit-il l'action, comprend-il ce qu'elle fait, voit-il le résultat ?), les 10
   heuristiques de Nielsen avec un verdict en mots (tient, tendu, cassé), la charge cognitive (plus de
   4 choix visibles à un point de décision = signal).
2. **B, contrôle mécanique**, sans voir A : checklist de `references/anti-motifs.md`, contrastes
   calculés depuis `palettes.xml` (ou le CSS de la page) pour chaque thème, tailles de texte et de
   cibles lues dans le XML, rendu à `font_scale 1.3`.
3. **Synthèse** : ce qui tient, puis 3 à 6 problèmes classés P0 à P3, chacun avec : quoi, où
   (`fichier:ligne` ou capture), pourquoi ça gêne, le changement attendu, la commande qui le traite.
   P0 bloque la tâche ou ment sur l'état ; P1 gêne fort ou viole une règle de plateforme ; P2 gêne
   peu ; P3 finition. Peu de P3.

Sortie : dans la réponse, ou dans `docs/team/design-companion/<écran>-critique.md` si l'humain veut
la garder ; captures jointes à la PR, pas commitées. Pas de note globale sur 40 : le nombre de
problèmes par niveau suffit.

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
- Ne pas changer le concept. S'il est faux, le dire à l'humain.

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

- Relecture finale par `relecteur-design` (jamais par l'auteur du code) : il reçoit le diff, une
  capture par thème et la demande, et rend une liste ordonnée de remarques ou « Rien d'autre ».
  Corriger tout `bloquant` et `important` avant de dire « fini ».
- Une modification de code suit les règles du dépôt : tests (`app/src/test/`, `backend/e2e/`),
  `ktlintFormat` / `npm run format`, puis une PR sur `master`.
