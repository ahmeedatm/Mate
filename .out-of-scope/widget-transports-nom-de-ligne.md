# Widget Transports : nom de ligne (RER A, métro 1…) non affiché

Décidé le 10/11/2026 (PR #217, relecture design).

## Ce qui n'est pas fait
Les listes et le gros bloc montrent la **destination** (direction) et l'attente, mais pas le nom
de la ligne. À un arrêt desservi par plusieurs lignes, en « Toutes les directions », des passages de
lignes différentes se mêlent sans repère de ligne.

## Pourquoi (motif durable)
L'API temps réel `stop-monitoring` d'IDFM ne renvoie qu'une **référence** de ligne opaque
(`LineRef`, ex. `STIF:Line::C01742:`), pas un nom lisible ni une couleur. Afficher cette référence
telle quelle serait du jargon. Obtenir « RER A » et sa couleur demanderait un second jeu de données
(référentiel des lignes IDFM), à embarquer ou à appeler : hors du périmètre validé « prochains
passages à un arrêt et une direction ».

## Quand y revenir
Si on veut un repère de ligne (pastille colorée « A », « 1 »), reprendre le référentiel des lignes
IDFM (`lines` / export GTFS) et faire correspondre `LineRef` → nom court + couleur, en tenant compte
du poids sur une tablette à 2 Go. En attendant, choisir une direction précise au réglage évite le
mélange.
