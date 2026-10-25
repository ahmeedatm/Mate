# Ne pas déranger pendant le Focus

Quand le chrono de concentration démarre sur la tablette (écran Focus, pastille « Ne pas déranger sur l'ordi »), l'ordinateur passe en Ne pas déranger. Il en sort à la pause, à la fin ou si on désactive la pastille. Il ne touche pas à un Ne pas déranger que l'utilisateur avait déjà mis, et le rend tout seul quelques minutes après la fin prévue si la tablette ne revient pas (`backend/src/dnd.ts`).

## Windows 11

Rien à installer. Le compagnon ouvre le centre de notifications (Win+N), bascule l'interrupteur Ne pas déranger et le referme : le panneau s'affiche environ une demi-seconde.

## Mac

Rien à installer non plus. Le compagnon ouvre le Centre de contrôle, bascule Ne pas déranger dans le détail de la Concentration et le referme : le panneau s'affiche environ une seconde. Il utilise l'autorisation Accessibilité déjà demandée pour les fenêtres ; les éléments sont trouvés par leur identifiant, pas par leur texte, donc quelle que soit la langue du Mac (vérifié sur macOS 15). Si une autre concentration est déjà active (Travail, Sommeil…), il n'y touche pas.
