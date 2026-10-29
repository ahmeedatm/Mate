# Confidentialité : données, flux et projet de politique (COMP-49)

Audit de vendabilité du 29/10/2026 : aucune politique de confidentialité, et des données qui partent chez des tiers sans être déclarées. Ce document recense ce qui est stocké et ce qui part, **vérifié dans le code le 29/10/2026** (avec le fichier en cause), et ce qui a été fait.

**Fait le 29/10/2026** (branche `fix/confidentialite`) :
- un flux **supprimé** plutôt que déclaré : le nom des sites du Deck n'est plus envoyé au service d'icônes de Google (un test du backend empêche son retour) ;
- **localisation** : la ville de la météo n'est plus trouvée seule que si l'option des Réglages est activée (coupée par défaut) ; « Ma position » reste un geste explicite ;
- **politique de confidentialité** affichée dans Réglages > À propos > « Confidentialité » et sur la page locale de l'ordinateur (`/local/confidentialite`), un seul texte : `backend/config/confidentialite.txt`, copié dans `app/src/main/assets/` (vérifié par `PrivacyTest`).

**Reste au juridique** : valider le texte, le compléter (raison sociale, adresse, contact DPO : introuvables dans le dépôt et sur le web, le texte renvoie au site d'Unowhy), et confirmer bases légales et transferts hors UE.

Le principe du produit est bon : les données restent chez l'utilisateur. La tablette parle à l'ordinateur sur le réseau local (chiffré depuis COMP-48), le compagnon garde ses données sur l'ordinateur, et il n'y a **ni mesure d'audience, ni rapport de plantage, ni publicité, ni compte Unowhy**.

## 1. Données stockées

| Où | Quoi | Durée |
|---|---|---|
| Tablette (préférences de l'appli) | ordinateurs appairés (nom, adresse, jeton, empreinte du certificat), profils et widgets de l'Accueil, ville de la météo et ses coordonnées, verres d'eau, réglages | jusqu'à la désinstallation ou la réinitialisation |
| Ordinateur, `backend/.data/` | identité et jeton du compagnon, certificat, agendas reliés (lien ICS), agenda perso, tâches et rappels, touches perso et macros, applis et sites du Deck, scènes de son, réglages de réunions ; si un compte Microsoft ou Google est relié : jeton de renouvellement (`accounts.json`, droits 600) | jusqu'à la désinstallation ou la suppression du dossier |
| Ordinateur, en mémoire seulement | historique du presse-papiers (20 textes, 1 h), notifications du système (100, 24 h), état des sessions Claude Code, appel en cours | jusqu'à l'arrêt du compagnon ; jamais écrit sur le disque ni dans le journal |
| Ordinateur, journal du compagnon | événements techniques ; jamais le contenu du presse-papiers, des notifications ou des commandes des agents | à préciser (aucune rotation aujourd'hui) |

## 2. Données qui partent chez des tiers

| Destinataire | Quand | Ce qui part | Où dans le code |
|---|---|---|---|
| **Google** (reconnaissance vocale d'Android) | bouton Parler, dictée, réponse vocale aux agents | **l'enregistrement de la voix** et la langue ; traité par l'appli Google de la tablette | `voice/VoiceControl.kt`, `voice/Dictation.kt` (`SpeechRecognizer`) |
| **Google** (services de localisation d'Android) | météo sans ville choisie **si l'option des Réglages est activée** (coupée par défaut), bouton « Ma position » | réseaux WiFi visibles et signal, envoyés par le système pour obtenir la position | `device/Position.kt` |
| **Google** (synthèse vocale d'Android) | rappels parlés, réponses de la commande vocale | le texte à prononcer, **si** le moteur utilise une voix en ligne (selon ses réglages) | `audio/Speaker.kt` |
| **Google** (recherche) | commande vocale « cherche … » | le texte cherché, ouvert dans le navigateur de l'ordinateur | `voice/VoiceControl.kt` |
| **Apple** (recherche iTunes, Mac) | page Média, morceau sans pochette | **titre et artiste** du morceau en cours | `backend/src/mac/media.ts` (`storeArtwork`) |
| **YouTube / Google** (Mac) | page Média, vidéo YouTube en cours | l'identifiant de la vidéo (image d'aperçu) | `backend/src/mac/media.ts` |
| **MET Norway** (Norvège, EEE) | météo, toutes les 30 min | coordonnées **de la ville** (jamais la position exacte), adresse IP, identifiant de l'appli | `ui/home/Weather.kt` |
| **Microsoft** (Graph) — inactif | si l'appli Azure est enregistrée et un compte relié | agenda ; **courrier** : expéditeur, objet, date, compteurs (jamais le corps) | `backend/src/accounts.ts`, `mail.ts` ; inactif tant que `config/accounts.json` est vide |
| **Google** (Agenda) — inactif | si l'appli Google est enregistrée et un compte relié | agenda | `backend/src/accounts.ts` ; même condition |
| **Unowhy** (serveur de mises à jour) | toutes les 6 h et au démarrage ; installeurs tous les quarts d'heure en WiFi | adresse IP, jeton de lecture des paquets ; rien sur l'utilisateur | `kiosk/AutoUpdater.kt`, `installers/` |
| Services **choisis par l'utilisateur** | à sa demande | l'ordinateur lit le lien d'agenda collé (Outlook, Google, iCloud…), Jira si configuré, l'icône d'un site sur le site lui-même ; la tablette ouvre la page affichée sur l'ordinateur (« Page de l'ordi ») et contacte donc ce site | `calendar.ts`, `jira.ts`, `deck.ts`, `ui/DocumentPanel.kt` |

Open-Meteo, cité par l'audit, n'est plus utilisé depuis COMP-52 ; le service d'icônes de Google a été retiré le 29/10/2026. Discord, Teams et les agents Claude sont pilotés sur l'ordinateur, sans rien envoyer hors de l'ordinateur et de la tablette.

Destinataires hors de l'Union européenne : Google, Apple et Microsoft (États-Unis) ; à couvrir dans la politique (cadre de transfert à préciser par le juridique).

## 3. Politique de confidentialité

Le texte affiché aux utilisateurs est `backend/config/confidentialite.txt` (copie dans `app/src/main/assets/confidentialite.txt`). À modifier là, puis recopier ; à revoir à chaque nouveau service appelé par la tablette ou l'ordinateur.

## 4. Reste à décider (juridique et DPO)

1. Raison sociale, adresse et contact DPO d'Unowhy à ajouter au texte ; bases légales et cadre des transferts hors UE à confirmer.
2. Public : UnoMate vise le B2B, les entreprises et leurs salariés, avec une vente B2C en complément (`cible-et-positionnement.md`) ; les tablettes viennent du parc scolaire. Si des mineurs peuvent l'utiliser, la reconnaissance vocale et la localisation demandent une attention particulière.
