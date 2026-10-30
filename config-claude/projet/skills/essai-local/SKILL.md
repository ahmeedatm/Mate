---
name: essai-local
description: Pose l'appli d'une MR sur la tablette branchée en adb, tout en local (sans pipeline ni Mises à jour), et passe le compagnon du Mac sur le backend de la même MR. Build sur ce Mac de l'essai com.unowhy.companion.essai signé par la clé de release, installé à côté de la stable. Invoquer via /essai-local [iid], ou quand l'utilisateur veut « charger la MR sur la tablette », « essayer la MR en local », « mettre la version de la MR sur la tablette ».
---

# /essai-local [iid]

Plus rapide que l'essai de `/ship` (étape 6) : pas de branche `essai/*`, pas de pipeline, pas d'annonce par Mises à
jour. Même résultat sur la tablette : l'essai, signé comme la stable, reprend ses ordinateurs.

## Une seule fois par session

La première demande de la session se lance sans demander. Ensuite, après une correction ou un nouveau commit, ne
pas reposer l'essai de soi-même : demander d'abord (`AskUserQuestion`, « Reposer l'essai de la MR !<iid> sur la
tablette ? », oui en premier choix). Seule exception : l'utilisateur redemande lui-même l'essai dans son message.
Un essai déjà posé sert peut-être à un test en cours sur la tablette ; le remplacer sans prévenir le coupe.

## Lancer

En arrière-plan (`run_in_background: true`, 3 à 6 min) :

```bash
@REPO@/.claude/skills/essai-local/essai-local.sh <iid>
```

- Avec `<iid>` : tête de la MR (`refs/merge-requests/<iid>/head`, marche aussi pour une MR d'un collègue), construite dans
  le worktree détaché `~/orca/workspaces/unowhy-companion/essai-mr`, réutilisé d'une fois à l'autre.
- Sans argument, lancé depuis un worktree : ce worktree tel quel, modifs non commitées comprises.
- Plusieurs tablettes : `ANDROID_SERIAL=<série>`. Les émulateurs sont ignorés.

Ce que fait le script :

1. Tablette en adb, installations permises (sinon sortie 2).
2. Version `X.Y.Z-essai.N` : dernier tag de la branche, N choisi pour passer juste au-dessus de l'essai installé.
3. Clé de release : copiée une fois des variables CI vers le trousseau macOS (`unowhy-companion-release-*`), posée
   dans un dossier temporaire le temps du build, puis effacée.
4. `assembleRelease`, puis garde-fous : paquet `com.unowhy.companion.essai` (jamais celui de la stable) et même
   certificat que la stable de la tablette. Sinon rien n'est installé.
5. `adb install -r`, ouverture de l'essai.
6. Compagnon du Mac rebâti et relancé sur le backend des mêmes sources (`ship/deploy-tablette.sh`, `BACKEND=1 APK=0`).

Dernière ligne : `com.unowhy.companion.essai X.Y.Z-essai.N (MR !<iid> …) posé`.

## Sorties

- `0` : relayer en une ligne la version et la MR posées.
- `2`, installations bloquées : la tablette est en kiosque avec `no_install_apps`. Dire à l'utilisateur : dans la
  stable, Réglages > appui long sur le titre > code technicien > « Autoriser les installations », puis relancer.
- `2`, aucune tablette : la brancher en USB.
- `1` : lire le message. « au-dessus de ce que vX.Y.Z permet » = essai d'un tag plus récent installé : « Revenir à la
  stable », `adb uninstall com.unowhy.companion.essai`, relancer.

## Limites

- Un seul essai sur la tablette et un seul backend sur le Mac : le dernier posé remplace le précédent, y compris un
  essai de `/ship`. Toujours dire quelle MR est posée.
- Un essai de la CI de code plus bas qu'un essai local ne s'installe plus (« Pas installable ») : désinstaller l'essai.
- L'essai n'est pas device owner : Mises à jour, WiFi et Kiosque ne s'y testent pas.

## Revenir

Sur la tablette, dans l'essai : Réglages > Mises à jour > « Revenir à la stable ». Mac sur `main` :
`git -C @REPO@ pull --ff-only`, puis depuis ce dossier
`BACKEND=1 APK=0 .claude/skills/ship/deploy-tablette.sh`.
