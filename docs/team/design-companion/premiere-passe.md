# Première passe « critique » : Accueil et page locale

27/10/2026. Skill `.claude/skills/design-companion`, commande `critique`. Aucun code de l'appli modifié.

Méthode : une seule session (relecture design et contrôle mécanique faits par le même agent, pas
isolés l'un de l'autre). Elle n'a écrit aucun des deux écrans.

Preuves :
- Accueil : APK de `main` (job CI `android` 1017947, commit 58bdd30) sur un émulateur à part
  (AVD `companion_test_api29_5590`, Android 10, 1280×800, 160 dpi), backend de test sur
  `10.0.2.2:3931` avec données jetables (3 tâches, 2 rendez-vous). 4 thèmes, `font_scale 1.3`,
  mode Modifier, backend arrêté. Pas de vraie tablette : toucher, fluidité et lecture à distance
  non vérifiés.
- Page locale : même backend, Playwright, 1280 et 800 px, clair et sombre, code d'appairage
  affiché, backend arrêté page ouverte. L'adresse `127.0.0.1` des captures vient du backend lancé
  avec `HOST=127.0.0.1`, ce n'est pas un défaut.
- Contrastes calculés depuis `palettes.xml` et `getComputedStyle`.

Captures : `captures/`.

## Accueil (appli)

### Ce qui tient

- Les 4 thèmes partagent les mêmes rôles de couleur : aucune carte ne casse d'un thème à l'autre
  (`accueil-nuit`, `-clair`, `-neon`, `-chaud`). Texte principal entre 14,5 et 17,5:1.
- Les teintes par fonction (`Look.iconHue`) servent vraiment : agenda orange, tâches vert, eau cyan,
  repérables sans lire.
- L'écran sans ordinateur (`accueil-sans-ordinateur`) est clair : trois étapes, l'état de chacune
  coché, une sortie « Plus tard ».
- Les messages d'erreur sont en français, nomment l'ordinateur et disent quoi vérifier.

### Problèmes

**[P1] Ordinateur injoignable : l'écran dit trois choses différentes.** `accueil-injoignable.png`.
L'agenda garde les rendez-vous sans dire qu'ils datent ; son en-tête porte l'erreur coupée
(« Mac de critique ne répond pas : v… ») ; la carte Tâches se vide et met l'erreur en petit gris ;
« Relier Outlook / Google » reste actif ; la barre latérale ne montre qu'un point rouge de 6 dp.
Pourquoi : on ne sait pas si les rendez-vous sont à jour ni où agir. Changement : un seul message à
un endroit stable avec Réessayer, les données gardées marquées « dernière mise à jour à HH:MM »,
les actions qui demandent l'ordinateur désactivées. Commande : `etats`, puis `harden`.

**[P1] Mode Modifier : les commandes d'édition cachent le contenu et d'autres boutons.**
`accueil-modifier.png`. La pastille « Grand » couvre « Relier Outlook / Google », les ✕ couvrent les
pastilles des titres, « Petit » chevauche le bouton Démarrer du minuteur. Pourquoi : on touche le
mauvais bouton dans un mode où un ✕ supprime un widget. Changement : commandes d'édition dans une
bande réservée de la carte, contenu de la carte inactif et atténué pendant l'édition. Commande :
`polish`.

**[P2] Les alertes en haut d'écran masquent la carte Agenda.** `accueil-toast.png`,
`accueil-nuit.png` avant que l'alerte parte. « Connecté à Mac de critique » puis « 💧 Pense à boire
un verre d'eau (0 / 8) » se posent sur le titre et le bouton + de l'agenda. L'alerte d'eau mélange
un emoji et une icône de message. Changement : alerte dans une zone qui ne recouvre pas d'action, une
icône `ic_water` du jeu Material. Commande : `polish`.

**[P2] Cibles tactiles sous 48 dp sur l'Accueil.** Actualiser et Tâches récurrentes 40 dp
(`view_tasks_card.xml:29`, `:38`), + de l'agenda 40 dp (`widget_agenda.xml:48`), « Relier Outlook /
Google » 36 dp (`widget_agenda.xml:39`). Sur une tablette posée et touchée en passant, ce sont les
boutons qu'on rate. Commande : `polish`.

**[P2] Texte trop petit ou trop pâle.** Nom de l'ordinateur sous le statut de la barre latérale :
11 sp en `c_text_faint` (`activity_main.xml:89`), 2,3 à 3,2:1 selon le thème ; c'est pourtant
l'information « à quoi je suis branché ». Pastille de la barre latérale 11 sp (`item_rail.xml:50`).
En Clair, l'orange (`c_warn`, `c_hue_orange`) est à 3,0-3,1:1 sur `c_surface` : « 3 à faire » et
« dans 39 min » (13 à 15 sp) passent sous 4,5:1 (`accueil-clair.png`). Commande : `polish` ; la
teinte orange du thème Clair relève de la DA.

**[P2] À `font_scale 1.3`, la liste des tâches est coupée sans le montrer.** `accueil-font-1.3.png` :
« Réserver la salle » est tronqué en bas de la carte, rien n'indique qu'elle défile. Changement :
fondu ou ombre de bord quand il reste du contenu, ou carte qui grandit. Commande : `harden`.

**[P3] Information dite deux fois.** « Prochain rendez-vous » répète la première ligne de l'agenda
juste à côté ; sans rendez-vous, les deux cartes affichent chacune leur « Rien de prévu »
(`accueil-toast.png`). Le badge « 3 » reste sur Accueil alors qu'on est sur l'Accueil. Commande :
`distill`.

### Charge et parcours

Tâche principale : savoir où on en est (heure, prochain rendez-vous, tâches) d'un coup d'œil. Elle
passe : l'heure et le prochain rendez-vous se lisent sans chercher. Point de décision le plus
chargé : la carte Tâches (cocher, supprimer, récurrentes, actualiser, ajouter : 5 actions visibles),
à la limite.

## Page locale (web)

### Ce qui tient

- Le code d'appairage en grand, en chiffres à chasse fixe, se lit de loin
  (`page-locale-appairage-sombre.png`).
- La marche à suivre reprend les mots exacts de la tablette (« Réglages › Ordinateurs › Ajouter »).
- Clair et sombre suivent le système, sans dépendance ni police externe.

### Problèmes

**[P1] Compagnon arrêté : la page continue d'afficher « Tablette connectée » et le code.**
`page-locale-arretee.png`. Seul le sous-titre passe à « Compagnon arrêté » (`local-page.ts:117-119`) ;
le point vert, le code d'appairage et l'adresse restent. Pourquoi : on saisit un code mort, on croit
la tablette reliée. Changement : en cas d'échec, masquer le code, passer le statut en « Compagnon
arrêté » avec le point rouge, dire comment le relancer. Test à ajouter dans
`backend/e2e/local-page.spec.ts`. Commande : `harden`.

**[P1] Bouton principal illisible en mode sombre.** Blanc sur `--accent` `#6d9bff` : 2,7:1
(`local-page.ts:14`, `:27`, mesuré). En clair, blanc sur `#2f6fed` : 4,5:1 (calculé), juste au seuil.
Changement : texte sombre sur l'accent clair en mode sombre (variable `--on-accent`). Commande :
`polish`.

**[P2] Agenda : libellés qui se contredisent.** « Aucun agenda : la tablette propose de le relier »
alors que la tablette affiche des rendez-vous notés depuis elle ; « Retirer » est actif sans lien
enregistré (`page-locale-clair.png`). Changement : distinguer « agenda relié (Outlook, Google, ICS) »
des rendez-vous notés sur la tablette ; « Retirer » seulement s'il y a un lien. Commande : `polish`.

**[P3] Structure et finitions.** Un seul `h1`, les titres de cartes en `<b>` (invisibles comme
titres pour un lecteur d'écran) ; `✓` et `⚠` en caractères au lieu d'icônes (`local-page.ts:134`,
`:168`) ; « Valable 2 minutes » sans décompte ; favicon en 401 dans la console à chaque chargement.
Commande : `polish`.

## À faire ensuite, dans l'ordre

1. Page locale, compagnon arrêté (P1).
2. Accueil, ordinateur injoignable (P1).
3. Accueil, mode Modifier (P1).
4. Page locale, contraste du bouton en sombre (P1).
5. Cibles et textes de l'Accueil (P2), puis le reste.

Non vérifié : vraie tablette (toucher, fluidité des animations, lecture à distance), TalkBack,
thème Clair de la page locale à 800 px, états bascule d'ordinateur et Accessibilité Mac refusée.
