# Scout références : DA Cadran

Date : 2026-10-27. Périmètre : pistes pour aller plus loin sur Cadran (tablette Android 10 lente, posée à côté de l'ordinateur).

Méthode : 3 recherches web, puis lecture directe des pages officielles. Les pages Apple HIG ont été lues en version `.md`. Une page marquée ⚠️ n'a pas été lue dans une source (source secondaire, page vide, 404, 429, ou trou de recherche).

Principes actuels de Cadran (référence) : une couleur (rouge = maintenant), ligne du jour 8 h à 20 h, battement de la minute, menu caché, mode repos, jour/nuit au coucher du soleil, ressorts Material 3.

## 1. Apple StandBy et Live Activities

| # | Idée | Ce que dit la source | Source | Application à Cadran |
|---|---|---|---|---|
| 1.1 | Plein écran à distance | En StandBy, la Live Activity remplit l'écran pour être lue de loin, ou prend peu de place en haut si les widgets doivent rester. | [HIG Live Activities](https://developer.apple.com/design/human-interface-guidelines/live-activities.md) | Minuteur ou réunion actifs : chiffre en plein écran. Sinon bandeau fin en haut avec heure et ligne. |
| 1.2 | Toucher pour agrandir | Toucher la Live Activity la fait passer en plein écran, à 2x, avec transition. | [HIG Live Activities, section StandBy](https://developer.apple.com/design/human-interface-guidelines/live-activities.md) | Toucher le bandeau du minuteur l'agrandit avec un ressort M3. |
| 1.3 | Durée bornée et fin nette | Pas plus de 8 h. Fin immédiate à la fin de la tâche. Résumé visible 15 à 30 min (« in most cases »). | [HIG Live Activities, Best practices](https://developer.apple.com/design/human-interface-guidelines/live-activities.md) | Un état actif (focus, rappel) a une fin franche. Résumé de 15 à 30 min, puis retour au décor. |
| 1.4 | Nuit rouge | En Night Mode, StandBy s'assombrit en pièce sombre et prend une teinte rouge. HIG : « Verify your design in Night Mode ». | [MacRumors, guide StandBy (20/11/2024)](https://www.macrumors.com/how-to/use-standby-mode-iphone) (⚠️ source secondaire). HIG ci-dessus pour la teinte rouge appliquée par le système. | Mode nuit Cadran = rouge seul, luminosité baissée si la pièce est sombre. ⚠️ Capteur de lumière de la tablette non vérifié. |
| 1.5 | Pas d'info sensible en veille | HIG : éviter les infos sensibles en Always-On et écran verrouillé, afficher un résumé anodin. | [HIG Always On](https://developer.apple.com/design/human-interface-guidelines/always-on.md), [HIG Live Activities](https://developer.apple.com/design/human-interface-guidelines/live-activities.md) | En repos, pas de titre d'événement ni de message perso. Écrire « Réunion 14 h » à la place du titre. |
| 1.6 | Rotation de piles (Smart Rotate) | L'option existe. L'article n'explique pas comment le widget est choisi. | [MacRumors](https://www.macrumors.com/how-to/use-standby-mode-iphone) (⚠️ mécanisme non documenté) | ⚠️ Pas à copier tel quel. Si rotation : ordre fixe, changement toutes les N minutes, jamais aléatoire. |
| 1.7 | Disposition par emplacement | StandBy retient écran et widgets par emplacement de charge. | [MacRumors](https://www.macrumors.com/how-to/use-standby-mode-iphone) (⚠️ source secondaire) | Disposition propre à chaque ordinateur appairé (Cadran a déjà `home_layout` par tablette). |
| 1.8 | Mettre à jour seulement si le contenu change | HIG : « Update a Live Activity only when new content is available. » | [HIG Live Activities, Starting, updating, and ending](https://developer.apple.com/design/human-interface-guidelines/live-activities.md) | Déjà aligné avec le battement de la minute. Ne rien redessiner si la valeur est identique. |
| 1.9 | Layout animé sur place | HIG : animer les éléments existants vers leur nouvelle position, plutôt que les retirer puis les réinsérer. Animations 2 s max. | [HIG Live Activities, Adding transitions](https://developer.apple.com/design/human-interface-guidelines/live-activities.md) | Jour vers repos : les éléments glissent à leur nouvelle place, pas de fondu. Durée max 2 s. |
| 1.10 | Mouvement qui se pose | HIG Always On : « Gracefully transition motion to a resting state; don't stop it instantly. » | [HIG Always On](https://developer.apple.com/design/human-interface-guidelines/always-on.md) | Entrée en repos : la ligne finit son mouvement et se pose, sans coupure. |
| 1.11 | Même mise en page en repos | HIG Always On : « Maintain a consistent layout ». Un composant interactif passe en état indisponible, il ne disparaît pas. ⚠️ Always On n'est pas supporté sur iPadOS : transposition à valider. | [HIG Always On](https://developer.apple.com/design/human-interface-guidelines/always-on.md) | Proposition : le menu caché laisse une fine marque au bord en repos, sans se dévoiler. |

## 2. Google : Nest Hub, Pixel Tablet, Android ambient

| # | Idée | Ce que dit la source | Source | Application à Cadran |
|---|---|---|---|---|
| 2.1 | Détection de présence par ultrasons | Le haut-parleur émet des ultrasons inaudibles, les micros captent l'écho. Portée d'environ 4 pieds (≈ 1,2 m). Traitement sur l'appareil, rien dans le cloud. | [Google Home, Turn on Ultrasound sensing](https://support.google.com/googlehome/answer/9509981) | Idée à valider : la tablette n'a pas de capteur de présence vérifié (⚠️ micro et haut-parleur à tester sur le MT8167). Demander le consentement, car écouter une présence est sensible. |
| 2.2 | Texte qui grossit en s'éloignant | Minuteur et météo passent en plus gros quand on est loin. Contrôles tactiles qui apparaissent quand on est près. | [Google Home, ultrasons](https://support.google.com/googlehome/answer/9509981) | Heure et ligne grossissent avec la distance. Sans capteur de distance : grossir sur demande (⚠️ non prévu par la source). |
| 2.3 | Notifications au bon moment | Les notifications perso n'apparaissent en écran ambiant que quand on est proche. | [Google Home, ultrasons](https://support.google.com/googlehome/answer/9509981) | Rappels discrets en repos : affichés au toucher ou à l'approche, pas en permanence. |
| 2.4 | Désactivé si micro coupé | Le détecteur est coupé quand le micro est muet. | [Google Home, ultrasons](https://support.google.com/googlehome/answer/9509981) | Couper le détecteur quand le micro est muet, comme Google. |
| 2.5 | Luminosité adaptative | Rien trouvé dans les sources lues. | ⚠️ non confirmé | Ne pas coder sur la base de cette idée sans autre source. |
| 2.6 | Ambient Wear OS : écran presque noir | « Keep at least 85% of the screen black (an on-pixel ratio of 15% or less). » Pas de grands aplats, contours plutôt que pleins. | [Android, Always-on (Wear OS)](https://developer.android.com/training/wearables/apps/always-on) | Repos Cadran : fond noir, traits fins, pas d'aplat de couleur. Mesurer le ratio de pixels allumés. |
| 2.7 | Ambient : zéro animation | « Pause or remove all animations in ambient mode. » | [Android, Always-on (Wear OS)](https://developer.android.com/training/wearables/apps/always-on) | Repos : pas d'animation. La ligne se pose une fois, puis reste fixe. |
| 2.8 | Ambient : une mise à jour par minute | « typically once per minute -- to conserve power. » Heure toujours affichée. | [Android, Always-on (Wear OS)](https://developer.android.com/training/wearables/apps/always-on) | Confirme le battement de la minute. Données qui changent vite : remplacer par `--`. |
| 2.9 | Même place entre les modes | « Keep elements in the same position across Interactive and Ambient modes. » | [Android, Always-on (Wear OS)](https://developer.android.com/training/wearables/apps/always-on) | Heure et ligne gardent leur place jour et repos. |
| 2.10 | Anti-brûlure par le système | Ne pas décaler soi-même les éléments. Garder les éléments critiques à 10 px du bord minimum. | [Android, Always-on (Wear OS)](https://developer.android.com/training/wearables/apps/always-on) | Marge de 10 px au bord en repos. Pas de décalage manuel. |
| 2.11 | Écran de veille système | La page DreamService lue en partie. Pas de citation exacte. | ⚠️ [DreamService](https://developer.android.com/reference/android/service/dreams/DreamService) non lu en entier | Piste : Cadran comme écran de veille Android déclenché en charge. À vérifier sur l'image kiosque. |
| 2.12 | Pixel Tablet en mode hub | Non lu. | ⚠️ non lu | Pas de conclusion. |
| 2.13 | Material « ambient » et « glanceable » | Page M3 Motion lue sans contenu. Pas de valeurs de ressort sourcées. | ⚠️ [M3 Motion](https://m3.material.io/styles/motion/overview) | Garder les ressorts actuels. Ne pas inventer de raideur ou d'amortissement. |

## 3. Calm technology et affichage « glanceable »

| # | Idée | Ce que dit la source | Source | Application à Cadran |
|---|---|---|---|---|
| 3.1 | Attention minimale, périphérie | « Technology should require the smallest possible amount of attention. » « Technology should make use of the periphery. » | [Calm Technology, Amber Case](https://calmtech.com/) (page sans date) | La ligne du jour en bord d'écran est dans la périphérie. Ne rien demander à l'œil sauf urgence. |
| 3.2 | Marche même en panne | « Technology should work even when it fails. » | [Calm Technology](https://calmtech.com/) | Si le backend ordinateur tombe, heure et ligne restent affichées depuis la tablette. |
| 3.3 | La voix est optionnelle | « Technology can communicate, but doesn't need to speak. » | [Calm Technology](https://calmtech.com/) | Les rappels parlés restent une option. L'écran suffit seul. |
| 3.4 | Le minimum | « The right amount of technology is the minimum needed to solve the problem. » | [Calm Technology](https://calmtech.com/) | Limiter les widgets du repos au strict utile. |
| 3.5 | Respect des normes sociales | « Technology should respect social norms. » Introduire les changements progressivement. | [Calm Technology](https://calmtech.com/) | Pas de flash ni de son la nuit. Un changement de mode progressif, pas brutal. |
| 3.6 | Taille de lecture et durée de coup d'œil | Non lu. Pas de source pour les distances ou les durées. | ⚠️ non lu | Mesurer nous-mêmes sur la tablette à 1 m. Ne pas citer de chiffre sans mesure. |

## 4. Haptique et son

| # | Idée | Ce que dit la source | Source | Application à Cadran |
|---|---|---|---|---|
| 4.1 | Moins c'est mieux | « When it comes to haptic feedback on mobile devices, less is more. » | [Android, Haptics design principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles) | Pas de vibration sur les ticks de la minute. Seulement sur action ou événement important. |
| 4.2 | Pas de « buzzy » | « Given the choice of buzzy haptics or no haptics for touch feedback, choose no haptics. » | [Android, Haptics design principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles) | Pas de vibration longue. Éviter `createOneShot` et `vibrate(long)`. |
| 4.3 | Clic net, 10 à 20 ms | Un clic doit durer 10 à 20 ms. Les vibrations longues « might feel buzzy ». | [Android, Haptics design principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles) | Toucher du menu ou fin de minuteur = clic court via `HapticFeedbackConstants`. |
| 4.4 | Intensité selon la fréquence | Un événement fréquent doit être très discret. Un événement important, plus marqué. | [Android, Haptics design principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles) | Molette ou balayage = quasi nul. Rappel = plus marqué. |
| 4.5 | Accord visuel, son, haptique | Une haptique désynchronisée « can be a bit unsettling ». | [Android, Haptics design principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles) | Un seul événement = une image, un son, un clic, tous au même instant. |
| 4.6 | Haptique optionnelle, rare | « Make haptics optional. » « Avoid overusing haptics. » Le meilleur retour est celui qu'on ne remarque que quand il manque. | [Apple HIG, Playing haptics](https://developer.apple.com/design/human-interface-guidelines/playing-haptics.md) | Réglage pour couper l'haptique. Peu de retours, bien choisis. |
| 4.7 | Motifs au sens fixe | Start et Stop pour une activité démarrée ou arrêtée. Click pour avancer par paliers : « Overusing the click haptic tends to diminish its utility ». | [Apple HIG, Playing haptics](https://developer.apple.com/design/human-interface-guidelines/playing-haptics.md) (motifs watchOS, ⚠️ pas Android) | Minuteur : un motif « start », un « stop ». Molette : clic par cran, sans excès. |
| 4.8 | Son d'ambiance, coupé en silence | Catégorie « Ambient » : sons non essentiels, répond au mode silencieux, se mélange aux autres sons. En silencieux, seuls les sons voulus jouent. Le volume système gouverne. | [Apple HIG, Playing audio](https://developer.apple.com/design/human-interface-guidelines/playing-audio.md) | Sons Cadran en catégorie ambiante : coupés en silencieux et au repos. Volume = volume système. |
| 4.9 | Pas d'information seulement par le son | « avoid communicating important information using only sound » (écrit pour visionOS, principe général). | [Apple HIG, Playing audio](https://developer.apple.com/design/human-interface-guidelines/playing-audio.md) | Chaque son a un texte ou une icône en face. |
| 4.10 | Trotteuse « stop-to-go » | Balayage d'environ 58,5 s, pause en haut, reprise au top de la minute. Moteur propre, indépendant du maître. | [Wikipedia, Swiss railway clock](https://en.wikipedia.org/wiki/Swiss_railway_clock) (⚠️ source secondaire, cite SBB) | Battement de la minute : la ligne avance, marque un arrêt court en fin de course, puis repart au top. À tester sur la tablette. |
| 4.11 | Sons Teenage Engineering, Nothing, Braun | Non lu. | ⚠️ non lu | Pas de conclusion. Lire leurs pages design avant de proposer un son. |

## 5. Produits de bureau

| # | Produit | Ce que dit la source | Source | Application à Cadran |
|---|---|---|---|---|
| 5.1 | Corsair Xeneon Edge | Écran 14,5 pouces, 2560×720, tactile 5 points, aimants et support. Widgets via iCUE ou Marketplace. Un appui sur une touche Stream Deck bascule tout le PC sur un profil. | [Corsair, Xeneon Edge](https://www.corsair.com/xeneon-edge) (page en français, FAQ incluse) | Format bandeau : la ligne du jour est déjà ce bandeau. Un appui qui bascule tout le contexte : déjà le rôle des modèles Travail / Maison / Veille. |
| 5.2 | Elgato Stream Deck+ | 4 encodeurs 360° avec appui qui cycle les actions (Dial Stacks). Bande tactile 108×14 mm qui montre l'info du cadran « at a glance ». Jusqu'à 10 pages. Smart Profiles : la disposition change selon l'appli. | [Elgato, Stream Deck+](https://www.elgato.com/us/en/p/stream-deck-plus) | Molette ou balayage = un cadran à plusieurs modes, cyclés à l'appui. Cadran change de mode selon l'appli active de l'ordinateur. |
| 5.3 | LaMetric Time | Page 404 au moment de la lecture. | ⚠️ non lu | Pas de conclusion. |
| 5.4 | Tidbyt | Erreur 429 au moment de la lecture. | ⚠️ non lu | Pas de conclusion. |
| 5.5 | Nothing, Teenage Engineering, Braun (Rams) | Non lus. « Less but better » est une formule souvent citée pour Braun, mais pas lue. | ⚠️ non lu | Pas de conclusion. |

## 6. Par où commencer (ordre valeur / coût)

1. Repos au style Wear OS : 15 % de pixels allumés au plus, zéro animation, même place, marge de 10 px (2.6, 2.7, 2.9, 2.10).
2. Entrée en repos par un mouvement qui se pose, sans coupure (1.9, 1.10).
3. Son en catégorie ambiante, coupé en silence et au repos (4.8).
4. Clic court seulement sur action, si la tablette a un moteur de vibration (4.2, 4.3). ⚠️ moteur non vérifié.
5. Minuteur actif en plein écran, fin nette, 8 h max (1.1, 1.3).
6. Ultrasons ou capteur de présence : seulement après test sur la tablette (2.1). ⚠️

## 7. Lacunes et suite

- Non lu dans une source : Pixel Tablet (2.12), glanceable (3.6), Material motion (2.13), Teenage Engineering, Nothing, Braun (4.11, 5.5), LaMetric (5.3), Tidbyt (5.4).
- DreamService lu en partie (2.11).
- Non trouvé : luminosité adaptative sur Nest Hub (2.5).
- Sources secondaires : MacRumors (1.4, 1.6, 1.7), Wikipedia (4.10).
- Prochaine recherche ciblée, si on veut combler : durée de coup d'œil et taille de lecture à 1 m (HCI), sons Teenage Engineering, LaMetric.

## Sources lues

- [Apple HIG, Live Activities](https://developer.apple.com/design/human-interface-guidelines/live-activities.md) (changelog du 13/10/2026)
- [Apple HIG, Playing haptics](https://developer.apple.com/design/human-interface-guidelines/playing-haptics.md)
- [Apple HIG, Playing audio](https://developer.apple.com/design/human-interface-guidelines/playing-audio.md)
- [Apple HIG, Always On](https://developer.apple.com/design/human-interface-guidelines/always-on.md)
- [MacRumors, StandBy (20/11/2024)](https://www.macrumors.com/how-to/use-standby-mode-iphone)
- [Google Home, Ultrasound sensing](https://support.google.com/googlehome/answer/9509981)
- [Android, Always-on Wear OS](https://developer.android.com/training/wearables/apps/always-on)
- [Android, Haptics design principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles)
- [Calm Technology, Amber Case](https://calmtech.com/)
- [Wikipedia, Swiss railway clock](https://en.wikipedia.org/wiki/Swiss_railway_clock)
- [Elgato, Stream Deck+](https://www.elgato.com/us/en/p/stream-deck-plus)
- [Corsair, Xeneon Edge](https://www.corsair.com/xeneon-edge)

Pages en échec : [Apple StandBy](https://www.apple.com/ios/standby/) (404), [LaMetric](https://lametric.com/en-US/products/lametric-time) (404), [Tidbyt](https://tidbyt.com/) (429), [M3 Motion](https://m3.material.io/styles/motion/overview) (titre seul).
