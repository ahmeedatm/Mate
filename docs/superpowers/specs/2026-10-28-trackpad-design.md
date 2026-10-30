# Trackpad — conception

28/10/2026 · Bruno · branche `feat/trackpad` (depuis `origin/main` 0cbf58c)

## Pourquoi

Utiliser l'écran de la tablette comme pavé tactile de l'ordinateur : piloter la souris depuis le canapé ou en
présentation, sans souris sous la main. Bruno a demandé de sauter les questions : les choix ci-dessous sont ceux de
Claude, à corriger.

## Objectifs

- Un onglet « Pavé » dans le Deck : une grande surface tactile + deux boutons « Clic gauche » / « Clic droit ».
- Gestes : 1 doigt glisse = déplacer le pointeur ; tape 1 doigt = clic gauche ; tape 2 doigts = clic droit ;
  2 doigts glissent = défilement (vertical et horizontal) ; double tape puis glisser = glisser-déposer (bouton gauche
  maintenu jusqu'au lever du doigt).
- Accélération : petit mouvement lent = précis, mouvement rapide = traverse l'écran (courbe simple, pas de réglage
  dans cette version).
- Latence ressentie faible en WiFi (cible : < 50 ms de bout en bout sur le réseau du bureau, mesurée).
- Rien à configurer.

## Non-objectifs

- Gestes à 3 ou 4 doigts (changer de bureau, Mission Control), zoom par pincement, clavier complet (la dictée « écris »
  et le Deck existent déjà), réglage de la sensibilité.

## Choix techniques (Claude)

- **Déplacements et défilement par UDP**, sur le port UDP que le compagnon écoute déjà pour la découverte
  (`backend/src/pairing.ts`, même port que HTTP) : un paquet ne fait que quelques octets, rien n'attend une réponse,
  une perte n'est pas grave. Format : `MATE-POINTER\n<jeton>\n<seq>\n<json>` avec `{"dx":int,"dy":int}` ou
  `{"sx":int,"sy":int}` (défilement, en crans). Le compagnon vérifie le jeton (comparaison à temps constant), ignore un
  `seq` déjà vu ou plus ancien (par adresse source), borne les valeurs (|d| ≤ 2000 px, |s| ≤ 50) et le débit (≤ 200
  paquets/s par source). Paquet invalide → ignoré sans réponse et sans journal de son contenu.
- **Clics et bouton maintenu par HTTP** (fiables) : `POST /pointer/button {button:"left"|"right", action:"click"|"down"|"up"}`,
  jeton comme les autres routes. Aussi `POST /pointer/move {dx,dy}` et `POST /pointer/scroll {sx,sy}` en HTTP, pour
  les tests, la sanity et un secours si l'UDP est bloqué.
- **Tablette** : regroupe les mouvements et envoie au plus un paquet toutes les 16 ms (~60/s) avec la somme des
  déplacements ; l'accélération est appliquée sur la tablette (logique pure, testée). Bascule automatique sur HTTP si
  aucun accusé… (non : pas d'accusé en UDP) → secours HTTP seulement si l'envoi UDP lève une erreur.
- **Windows** : opération `pointer` du pilote (`SendInput` : déplacement relatif `MOUSEEVENTF_MOVE`, boutons, molette
  `MOUSEEVENTF_WHEEL`/`HWHEEL`). Le pilote est un processus résident : un aller-retour par paquet suffit.
- **Mac** : un processus JXA **résident** (lit des lignes sur l'entrée standard, `CGEventCreateMouseEvent` à la position
  courante + delta, `CGEventCreateScrollWheelEvent`, boutons), lancé au premier usage ; demande l'Accessibilité déjà
  accordée. Pas de Mac ici : écrit, non vérifié.
- **Linux** : 501 comme le reste du pilotage.
- Style Cadran (surface en `c_surface`, filet discret, boutons pilules), l'écran reste allumé tant que l'onglet est
  affiché.

## Réussite

1. Sur la tablette avec le PC Windows de Bruno : le pointeur suit le doigt sans à-coups visibles, clics gauche et droit,
   défilement dans une page web, glisser-déposer d'une fenêtre.
2. Latence mesurée (horodatage tablette → injection) : médiane < 50 ms en WiFi.
3. Un paquet UDP sans le bon jeton ne bouge rien. Rien du contenu des paquets dans les journaux.
4. Tests backend (paquets, jeton, seq, bornes, débit, routes), JVM (gestes, accélération, regroupement), sanity.

## Ajout du 28/10 : gestes des pavés tactiles modernes

Demandé par Bruno après le premier essai. La tablette envoie un **nom de geste** (`POST /pointer/gesture
{gesture}`), jamais un raccourci libre ; le compagnon le traduit selon le système à partir d'une liste fermée.

| Geste | Nom | Windows | Mac |
|---|---|---|---|
| Écarter / pincer 2 doigts | `zoom-in` / `zoom-out` | Ctrl + molette (pilote, opération `pointer` `kind:"zoom"`) | Cmd + = / Cmd + − |
| 2 doigts, balayage rapide gauche / droite | `back` / `forward` | Alt + ← / Alt + → | Cmd + [ / Cmd + ] |
| 3 doigts vers le haut | `task-view` | Win + Tab | Ctrl + ↑ (Mission Control) |
| 3 doigts vers le bas | `desktop` | Win + D | Ctrl + ↓ (Exposé de l'app) |
| 3 doigts gauche / droite | `app-previous` / `app-next` | Alt + Maj + Tab / Alt + Tab | Cmd + Maj + Tab / Cmd + Tab |
| Toucher à 3 doigts | `search` | Win + S | Cmd + Espace |
| 4 doigts gauche / droite | `desktop-previous` / `desktop-next` | Ctrl + Win + ← / → | Ctrl + ← / → |
| Toucher à 4 doigts | `notifications` | Win + N | (clic sur l'horloge non fiable) → Ctrl + Cmd + N n'existe pas : ignoré sur Mac (200 sans effet) |

- Balayage « rapide » à 2 doigts = déplacement surtout horizontal (≥ 2× le vertical), assez long (≥ 25 % de la largeur
  du pavé) et court dans le temps (≤ 350 ms), sans pincement ; sinon défilement comme avant.
- Pincement = l'écart entre les deux doigts change de plus de 10 % ; à partir de là le geste reste un zoom jusqu'au
  lever ; un cran de zoom par 12 % d'écart.
- 3 et 4 doigts : un seul geste par contact (déclenché au seuil, pas de répétition), sens = déplacement du centre.
