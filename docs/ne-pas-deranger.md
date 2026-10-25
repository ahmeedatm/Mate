# Ne pas déranger pendant le Focus

Quand le chrono de concentration démarre sur la tablette (écran Focus, pastille « Ne pas déranger sur l'ordi »), l'ordinateur passe en Ne pas déranger. Il en sort à la pause, à la fin ou si on désactive la pastille. Il ne touche pas à un Ne pas déranger que l'utilisateur avait déjà mis, et le rend tout seul quelques minutes après la fin prévue si la tablette ne revient pas (`backend/src/dnd.ts`).

## Windows 11

Rien à installer. Le compagnon ouvre le centre de notifications (Win+N), bascule l'interrupteur Ne pas déranger et le referme : le panneau s'affiche environ une demi-seconde.

## Mac : deux raccourcis à créer une fois

macOS n'a pas de commande pour la concentration ; l'appli Raccourcis, si. Dans **Raccourcis**, créer :

1. **Compagnon Ne pas déranger** : action « Définir la concentration » → **Activer** « Ne pas déranger » **jusqu'à ce que je le désactive**.
2. **Compagnon Fin Ne pas déranger** : action « Définir la concentration » → **Désactiver** « Ne pas déranger ».

Les noms doivent être exactement ceux-ci. Sans eux, la tablette n'affiche pas d'erreur : le Focus marche, sans Ne pas déranger. Vérifier : `shortcuts list` dans le Terminal.
