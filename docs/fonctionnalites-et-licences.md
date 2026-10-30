# Fonctionnalités et licences

Inventaire au 30/10/2026 (main après v0.2.25) de ce que fait Mate, et pour chaque fonctionnalité ce qu'il faut en droits pour la vendre. Sources : le code, `app/src/main/assets/licences.txt`, `backend/package.json`, `docs/confidentialite.md`. Ce document n'est pas un avis juridique : la colonne « Droits » dit ce qu'il faut faire valider, pas ce qui est validé.

Légende de la colonne « Droits » :

- **Libre** : notre code, ou un composant open source ou dans le domaine public. Rien à demander.
- **Libre, à citer** : libre, mais la licence oblige à citer l'auteur. Fait dans `licences.txt` sauf mention contraire.
- **Droit** : il faut un compte, une licence, un accord ou une validation d'un tiers (détail en partie 3).
- **Risque** : marche sans licence, mais repose sur une API non officielle ou sur l'automatisation d'une appli tierce.

## 1. Fonctionnalités disponibles

### Tablette : socle

| Fonctionnalité | Ce qu'elle fait | Droits |
|---|---|---|
| Mode kiosque | Mate device owner, lanceur unique, installations bloquées, carte cachée par code technicien | Libre (API Android) ; sortie du MDM SQOOL = accord interne Unowhy |
| Mises à jour silencieuses | APK lus dans les releases GitHub du projet ou sur l'ordinateur, installés seuls toutes les 6 h | Libre |
| Essais de MR | appli d'essai à côté de la stable, choisie dans Réglages > Mises à jour | Libre |
| Appairage sécurisé | recherche UDP, code à 6 chiffres, HTTPS auto-signé, empreinte épinglée | Libre |
| Plusieurs ordinateurs | liste, renommage, bascule si l'actif ne répond plus | Libre |
| Barre de gauche | Parler, profils, Focus, Ordi, Réglages ; noms et outils réglables | Libre |
| Bandeau du haut | heure, WiFi, batterie, date, météo, rendez-vous, chrono, micro et volume de l'ordi, agents | Libre (météo : voir Météo) |
| Profils et widgets | grille 4 × 4, profils sans limite, rôles Accueil / Deck / Réunion, redimensionnement, zoom par appui long | Libre |
| Thèmes | Cadran, Cadran nuit, Clair, Sombre, Rétro, Contraste élevé, 5 thèmes de démo | Libre, à citer (polices OFL, icônes Apache 2.0) |
| Animations | ressorts, changement d'écran, coupables une à une | Libre |
| Écran et batterie | luminosité, atténuation 22 h – 7 h, bandeau batterie faible | Libre |
| Cadre photo | photos du dossier partagé après un délai sans toucher | Libre |
| WiFi | réglé depuis l'appli (le kiosque cache les Paramètres) | Libre |
| Accessibilité | TalkBack, taille du texte, grandes touches, retour parlé, choix de la voix et du débit | Libre ; voix : moteur de synthèse Google de l'image (Droit) |
| Démo et personas | Nour, Gwen, Camille, Aimée, Sacha, Sam : thème, agenda fictif, signature | Libre |
| Confidentialité et licences | textes affichés dans Réglages > À propos et sur la page locale | Libre |

### Widgets de l'Accueil

| Fonctionnalité | Ce qu'elle fait | Droits |
|---|---|---|
| Heure, héros, ligne du jour | grands chiffres qui basculent, journée en ligne fine | Libre |
| Agenda | lien ICS, agenda perso noté sur la tablette, rappel parlé | Libre ; comptes Microsoft ou Google : Droit |
| Tâches | ponctuelles, récurrentes, rappels, worklog Jira | Libre ; Jira : Droit |
| Eau | verres bus, rappel « Pense à boire » | Libre |
| Météo | MET Norway, villes GeoNames, bouton « Ma position » | Libre, à citer ; position : localisation Google (Droit) |
| Clôture | checklist avec échéance et compte à rebours | Libre |
| Je m'absente / Reprendre | coupe le micro, met Focus en pause, garde puis rend les fenêtres | Libre |
| Présentation | diapos de PowerPoint ou Keynote, chrono, micro | Libre ; PowerPoint et Keynote : licence de l'utilisateur |
| Alerte réunion | plein écran 5 min avant un événement | Libre |
| Réunion / appel en cours | bascule sur le profil Réunion quand le micro est pris par une appli d'appel | Libre |
| Son (mixeur) | console de mixage, volume par appli (Windows), scènes | Libre |
| Ouvertes | toutes les fenêtres sur le schéma des écrans, devant, réduire, déplacer, fermer | Libre (Windows) ; Mac : API privées (Risque) |
| Média | lecture en cours, commandes, pochette | Windows : Libre ; Mac : API privée MediaRemote (Risque), pochettes Apple et YouTube (Droit) |
| Discord | page d'appli, carte « En appel », touches micro et sourdine | Risque (automatisation du client, marque) |
| Jira | tickets assignés en colonnes, transitions | Droit |
| GitLab | MR assignées et à relire, fusion | Droit |
| Pipelines | dernier pipeline de la branche par défaut des projets | Droit (même accès que GitLab) |
| Agents Claude | état des sessions Claude Code, réponses aux permissions et questions, usage 5 h / 7 j | Droit |

### Focus

| Fonctionnalité | Ce qu'elle fait | Droits |
|---|---|---|
| Pomodoro | chrono, durées perso, survit aux changements d'écran | Libre |
| Respiration | sphère animée | Libre |
| Ambiances | pluie, forêt, Japon, mer, lofi | Libre (CC0, cités par courtoisie) |
| Humeur | colonne d'humeurs, icônes Material | Libre, à citer |
| Ne pas déranger | coupe les notifications de l'ordinateur pendant la concentration | Libre |

### Ordi (depuis l'ordinateur)

| Fonctionnalité | Ce qu'elle fait | Droits |
|---|---|---|
| Fichiers partagés | dossier de l'ordinateur ouvert à côté | Libre |
| Page de l'ordi | page web ouverte sur l'ordinateur, affichée dans le WebView | Libre ; extension du navigateur : voir partie 3 |
| Onglets | onglets du navigateur de l'ordinateur | Libre |
| Presse-papiers | 20 derniers textes, en mémoire 1 h | Libre |
| Montrer sur l'ordi | la tablette sert de caméra, page `/camera` en MJPEG | Libre |
| Envoyer une photo | photo pleine taille dans « Partage tablette » | Libre |

### Pilotage de l'ordinateur

| Fonctionnalité | Ce qu'elle fait | Droits |
|---|---|---|
| Deck : touches et macros | applis, raccourcis, sites, macros en plusieurs étapes | Libre ; icônes des sites lues sur le site (marques de tiers, affichées pour l'utilisateur) |
| Fenêtres et écrans | mettre devant, déplacer d'un écran à l'autre, moitiés, agrandir | Libre |
| Pages du Mac (Spaces) | bande des bureaux, plein écran, ordre des pages | Risque (API privée `CGSCopyManagedDisplaySpaces`) |
| Pavé tactile | souris depuis la tablette, signé par HMAC | Libre |
| Son de l'ordinateur | volume, sortie, micro coupé | Libre |
| Notifications | notifications de l'ordinateur sur la tablette, en mémoire 24 h | Libre |
| Appels entrants | sonnerie Teams et Discord, décrocher, refuser, raccrocher | Risque (automatisation des clients, marques) |
| Rejoindre une réunion | lien Teams, Meet, Zoom, Webex ouvert sur l'ordinateur | Libre (on ouvre un lien) |

### Voix

| Fonctionnalité | Ce qu'elle fait | Droits |
|---|---|---|
| Commande vocale | phrases comprises sans IA, touches et applis par leur nom | Droit (reconnaissance Google de l'image) |
| Dictée | texte tapé sur l'ordinateur | Droit (reconnaissance Google) |
| Mot de réveil « Mate » | écoute hors ligne par Vosk, modèle `vosk-model-small-fr-0.22` | Libre, à citer : le modèle (Apache 2.0) n'est pas dans `licences.txt` |
| Synthèse vocale | rappels, réponses, retour parlé | Droit (moteur Google de l'image) |

### Ordinateur (compagnon)

| Fonctionnalité | Ce qu'elle fait | Droits |
|---|---|---|
| Appli portable Mac et Windows | sans installateur, node embarqué, lancement au démarrage au choix | Libre ; distribution : signature (Droit) |
| Pilote Windows | `UnowhyHelper.exe` en C# (.NET Framework 4.8 de Windows) | Libre |
| Icône dans la barre de menus | visage Mate (Mac et zone de notification Windows) | Libre |
| Page locale / panneau de configuration | appairage, réglages de la tablette, profils, barres, touches, connecteurs | Libre |
| Mises à jour du compagnon | poussées par la tablette, SHA-256 vérifié | Libre |
| Extension MCP | une IA de bureau pilote et règle la tablette | Libre (SDK MIT) ; l'IA choisie relève de l'abonnement de l'utilisateur |
| Plugins | Discord, Teams, Claude Code, Ouvertes, Son, Pavé, Média activables par ordinateur | Libre |

### Site vitrine

| Fonctionnalité | Ce qu'elle fait | Droits |
|---|---|---|
| Site B2B | page statique servie par nginx, installeurs de la dernière release | Libre (nginx BSD-2) |

## 2. Libre de droits

Rien à demander à personne. Les obligations de citation sont remplies par `licences.txt` (tablette) et `LICENCES-TIERS.txt` (ordinateur), sauf le point signalé.

| Élément | Licence | Obligation |
|---|---|---|
| Notre code (tablette, compagnon, site) | propriété Unowhy | aucune |
| AndroidX, Kotlin, coroutines, serialization, JSpecify | Apache 2.0 | citer, joindre le texte (fait) |
| Vosk (bibliothèque) et JNA | Apache 2.0 (JNA : double licence, Apache retenue) | citer (fait) |
| Modèle Vosk `small-fr-0.22` | Apache 2.0 | **à ajouter** dans `licences.txt` |
| Fastify, @fastify/multipart, zod, SDK MCP | MIT | citer (fait par `scripts/licences.ts`) |
| node-ical | Apache 2.0 | citer (fait) |
| Node.js embarqué | MIT et composants tiers | citer (fait) |
| Icônes Material | Apache 2.0 | citer (fait) |
| 13 polices (Outfit, Nunito, Abril Fatface, Atkinson Hyperlegible, IBM Plex Sans et Mono, Instrument Serif et Sans, Young Serif, Figtree, Barlow, Barlow Semi Condensed, JetBrains Mono) | SIL OFL 1.1 | citer ; une police modifiée ne garde pas les noms réservés « Abril » et « Plex » |
| Prévisions MET Norway | CC BY 4.0 | citer (fait) ; User-Agent identifiant l'appli exigé par MET (fait) |
| Villes GeoNames | CC BY 4.0 | citer (fait) |
| 5 sons d'ambiance | CC0 1.0 | aucune (cités quand même) |
| Son « Crunch » | généré par nous | aucune |
| nginx (site) | BSD-2 | aucune pour l'image servie |
| API publiques des systèmes | Android (device owner, Camera2, MediaCodec), Windows (UI Automation, WinRT, Win32), macOS (Accessibilité, AppleScript / JXA, CGEvent) | aucune |
| Formats et protocoles | ICS, HTTPS, MCP, Media Session, OAuth 2 + PKCE | aucune |

## 3. Droits ou licence nécessaires

Classé du plus bloquant pour la vente au moins bloquant.

| # | Sujet | Fonctionnalités | Ce qu'il faut | État au 30/10 |
|---|---|---|---|---|
| 1 | **Signature des paquets** | appli Mac et Windows | Apple Developer Program et notarisation pour le Mac ; certificat de signature de code Authenticode pour Windows (sinon Gatekeeper, SmartScreen et antivirus bloquent ou alertent) | aucune signature dans `scripts/package.ts` ni la CI |
| 2 | **Services Google de la tablette** | commande vocale, dictée, synthèse vocale, « Ma position » | licence GMS de l'image Unowhy : vérifier qu'elle couvre la revente des tablettes hors école, en B2B ; conditions Google sur la voix envoyée | à faire valider (juridique) ; Vosk hors ligne déjà en place pour le mot de réveil |
| 3 | **Nom Mate** | tout le produit | recherche d'antériorité et dépôt de marque (INPI, EUIPO) | non fait à notre connaissance |
| 4 | **Parc de tablettes** | kiosque | accord interne pour retirer le MDM SQOOL et réinitialiser les 18 000 tablettes | décision Unowhy |
| 5 | **Microsoft Graph** | agenda Outlook relié | appli Entra ID multi-locataire, vérification de l'éditeur, consentement de l'admin chez chaque client | inactif (`config/accounts.json` vide) |
| 6 | **Google Agenda** | agenda Google relié | projet Google Cloud, écran de consentement, vérification par Google de la portée agenda (sinon 100 utilisateurs et avertissement « appli non validée ») | inactif |
| 7 | **Jira** | widget Jira, worklog | licence Atlassian et jeton API de l'utilisateur ; marque Atlassian | marche avec le compte du client |
| 8 | **GitLab** | widgets GitLab et Pipelines, fusion | appli OAuth créée par l'admin de l'instance du client, compte utilisateur | `gitlab.clientId` vide tant que l'admin ne l'a pas créée |
| 9 | **Claude Code et Orca** | widget Agents, usage Claude | abonnement Anthropic de l'utilisateur ; Orca installé chez lui (vérifier ses conditions) ; marque Claude : icône neutre `ic_agents` déjà utilisée | marche avec le compte de l'utilisateur |
| 10 | **Discord** | page Discord, appels entrants, voix | pas d'API officielle (RPC écarté) ; faire relire les conditions de Discord sur l'automatisation du client ; marque Discord | Risque |
| 11 | **Microsoft Teams** | page Réunion, appels entrants, Rejoindre | pas d'API (automatisation de la fenêtre) ; marque Teams | Risque |
| 12 | **API privées Apple** | Média sur Mac (MediaRemote), pages du Mac (CGS) | aucune licence possible : API non documentées, peuvent casser à chaque macOS | Risque |
| 13 | **Pochettes Apple** | Média sur Mac, morceau sans pochette | conditions de l'API iTunes Search (usage lié à la promotion du contenu Apple) | à faire relire |
| 14 | **Aperçus YouTube** | Média sur Mac | conditions de YouTube pour l'image d'aperçu | à faire relire |
| 15 | **Extension du navigateur** | Page de l'ordi, Onglets, commandes média | chargée à la main aujourd'hui ; la publier = compte développeur Chrome Web Store et Edge Add-ons | non publiée |
| 16 | **PowerPoint, Keynote, Office** | Présentation | licences de l'utilisateur | rien à faire de notre côté |
| 17 | **Données personnelles** | voix, localisation, agenda | RGPD : politique à compléter (raison sociale, DPO, transferts hors UE) | `docs/confidentialite.md`, partie 4 |

## 4. À corriger dans le dépôt

Rien depuis le 30/10/2026 : le modèle Vosk `vosk-model-small-fr-0.22` (Alpha Cephei, Apache 2.0) est cité dans `licences.txt`, et son téléchargement depuis alphacephei.com est déclaré dans `docs/confidentialite.md` et `backend/config/confidentialite.txt`.
