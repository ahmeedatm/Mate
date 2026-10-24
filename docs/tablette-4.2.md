# Tablette Unowhy 4.2 : fiche

Relevée par adb le 23/10/2026 sur la tablette d'Eliot (série 7733…, image SQOOL d'origine).

```bash
adb shell getprop ro.build.version.release   # version Android
adb shell getprop ro.build.display.id        # version du firmware Unowhy
adb shell getprop ro.board.platform          # chipset
adb shell cat /proc/meminfo | head -1        # RAM
adb shell wm size; adb shell wm density      # écran
adb shell dumpsys device_policy | grep -A3 Owner   # MDM (device / profile owner)
adb shell pm list packages -3                # applis installées en plus du système
```

| Info | Valeur |
|---|---|
| Modèle | `S10G001S4M_EEA` (marque UNOWHY), série commençant par 7733 = V4.2 |
| Firmware | `S10G001S4M_V1.4_20200713`, build `user` / `release-keys`, correctif de sécurité 2020-06-05 |
| Android / API | 10 / 29 (le parc va d'Android 8 à 11 selon les lots) |
| Chipset | MediaTek MT8167, 4 cœurs, arm64-v8a |
| RAM | 2 Go (1,9 Gio utilisables) |
| Stockage | 32 Go (25 Go pour `/data`) |
| Écran | 1280×800, 160 dpi (10,1") |
| Services Google | Présents : Play Services, Play Store, WebView, Google TTS |
| Synthèse vocale | Google TTS installé (voix française à confirmer) |
| Caméra | Arrière 5 Mpx autofocus, avant 2 Mpx sans autofocus, pas de flash (détails plus bas) ; appli système `com.mediatek.camera` |
| Encodeur vidéo | H.264 matériel (`OMX.MTK.VIDEO.ENCODER.AVC`) **limité à 1280×720** ; au-delà, encodeur logiciel `c2.android.avc.encoder` (trop lent pour ce processeur) ; HEVC désactivé |
| Comptes | Aucun |
| Bootloader | Verrouillé, déverrouillage OEM non autorisé : flash uniquement avec les outils Unowhy |
| MDM | `com.hisqool.devicemanager`, **profile owner** de l'utilisateur 0 (pas device owner), restriction `no_add_user` |
| Agent SQOOL dans l'image | `com.unowhy.sqoolota` (`/product/priv-app/SqoolOTA`, UID système) : se met à jour et (ré)installe le MDM dès qu'il y a du réseau. Masqué par Companion en kiosque |
| Applis SQOOL | `com.sqool.student`, `com.hisqool.rooms`, `com.hisqool.authentication`, `com.unowhy.sqoolclasse`, `com.unowhy.sqool.sqpa`, `com.unowhy.projection`… (toutes dans `/data/app`) |
| Lanceur | Launcher3 d'Android (pas de lanceur SQOOL) |
| Micro / haut-parleur | À tester |
| WiFi / Bluetooth | À relever |

## Caméras

Relevé avec `adb shell dumpsys media.camera` (Camera2, niveau `LIMITED` pour les deux caméras).

| | Arrière | Avant |
|---|---|---|
| Capteur | 2560×1920 (5 Mpx), 3,83 × 2,92 mm | 1636×1227 (2 Mpx) |
| Focale | 3,23 mm, champ ≈ 61° × 49° | — |
| Mise au point | Autofocus (modes 0 à 4, dont continu), distance minimale 5 cm | Fixe |
| Flash / LED | Non | Non |
| Flux JPEG | 2560×1920, 1920×1088, 1600×1200, 1280×720… à 30 i/s | jusqu'à 1600×1200 |
| Flux YUV / preview | jusqu'à 1920×1080 à 30 i/s | jusqu'à 1280×720 |

Avec la caméra arrière à 30 cm au-dessus de la table, le champ couvre environ 36 × 27 cm : une page A4 avec de la marge (un cahier ouvert sur deux pages demande environ 35 cm). En 720p, ça donne environ 90 points par pouce sur la page ; en photo 5 Mpx, environ 180.

## Ce que ça implique

- adb et l'installation par adb marchent déjà : on peut tester l'appli **sans reflasher**.
- Le mode kiosque (device owner) est impossible tant que le MDM est profile owner : il faut une image ou une réinitialisation sans provisionnement SQOOL.

## Checklist avant le hackathon

- [x] Specs relevées sur 1 tablette.
- [x] adb vérifié (fonctionne avec l'image SQOOL).
- [ ] Micro, haut-parleur, voix française testés avec l'appli.
- [x] Tablette sans MDM : réinitialisée le 23/10/2026, Companion device owner, SqoolOTA masqué, 24 applis désactivées (procédure dans `docs/kiosque.md`).
- [ ] Fournisseur STT / LLM choisi, clés créées, budget fixé.
- [ ] WiFi, câbles USB, enceinte Bluetooth, supports de tablette.
