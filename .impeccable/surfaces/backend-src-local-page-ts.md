---
version: 1
slug: "backend-src-local-page-ts"
primary_target: "backend/src/local-page.ts"
related_targets: []
---

# Page locale du compagnon (panneau de l'ordinateur)

Mode : Operate. Public : le pro qui règle sa tablette, l'IT qui installe. Tâche : savoir si tout marche, appairer, régler la tablette et les comptes, puis (session config-profils-deck-pc) profils, touches, plugins, applis, barres.

## Direction contract

THESIS : un panneau de réglages qui se lit comme la tablette elle-même ; refuse la pile de cartes centrée d'une page d'admin.
OWN-WORLD : tokens Cadran jour/nuit, Outfit 300/400/500, rouge « maintenant » seulement pour l'état qui demande une action et la sélection ; barre de gauche teintée (--rail), listes groupées sur --tile, pilules, interrupteurs et curseurs habillés en Cadran.
STORY : on voit d'abord l'état (compagnon, tablette, autorisation), on va à une section par la barre, on règle, la tablette dessinée le reflète.
FIRST VIEWPORT : barre de gauche 272 px (logo, nom, bloc d'état, sections avec trait rouge qui glisse) ; à droite la section Tablette : tablette dessinée à l'échelle 16:10 à gauche, thème, luminosité, son à droite.
FORM : structure 1 (réglages à rail) fusionnée avec l'aperçu de la structure 3 (jumeau), choix de l'utilisateur. seed: none (unavailable)
FINISH : unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

Signature : la tablette dessinée qui change en direct (thème, luminosité) ; le trait rouge de la barre, même geste que ProfileRail.
