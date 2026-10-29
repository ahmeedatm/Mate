# Public cible et positionnement

> 29/10/2026 : direction **B2B**, d'après la note projet UNOMATE (voir Sources). Elle remplace le cadrage gamers / streamers (« régie de poche ») décidé les 27 et 28/10, gardé plus bas en pistes écartées. Les chiffres commerciaux de la note (prix, chiffre d'affaires, tailles de marché, prix des concurrents) ne sont volontairement pas dans le dépôt.

Doc de cadrage produit : à qui vendre la tablette, pour quoi faire, et ce qu'il reste à prouver. Les offres et les canaux sont des hypothèses à tester.

## Contexte

Unowhy a **18 000 tablettes 4.2** à valoriser (note, section 3). L'objectif est de créer de la valeur par le logiciel, sans nouveau matériel, et d'ouvrir une **nouvelle offre B2B**, vendue seule ou en complément des PC Unowhy, avec un élargissement possible au grand public ([etat-du-projet.md](etat-du-projet.md), section Pourquoi ce projet).

Nom du produit : **UnoMate** (la note écrit « UNO-Mate »).

## Le cadre : un accessoire de productivité personnalisable

UnoMate transforme une tablette 4.2 en un **écran de contrôle tactile personnalisable**, relié en WiFi à un Mac ou un PC Windows. Il sert à piloter l'ordinateur, accéder à ses outils et automatiser ses actions, sur le modèle du Stream Deck.

Positionnement retenu par la note :

> Un accessoire de productivité personnalisable, polyvalent et robuste, destiné d'abord aux professionnels.

### Cinq bénéfices

| Bénéfice | Sens (note) | Dans le produit |
|---|---|---|
| Personnalisation | Tableaux de bord, widgets, raccourcis, automatisations selon les usages | Profils nommés, widgets en grille, touches perso et macros éditées sur la tablette |
| Productivité et simplicité | Commandes essentielles centralisées, plusieurs actions en un geste, moins de manipulations sur l'ordinateur | Deck, macros, fenêtres ouvertes sur le schéma des écrans, Rejoindre une réunion |
| Accessibilité | Interface adaptable, pour faciliter certains usages des travailleurs en situation de handicap | TalkBack, taille du texte, grandes commandes ([spec accessibilité](superpowers/specs/2026-10-29-accessibilite-design.md)) |
| Polyvalence | Un même équipement dans des environnements professionnels et personnels | Plusieurs ordinateurs (bureau le jour, maison le soir), profils |
| Durabilité | Prolonger un équipement robuste déjà fabriqué | Seconde vie du stock, kiosque ([kiosque.md](kiosque.md)) |

## Publics

### Canal prioritaire : B2B

Entreprises et professionnels qui travaillent chaque jour sur ordinateur. Besoins visés : simplifier le poste de travail, automatiser les tâches répétitives, gagner en confort, personnaliser l'environnement numérique, notamment pour les collaborateurs en situation de handicap.

Vente en **produit autonome** ou en **pack avec un PC Unowhy**, par les équipes commerciales B2B et le portefeuille clients existant.

### Canal complémentaire : B2C

Vente directe sur un site e-commerce : indépendants, freelances du numérique et des prestations intellectuelles, télétravailleurs qui personnalisent leur espace de travail.

### Hypothèse d'usage : la réunion

Le moment où la tablette compte le plus pour un salarié de bureau est la **réunion** (écran partagé ou projeté) : l'écran de l'ordinateur est occupé, la tablette garde micro, caméra, agenda et notes sous la main. C'est le parti pris du [Deck V2](superpowers/specs/2026-10-29-deck-v2-barre-profils-design.md) (profil Réunion, Rejoindre, appel en cours).

⚠️ Non prouvé. L'objection du cadrage du 27/10 reste ouverte : en bureautique classique on passe d'une fenêtre à l'autre et l'écran n'est jamais bloqué. La note ne la lève pas. À tester avec de vrais salariés.

## Concurrence et différenciation

Concurrents cités par la note : Elgato (Stream Deck), Logitech (MX Creative Console), Corsair (Xeneon Edge). Tous permettent d'accéder vite aux applis et d'automatiser des tâches.

Cinq atouts d'UnoMate d'après la note :

| Atout | État |
|---|---|
| Grand écran tactile 10,1", 10 points | 10,1" relevé ([tablette-4.2.md](tablette-4.2.md)) ; les 10 points viennent de la note |
| Personnalisation avancée | Fait (profils, widgets, touches perso, macros) |
| Usages variés : réunions, organisation du travail, mode concentration, commande vocale, documents filmés par la caméra arrière 5 Mpx | Fait (Réunion, agenda, tâches, Focus, voix, mode Caméra) ; micro et caméra 5 Mpx relevés dans [tablette-4.2.md](tablette-4.2.md) |
| Approche inclusive : interfaces simplifiées, grandes commandes | En cours ([spec accessibilité](superpowers/specs/2026-10-29-accessibilite-design.md)) ; l'effet réel sur les personnes en situation de handicap n'est pas testé |
| Robuste et durable : IP52, IK06, indice de réparabilité 7,8/10 | Annoncé par la note, non vérifié par nous |

L'appartenance au catalogue B2B d'Unowhy (réseau de distribution, portefeuille clients) est un avantage commercial cité par la note. Comparatif détaillé : annexe 3 de la note, absente du fichier reçu.

## Produit

Déjà là et utile en B2B : Profils Accueil / Deck / Réunion, agenda et Rejoindre (Teams, Meet, Zoom, Webex), appel en cours, tâches, Focus et Ne pas déranger, fenêtres ouvertes de l'ordinateur, onglet Son, page Média, plusieurs ordinateurs, kiosque, accessibilité de base. Widgets Jira, GitLab et agents Claude pour les équipes techniques (Mac pour les agents).

La note ne liste **aucune fonction nouvelle**. Les briques à ajouter se cadrent avec l'équipe, à partir des retours de la cible.

Briques de l'ancien cadrage gamers / streamers, **mises de côté** (B2C secondaire, rien à coder avant décision) :

| Brique | Détail |
|---|---|
| Soundboard | Étape `sound` dans `Step` (`backend/src/deck.ts`) |
| Page OBS | Voir [obs.md](obs.md) |
| Onglet web du Deck | Tableau de bord SimHub dans « Page de l'ordi » |
| Chat Twitch | Lecture seule, anonyme |

## Canaux (hypothèses à tester)

- B2B, prioritaire : produit autonome ou pack avec un PC Unowhy, via les équipes commerciales et le portefeuille clients.
- B2C, complémentaire : site e-commerce.

La note envisage trois offres tarifaires (individuelle, volume B2B, pack avec un PC). Les prix ne sont pas repris ici.

## Validation

Hypothèses, à confirmer avec l'équipe.

**Démo du hackathon** : une journée de salarié depuis la tablette. Agenda, Rejoindre une réunion Teams, micro, caméra et raccrocher, Focus avec Ne pas déranger sur l'ordinateur (parcours du Deck V2).

**Après** : faire tester la tablette par des salariés de quelques entreprises clientes, dont un collaborateur en situation de handicap, pour valider l'hypothèse de la réunion et l'argument accessibilité.

## Bloquants avant la vente

Déjà suivis dans [etat-du-projet.md](etat-du-projet.md) :

- exe Windows (`UnowhyHelper.exe`, `UnowhyCompanion.exe`) à signer avec un certificat Unowhy (section Pourquoi ce projet) ;
- kiosque à poser en usine par Unowhy, à confirmer avec eux (section Avant de livrer, [kiosque.md](kiosque.md)).

## Points non vérifiés

- IP52, IK06 et indice de réparabilité 7,8/10 : chiffres de la note, non mesurés par nous.
- Annexes 2 (hypothèses tarifaires, coûts, marges) et 3 (benchmark) : absentes du fichier reçu. Il s'arrête aussi sur une ligne « Ressource : OS linux » sans contenu.
- Stock : la note donne 20 000 tablettes (section 1) puis 18 000 (section 3). On retient 18 000.
- L'en-tête de la note porte « Janvier 2024 », incohérent avec le calendrier du projet : date probablement à corriger.
- Nombre de tablettes 4.2 encore chez des élèves. L'Île-de-France équipe ~150 000 élèves par an, mais en PC Y13.
- Hypothèse « la réunion » et argument accessibilité : aucun test utilisateur.

## Pistes écartées (cadrage 27-28/10)

Le cadrage précédent visait la « régie de poche » : piloter un direct sans quitter l'écran principal. Cœur : gamers, streamers, créateurs de contenu (persona : joueur-streamer sur PC Windows). La note B2B ne les cible plus.

| Public | Usage | Preuve de la demande |
|---|---|---|
| Gamers, streamers, créateurs | Scènes OBS, jingles, micro Discord, chat | Non retenu comme cœur |
| Simracers et simulateurs de vol | Tableau de bord (vitesse, rapport, carburant) à côté du jeu | SimHub affiche déjà son tableau de bord sur une tablette Android du réseau ; Corsair vend le Xeneon Edge pour cet usage |
| Podcasteurs et webradios scolaires | Jingles, micro, chrono de l'émission | Programme « Une webradio, un parrain » : plus de 700 collèges équipés en 2022-2023 |
| Enseignants | PC projeté, visualiseur (mode Caméra) | Le mode Caméra reste dans le produit |
| Traders, seniors, musiciens | Plusieurs écrans déjà, usage hors PC, latence du WiFi | Écartés, raisons inchangées |
| Domotique, borne d'accueil | Pas de PC à piloter | Autre produit |

## Sources

- Note projet UNOMATE (`UNOMATE_Note Projet.docx`, en-tête « Janvier 2024 »), sections 1 à 4. Annexes absentes.
- [Spec Deck V2](superpowers/specs/2026-10-29-deck-v2-barre-profils-design.md) : cible B2B et moment de la réunion.
- SimHub sur une tablette : https://simxpro.com/blogs/guides/simhub-dashboard-on-a-tablet-cheap-second-screen-setup-that-works
- Corsair Xeneon Edge pour le simracing : https://www.corsair.com/us/lt/explorer/gamer/monitors/how-to-use-xeneon-edge-as-a-dashboard-for-sim-racing/
- Webradio à l'école : https://eduscol.education.gouv.fr/6687/usages-pedagogiques-de-la-webradio et https://www.education-aux-medias.ac-versailles.fr/IMG/pdf/mettre_en_place_un_projet_webradio.pdf
- Équipement des élèves d'Île-de-France : https://www.unowhy.com/wp-content/uploads/2024/04/CP_UNOWHY_IDF_29012024.pdf
