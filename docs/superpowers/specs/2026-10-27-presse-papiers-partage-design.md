# Presse-papiers partagé — conception

27/10/2026 · Bruno · branche locale `feat/presse-papiers` (depuis `origin/main` 59711cd), non poussée

## Pourquoi

Faire passer du texte et des photos entre le PC et la tablette par un copier-coller, sans câble, sans mail à soi-même,
sans aller chercher un fichier. Utile aux deux publics du projet : au bureau (coller un schéma dans Teams, garder un
code sous les yeux) et à l'école (le prof photographie la copie d'un élève et la colle dans sa présentation).

## Objectifs (les trois usages, par ordre d'intérêt)

1. **Photo → presse-papiers du PC.** Dans le mode Caméra (« Envoyer une photo »), après la photo, un bouton
   « Copier sur l'ordi » met l'image dans le presse-papiers du PC : Ctrl+V (Cmd+V) dans Teams, Word, PowerPoint, un mail.
2. **Texte de la tablette → presse-papiers du PC.** Écrire ou dicter un texte sur la tablette, puis « Copier sur
   l'ordi » ; on le colle ensuite où on veut. Aussi à la voix : « copie sur l'ordi … ».
3. **Presse-papiers du PC → tablette.** Un écran « Presse-papiers » affiche le texte copié sur le PC (un code, une
   adresse, un numéro), avec « Copier sur la tablette » et, si c'est un lien, « Ouvrir ».

## Non-objectifs (pas dans cette version)

- Historique des copies ; synchronisation automatique ; fichiers (déjà couverts par « Fichiers de l'ordi »).
- Afficher sur la tablette une **image** copiée sur le PC : l'écran dit seulement « Une image est copiée sur l'ordi ».
- Coller automatiquement dans l'appli au premier plan (c'est le rôle de la dictée « écris … » existante).

## Anti-objectifs (échec même si « ça marche »)

- Un **mot de passe** copié depuis un gestionnaire qui s'affiche sur la tablette.
- Le contenu du presse-papiers qui part sans un toucher de l'utilisateur, ou qui est gardé, journalisé, renvoyé
  ailleurs que dans la réponse demandée.
- Quoi que ce soit à configurer ; un antivirus qui bloque le pilote Windows à cause de ce code.

## Décisions de Bruno

- Les trois usages ci-dessus, dans cet ordre de priorité.
- Emplacements : un 3e choix « Presse-papiers » dans l'écran « Depuis l'ordi » (à côté de « Fichiers de l'ordi » et
  « Page de l'ordi ») ; le bouton « Copier sur l'ordi » dans le mode Caméra ; une phrase vocale.
- Règles : rien ne passe sans un toucher ; contenus marqués sensibles ignorés ; rien gardé ni journalisé ; rien à
  configurer ; style Cadran.

## Choix faits par Claude (à vérifier par Bruno)

- **Lecture du presse-papiers du PC à la demande seulement** : à l'ouverture de l'écran « Presse-papiers » et par un
  bouton « Actualiser ». Pas de lecture en boucle, pas de lecture quand l'écran n'est pas affiché.
- **Contenus sensibles** : si le presse-papiers porte un marqueur de contenu sensible, le compagnon ne lit pas le
  texte et répond « protégé » ; la tablette affiche « Contenu protégé (mot de passe ?) : il n'est pas affiché ».
  - Windows : formats `ExcludeClipboardContentFromMonitorProcessing`, `Clipboard Viewer Ignore`, ou
    `CanIncludeInClipboardHistory` à 0 (posés par 1Password, Bitwarden, KeePass, le gestionnaire de Windows…).
  - Mac : types `org.nspasteboard.ConcealedType`, `org.nspasteboard.TransientType`, `com.agilebits.onepassword`.
- **Ce que voit la tablette** : texte (coupé à 10 000 caractères à l'affichage, « … » ensuite), lien (texte qui est une
  seule URL http/https → bouton « Ouvrir » dans le panneau de la tablette comme « Page de l'ordi »), image (« Une image
  est copiée sur l'ordi »), vide (« Rien n'est copié sur l'ordi »), autre format (« Ce contenu ne s'affiche pas ici »).
- **Texte vers le PC** : au plus 100 000 caractères ; saisie dans une boîte (`showForTyping`, règle du projet : pas de
  champ dans les cartes) avec un bouton « Dicter » (reconnaissance Android déjà utilisée par le bouton Parler) ;
  confirmation « Copié sur l'ordi ». Voix : « copie sur l'ordi … » et « copie ça sur l'ordi … » seulement (voir la section Voix : « copie » seul n'est pas pris).
- **Photo vers le PC sans renvoi** : la photo est déjà envoyée au dossier « Photos tablette » par le mode Caméra ;
  « Copier sur l'ordi » demande au compagnon de mettre **ce fichier** dans le presse-papiers (chemin relatif au dossier
  partagé, vérifié comme pour `/docs/open` : rien en dehors du dossier, images JPEG/PNG seulement). Le bouton apparaît
  après la photo, à côté de la confirmation actuelle.
- **Écran « Presse-papiers »** (style Cadran, comme les autres écrans de « Depuis l'ordi ») : une tuile « Sur l'ordi »
  (contenu + Actualiser, Copier sur la tablette, Ouvrir) et une tuile « Vers l'ordi » (Écrire…, Dicter).
- **Pas de restauration** de l'ancien contenu quand on copie vers le PC (contrairement à « écris … ») : le but est
  justement de remplacer le presse-papiers.
- Linux / pilotage absent : 501, l'écran affiche « Non disponible sur cet ordinateur ».

### Précisions après relecture (choix de Claude)

- **Bouton photo** : dans la vue Caméra, un bouton « Copier sur l'ordi » à côté de « Photo », actif après une photo
  envoyée avec succès et lié à **cette dernière photo** (la suivante la remplace) ; il disparaît en quittant le mode.
  Pas de bouton dans le panneau document dans cette version.
- **Dossier** : celui du code, « Photos tablette » (`PHOTOS_DIR`), chemin relatif renvoyé par `/docs/upload`.
- **« Ouvrir » un lien** : sur la tablette, dans le panneau document, comme une page de « Page de l'ordi » (même
  WebView, mêmes règles : le kiosque y charge déjà des pages venues du PC). Seulement http/https.
- **Voix** : « copie sur l'ordi … » (et « copie ça sur l'ordi … ») seulement ; « copie » seul n'est pas pris (trop
  courant). Sans texte après : la tablette répond « Dis ce que je dois copier ». Appuyer sur Parler compte comme le
  toucher exigé.
- **Plusieurs formats à la fois** : marqueur sensible d'abord (→ `protected`, quel que soit le contenu, même une image
  seule), puis texte (→ `text`, même si une image l'accompagne, cas d'Excel ou de Word), puis image (→ `image`) ; liste
  de fichiers et le reste → `other`.
- **Mac** : code écrit et couvert par les tests du faux système et la sanity CI ; vérification réelle sur le Mac
  d'Eliot plus tard (pas de Mac ici). Le pilote Windows est recompilé ici (SDK local) ; le commit attend l'accord de
  Bruno.
- **Sanity** : si le presse-papiers est inaccessible (session sans bureau : le compagnon répond 503) → contrôle
  ignoré (`Skip`), pas un échec ; toute autre erreur fait échouer le contrôle.
  L'écriture de test n'a lieu que si l'ancien contenu est du texte ou vide ; un ancien texte est remis ensuite (un
  presse-papiers vide garde le texte de test : l'API ne sait pas vider) ; sinon, contrôle limité à la lecture, pour ne
  jamais perdre une image ou un contenu protégé.

## Contraintes du projet

- Tout accès système par `backend/src/os.ts` → `mac/` ou `win/` ; Windows natif uniquement dans `UnowhyHelper.exe`
  (C# .NET 4.8, versionné, recompilé par `npm run build:helper`, job CI `helper-windows`). Le pilote a déjà
  `SetClipboard` avec reprises (`Native.Keys.cs`) et `Clipboard.SetImage` (`Native.System.cs`), et tourne en STA.
- Mac : `pbcopy`/`pbpaste` déjà utilisés (`mac/keys.ts`) ; l'image et les types de presse-papiers passent par JXA
  (`NSPasteboard`), comme le reste du pilotage Mac.
- Chaque export de `os.ts` existe aussi dans `backend/test/fake-os.ts`. Nouveau comportement backend = test dans
  `backend/test/` ; nouvelle feature backend = contrôle dans `backend/qa/sanity.ts` (lire, écrire un texte de test,
  relire, puis remettre l'ancien contenu) ; la couverture du backend est bloquante en CI (`backend/.c8rc.json`).
- Routes protégées par le jeton ; **le contenu ne doit jamais apparaître dans les journaux** (pas de corps de requête
  ou de réponse logué ; erreurs sans le texte).
- Tablette : vues en code / XML, pas de Compose, dépendances inchangées ; HTTP via `net/` (`client.system`) ; erreurs
  via `errorText(e)` ; textes dans `strings.xml` ; DA Cadran (`docs/ui-ux/da/README.md`) : gris chaud, pas de couleur
  de titre, rouge seulement pour « maintenant », pilules de 44 dp, Outfit.
- Android 10 : lecture/écriture du presse-papiers de la tablette autorisées quand l'appli est au premier plan (c'est le
  cas : kiosque).

## Comment (pistes)

- Compagnon : `GET /system/clipboard` → `{ kind: "text", text } | { kind: "image" } | { kind: "empty" } |
  { kind: "protected" } | { kind: "other" }` ; `POST /system/clipboard` `{ text }` ; `POST /system/clipboard/image`
  `{ path }` (fichier du dossier partagé). Pilote Windows : opérations `clipget` (formats sensibles d'abord), `clipset`,
  `clipimage`. Mac : JXA pour lire types et texte, `pbcopy` pour le texte, JXA/AppleScript pour l'image.
- Tablette : `SystemApi` (ou une API dédiée) ; `FromComputerFragment` gagne le 3e choix ; un écran/vue
  `ClipboardView` ; `CameraFragment` gagne le bouton ; `VoiceCommands` gagne « copie (sur l'ordi) … ».
- Tests : backend (forme des réponses, protégé, 501, limites, chemin hors dossier refusé, rien dans les logs si
  vérifiable) ; JVM (lecture de la réponse, troncature, détection de lien, commande vocale) ; sanity.

## Réussite

1. Photo prise sur la tablette → « Copier sur l'ordi » → Ctrl+V dans Teams/Word/Paint sur le PC de Bruno colle l'image.
2. Texte écrit ou dicté → « Copier sur l'ordi » → Ctrl+V le colle ; « copie sur l'ordi bonjour » aussi.
3. Texte copié sur le PC → visible dans « Presse-papiers », copiable sur la tablette ; un lien s'ouvre.
4. Un mot de passe copié depuis un gestionnaire (ou le marqueur posé à la main) → « Contenu protégé », rien affiché.
5. Rien dans les journaux du compagnon ; aucune alerte antivirus avec le pilote recompilé ; style Cadran jour et nuit.

## Laissé au développeur

Mise en page exacte dans l'esprit Cadran ; noms des fichiers et des classes ; découpage du code du pilote ; formulation
exacte des messages (français, tutoiement, phrases courtes, sans point d'exclamation).
