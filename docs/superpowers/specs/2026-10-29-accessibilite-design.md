# Accessibilité de l'appli tablette — lots 1 et 2

29/10/2026 · Bruno · branche `feat/accessibilite` (depuis `origin/main` 2d9ccf8)

## Pourquoi

Rendre l'appli utilisable par le plus grand nombre : daltonisme, dyslexie, vue faible, motricité fine, attention.
Bruno n'a pas de personne précise en tête : on vise le grand public. Faciliter certains usages des personnes en situation de handicap est un objectif en soi (interfaces simplifiées, grandes commandes tactiles). Aucun test avec des personnes concernées à ce jour. Un état des lieux (29/10) a montré une base
correcte (textes courants contrastés ≥ 5:1, taille de police d'Android respectée presque partout) et des manques :
états portés par la seule couleur, pas d'option d'accessibilité, TalkBack très partiel.

## Lot 1 — corrections rapides

1. **Connexion** : l'état (« en ligne », « non autorisé », « hors ligne ») écrit à côté du nom de l'ordinateur, plus
   une pastille de forme différente par état ; plus seulement vert / ambre / rouge (même luminance : invisible en
   daltonisme).
2. **Contrastes** : badge du rail en Cadran nuit (blanc sur `#FFB4AB`, 1,7:1) et accent sur `accent_soft` en Cadran
   nuit (4,13:1) portés à ≥ 4,5:1.
3. **Plus d'information par la couleur seule** : alerte du widget Performances (icône ou mot), Wi-Fi faible, batterie
   en charge, eau bue, valeur en sourdine du mélangeur.
4. **TalkBack, le facile** : descriptions manquantes (`water_bell` avec son état, pastilles de couleur de l'éditeur de
   touches nommées, ImageView créées en code), bandeau annoncé (live region), `TitleView` marquée comme titre.
5. **Cibles tactiles** : 48 dp au moins pour les boutons à icône, chips et petites lignes (style `Chip`, 36–44 dp
   relevés), sans changer l'apparence quand c'est possible (zone de toucher élargie).
6. **Délais** : confirmations des agents affichées au moins 6 s (3 s aujourd'hui).

## Lot 2 — Réglages > Accessibilité

La carte « Accessibilité » des Réglages est arrivée sur `main` avec la MR !135 : interrupteur **TalkBack**
(avec le raccourci des touches de volume) et **taille du texte** à quatre niveaux (`Prefs.textScale`, appliquée dans
`MainActivity.attachBaseContext`). Elles sont gardées telles quelles ; la taille du texte prévue ici (×1, ×1,15, ×1,3)
est abandonnée au profit de la leur. Nos trois réglages, gardés sur la tablette, sont ajoutés dans cette même carte,
sous la taille du texte (`ui/ReadingAids.kt`) :

- **Police lisible** : Atkinson Hyperlegible (OFL, conçue pour la vue faible, aidant aussi à la dyslexie) à la place
  des polices du thème, avec un peu plus d'espacement des lettres et des lignes ; aussi pour les titres Rétro (Abril
  Fatface) et les grands chiffres en graisse fine.
- **Réduire les animations** : coupe les animations continues (tubes Rétro, voyants des agents, roue) et les ressorts ;
  activé d'office quand Android demande déjà moins d'animations.
- **Lire les bandeaux à voix haute** : notifications du PC et messages lus par la voix de la tablette (en mode discret
  des notifications : appli et titre seulement).

## Non-objectifs (lot 3, plus tard)

Rendre lisibles par TalkBack les vues dessinées (widget Performances, Focus, mélangeur, cases des tâches, boutons du mode
édition de l'Accueil, pavé tactile) ; alternatives aux appuis longs ; thème contraste élevé.

## Réussite

- En simulation de daltonisme (deutéranopie), l'état de connexion et les alertes restent lisibles.
- Avec « Très grande » et « Police lisible », aucun texte coupé sur les écrans principaux de la 4.2 (1280 × 800).
- « Réduire les animations » : plus rien ne bouge en continu.
- Tests JVM des parties pures (échelle de texte, textes d'état), ktlint, LicencesTest.
