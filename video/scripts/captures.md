# Captures de l'émulateur : `captures.mjs`

Refait toutes les captures d'écran de l'appli UnoMate (1280×800) pour les vidéos. À lancer après chaque changement d'icônes, de thèmes ou de profils.

```bash
node video/scripts/captures.mjs                    # tout : build, émulateur, 8 phases, INDEX.md
node video/scripts/captures.mjs --no-build         # garde l'APK debug déjà construit
node video/scripts/captures.mjs --only sylvie,sam  # seulement ces phases
node video/scripts/captures.mjs --date 2026-10-29  # jour affiché (défaut : aujourd'hui, l'agenda de démo suit)
node video/scripts/captures.mjs --serial emulator-5612   # réutilise un émulateur déjà lancé (jamais arrêté)
```

Phases : `sylvie`, `gwen`, `camille`, `aimee`, `sam`, `sacha`, `commun` (jour : Accueil, barre, modèles, Son) et `commun-nuit` (`--only commun` lance les deux). Durée : voir `video/captures/INDEX.md` (en tête). Un lancement partiel (`--only`) met à jour ses lignes et garde les autres dans `INDEX.md`. Prérequis : Node, `local.properties` (SDK Android), AVD `companion_api29`, `backend/node_modules` installés.

## Ce qu'il fait

1. Gradle : `./gradlew :app:assembleDebug` (sauf `--no-build`).
2. Lance l'AVD `companion_api29` en headless, `-read-only` (l'AVD d'une autre session n'est pas touché), sur un port libre entre 5620 et 5680. Attend le boot, passe `adb root`, coupe les animations d'Android, met l'émulateur en français (dates relatives de « Fichiers de l'ordi »), installe l'APK.
3. Par phase : relance `video/demo-backend.mts` (persona, ports libres ≥ 3970, jamais 3000/3001, `DEMO_DIR` temporaire), choisit les plugins, fait `adb reverse` du port, écrit `shared_prefs/companion.xml` (ordinateur appairé, thème, disposition de l'Accueil de la phase), règle l'heure du persona (`date`, `auto_time 0`).
4. Pour chaque écran : navigue par texte ou `contentDescription` (`uiautomator dump`, jamais de coordonnées sauf les gestes de défilement), capture `adb exec-out screencap -p` dans un `.tmp` puis renomme.
5. Contrôle chaque capture : taille 1280×800, image non noire (couleurs distinctes, écart-type), différente de toutes les précédentes, aucun nom de marque dans les textes de l'écran (liste dans le script). Écrit `video/captures/INDEX.md` (fichier, écran, persona, heure, statut, note).
6. À la fin, même en erreur (trap) : heure automatique, langue, `adb reverse` et animations remis, heure de l'ordinateur reposée, émulateur et backends arrêtés **par leur PID** (SIGTERM, puis SIGKILL si besoin). Jamais `pkill`, jamais `kill` via `lsof`. Toute commande adb passe par `-s emulator-<port>` (une vraie tablette est souvent branchée) ; `--serial` refuse autre chose.

Un écran introuvable donne un message clair, une ligne « ÉCHEC » dans l'index, et la suite continue. En cas d'échec, les journaux restent dans `$TMPDIR/captures-<pid>/` (le chemin est affiché).

## Écrans

| Phase | Heure | Thème | Captures |
|---|---|---|---|
| sylvie | 8:30 | Cadran | `sylvie-accueil-ma-journee`, `sylvie-deck-touches-compta`, `sylvie-editeur-touche-macro` |
| gwen | 10:30 | Cadran | `gwen-accueil-rejoindre`, `gwen-applis`, `gwen-reunion-en-appel` |
| camille | 12:30 | Cadran | `camille-profil-travail`, `camille-profil-perso`, `camille-reunion-micro-coupe`, `camille-focus` |
| aimee | 14:29 | Cadran | `aimee-accueil-ma-journee` (réunion « dans 1 min »), `aimee-fichiers-ordi`, `aimee-ouvertes` |
| sam | 17:00 | Contraste élevé | `sam-accueil-contraste`, `sam-grandes-touches`, `sam-reglages-accessibilite`, `sam-reglages-texte-grand` |
| sacha | 19:10 | Cadran nuit | `sacha-agents`, `sacha-gitlab`, `sacha-jira` |
| commun | 11:00 | Cadran | `commun-accueil-jour`, `commun-barre-profils`, `commun-modeles-profil`, `commun-son` |
| commun-nuit | 19:10 | Cadran nuit | `commun-accueil-nuit`, `commun-media` |

Les profils (écrans de la barre) sont posés par la disposition écrite dans les préférences, pas créés par l'interface : changer un profil se fait dans `PHASES` (`layout`), changer un parcours dans `screens`.

## Marques

Aucune marque tierce n'est dans les données de démo (`demo-backend.mts` : Visio, Messagerie, Tableur, Navigateur, Visuels, CRM…). Une source vient du **code de l'appli**, pas des données, et est notée dans l'index : la carte de l'onglet Agents (`sacha-agents`) écrit « Claude · <dossier> » sur chaque session. Les profils de `sacha` s'appellent Agents, Revues, Tickets pour ne pas afficher « GitLab » ni « Jira » dans la barre ; les widgets GitLab et Jira n'écrivent pas leur marque dans leur contenu (vérifié par le contrôle de texte).

Réglages > Plugins cite Discord, Teams et Claude Code (carte « Plugins » de la page Réglages, hors des captures actuelles : le contrôle de marque le signalerait). Le contrôle lit les textes de l'écran, pas les pixels : un logo dessiné sans texte n'est pas détecté (les icônes d'applis de la démo sont des initiales).

Un nom de marque inattendu à l'écran passe la capture en « À VÉRIFIER » dans l'index.

## Limites connues

- La météo vient d'un appel réseau de l'émulateur (MET Norway) : sans internet, la carte reste vide.
- Écran d'un appel actif (`gwen-reunion-en-appel`) : l'animation continue empêche `uiautomator` de lire l'écran. Le script n'y navigue pas (l'appli bascule seule sur Réunion) et n'y contrôle pas les marques.
- « Réduire les animations » est activé pour toutes les captures sauf les deux écrans Réglages de `sam` (il se verrait dans la capture).
- Les projets vidéo lisent `video/captures/` par liens physiques (`assets/screens/`) : le script remplace les fichiers (renommage), il faut refaire les liens après un lancement.
- Les noms de fichiers changent avec l'interface (Deck V2) : `demo/chapitres.json` est à ajuster.
