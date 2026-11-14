# Cyberpunk : le tiroir Téléphone comme le téléphone du jeu

Design du 2026-11-14, demande d'Ahmed. Suite de `2026-11-14-plugin-cyberpunk-design.md` (plugin
Cyberpunk, PR #260), qui listait « Répondre aux SMS (choix de dialogue) » en non-objectif : cette
exclusion est levée ici.

## Intention

Le tiroir Téléphone de la page Cyberpunk montre aujourd'hui une liste de fils (nom + dernier message)
et une liste de contacts à appeler. Ahmed veut le téléphone du jeu : mêmes onglets, même allure, et
pouvoir répondre aux SMS depuis la tablette sans ouvrir le téléphone dans le jeu. C'est le premier
chantier où la tablette agit dans le jeu au-delà d'un bouton (les suivants : cyberware, journal, perks).

## Décisions d'Ahmed

- Deux onglets, comme dans le jeu : Messages et Contacts. Contacts garde ce qui existe (appeler,
  raccrocher).
- Messages n'affiche que les conversations qui ont un message non lu. Toucher une conversation l'ouvre
  en entier (fil complet). Une fois lue, elle reste listée jusqu'à la fermeture du tiroir.
- Répondre = choisir parmi les réponses que le jeu propose (2 ou 3 choix de dialogue), envoyées au jeu
  par le mod. Pas de texte libre.
- Une conversation qui attend une réponse (choix ouverts) reste dans Messages même lue (réponse
  d'Ahmed au contrôle du constructeur) : Messages = non lus + fils à répondre + fils ouverts depuis
  l'ouverture du tiroir. Le mod ajoute `hasChoices` à chaque fil de `contacts.json`.
- Look : copie du téléphone du jeu (pas une version « style Mate »).
- Les images du jeu (fonds, icônes, police) sont extraites de son jeu installé sur ATLAS et servies par
  le backend du PC ; jamais commitées (dépôt public, droits CD Projekt).
- On commence par un spike sur sa vraie partie. Si le mod ne sait pas lire les choix ou en envoyer un,
  on revient vers Ahmed avant de construire l'écran.

## Objectifs

1. Voir d'un coup d'œil qui a écrit (non lus seulement) et lire le fil entier.
2. Répondre à un SMS du jeu depuis la tablette, avec les mêmes choix que dans le jeu, et voir la
   réponse partir (le fil se met à jour, le jeu enchaîne comme si on avait répondu au téléphone).
3. Un tiroir qui ressemble au téléphone du jeu, pas à un écran de Mate.

## Non-objectifs

- Texte libre, messages inventés, envoi d'un SMS à un contact qui n'a rien écrit.
- Historique des conversations déjà lues (le jeu le garde ; la tablette ne liste que les non lus).
- Appels vidéo affichés (portrait animé) : le bandeau d'appel actuel reste.
- Les autres tiroirs (Journal, Objets, Cyber, Perso) et la carte : inchangés.
- Mac, Epic/GOG : comme le plugin (Windows, Steam).

## Anti-objectifs (échec même si ça marche)

- Une image, une police ou une icône du jeu dans le dépôt, dans l'APK ou dans une release.
- Une réponse envoyée deux fois (double appui, ordre rejoué au rechargement du mod) ou à la mauvaise
  conversation.
- Débloquer une suite de quête que le joueur n'a pas encore atteinte : seuls les choix que le jeu
  montre au même moment dans son téléphone sont proposés.
- Faire planter le jeu : appels du moteur sous `pcall`, seulement ceux vus marcher au spike.
- Écrire dans une sauvegarde de partie.
- Marquer un message lu sans que la personne l'ait ouvert sur la tablette.

## Contraintes

- Tablette 4.2 (`docs/tablette-4.2.md`) : tiroir de 420 px par-dessus la carte, vues XML natives,
  images décodées à la taille d'affichage, cache borné.
- Couleurs de la page dans `colors.xml` (`cp_*`), comme le reste du plugin ; textes dans `strings.xml`.
- Les images extraites sont optionnelles : sans elles (extraction pas faite, autre PC, échec), le tiroir
  marche pareil avec les couleurs `cp_*` et Chakra Petch (déjà dans `res/font`).
- Backend : jeton d'appairage sur toutes les nouvelles routes ; les images servies seulement à une
  tablette appairée (pas de route publique qui redistribue les fichiers du jeu).
- Le mod lit par le `JournalManager` ou `MessengerUtils` (qui l'enveloppe) et n'active un choix que par
  le `JournalManager` (pas de touche simulée pour répondre : le choix doit viser une conversation précise).

## Le comment, partie tranchée

Trois morceaux, sur le modèle du plugin.

1. **Mod** (`backend/assets/cyberpunk-mod/logic.lua`) : `contacts.json` garde ses fils, avec en plus
   un identifiant stable par fil (`hash` de l'entrée de journal du contact) et le nombre de non lus.
   Nouveau fichier à la demande `thread.json` : le fil complet d'une conversation (messages dans
   l'ordre, qui parle, texte localisé) et les choix de réponse ouverts. Nouveaux ordres dans
   `commands.json` : `thread{hash}` (écrit `thread.json` et marque le fil lu, comme l'ouvrir dans le
   jeu) et `reply{hash, choice}` (active le choix, comme le toucher dans le jeu).
2. **Backend** (`backend/src/cyberpunk.ts`, `routes/cyberpunk.ts`) : `GET /cyberpunk/threads/:hash`
   (envoie l'ordre `thread`, attend `thread.json` frais, le rend) et `POST /cyberpunk/threads/:hash/reply`
   `{ choice }` (ordre `reply`, 409 si le choix n'est plus ouvert, 504 sans réponse du mod). Images du
   téléphone : extraites une fois de l'archive du jeu vers `.data/cyberpunk-phone/` (PNG réduits),
   listées par `GET /cyberpunk/phone-assets` et servies par `GET /cyberpunk/phone-assets/:name`.
3. **Tablette** (`ui/cyberpunk/CyberpunkDrawers.kt` et voisins, `net/CyberpunkApi.kt`) : le tiroir
   Téléphone devient deux onglets. Messages : fils non lus, plus ceux ouverts depuis l'ouverture du
   tiroir (liste gardée en mémoire, vidée à la fermeture). Fil : bulles (le contact à gauche, V à
   droite), puis les choix en boutons en bas ; toucher un choix l'envoie, les boutons se désactivent
   jusqu'à la réponse, puis le fil se recharge. Contacts : la liste actuelle (appeler, raccrocher) dans
   le cadre du téléphone. Images du jeu chargées depuis le backend si présentes, sinon repli `cp_*`.

## Réponses au contrôle du constructeur (2026-11-14)

- Identifiants : chaque fil a un `hash` (entrée de journal du contact) écrit en chaîne de chiffres
  (peut être négatif ou dépasser 53 bits, comme les repères), motif `^-?[0-9]{1,20}$` dans le schéma de
  route. Chaque choix a aussi son `hash` d'entrée de journal, jamais un index.
- Pas de mauvais choix ni de double envoi : `thread.json` porte une version du fil (nombre de messages
  et `hash` du dernier) ; `reply{hash, choice, version}` envoie le `hash` du choix et cette version ; le
  mod refuse si le choix n'est plus ouvert ou si la version a bougé. La tablette désactive les choix dès
  le premier appui.
- `thread.json` lié à sa requête : le mod y écrit `cmd` (numéro de l'ordre `thread`) et le `hash` du fil ;
  le backend le lit après l'ack et vérifie `cmd`, sans passer par le cache des blocs lents.
- Codes : `ack.ok = false` devient 409 avec un `res` connu (`choix-perime`, `fil-introuvable`) ; 409
  « hors partie » garde son `res` actuel ; la tablette lit `res`, pas seulement le code. 504 sans
  réponse du mod en 5 s, comme les autres ordres.
- Après une réponse, la tablette recharge le fil toutes les 2 s pendant 10 s tant qu'il n'y a ni
  nouveau message ni nouveaux choix (le contact « tape » avant de répondre). Le nombre de non lus de la
  liste suit `blocks.contacts`, avec jusqu'à 5 s de retard : assumé.
- Images : `GET /cyberpunk/phone-assets/:name` ne sert qu'un nom de la liste écrite par l'extraction
  (liste blanche, jamais un chemin libre). Aucune image du jeu dans les tests ni les fixtures : les tests
  utilisent une image générée.

## Laissé au constructeur

- Noms exacts des champs de `thread.json` et des ordres, découpage en commits.
- Outil d'extraction des images sur ATLAS (WolvenKit CLI ou autre outil libre déjà utilisé par les
  moddeurs), téléchargé sur ATLAS au besoin, jamais dans le dépôt ; liste exacte des fichiers retenus
  (cadre du téléphone, icônes Messages / Contacts, fond, police de l'interface).
- Mise en page fine du tiroir, à caler sur des captures du téléphone du jeu prises sur ATLAS (pas de
  3 variantes : Ahmed a choisi « copie du jeu », la référence est le jeu lui-même).

## Questions ouvertes (le spike les tranche, avant tout écran)

1. Lire le fil complet d'un contact sous CET : `JournalManager:GetMessagesAndChoices` (ou
   `MessengerUtils`) rend-il les messages et les choix, et sous quelle forme (paramètres de sortie) ?
2. Envoyer un choix : `JournalManager:ActivateEntry(choix)` suivi du marquage visité, comme
   `MessengerDialogViewController` du jeu ; la quête enchaîne-t-elle (réponse du contact, suite) ?
3. Marquer un fil lu : `SetEntryVisited` sur les messages suffit-il pour que le jeu le voie lu ?
4. Les choix se lisent-ils quand le téléphone du jeu est fermé, avec un `hash` stable d'une lecture à
   l'autre ? Que fait le jeu d'une activation pendant que son propre téléphone est ouvert ?
5. Après marquage lu, le jeu garde-t-il un fil qui attend encore une réponse visible comme « à
   répondre » ?
6. Extraction : quels fichiers de l'archive portent le téléphone (cadre, icônes, police) et lesquels
   s'affichent bien en PNG réduit sur la tablette ?

Spike sur la vraie partie d'Ahmed, sur un fil réel qui attend une réponse ; s'il n'y en a aucun, on
lui demande d'en provoquer un en jouant (on ne fabrique pas de message). Le mod jetable reçoit ses
ordres par un fichier de son dossier (`cmd.txt`, comme le premier spike), écrit par `ssh pc`. Questions 1 ou 2 en échec : retour à Ahmed avant de construire l'écran.

## Critère de réussite

Un contact écrit à V en jeu. Sur la tablette, Téléphone > Messages montre ce contact ; Ahmed touche la
conversation, lit le fil entier, touche une des réponses proposées. Dans le jeu, la réponse apparaît
dans le téléphone et la conversation continue (réponse du contact ou suite de quête), sans qu'il ait
ouvert le téléphone du jeu. Le tiroir ressemble au téléphone du jeu.

## Premier geste

Spike sur ATLAS : un mod CET jetable `mods\mate_spike_sms` (à côté de `mods\mate`, sans le modifier)
qui écrit dans son dossier le fil et les choix d'un contact, puis active un choix sur ordre ; retiré
après le spike.
