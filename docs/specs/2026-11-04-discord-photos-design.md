# Discord : photos des participants et halo de celui qui parle (#169)

Design validé par Ahmed le 2026-11-04 (kickoff). À ranger dans `docs/specs/2026-11-04-discord-photos-design.md`.

## Intention

Pendant un appel Discord, le bloc Discord de l'écran Son montre aujourd'hui « Avec madenheaven., player_678 » en
texte, et « Quelqu'un parle » d'après le seul niveau sonore de Discord. Ahmed veut voir **les photos** des
participants et **qui parle**, d'un coup d'œil, de loin.

## Décision : RPC local de Discord (tranchée par Ahmed)

La lecture actuelle (UI Automation sous Windows, Accessibilité sur Mac : `Voice` dans `helper/Program.cs`,
`discordStateOf` dans `src/mac/calls.ts`) ne donne ni photo ni « qui parle ». On passe par le **RPC local** de
l'appli Discord de l'ordinateur, comme le projet libre Discover (trigg/Discover, GPL-3, vivant : on reprend
l'approche, pas le code).

Cette décision **remplace** « Pas de RPC Discord (décision d'Eliot) » (`docs/architecture.md`,
`docs/discord-pilotage.md`) : Ahmed l'a tranchée en connaissant cette décision. Les deux docs sont mises à jour
dans la même PR, avec la date et la raison.

Comment fait Discover (lu dans `discover_overlay/discord_connector.py`) :
- WebSocket `ws://127.0.0.1:6463/?v=1&client_id=207646673902501888`, l'identifiant de StreamKit, l'outil
  de Discord pour les streamers. Si 6463 est pris, Discord essaie les ports suivants, jusqu'à 6472.
- `AUTHORIZE` avec cet identifiant. Discord ouvre une fenêtre « Autoriser » sur l'ordinateur et rend un
  `code`. Le code s'échange contre un `access_token` par `POST https://streamkit.discord.com/overlay/token
  {code}`. Puis `AUTHENTICATE {access_token}`.
- Ensuite `GET_SELECTED_VOICE_CHANNEL` et `SUBSCRIBE` à `VOICE_STATE_CREATE/UPDATE/DELETE`,
  `SPEAKING_START/STOP` (par salon) et `VOICE_CHANNEL_SELECT`.
- Les photos se lisent sur `cdn.discordapp.com/avatars/<id>/<hash>.png`.

Le constructeur vérifie sur la vraie appli : l'en-tête `Origin` attendu, le plus petit jeu de scopes qui suffit
(viser `rpc` seul, sans `messages.read`), la durée de vie du jeton.

## Objectifs

- Une rangée de photos rondes à la place de la ligne « Avec … », dans le bloc Discord de l'écran Son
  (`CallWidget`, taille L, celle de la photo d'Ahmed).
- Un halo (anneau de la couleur d'accent du thème) autour de la photo de chaque personne qui parle. Il apparaît
  en 120 ms environ et s'éteint en 300 ms environ. Pas d'animation en boucle (écran à 55 Hz, GPU modeste,
  `docs/tablette-4.2.md`).
- Salon plein : les derniers à avoir parlé d'abord, autant que la largeur permet, puis un rond « +6 ».
- Pas de photo (pas d'avatar Discord, ou image qui ne charge pas) : les initiales dans un rond.
- Windows et Mac (la porte locale de Discord est la même).

## Autorisation (une seule fois)

- Discord est ouvert mais pas encore autorisé : le bloc Discord montre un bouton « Montrer les photos ». Le
  toucher envoie une demande au compagnon, qui lance `AUTHORIZE`. La tablette affiche « Autorise sur
  l'ordinateur » pendant 60 s au plus.
- Accepté : le jeton est gardé **sur l'ordinateur seulement**, dans `backend/.data/discord.json` (droits 600),
  comme `github.json`. La tablette ne le voit jamais.
- Refusé, délai dépassé ou Discord injoignable : message par `errorText`, le bouton revient.
- Jeton refusé plus tard (expiré, révoqué) : on l'efface, et le bouton revient.

## Flux de données

- Le compagnon garde une connexion RPC ouverte tant que Discord tourne et qu'un jeton existe. Il se reconnecte
  seul quand Discord redémarre, avec un délai croissant, sans fenêtre surprise.
- `VoiceState` (backend `win/index.ts` et l'équivalent Mac, Android `DeckApi.kt`) gagne
  `members: [{ id, name, avatar: boolean, speaking: boolean }]`. `people` reste rempli, pour la compatibilité
  et le repli.
- Photos : le compagnon les télécharge (Discord ne voit jamais la tablette) et les garde en cache comme les
  icônes (`cachedIcon`, 64 px). Il les sert par une route avec le jeton d'appairage, sur le modèle de
  `/windows/icon`.
- Vitesse du halo : la carte relit l'appel toutes les 4 s, trop lent pour savoir qui parle. **Mon choix** :
  pendant un appel, tant que le bloc est affiché, la tablette lit l'état « qui parle » toutes les 500 ms par une
  route légère. Si un canal de poussée du compagnon vers la tablette existe déjà, le constructeur le préfère.

## Repli

RPC indisponible (Discord fermé, pas autorisé, refus, porte introuvable) : le comportement d'aujourd'hui, sans
changement. On lit la fenêtre par l'accessibilité et on montre les noms. Rien ne casse si Discord ferme sa porte
à StreamKit.

## Anti-objectifs

- Aucun jeton Discord sur la tablette, dans un intent ou dans un journal.
- Ne lire aucun message, et ne demander aucun scope de messages.
- Le RPC ne pilote rien : micro, sourdine et « quitter » restent les boutons actuels.

## Hors périmètre

Photos dans le bandeau du haut ou dans la carte d'appel plein écran ; badge micro coupé sur chaque photo ; Teams.

## Docs et tests

- Docs : `docs/discord-pilotage.md` (le RPC est maintenant utilisé, on dit ce qui est lu), `docs/confidentialite.md`
  (`.data/discord.json` ; appels de l'ordinateur à `streamkit.discord.com` et `cdn.discordapp.com`),
  `docs/architecture.md`.
- Backend : tests `backend/test/` avec un faux serveur WebSocket (autorisation, échange du code, abonnements,
  qui parle, reconnexion, jeton refusé), et un contrôle dans `backend/qa/sanity.ts`.
- Android : test JVM de l'ordre d'affichage (derniers à parler d'abord, « +N », initiales).
- Essai réel : un appel Discord sur le PC d'Ahmed, la tablette montre les photos et le halo suit la voix.

## Choix du constructeur (après relecture, marqués « mon choix »)

- Appels privés (DM, groupe) : même chemin que les salons (`GET_SELECTED_VOICE_CHANNEL` renvoie aussi un appel privé). Sinon, le repli s'applique, sans chemin à part. Mon choix.
- Ordre de travail : d'abord le connecteur RPC seul, vérifié sur le PC d'Ahmed (`ssh pc`) avec un vrai Discord : `Origin`, scopes, durée du jeton, appel privé. Ensuite l'écran. Le Mac se vérifie de la même façon. Mon choix.
- Durée du jeton : si l'échange rend un `refresh_token`, on renouvelle sans bruit. Sinon, on rejoue `AUTHORIZE` avec `prompt: none` (Discover le fait, sans fenêtre s'il est déjà autorisé). Le bouton ne revient que si ça échoue. Mon choix.
- L'utilisateur autorisé (renvoyé par `AUTHENTICATE`) n'apparaît pas dans la rangée, comme dans `people` aujourd'hui (tranché par Ahmed).
- Ordinateur verrouillé : le bouton « Montrer les photos » est masqué. Un délai dépassé suffit comme fin. Mon choix.
- Plusieurs ordinateurs appairés : un jeton par ordinateur, dans son propre `.data/`. Rien n'est partagé. Mon choix.

## Laissé au constructeur

La taille des photos selon la largeur du bloc ; le nom de la route ; la bibliothèque WebSocket (le module natif
de Node 22 si possible, pas de nouvelle dépendance sinon justifiée) ; la façon de trouver le port.

## Questions ouvertes

Aucune pour Ahmed. Risque connu : Discord peut un jour bloquer l'emprunt de l'identifiant de StreamKit. Dans ce
cas, le repli garde l'écran actuel.
