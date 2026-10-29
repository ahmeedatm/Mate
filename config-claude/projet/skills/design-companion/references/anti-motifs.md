# Anti-motifs

Deux familles : ce qui fait « généré par une IA » (impeccable) et ce qui trompe ou fatigue
l'utilisateur (Intent). La liste web détaillée est dans `web.md` ; ici, ce qui vaut pour les deux
surfaces, traduit pour la tablette.

## Look IA

Un de ces motifs n'est pas interdit par principe : il doit avoir une raison tirée du produit. Pris
par réflexe, il se réécrit.

| Motif | Sur la tablette | Que faire |
|---|---|---|
| Grille de cartes identiques icône + titre + texte | Accueil rempli de cartes de même poids | hiérarchie : une carte mène, les autres suivent ; tailles S/M/L de `WidgetModel` au service du contenu |
| Cartes dans des cartes | carte qui contient des lignes en `c_surface_raised` qui contiennent des pastilles | un seul niveau de conteneur, le reste par l'espacement |
| Étiquette en capitales au-dessus d'un titre | `CardTitle` + un sous-titre qui répète | le titre de carte suffit ; pas de second libellé |
| Gros chiffre + petit libellé + chiffres annexes | widget « métrique » sans décision derrière | seulement si le chiffre fait agir (verres d'eau, minutes de Focus) |
| Halo, lueur, dégradé violet ou cyan | ombres colorées, fonds néon | élévation par la teinte (`c_surface_*`), pas par la lueur ; Néon reste un thème, pas un effet |
| Texte en dégradé | — | couleur pleine, emphase par la graisse |
| Bordure colorée sur un côté | ligne de liste « accentuée » | pastille `TitleView`, ou rien |
| Emoji ou caractère à la place d'une icône | `✓`, `✎`, `💧` dans un texte ou une alerte | icône `ic_*` du jeu Material |
| Monospace pour faire « technique » | adresse, code | monospace seulement pour un code ou une adresse à recopier |
| Rebond partout, point qui pulse | `Overshoot` sur chaque arrivée, `pulse` décoratif | budget de `android.md` ; une boucle = un état vivant |
| Thème sombre par habitude | — | ici justifié par la scène (bureau, soir, écran allumé tout le jour) ; Clair doit rester aussi soigné |
| Mots creux, formules à effet | « Boostez votre productivité » | dire ce que fait le bouton |

## Dark patterns et fatigue (Intent, ce qui s'applique ici)

Le compagnon ne vend rien. Les risques sont l'insistance et l'opacité, pas la vente forcée.

| Motif | Exemple possible ici | Règle |
|---|---|---|
| Relance qui harcèle | rappel « Pense à boire » chaque minute, alerte qui revient après refus | un rappel se tait après réponse ; fréquence réglable ; rien la nuit (22 h – 7 h) |
| Notification pour faire revenir, pas pour informer | badge sur l'Accueil alors qu'on y est | un badge disparaît quand on voit ce qu'il signale |
| Série qui punit l'absence | compteur de jours de Focus remis à zéro | pas de série culpabilisante |
| Option du produit mise en avant au détriment de l'utilisateur | « Relier Outlook / Google » plus visible que « Plus tard » partout | une invitation se masque et ne revient pas sur le même ordinateur |
| Sortie cachée | boîte sans Annuler, mode Modifier sans Annuler | toute boîte et tout mode se quittent en un geste visible |
| Contraste faible pour cacher une info | statut d'erreur en `c_text_faint` | une info qui change la décision passe le contraste |
| Échec silencieux | touche du Deck qui ne fait rien sans retour | toujours un retour : effet visible, ou message `errorText` |
| Navigation mystère | icônes sans libellé | barre latérale avec libellés ; icône seule = `contentDescription` + geste connu |
| Cul-de-sac | écran vide sans action | état vide = pourquoi + une action (voir `etats.md`) |
| Kiosque qui enferme | aucune sortie technique documentée | appui long + code technicien, documenté dans `docs/kiosque.md` |

## Microcopie

- Un bouton dit son action avec un verbe : « Connecter », « Ajouter la tâche », pas « OK ».
- Une erreur dit ce qui s'est passé, puis quoi faire : « Mac du bureau ne répond pas : vérifie qu'il
  est allumé et sur le même WiFi. » Pas de code HTTP, pas d'anglais, pas de reproche.
- Un état vide dit pourquoi c'est vide et ce qu'on peut faire, en une phrase.
- Un succès dit ce qui a changé, brièvement, ou ne dit rien si l'effet se voit.
- Mêmes mots pour la même chose partout (tablette et page locale).
- Pas de phrase concaténée en code : une chaîne `strings.xml` avec paramètres (`%1$s`) et des
  pluriels (`plurals`).
- Pas de tiret cadratin en série, pas de chiffres qui ne servent à rien (« 3 cartes · page 1 »).
