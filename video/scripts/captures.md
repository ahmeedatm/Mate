# Captures de l'émulateur : `captures.mjs`

Refait les captures d'écran de l'appli UnoMate (1280×800) pour les vidéos, à partir de la démo par personas de l'appli (Réglages > Démo). À lancer après chaque changement d'écrans, de thèmes ou de données de démo.

```bash
node video/scripts/captures.mjs                    # tout : build, émulateur, 7 phases, INDEX.md
node video/scripts/captures.mjs --no-build         # garde l'APK debug déjà construit
node video/scripts/captures.mjs --only nour,sam    # seulement ces phases
node video/scripts/captures.mjs --date 2026-10-29  # jour affiché (défaut : aujourd'hui, l'agenda de démo suit)
node video/scripts/captures.mjs --serial emulator-5622   # réutilise un émulateur déjà lancé (jamais arrêté)
```

Phases : `commun` (boîte des six personas), `nour`, `gwen`, `camille`, `aimee`, `sam`, `sacha`. Durée : environ 8 min pour tout. Un lancement partiel (`--only`) met à jour ses lignes et garde les autres dans `INDEX.md`. Prérequis : Node, `local.properties` (SDK Android), AVD `companion_api29`, `backend/node_modules` installés.

## Ce qu'il fait

1. Gradle : `./gradlew :app:assembleDebug` (sauf `--no-build`).
2. Lance l'AVD `companion_api29` en headless, `-read-only` (l'AVD d'une autre session n'est pas touché), sur un port libre entre 5620 et 5680. Attend le boot, passe `adb root`, coupe les animations d'Android, met l'émulateur en français, installe l'APK.
3. Par phase : relance `video/demo-backend.mts` (vrai compagnon, faux pilotage de l'ordinateur, applis « installées » de la démo, port libre ≥ 3970, `DEMO_DIR` temporaire), valide les plugins (sinon l'écran de première utilisation recouvre l'appli), fait `adb reverse` du port, écrit `shared_prefs/companion.xml` (ordinateur appairé, thème Cadran), règle l'heure de la phase.
4. Choisit le persona dans l'appli : Réglages (ouverts par l'ordre `mode` du compagnon, l'icône de la barre étant rognée), Démo, « Choisir un persona », puis le prénom. L'appli pose écrans de la barre, thème, taille du texte et réglages ; le compagnon pose touches, tâches, agenda, GitLab, Jira, agents et fenêtres fictifs (`backend/src/demo.ts`, `demo-mock.ts`). Les écrans d'une phase se suivent sans relancer l'appli.
5. Pour chaque écran : navigue par texte ou `contentDescription` (`uiautomator dump`), capture `adb exec-out screencap -p` dans un `.tmp` puis renomme. Un écran que uiautomator ne peut lire (lecture en cours, chrono) est atteint par la place de l'entrée de la barre relevée à l'arrivée du persona.
6. Contrôle chaque capture : taille 1280×800, image non noire, différente de toutes les précédentes. Les marques lues à l'écran sont notées dans `video/captures/INDEX.md` (fichier, écran, persona, heure, statut, note).
7. À la fin, même en erreur : heure automatique, langue, `adb reverse` et animations remis, émulateur et backends arrêtés **par leur PID**. Jamais `pkill`, jamais `kill` via `lsof`. Toute commande adb passe par `-s emulator-<port>` (une vraie tablette est souvent branchée) ; `--serial` refuse autre chose.

Un écran introuvable donne un message clair (avec les textes visibles), une ligne « ÉCHEC » dans l'index, et la suite continue. En cas d'échec, les journaux restent dans `$TMPDIR/captures-<pid>/`.

## Écrans

| Phase | Heure | Thème | Captures |
|---|---|---|---|
| commun | 9:00 | Cadran | `commun-choix-persona` |
| nour | 8:30 | Registre | `nour-excel`, `nour-dossiers`, `nour-cloture` |
| gwen | 10:30 | Studio | `gwen-accueil`, `gwen-presentation`, `gwen-campagnes` |
| camille | 12:30 | Maison | `camille-travail`, `camille-absente` (après « Je m'absente »), `camille-maison` |
| aimee | 14:29, l'horloge tourne | Tableau | `aimee-accueil`, `aimee-alerte` (alerte à 14:30), `aimee-projets`, `aimee-jira` |
| sam | 17:00 | Contraste élevé | `sam-accueil`, `sam-reglages-retour-parle` |
| sacha | 19:10 | Terminal | `sacha-code`, `sacha-gitlab`, `sacha-jira` |

Aimée : la démo pose « Point client Boréal » 6 min après le choix, à l'heure de l'ordinateur. Le script la déplace à 14:35 du jour affiché (`PUT /calendar/events/:id`) et relance l'appli ; l'alerte part 5 min avant.

## Marques

Les données de démo de l'appli citent les vrais logiciels des personas (Excel, Outlook, Teams, Chrome, Canva, Slack, Jira, GitLab, Claude…) : elles apparaissent dans les captures et sont notées dans l'index, sans faire échouer la capture.

## Limites connues

- La météo vient d'un appel réseau de l'émulateur (MET Norway) : sans internet, la carte reste vide.
- Les projets vidéo lisent `video/captures/` par liens physiques (`demo/assets/screens/`) : le script remplace les fichiers (renommage), il faut refaire les liens après un lancement.
- Les noms de fichiers changent avec l'interface : `demo/chapitres.json` est à ajuster.
