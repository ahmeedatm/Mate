# Tablette Unowhy 4.2 : spec matérielle

Companion ne tourne que sur ce matériel. Toute décision technique (dépendance, animation, page web,
format média) se vérifie contre cette fiche, pas contre l'émulateur ni un téléphone récent.

Relevés par adb :

- 23/10/2026 : tablette d'Eliot, image SQOOL d'origine (MDM, caméras).
- 27/10/2026 : tablette `77332110160003967`, Companion device owner en kiosque (le reste).

```bash
adb shell getprop ro.build.display.id                 # firmware Unowhy
adb shell 'grep "CPU part" /proc/cpuinfo; cat /sys/devices/system/cpu/online'
adb shell dumpsys SurfaceFlinger | grep -m1 ^GLES     # GPU
adb shell dumpsys display | grep -m1 -o 'fps=[0-9.]*' # fréquence de l'écran
adb shell pm list features                            # capteurs, radios, Vulkan
adb shell dumpsys sensorservice | grep '^0x'          # capteurs
adb shell dumpsys media.audio_policy | grep 'type: AUDIO_DEVICE'
adb shell dumpsys webviewupdate | grep 'Current WebView'
adb shell dumpsys device_policy | grep -A3 Owner      # MDM ou Companion
adb shell dumpsys thermalservice | grep Temperature   # températures
```

## Système

| Info | Valeur |
|---|---|
| Modèle | `S10G001S4M_EEA` (fabricant déclaré `UMOWHY`), série commençant par 7733 = V4.2 |
| Firmware | `S10G001S4M_V1.4_20200713`, build `user` / `release-keys`, correctif de sécurité 2020-06-05 |
| Android / API | 10 / 29 (le parc va d'Android 8 à 11 selon les lots) |
| Noyau | Linux 4.14.141 |
| Bootloader | Verrouillé, déverrouillage OEM non autorisé : flash uniquement avec les outils Unowhy |
| Stockage | 32 Go, 25 Go pour `/data` (0,9 Go utilisés en kiosque) |
| Batterie | Li-ion, `charge_full` = 7 794 mAh, recharge 5 V ; `cycle_count` vaut 0 (compteur non tenu) |

## Processeur et mémoire

| Info | Valeur |
|---|---|
| SoC | MediaTek MT8167 |
| CPU | 4 × Cortex-A35 (`CPU part 0xd04`), 598 MHz à 1,3 GHz, gouverneur `ondemand` |
| Cœurs en ligne | Variable : 1 au repos, 3 sous charge (cœurs coupés à chaud) |
| ABI | `arm64-v8a`, `armeabi-v7a`, `armeabi` |
| RAM | 2 Go (1,9 Gio visibles), environ 870 Mo libres en kiosque avec Chrome ouvert |
| Tas Java par appli | 128 Mo (`dalvik.vm.heapgrowthlimit`), 256 Mo avec `largeHeap` |
| Appareil « low RAM » | Non |

## Graphisme et écran

| Info | Valeur |
|---|---|
| GPU | Imagination PowerVR Rogue GE8300 |
| API | OpenGL ES 3.2 (avec AEP), Vulkan 1.1, EGL 1.4 |
| Écran | 10,1", 1280 × 800, 160 dpi (`mdpi`, 1 dp = 1 px) |
| Fréquence | **54,92 Hz** : une image toutes les 18,2 ms, pas 16,7 |
| Tactile | `mtk-tpd`, multipoint (`jazzhand`, 5 doigts et plus), jusqu'à 10 contacts suivis |

## Capteurs, son, radios

| Info | Valeur |
|---|---|
| Capteurs | Accéléromètre, magnétomètre, **luminosité ambiante** (MTK) ; pas de gyroscope, pas de proximité |
| Vibreur | Probablement absent : pas de `timed_output`, `/sys/class/leds` illisible ; le service `vibrator` existe quand même |
| Sortie son | Haut-parleur intégré, prise casque (micro-casque reconnu) |
| Entrée son | Micro intégré |
| WiFi | 802.11, WiFi Direct ; connectée en 2,4 GHz (2 437 MHz), réseaux 5 GHz vus au scan |
| Bluetooth | Classique et LE, éteint par défaut, nom `SMB-L1006` |
| USB | Hôte (OTG) et accessoire |
| Localisation | GPS et réseau déclarés |
| Absents | Téléphonie, NFC, HDMI, Ethernet |

## Caméras

Relevé avec `adb shell dumpsys media.camera` (Camera2, niveau `LIMITED` pour les deux caméras).
Appli système `com.mediatek.camera`, inutile à Companion (il passe par Camera2).

| | Arrière | Avant |
|---|---|---|
| Capteur | 2560×1920 (5 Mpx), 3,83 × 2,92 mm | 1636×1227 (2 Mpx) |
| Focale | 3,23 mm, champ ≈ 61° × 49° | — |
| Mise au point | Autofocus (modes 0 à 4, dont continu), distance minimale 5 cm | Fixe |
| Flash / LED | Non | Non |
| Flux JPEG | 2560×1920, 1920×1088, 1600×1200, 1280×720… à 30 i/s | jusqu'à 1600×1200 |
| Flux YUV / preview | jusqu'à 1920×1080 à 30 i/s | jusqu'à 1280×720 |

Avec la caméra arrière à 30 cm au-dessus de la table, le champ couvre environ 36 × 27 cm : une page A4
avec de la marge (un cahier ouvert sur deux pages demande environ 35 cm). En 720p, ça donne environ
90 points par pouce sur la page ; en photo 5 Mpx, environ 180.

## Médias

| Info | Valeur |
|---|---|
| Décodage vidéo matériel | H.264, HEVC, VP9, MPEG-2, MPEG-4, H.263, VC-1, DivX / Xvid |
| Encodage vidéo matériel | H.264 (`OMX.MTK.VIDEO.ENCODER.AVC`) **limité à 1280×720**, MPEG-4, H.263 ; au-delà de 720p, encodeur logiciel `c2.android.avc.encoder` (trop lent) ; pas d'encodage HEVC |
| Audio matériel | MP3, ALAC, APE, ADPCM, GSM |

## Logiciel embarqué

| Info | Valeur |
|---|---|
| WebView | `com.google.android.webview` **81.0.4044.138** ; installations bloquées en kiosque, mise à jour seulement par la carte Mises à jour (`kiosk/Updater`) |
| Chrome | 81.0.4044.138, désactivé en kiosque |
| Services Google | Play Services, Play Store, appli Google 10.97 (reconnaissance vocale de la commande vocale) |
| Synthèse vocale | Google TTS 3.20 ; voix française à confirmer |
| Lanceur | Launcher3 d'Android (pas de lanceur SQOOL) ; Companion le remplace en kiosque |
| MDM d'origine | `com.hisqool.devicemanager`, **profile owner** (pas device owner), restriction `no_add_user` ; effacé par la réinitialisation du kiosque |
| Agent SQOOL dans l'image | `com.unowhy.sqoolota` (`/product/priv-app/SqoolOTA`, UID système) : réinstalle le MDM dès qu'il a du réseau ; masqué par Companion en kiosque |
| Applis SQOOL | `com.sqool.student`, `com.hisqool.rooms`, `com.hisqool.authentication`, `com.unowhy.sqoolclasse`, `com.unowhy.sqool.sqpa`, `com.unowhy.projection`… (toutes dans `/data/app`) |
| Comptes | Aucun |

## Mesures de performance

27/10/2026, prototype Cadran (`docs/ui-ux/da/accueil.html`, branche `docs/da-cadran`) dans Chrome 81 sur la tablette, intervalle
entre images mesuré par `requestAnimationFrame` :

| Situation | Images/s | Intervalle médian | Images au-dessus de 33 ms |
|---|---|---|---|
| Repos (horloge, chrono Focus) | 55,2 | 18,2 ms | 0 sur 331 |
| Animations (tâches, eau, pause) | 54,5 | 18,2 ms | 3 sur 654, dont une à 109 ms |

Mémoire au même moment (PSS) : Companion 67 Mo, Chrome 92 Mo + 84 Mo, `system_server` 147 Mo,
appli Google 102 Mo.

## Ce que ça impose à Companion

- **Une image = 18,2 ms** (écran à 55 Hz). Les budgets d'animation et les mesures `gfxinfo` se lisent
  sur cette base, pas sur 16,7 ms.
- **Processeur faible** (Cortex-A35, cœurs coupés au repos) : rien de lourd sur le fil principal,
  boucles d'animation arrêtées hors écran, pas de flou ni d'ombre recalculée à chaque image.
- **Tas de 128 Mo** : images décodées à la taille d'affichage (une image 1280 × 800 en ARGB = 4 Mo).
- **WebView 81** : tout ce qui s'affiche dans le WebView de la tablette doit marcher en Chrome 81.
  Pas de `gap` en flexbox (Chrome 84), pas de `inset` (Chrome 87), pas de `:is()` / `:where()`
  (Chrome 88), pas d'`aspect-ratio` (Chrome 88), pas de `:has()` ni de `@container`.
- **Animations système à 0 en kiosque** (`docs/kiosque.md`, étape 1) : `animator_duration_scale = 0`
  coupe les `ValueAnimator`. ⚠️ À trancher avant le passage de Cadran dans l'appli, dont la DA
  repose sur les animations et les coupe justement quand cette échelle vaut 0.
- **Encodage vidéo** : 720p au plus (visualiseur de la caméra en JPEG 720p).
- **Capteur de luminosité** : disponible pour atténuer l'écran selon la pièce, en plus de l'horaire
  22 h – 7 h de `device/Screen`.
- **Pas de vibreur** : aucun retour haptique à prévoir (la vibration du `TrashZone` ne fait rien ici).
- **mdpi** : 1 dp = 1 px, les cibles tactiles de 48 dp font 48 px, soit environ 8 mm sur cet écran (149 pixels par pouce réels).
- adb et l'installation par adb marchent : on teste sans reflasher.
- Le mode kiosque (device owner) exige une réinitialisation hors ligne sans provisionnement SQOOL
  (`docs/kiosque.md`).

## Reste à relever

- [ ] Qualité du micro et du haut-parleur avec l'appli, voix française de Google TTS.
- [ ] Connexion WiFi en 5 GHz (seul le scan est vu).
- [ ] Version Bluetooth et appairage d'une enceinte.
- [ ] Température sous charge longue. Lisible par `dumpsys thermalservice` : CPU et GPU 42,6 °C,
  batterie 27 °C au repos en kiosque (27/10/2026).
- [ ] Luminosité maximale de l'écran et lisibilité au soleil.
- [ ] Vibreur : confirmer l'absence en appelant `Vibrator.hasVibrator()` depuis l'appli.
- [x] Tablette sans MDM : réinitialisée le 23/10/2026, Companion device owner, SqoolOTA masqué,
  24 applis désactivées (`docs/kiosque.md`).
