# Pilotage de Discord : ce que fait le compagnon (à faire valider)

Note pour la validation demandée par l'audit de vendabilité (carte COMP-52, 29/10/2026). Elle décrit le fonctionnement réel, vérifié dans le code ; la décision revient au juridique ou au produit.

## En une phrase

Le compagnon pilote l'appli Discord officielle de l'ordinateur **comme le ferait un lecteur d'écran** : il lit son interface et appuie sur ses boutons par les API d'accessibilité du système (UI Automation sous Windows, Accessibilité sous macOS), seulement quand l'utilisateur touche une touche sur la tablette. Aucune API Discord, aucun compte ni jeton Discord, aucune modification de l'appli.

## Ce qu'il fait

| Fonction | Comment | Où |
|---|---|---|
| Ouvrir Discord, le mettre devant | lancement de l'appli et gestion des fenêtres du système | `backend/src/os.ts` |
| Couper le micro, sourdine du casque, quitter l'appel, arrêter le partage d'écran | appui sur le bouton de Discord qui porte ce nom (« Rendre muet », « Mettre en sourdine », « Déconnecter », « Arrêter de streamer »), à chaque toucher de la touche correspondante | `backend/config/deck.example*.json` (étape `press`), `helper/Program.cs`, `src/mac/calls.ts` |
| Afficher l'état de l'appel | lecture du panneau « Voix connectée » : salon ou appel, **noms des participants**, micro coupé, sourdine, partage d'écran ; relu tant que la page Discord est affichée | `Voice` dans `helper/Program.cs`, `discordStateOf` dans `src/mac/calls.ts` |
| Appel entrant | lecture de la fenêtre de sonnerie (titre, **nom de l'appelant**), puis Répondre / Vidéo / Ignorer = vrais boutons de Discord, sur toucher | `backend/config/calls.json`, `src/calls.ts` |
| Volume de Discord | mélangeur de son de Windows (volume par appli), pas Discord lui-même | `helper/Native.AppAudio.cs` |
| Mac : ouvrir l'arbre d'accessibilité | pose de l'attribut standard `AXManualAccessibility` sur l'appli (Electron n'expose son interface qu'ainsi, comme pour VoiceOver) | `src/mac/calls.ts` |

## Ce qu'il ne fait pas

- Pas d'API Discord : ni bot, ni RPC local (écarté par décision d'Eliot), ni compte, ni jeton.
- Pas de modification ni d'injection dans le client Discord, pas de lecture de ses fichiers.
- Pas de lecture ni d'envoi de messages ; seuls l'appelant et les participants d'un appel sont lus.
- Aucune action sans geste de l'utilisateur sur la tablette (pas d'automatisation en arrière-plan, pas de macro qui tournerait seule).
- Rien n'est stocké : noms et états restent en mémoire, le temps de l'affichage.

## Questions à trancher

1. **Conditions de Discord** : piloter l'appli officielle par les API d'accessibilité du système, à la demande de l'utilisateur, est-il compatible avec les conditions d'utilisation et la politique développeurs de Discord (règles sur l'automatisation de comptes et les modifications du client) ? Comparaison utile : les produits du marché (Elgato Stream Deck) passent par l'intégration officielle de Discord, sur accord de Discord.
2. **Données personnelles** : les noms des participants et de l'appelant passent de l'ordinateur à la tablette sur le réseau local (chiffré depuis COMP-48) et s'affichent à l'écran. Faut-il le mentionner dans la politique de confidentialité ?
3. **Marque** : le nom « Discord » apparaît sur la tablette (page et touches) pour désigner l'appli ; l'icône vient de l'ordinateur (icône de l'appli installée). Usage acceptable sans accord ?

Si la réponse à 1 est non : le plus simple est de garder « Ouvrir Discord » et le volume par appli (qui ne touchent pas Discord), et de retirer les touches `press`, la lecture de l'appel et les appels entrants pour Discord.
