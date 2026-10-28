# Public cible et positionnement

Décidé avec l'utilisateur les 27 et 28/10/2026. Doc de cadrage produit : à qui vendre la tablette, pour quoi faire, et ce qu'il reste à prouver. Les offres et les canaux sont des hypothèses à tester.

## Contexte

L'objectif réel est de **vendre les ~20 000 tablettes 4.2** en stock comme **accessoire du PC** ([etat-du-projet.md](etat-du-projet.md), section Pourquoi ce projet).

Critère retenu : un écran de plus n'a de valeur que si **l'écran du PC est occupé**. Plein écran, partagé, projeté, ou un direct en cours. Sinon, l'utilisateur fait tout sur le PC.

La bureautique classique est donc écartée : on y passe d'une fenêtre à l'autre (alt-tab), l'écran n'est jamais bloqué.

## Le cadre : la régie de poche

Tâche commune à tous les publics retenus : **piloter un direct sans quitter l'écran principal**.

Phrase de positionnement :

> La régie de poche : une tablette de récup qui pilote ton direct sans toucher à l'écran principal.

## Critères de tri des publics

| Critère | Question posée |
|---|---|
| Écran du PC occupé | Le public a-t-il un écran qu'il ne peut pas quitter (jeu, direct, projection) ? |
| Actions répétitives | Refait-il les mêmes gestes, qu'une touche ou une macro remplace ? |
| Info d'un coup d'œil | A-t-il une info à surveiller sans changer de fenêtre ? |
| Sensibilité au prix | Une tablette de récup lui suffit-elle face à un Stream Deck MK.2 à 119-150 € ? |
| Atteignable par Unowhy | Unowhy peut-il le toucher (PC Unowhy, éducation, vente directe) ? |

## Publics

### Cœur : gamers, streamers, créateurs de contenu

Ils cochent tous les critères : jeu en plein écran, scènes et micros à changer en direct, chat et état du direct à surveiller, budget serré.

**Persona** : joueur-streamer sur PC Windows, sans Stream Deck.

### Voisins : mêmes fonctions, autre public

| Public | Usage | Preuve de la demande |
|---|---|---|
| Simracers et simulateurs de vol | Tableau de bord (vitesse, rapport, carburant) à côté du jeu | SimHub affiche déjà son tableau de bord sur une tablette Android du réseau ; Corsair vend le Xeneon Edge pour cet usage |
| Podcasteurs et webradios scolaires | Jingles, micro, chrono de l'émission | Programme « Une webradio, un parrain » : plus de 700 collèges équipés en 2022-2023 ; pont avec le métier éducation d'Unowhy |

### Secondaires

- **Développeurs avec agents IA** : le serveur MCP est déjà là (`backend/src/mcp.ts`).
- **Graphistes et monteurs** : pages de raccourcis du Deck.
- **Enseignants** : PC projeté, visualiseur (mode Caméra).
- **Accessibilité** : belle histoire, pas le cœur.

### Écartés

| Public | Raison |
|---|---|
| Bureautique | Alt-tab : l'écran du PC n'est jamais occupé |
| Traders | Ont déjà plusieurs écrans |
| Seniors | Utiliseraient la tablette seule, pas comme accessoire du PC |
| Domotique, borne d'accueil | Autre produit : pas de PC à piloter |
| Musiciens | MIDI attendu, latence du WiFi trop forte |

## Produit

Déjà là et utile à la régie : Deck avec pages d'appli (Discord : micro, sourdine, appel), touches perso et macros, page Média, onglet Son (console de mixage, `ui/mixer/`, scènes de volumes), appels entrants (Windows), Ne pas déranger.

Briques à ajouter, par ordre de priorité :

| # | Brique | Comment | Sert |
|---|---|---|---|
| 1 | Soundboard | Étape `sound` dans `Step` (`backend/src/deck.ts`). Le son est joué sur l'ordinateur : OBS capte le son du bureau. Sons ajoutés depuis le dossier « Partage tablette ». | Les trois publics |
| 2 | Page OBS | Voir [obs.md](obs.md) | Streamers, webradios |
| 3 | Onglet web du Deck | Une URL de l'ordinateur (tableau de bord SimHub) dans le WebView de « Page de l'ordi » | Simracers |
| 4 | Chat Twitch | Lecture seule, anonyme, sans clé | Streamers |
| Plus tard | FPS, températures, nombre de spectateurs | Spectateurs : compte développeur Twitch | Streamers |

## Offre et canaux (hypothèses à tester)

Aucun prix annoncé. Principe : **nettement sous le Stream Deck**.

| Offre | Pour qui |
|---|---|
| Tablette seule | Joueurs qui ont déjà un PC |
| Tablette + PC Unowhy | Acheteurs d'un PC Unowhy |
| « Kit régie webradio » | Collèges |

Canaux :

- pack avec les PC Unowhy ;
- vente directe « seconde vie » ;
- collectivités et académies (webradio) ;
- quelques streamers français équipés gratuitement.

## Validation

**Démo du hackathon** : un jeu en plein écran sur le PC. Depuis la tablette, on change de scène OBS, on lance un jingle, on coupe le micro Discord, on décroche un appel.

**Après** : prêter 10 tablettes à des streamers pendant deux semaines.

## Bloquants avant la vente

Déjà suivis dans [etat-du-projet.md](etat-du-projet.md) :

- exe Windows (`UnowhyHelper.exe`, `UnowhyCompanion.exe`) à signer avec un certificat Unowhy (section Pourquoi ce projet) ;
- kiosque à poser en usine par Unowhy, à confirmer avec eux (section Avant de livrer, [kiosque.md](kiosque.md)).

## Points non vérifiés

- Nombre de tablettes 4.2 encore chez des élèves. L'Île-de-France équipe ~150 000 élèves par an, mais en PC Y13.
- Prix du Corsair Xeneon Edge.
- Fluidité du tableau de bord SimHub dans le WebView du MT8167 ([tablette-4.2.md](tablette-4.2.md)).
- Lecture d'un son par `UnowhyHelper.exe` sans dépendance.

## Sources

- Prix du Stream Deck MK.2 : https://www.comparateur-gamer.fr/prix/elgato-stream-deck-mk-2-black-840006640554 et https://frontdeskreview.com/product/stream-decks/elgato-stream-deck-mk2/
- SimHub sur une tablette : https://simxpro.com/blogs/guides/simhub-dashboard-on-a-tablet-cheap-second-screen-setup-that-works
- Corsair Xeneon Edge pour le simracing : https://www.corsair.com/us/lt/explorer/gamer/monitors/how-to-use-xeneon-edge-as-a-dashboard-for-sim-racing/
- Webradio à l'école : https://eduscol.education.gouv.fr/6687/usages-pedagogiques-de-la-webradio et https://www.education-aux-medias.ac-versailles.fr/IMG/pdf/mettre_en_place_un_projet_webradio.pdf
- Équipement des élèves d'Île-de-France : https://www.unowhy.com/wp-content/uploads/2024/04/CP_UNOWHY_IDF_29012024.pdf
