# Inventaire d'états

Le parcours idéal (ordinateur appairé, réseau stable, données propres) n'est qu'une partie de la
journée. Pour chaque écran, passer cette liste et ne garder que les états que l'écran ne gère pas,
ou gère mal.

## Les états du compagnon

| État | Comment le provoquer | Ce qu'on attend |
|---|---|---|
| Sans ordinateur appairé | données de l'appli effacées (`adb shell pm clear com.unowhy.companion`) | `WelcomeView` par-dessus tout ; Deck, Ordi, Caméra remplacés par `view_connect` ; l'Accueil garde heure et Focus |
| Ordinateur injoignable | arrêter le backend de test | le dire une fois, à un endroit stable, avec le nom de l'ordinateur et quoi vérifier ; données affichées marquées comme anciennes ; bouton Réessayer ; pas de valeur fausse (curseur à 0, liste vide) |
| Bascule vers un autre ordinateur | deux backends, arrêter l'actif (`RemoteChannel.SWITCH_AFTER_MS`) | annonce de la bascule, nom du nouvel ordinateur visible |
| Jeton refusé (401) | supprimer `identity.json` du backend de test | « appairer à nouveau », avec le chemin vers Réglages |
| Backend plus ancien (route inconnue) | — | `error_bad_reply`, pas un écran vide |
| Chargement | réponse lente (backend en pause, `kill -STOP`) | rien les 300 premières ms ; puis un indicateur à la place du contenu ; au-delà de 10 s, une phrase |
| Vide, première fois | données vides | pourquoi c'est vide + une action (« Touche + pour noter un rendez-vous ») |
| Vide, tout est fait | tâches toutes cochées | un mot positif court, pas le même texte que « jamais rien eu » |
| Partiel | agenda OK, tâches en erreur | chaque carte porte son propre état ; une erreur ne vide pas les autres cartes |
| Erreur d'action | toucher une touche du Deck qui répond 501 | message `errorText` près de l'action, l'action reste possible |
| Débordement | nom d'ordinateur de 40 caractères, tâche de 200, 50 tâches, emoji | retour à la ligne ou `ellipsize` ; liste qui défile et le montre ; rien sous un autre élément |
| `font_scale 1.3` | `settings put system font_scale 1.3` | rien de coupé ni de chevauché |
| Mode Modifier (Accueil) | appui long sur un widget | les commandes d'édition ne masquent pas le contenu ni d'autres boutons |
| Clavier ouvert | champ d'une boîte | `showForTyping` : la boîte en haut, au-dessus du clavier ; barre latérale cachée |
| Accessibilité Mac refusée | Mac sans l'autorisation | bandeau dans le Deck et carte sur la page locale, bouton qui ouvre le réglage ; disparaît dès l'autorisation donnée |
| Pilotage indisponible (Linux, 501) | backend `os: other` | phrase claire, pas de bouton mort |
| Kiosque | tablette device owner | pas de barre d'état : heure, WiFi, batterie lisibles dans `TabletStatusView` ; aucune sortie vers le système sans le code technicien |
| WiFi coupé | désactiver le WiFi | bannière WiFi, recherche relancée au retour ; ne pas confondre avec « ordinateur éteint » |
| Batterie faible | `adb shell dumpsys battery set level 10` | bandeau batterie ; rétablir avec `dumpsys battery reset` |
| Nuit (22 h – 7 h) | changer l'heure de l'émulateur | luminosité atténuée ; un toucher rallume ; rien qui clignote |
| Double appui | toucher deux fois vite | l'action ne part qu'une fois ; bouton désactivé pendant l'envoi |
| Geste interrompu | glisser une fenêtre puis sortir de l'écran | état de glisser effacé, geste suivant normal |
| Permission refusée (micro, caméra) | `pm revoke` | phrase + chemin pour l'accorder |

## Page locale (web)

| État | Attendu |
|---|---|
| Compagnon arrêté pendant que la page est ouverte | tout l'état affiché passe en « inconnu » : pas de « Tablette connectée » en vert, pas de code d'appairage resté à l'écran |
| Code d'appairage | grand, lisible de loin, temps restant visible, disparaît une fois utilisé ou expiré |
| Lien ICS refusé | message près du champ, le lien saisi reste dans le champ |
| Aucun réseau | « aucune adresse » + quoi faire |

## Format dans une critique

| État | Ce qu'on voit | Ce qu'on peut faire | Comment on s'en sort |
|---|---|---|---|

Une ligne par état manquant. Une ligne vague (« gérer l'erreur ») ne compte pas : écrire le message,
l'emplacement et l'action.
