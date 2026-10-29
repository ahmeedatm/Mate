# Piloter OBS (idée, pas faite)

> 29/10/2026 : mise de côté. La cible est le B2B ([cible-et-positionnement.md](cible-et-positionnement.md)) ; OBS servait le cadrage gamers / streamers.

Étudié le 26/10/2026. OBS 28+ intègre obs-websocket v5 (port 4455) : le backend, sur l'ordinateur, s'y connecte en local (WebSocket natif de Node 22, sans dépendance). Mac et Windows.

## Possible
- Scènes : liste avec miniatures, changer de scène, mode studio, transitions.
- Direct et enregistrement : lancer, arrêter, pause, replay buffer, caméra virtuelle, chapitres.
- Son : couper un micro, volume par source.
- Sources : afficher ou masquer, activer un filtre.
- État : en direct + durée, images perdues, débit, CPU d'OBS.
- Raccourcis d'OBS, et étape `obs` dans les macros du deck (avec le reste).
- Ne pas déranger pendant le direct (`dnd.ts`).

## Pas possible / limites
- Pas de vraie vidéo : captures 1 à 2 i/s seulement ; vu-mètres à ralentir (OBS les envoie toutes les 50 ms).
- Chat, spectateurs, alertes : pas OBS, il faudrait l'API Twitch/YouTube (OAuth), deuxième étape.
- OBS seulement (ni Streamlabs, ni Twitch Studio).
- Le serveur WebSocket doit être activé une fois dans OBS (désactivé par défaut). Son mot de passe est dans `obs-studio/plugin_config/obs-websocket/config.json` : le backend pourrait le lire seul (à vérifier).
- Pas d'édition des scènes depuis la tablette.

## Intégration envisagée
`backend/src/obs.ts` + étape `obs` dans `runAction` ; page d'appli `"app": "OBS Studio"` du deck (scènes, Direct, Enregistrer, Replay, micros) ; pastille « En direct » dans la barre latérale. Pitch : moins cher et plus complet qu'un Stream Deck Elgato.
