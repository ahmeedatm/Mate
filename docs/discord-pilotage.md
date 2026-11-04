# Pilotage de Discord : ce que fait le compagnon (à faire valider)

Note de validation (29/10/2026). Elle décrit le fonctionnement réel, vérifié dans le code ; la décision revient au juridique ou au produit.

## En une phrase

Le compagnon pilote l'appli Discord officielle de l'ordinateur **comme le ferait un lecteur d'écran** : il lit son interface et appuie sur ses boutons par les API d'accessibilité du système (UI Automation sous Windows, Accessibilité sous macOS), seulement quand l'utilisateur touche une touche sur la tablette. Aucune modification de l'appli. Depuis le 4/11/2026, il lit aussi, en lecture seule, les participants d'un appel (photos, qui parle) par le **RPC local** de l'appli Discord, une fois que l'utilisateur l'a autorisé (voir plus bas).

## Ce qu'il fait

| Fonction | Comment | Où |
|---|---|---|
| Ouvrir Discord, le mettre devant | lancement de l'appli et gestion des fenêtres du système | `backend/src/os.ts` |
| Couper le micro, sourdine du casque, quitter l'appel, arrêter le partage d'écran | appui sur le bouton de Discord qui porte ce nom (« Rendre muet », « Mettre en sourdine », « Déconnecter », « Arrêter de streamer »), à chaque toucher de la touche correspondante | `backend/config/deck.example*.json` (étape `press`), `helper/Program.cs`, `src/mac/calls.ts` |
| Afficher l'état de l'appel | lecture du panneau « Voix connectée » : salon ou appel, **noms des participants**, micro coupé, sourdine, partage d'écran ; relu tant que la page Discord est affichée | `Voice` dans `helper/Program.cs`, `discordStateOf` dans `src/mac/calls.ts` |
| Appel entrant | lecture de la fenêtre de sonnerie (titre, **nom de l'appelant**), puis Répondre / Vidéo / Ignorer = vrais boutons de Discord, sur toucher | `backend/config/calls.json`, `src/calls.ts` |
| Volume de Discord | mélangeur de son de Windows (volume par appli), pas Discord lui-même | `helper/Native.AppAudio.cs` |
| Photos des participants, qui parle (4/11/2026) | RPC local de l'appli Discord (`ws://127.0.0.1:6463`, en se présentant comme StreamKit, l'outil de Discord pour les streamers, scope `rpc` seul) : participants de l'appel en cours, leur photo, `SPEAKING_START/STOP`. Autorisé une fois par l'utilisateur (« Montrer les photos » sur la tablette, puis « Autoriser » dans Discord). Les photos sont téléchargées par l'ordinateur sur `cdn.discordapp.com` | `src/discord-rpc.ts`, `src/discord-auth.ts`, `src/routes/discord.ts` |
| Mac : ouvrir l'arbre d'accessibilité | pose de l'attribut standard `AXManualAccessibility` sur l'appli (Electron n'expose son interface qu'ainsi, comme pour VoiceOver) | `src/mac/calls.ts` |

## Ce qu'il ne fait pas

- Pas de bot ni de compte Discord à part. Le **RPC local** est utilisé depuis le 4/11/2026, en lecture seule : décision d'Ahmed, qui **remplace** « pas de RPC local » (décision d'Eliot), parce que la lecture de la fenêtre ne donne ni les photos ni qui parle (#169, `docs/specs/2026-11-04-discord-photos-design.md`). Le RPC ne pilote rien : micro, sourdine et « quitter » restent les boutons ci-dessus. Aucun scope de messages n'est demandé.
- Le jeton du RPC (7 jours, renouvelé sans fenêtre) reste sur l'ordinateur, dans `.data/discord.json` (droits 600) : jamais sur la tablette, dans une réponse ou un journal. Refusé ou révoqué : il est effacé, la tablette repropose « Montrer les photos ».
- Si Discord ferme sa porte à StreamKit, ou si l'utilisateur n'autorise pas : on revient à la lecture de la fenêtre (noms seulement), sans rien casser.
- Pas de modification ni d'injection dans le client Discord, pas de lecture de ses fichiers.
- Pas de lecture ni d'envoi de messages ; seuls l'appelant et les participants d'un appel sont lus.
- Aucune action sans geste de l'utilisateur sur la tablette (pas d'automatisation en arrière-plan, pas de macro qui tournerait seule).
- Rien n'est stocké sauf le jeton du RPC et les photos des participants (64 px, cache de l'ordinateur) : noms et états restent en mémoire, le temps de l'affichage.

## Questions à trancher

1. **Conditions de Discord** : piloter l'appli officielle par les API d'accessibilité du système, à la demande de l'utilisateur, est-il compatible avec les conditions d'utilisation et la politique développeurs de Discord (règles sur l'automatisation de comptes et les modifications du client) ? Comparaison utile : les produits du marché (Elgato Stream Deck) passent par l'intégration officielle de Discord, sur accord de Discord.
2. **Données personnelles** : les noms des participants et de l'appelant passent de l'ordinateur à la tablette sur le réseau local (chiffré) et s'affichent à l'écran. Faut-il le mentionner dans la politique de confidentialité ?
3. **Marque** : le nom « Discord » apparaît sur la tablette (page et touches) pour désigner l'appli ; l'icône vient de l'ordinateur (icône de l'appli installée). Usage acceptable sans accord ?

Si la réponse à 1 est non : le plus simple est de garder « Ouvrir Discord » et le volume par appli (qui ne touchent pas Discord), et de retirer les touches `press`, la lecture de l'appel et les appels entrants pour Discord.
