# Vidéo de démo

Film HyperFrames sur UnoMate (le UnoMate), construit sur les personas de [../docs/personas.md](../docs/personas.md).

| Dossier | Film | Durée |
|---|---|---|
| `demo/` | Démo produit : un chapitre par persona dans la vraie interface, gros plans réels | 84 s |

Choix : `demo/SPEC.md`. Les pubs ont été abandonnées le 29/10/2026.

## Écrans de la tablette

`captures/` : vraies captures de l'émulateur `companion_api29` (index dans `captures/INDEX.md`). Chaque persona est choisi dans l'appli (Réglages > Démo) : écrans, thème et données viennent de la démo de l'appli ; `demo-backend.mts` lance le vrai compagnon avec un faux pilotage de l'ordinateur. Le projet les lit dans `demo/assets/screens/` (liens physiques).

## Médias non versionnés

- `captures/` : ignoré par le `.gitignore` de la racine ; seules les copies de `demo/assets/screens/` sont versionnées.
- `demo/assets/bgm/track.mp3` : musique du catalogue HeyGen 58e906c3 (`heygen audio sounds list --query "subtle modern corporate background"`, puis conversion en MP3 par ffmpeg).

## Commandes

```bash
cd video/demo
npx hyperframes preview --background   # aperçu dans le navigateur
npx hyperframes check                  # vérifications
npx hyperframes render -o renders/demo.mp4
```

## Régénérer après un changement d'interface (icônes, thèmes)

1. Captures : `video/scripts/captures.*` (voir `video/scripts/captures.md`).
2. Liens : `ln -f ../../../captures/<fichier>.png` depuis `demo/assets/screens/` (le script remplace les fichiers, les liens sont à refaire).
3. Démo : ajuster `demo/chapitres.json` (fichiers, points touchés, gros plans), puis depuis `video/demo` : `python3 ../scripts/demo-parcours.py && npx hyperframes check && npx hyperframes render -o renders/demo.mp4`.
