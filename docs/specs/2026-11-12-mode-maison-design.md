# Mode Maison : Mate sans ordinateur

Date : 2026-11-12. Demandé par Ahmed. Statut : à approuver.

## Intention

Aujourd'hui Mate n'a qu'un rôle : écran de contrôle d'un Mac ou d'un PC. PC éteint, l'Accueil n'affiche
que le widget « Connecter un ordinateur » (`ConnectWidget`) et la tablette ne sert plus à rien.
On lui donne un second rôle : **tableau de bord de la maison**, vivant sans aucun PC allumé. Exemple
d'Ahmed : PC éteints, il veut voir l'heure des prochains transports et régler ses LED.

## Ce qui existe déjà (à réutiliser, pas à refaire)

- Météo (MET Norway) et Transports (IDFM, clé injectée au build) : lus par la tablette elle-même,
  marchent déjà sans PC. Horloge : locale.
- Widget Maison (`ui/home/HaWidget`, `HaLight`, `HaLightPanel`, `LightFader`, PR #86, #202, #241) :
  toucher = allumer/éteindre, appui long sur une lumière réglable = panneau « Tiroir » (luminosité +
  8 couleurs). PR #240 (ouverte) : télé et Apple TV (`media_player.`). **Tout passe par le backend du
  PC** (`net/HaApi` → `backend/src/ha.ts`, liste blanche `HA_ENTITIES`, jeton `HA_TOKEN` sur le PC).
- Liste des ordinateurs (`Computers.kt`), écrans par ordinateur, bascule vers un autre ordinateur qui
  répond (`net/Connection.kt`), recréation des écrans (`MainActivity.onComputerChanged`).
- Matériel d'Ahmed (lu dans son Home Assistant, NAS) : WLED `light.wled_bureau`, `light.wled_tv`,
  `light.wled_nas_principal`, `light.ecran_pc`, `light.ventilateurs_nas`… et ~30 `switch.` techniques
  WLED (gel, inverser, synchroniser) qui ne doivent pas encombrer l'écran.

## Décisions d'Ahmed

1. **Entrée « Maison » à part** dans la liste des ordinateurs, toujours présente, qui n'est pas un PC.
   Elle a ses propres écrans, son bandeau et sa barre, comme un ordinateur. Choisie à la main ; prise
   **toute seule quand aucun PC ne répond**.
2. Quand un PC se rallume pendant qu'on est sur Maison : **on reste sur Maison** (pas d'écran qui change
   sous les doigts). Revenir au PC = le choisir.
3. Première version sans PC : **heure, météo, transports, lumières et prises**.
4. Lumières : **Home Assistant en direct ET WLED en direct**. WLED marche même si Home Assistant (le NAS)
   est éteint, et pour les gens qui n'ont pas Home Assistant.
5. Gestes : ceux déjà faits (#241) restent, à l'identique.
6. Jeton Home Assistant : **copié une fois depuis un PC appairé** qui a `HA_URL`/`HA_TOKEN`, plus une
   **saisie à la main** dans Réglages pour qui n'en a pas. Gardé **chiffré** sur la tablette (Android
   Keystore). C'est une **nouvelle exception** à la règle « aucun secret sur la tablette » du CLAUDE.md,
   à y écrire.
7. Ce qui s'affiche : **choisi sur la tablette**. Elle lit la liste de Home Assistant et Ahmed coche les
   entités à montrer ; les WLED du réseau sont trouvées toutes seules et cochables aussi.

## Objectifs

- PC tous éteints : en moins de ~10 s la tablette montre l'Accueil Maison, et les lumières répondent.
- Allumer, éteindre, régler luminosité et couleur d'une WLED sans PC, avec HA allumé ou éteint.
- Un nouvel utilisateur sans PC configuré peut tout régler depuis la tablette.

## Non-objectifs (v1)

Agenda, tâches, courses, Deck, Focus lié au PC, effets et presets WLED, scènes en direct de WLED,
alertes maison en direct (`alerts.ts` reste côté PC), températures de blanc.

## Anti-objectifs (échec même si ça marche)

- Les ~30 interrupteurs techniques WLED s'affichent par défaut.
- La tablette saute d'elle-même de Maison vers un PC, ou d'un PC vers Maison alors que le PC répond.
- Le jeton HA part en clair sur le WiFi, ou apparaît dans un journal, un intent, une capture, une
  sauvegarde (`adb backup`, `allowBackup`).
- Le mode PC est cassé ou ralenti (aucune régression sur l'existant).

## Contraintes

- Matériel : `docs/tablette-4.2.md` (2 Go de RAM, 128 Mo de tas). Pas de dépendance nouvelle
  (HTTP par `HttpURLConnection` + `org.json`, découverte par `NsdManager` natif).
- Réseau (règle du projet) : jeton jamais en clair hors de 127.0.0.1, sauf l'exception HA ci-dessous
  (« Réponses après relecture »).
- Mêmes règles d'affichage : textes dans `strings.xml`, erreurs par `errorText(e)`, couleurs par
  attributs de thème, widgets dans leurs 16 formes (Maison a déjà les siennes).

## Le comment, partie tranchée

- Une seule interface côté widget : une source « lumières » avec trois implémentations
  (par le PC = l'actuelle `HaApi`, HA en direct, WLED en direct). Le widget Maison ne sait pas d'où
  viennent les entités. Sur un PC : comportement inchangé. Sur Maison : HA direct + WLED direct.
- Une WLED vue à la fois par HA et en direct n'apparaît qu'une fois (dédoublonnage par adresse MAC
  ou IP ; HA prioritaire quand il répond, bascule en direct quand il ne répond pas).
- Écrans par défaut de Maison : un Accueil avec Horloge, Météo, Transports et Maison.

## Laissé au constructeur

Nom de l'entrée et icône ; où vit la case « entrée Maison » (`Computers.kt` ou à côté) ; format exact du
transfert du jeton depuis le PC (nouvelle route du backend, protégée par le jeton d'appairage, HTTPS) ;
fréquence de relecture des états ; écran de choix des entités ; dédoublonnage précis.

## Réponses après relecture

- **HTTP pour HA (décision d'Ahmed)** : le Home Assistant d'Ahmed répond en HTTP simple sur le réseau
  local, sans HTTPS. HTTP est permis seulement pour une adresse du réseau local (privée), après une case
  « Autoriser HTTP » avec avertissement (« quelqu'un sur ton WiFi pourrait lire le jeton »), comme
  `HA_ALLOW_HTTP` côté PC. Sinon HTTPS obligatoire. Le refus de `Http.kt` (`cleartextRefused`) garde
  la même logique, avec cette exception explicite.
- **Bascule automatique (mon choix)** : au démarrage et en cours de session. Quand la recherche
  existante (`Connection.resolve` : actif, puis autres PC) n'a trouvé aucun PC qui répond, la tablette
  passe sur Maison. Jamais tant qu'un PC répond. Retour vers un PC : seulement à la main.
- **WLED en HTTP (mon choix)** : déjà permis (`network_security_config` autorise le clair, `Http.kt`
  ne refuse que si un jeton part en clair). WLED n'a pas de jeton : rien à changer.
- **Pas de fusion HA / WLED en v1 (mon choix)** : la liste à cocher montre chaque lumière avec sa
  source (« par Home Assistant » / « WLED direct »). Par défaut sont cochées : les WLED trouvées en
  direct (elles marchent sans NAS) et les `light.` de HA qui ne sont pas des WLED (`platform` ≠ `wled`,
  sinon préfixe `light.wled_`). Aucun `switch.` coché par défaut. Ahmed coche ce qu'il veut en plus.
- **Domaines HA en direct (mon choix)** : les mêmes que le backend au moment de la construction
  (`light.`, `switch.`, `scene.`, et `media_player.` si la PR #240 est fusionnée avant).

## Critère de réussite

Sur la vraie tablette, PC éteints : Accueil Maison affiché tout seul, prochains passages lus, WLED du
bureau allumée, réglée à 40 % et en bleu ; puis NAS arrêté (HA injoignable) : la même WLED répond
encore en direct.

## Premier geste

Test JVM rouge : une source « WLED en direct » qui lit `GET /json/state` d'une WLED de test et rend une
entité réglable (luminosité %, couleur) au format de `HaEntity`.
