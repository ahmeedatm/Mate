# Tablette Gen2 : spec matérielle

Y10 Premium Gen 2 du constructeur, la génération après la [tablette 4.2](tablette-4.2.md). Elle sert à la
branche `Version-Gen2` ; ce qui part dans `master` doit toujours tourner sur la 4.2.

Relevés par adb :

- 01/11/2026 : tablette d'Eliot (série `UYM300900962`), image constructeur d'origine (MDM, comptes Google).

Mêmes commandes que pour la 4.2 (`docs/tablette-4.2.md`). Sous Windows, dans Git Bash, préfixer par
`MSYS_NO_PATHCONV=1` : sinon `/data` devient `C:/Program Files/Git/data`.

## Système

| Info | Valeur |
|---|---|
| Modèle | `Y10G652S8M` (produit `Y10PG2_EEA`, fabricant déclaré `UNOWHY`) |
| Firmware | `Y10PG2250117`, build `user` / `release-keys`, correctif de sécurité 2024-12-05 |
| Android / API | 14 / 34 (première API 34) |
| Noyau | Linux 5.10.226 (GKI android12) |
| Bootloader | Verrouillé (`ro.boot.flash.locked=1`, `verifiedbootstate=green`), déverrouillage OEM non autorisé |
| Stockage | 128 Go, 109 Go pour `/data` (65 Go utilisés, image d'origine) |

## Processeur et mémoire

| Info | Valeur |
|---|---|
| SoC | MediaTek MT8781 (`ro.soc.model=MT8781V/NA`, plateforme `mt6789`, alias Helio G99) |
| CPU | 2 × Cortex-A76 (`0xd0b`) 725 MHz à 2,2 GHz + 6 × Cortex-A55 (`0xd05`) 500 MHz à 2 GHz |
| Cœurs en ligne | 8 (`0-7`) |
| ABI | `arm64-v8a`, `armeabi-v7a`, `armeabi` |
| RAM | 8 Go (7,7 Gio visibles), environ 5,8 Go disponibles |
| Tas Java par appli | 256 Mo (`dalvik.vm.heapgrowthlimit`), 512 Mo avec `largeHeap` |
| Appareil « low RAM » | Non |

## Graphisme et écran

| Info | Valeur |
|---|---|
| GPU | ARM Mali-G57 MC2 |
| API | OpenGL ES 3.2 (avec AEP), Vulkan 1.1 |
| Écran | 10,36", 1200 × 2000 natif (portrait), 240 dpi (`hdpi`, 1 dp = 1,5 px) |
| En paysage | 2000 × 1200 px = **1333 × 800 dp** (4.2 : 1280 × 800 dp) |
| Fréquence | **56 Hz** : une image toutes les 17,9 ms, un seul mode |
| Tactile | Multipoint (`jazzhand`, 5 doigts et plus) |

## Capteurs, son, radios

| Info | Valeur |
|---|---|
| Capteurs | **Accéléromètre seul** (`mxc4005_acc`) et orientation calculée par MTK ; pas de luminosité, pas de magnétomètre, pas de gyroscope, pas de proximité |
| Vibreur | Absent (`cmd vibrator_manager list` : `No vibrator found`) |
| Entrée son | Micro intégré |
| WiFi | 802.11, WiFi Direct, Passpoint |
| Bluetooth | Classique et LE, nom `Y10PG2` |
| USB | Hôte (OTG) et accessoire |
| Localisation | GPS et réseau déclarés |
| Absents | Téléphonie, NFC |

## Caméras

Relevé avec `adb shell dumpsys media.camera` (Camera2, niveau `FULL` pour les deux caméras).
Appli système `com.mediatek.camera`.

| | Arrière | Avant |
|---|---|---|
| Capteur | 3264×2448 (8 Mpx) | 2592×1944 (5 Mpx) |
| Focale | 1,6 mm | 3,75 mm |
| Mise au point | Autofocus | — |
| Flash | Oui (`camera.flash`) | — |

## Logiciel embarqué

| Info | Valeur |
|---|---|
| WebView | `com.google.android.webview` **153.0.8010.36** |
| Services Google | Play Services, Play Store, appli Google (reconnaissance vocale), Google TTS, TalkBack |
| Lanceur | Launcher3 d'Android (`SearchLauncher`) |
| DuraSpeed | Absent (réglage `setting.duraspeed.enabled` inexistant) |
| MDM du constructeur | `com.hisqool.devicemanager`, **profile owner** (pas device owner), restriction `no_add_user` ; installé dans `/data` |
| Autres applis du constructeur | `com.unowhy.sqoolclasse`, `com.unowhy.sqool.sqpa`, `com.unowhy.projection`, `com.hisqool.authentication`, toutes dans `/data` |
| Agent de provisionnement | `com.unowhy.sqoolota` (celui de la 4.2) **absent** ; aucune appli du constructeur trouvée dans `/system`, `/system_ext` ou `/product` |
| Comptes | 3 |
| Mate | Pas installée |

## Allègement (02/11/2026)

Applis désactivées par `pm disable-user --user 0` (réversible : `pm enable <paquet>`). Gardés : YouTube,
YouTube Music, Crunchyroll, Play Store, services Google, WebView, Google TTS, appli Google (reconnaissance
vocale), Gboard, TalkBack, Launcher3, Fichiers (`documentsui`), Chrome, Appareil photo, VPN.

Applis du constructeur désactivées aussi : `com.unowhy.sqoolclasse`, `com.unowhy.sqool.sqpa`,
`com.unowhy.projection`, `com.hisqool.authentication`. Le MDM `com.hisqool.devicemanager` refuse
(`Cannot disable a protected package` : profile owner) ; il peut réactiver les autres, à surveiller.

```bash
for p in com.google.android.apps.docs com.google.android.apps.maps com.google.android.apps.photos \
  com.google.android.apps.tachyon com.google.android.apps.nbu.files com.google.android.calendar \
  com.google.android.contacts com.google.android.deskclock com.google.android.calculator com.google.android.gm \
  com.google.android.keep com.google.android.videos com.google.android.feedback com.google.android.apps.turbo \
  com.google.android.as com.google.android.apps.restore com.google.android.gms.location.history \
  com.google.android.printservice.recommendation com.google.android.contactkeys com.google.android.verifier \
  com.android.egg com.android.soundrecorder com.android.dreams.basic com.android.wallpaper.livepicker \
  com.android.bips com.debug.loggerui; do
  adb shell pm disable-user --user 0 $p
done
```

## Ce que ça change par rapport à la 4.2

- **Une image = 17,9 ms** (56 Hz) au lieu de 18,2 ms.
- **Puissance** : 8 cœurs dont 2 gros, 8 Go de RAM, tas de 256 Mo. Les limites de la 4.2 (rien de lourd sur
  le fil principal, pas de flou) ne bloquent plus ici, mais `master` reste soumise à la 4.2.
- **Écran hdpi** : même hauteur en dp que la 4.2, 53 dp de plus en largeur ; les mises en page XML
  devraient tenir. Les images et icônes en mdpi sont agrandies ×1,5 et paraissent floues : vectoriel ou
  ressources `hdpi`. Les captures font 2000 × 1200 px.
- **Cibles tactiles** : 48 dp = 72 px, environ 8 mm sur cet écran (225 pixels par pouce réels), comme sur la 4.2.
- **WebView 153** : les interdits CSS de Chrome 81 (`gap`, `inset`, `:is()`, `aspect-ratio`, `:has()`,
  `@container`) ne concernent pas cette tablette.
- **Encodage vidéo** : à relever (la limite de 720p de la 4.2 ne s'applique probablement pas).
- **Pas de capteur de luminosité** : pas d'atténuation selon la pièce, seulement l'horaire 22 h – 7 h de
  `device/Screen`.
- **Pas de vibreur** : comme la 4.2.
- **Android 14** : Mate vise déjà `targetSdk 34`, mais n'a jamais tourné en Android 14. À vérifier :
  Bluetooth (`BLUETOOTH_CONNECT`), notifications (`POST_NOTIFICATIONS`), services de premier plan typés,
  commande vocale, caméra, installation d'APK par la carte Mises à jour.
- **Pas de DuraSpeed** : l'étape correspondante de `docs/kiosque.md` est sans objet.
- **Kiosque** : pas tenté. Le MDM est le même que sur la 4.2 et s'efface avec une réinitialisation ; sans
  l'agent de provisionnement de la 4.2 dans l'image, il ne devrait pas revenir seul, mais il peut encore
  revenir par l'enrôlement Google ou le Play Store. Ne pas réinitialiser cette tablette avant d'en savoir plus.

## Reste à relever

- [ ] Installer Mate en debug et regarder chaque écran.
- [ ] Encodeurs vidéo matériels (`adb shell dumpsys media.player` ou `media_codecs.xml`).
- [ ] Sorties et entrées son (`dumpsys media.audio_policy` n'a rien rendu en Android 14).
- [ ] Batterie : capacité et recharge.
- [ ] Bande WiFi utilisée, version Bluetooth.
- [ ] Températures sous charge longue (au repos, branchée en USB : CPU et GPU 39 °C, batterie 22 °C).
- [ ] Comportement du MDM après une réinitialisation hors ligne.
