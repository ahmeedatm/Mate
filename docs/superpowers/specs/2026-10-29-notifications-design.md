# Notifications de l'ordinateur sur la tablette — conception

29/10/2026 · Bruno · branche `feat/notifications` (depuis `origin/main` 940bfd5)

## Pourquoi

La tablette posée sur le bureau sert de second écran de notifications : voir ce qui arrive sur l'ordinateur sans
quitter ce qu'on y fait, et vérifier d'un coup d'œil qu'aucune notification importante n'est passée inaperçue.
Piste du document de cadrage. Bruno a demandé de coder sans plus de questions : les choix techniques sont ceux de
Claude, à corriger.

## Ce que Bruno a décidé

- **En direct et en historique** : une notification de l'ordinateur s'affiche aussitôt sur la tablette, puis reste dans
  une liste pour rattraper ce qu'on a manqué.
- **Toutes les applis** : Discord, Outlook, Teams, Claude, ChatGPT… et celles du système (Windows, macOS). Pas de choix
  appli par appli au départ.
- **Confidentialité** (proposition de Claude acceptée) : tout en clair par défaut (appli, titre, texte) ; un réglage
  « Mode discret » sur la tablette n'affiche plus que l'appli et le titre ; rien sur le disque ni dans les journaux.

## Non-objectifs

- Répondre à une notification, l'ouvrir sur l'ordinateur, la retirer du centre de notifications de l'ordinateur.
- Filtrer par appli, règles d'importance, son ou voix (les rappels parlés et les appels Discord restent comme avant).
- Images des notifications. Plusieurs tablettes.

## Choix techniques (Claude)

- **Windows** : lecture en lecture seule de la base de notifications de l'utilisateur,
  `%LOCALAPPDATA%\Microsoft\Windows\Notifications\wpndatabase.db` (SQLite, `node:sqlite`, sans dépendance), toutes les
  2 s : lignes `Notification` de type `toast` d'identifiant plus grand que le dernier vu, jointes à
  `NotificationHandler` (identifiant de l'appli, AppUserModelID). Le texte vient du XML du toast : premier `<text>` =
  titre, les suivants = texte (sauf `placement="attribution"`). Heure : `ArrivalTime` (FILETIME). Nom de l'appli : celui
  du menu Démarrer pour cet AppUserModelID (`startApps` du pilote), sinon un nom tiré de l'identifiant. Vérifié le
  29/10 sur le PC de Bruno : base lisible, toasts d'Outlook présents. `UserNotificationListener` (WinRT) écarté : il
  demande une appli empaquetée.
- **Mac** : base de `usernoted`
  (`~/Library/Group Containers/group.com.apple.usernoted/db2/db`, table `record` + `app`, `data` en plist binaire lu par
  `plutil`). Demande l'accès complet au disque pour node : sans lui, notifications indisponibles (503 avec un message).
  Pas de Mac ici : écrit, non vérifié.
- **Ailleurs** : 501, pas de surveillance.
- **Compagnon** (`src/notifications.ts`) : au démarrage, reprend les notifications des dernières 24 h encore dans la base
  (historique, sans envoi en direct) ; ensuite, chaque nouvelle notification va dans l'historique et part vers la
  tablette par le canal existant (`sendToTablet`, ordre `notification`, sans attendre). Historique en mémoire : 100
  notifications au plus, 24 h. Textes coupés à 1 000 caractères.
- **API** (jeton, journal en `warn`, contenu jamais journalisé) :
  - `GET /notifications` → `{ items: [{ id, app, title, body, at }] }`, la plus récente en premier ;
  - `DELETE /notifications/:id`, `DELETE /notifications` (efface l'historique du compagnon, pas celui de l'ordinateur).
- **Tablette** :
  - en direct : bandeau en haut de l'écran (comme les messages), « App · titre » et le texte, quelques secondes, quel que
    soit l'écran affiché ;
  - historique : cloche en haut à gauche de chaque écran (pastille quand une notification arrive, demandé par Bruno
    après le premier essai), qui ouvre l'écran Notifications de Depuis l'ordi : liste (appli, titre, texte, heure), Actualiser, Tout
    effacer, retirer une notification ;
  - Réglages : interrupteur « Mode discret » (texte masqué dans le bandeau et la liste), gardé sur la tablette.
- Intervalle forçable par `NOTIFICATIONS_POLL_MS` (tests).

## Réussite

1. Une notification Outlook ou Teams arrive sur le PC : le bandeau s'affiche sur la tablette en moins de 3 s.
2. Depuis l'ordi > Notifications : les notifications de la journée, la plus récente en haut.
3. Mode discret : plus aucun texte de notification visible sur la tablette.
4. Tests backend (lecture du XML, historique, surveillance, routes, rien dans le journal) et JVM (lecture, affichage).
