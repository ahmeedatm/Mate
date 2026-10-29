# Historique du presse-papiers — conception

29/10/2026 · Bruno · branche `feat/historique-presse-papiers` (depuis `origin/main` 18d7cee)

## Pourquoi

Retrouver un texte copié sur l'ordinateur un peu plus tôt (un code reçu par mail, un identifiant, une adresse) et le
recopier depuis la tablette, sans quitter l'appli au premier plan. Le presse-papiers partagé (MR !69) ne montre que le
contenu actuel, lu à l'ouverture de l'écran : un texte remplacé entre-temps est perdu.

## Objectifs

- Le compagnon note chaque nouveau **texte** copié sur l'ordinateur, même quand la tablette ne regarde pas.
- Sur la tablette, sous la vue presse-papiers, une liste « Récents » : le plus récent en haut, l'heure de la copie.
  Toucher une ligne : « Recopier sur l'ordi » ou « Copier sur la tablette » ; « Tout effacer » vide la liste.
- Les codes sont masqués (« •••• ») avec un bouton œil pour les voir.

## Non-objectifs

- Images, fichiers, texte enrichi. Épingler une entrée. Synchroniser plusieurs ordinateurs. Recherche dans la liste.

## Choix

- **Mémoire seulement** : rien sur le disque, l'historique disparaît à l'arrêt du compagnon. 20 entrées au plus, chacune
  oubliée au bout d'une heure. Un texte déjà présent remonte en haut (pas de doublon). Texte de plus de 10 000
  caractères : ignoré (l'historique sert aux petits textes ; la vue presse-papiers garde le cas du long texte).
- **Gestionnaires de mots de passe exclus** : un contenu `protected` (marqueur posé par Bitwarden, KeePass, 1Password…,
  déjà lu par `getClipboard`) n'entre jamais dans l'historique, comme pour l'historique Win + V de Windows.
- **Masquage** décidé par le compagnon (`secret: true`) : une seule « mot » de 4 à 64 caractères, sans espace, qui n'est
  ni une adresse web ni une adresse mail, et qui contient un chiffre ou un symbole. Un chemin de fichier peut être
  masqué à tort : l'œil le montre.
- **Détection des copies** sans relire le contenu à chaque fois : le compagnon demande un repère qui change à chaque
  copie (`clipboardStamp`) et ne lit le presse-papiers que quand il a changé.
  - Windows : `GetClipboardSequenceNumber` (opération `clipseq` du pilote, pas d'ouverture du presse-papiers), toutes
    les secondes.
  - Mac : `NSPasteboard.changeCount` (JXA), toutes les 2 s. Pas de Mac ici : écrit, non vérifié.
  - Ailleurs : pas de surveillance.
  - Session sans bureau (503) ou pilote absent : tick ignoré, sans journal du contenu.
- **API** (jeton, journal en `warn`, contenu jamais journalisé) :
  - `GET /system/clipboard/history` → `{ items: [{ id, text, at, secret }] }` (`at` en ms depuis 1970).
  - `POST /system/clipboard/history/:id/copy` → remet le texte dans le presse-papiers de l'ordinateur ; 404 si
    l'entrée a disparu.
  - `DELETE /system/clipboard/history/:id`, `DELETE /system/clipboard/history`.
- Intervalle forçable par `CLIPBOARD_POLL_MS` (tests).

## Réussite

1. Copier trois textes sur le PC, ouvrir le presse-papiers sur la tablette : les trois sont listés, le plus récent en haut.
2. Un mot de passe copié depuis un gestionnaire n'apparaît pas ; un code à 6 chiffres apparaît masqué.
3. « Recopier sur l'ordi » puis Ctrl + V colle le bon texte ; « Tout effacer » vide la liste.
4. Tests backend (historique pur, surveillance, routes, rien dans le journal), test JVM du masquage côté affichage.
