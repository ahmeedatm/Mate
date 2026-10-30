# Mode kiosque : préparer la tablette

But : la tablette ne fait tourner que Mate, toute la puissance pour l'appli.

## 1. Alléger la tablette (réversible)

```bash
adb shell pm list packages -3                        # applis installées en plus du système
adb shell pm disable-user --user 0 <package>         # désactiver (réversible : pm enable <package>)
# Animations du système coupées : gros gain de fluidité sur une tablette lente. Celles de l'appli, elle les règle
# elle-même : au démarrage, Mate remet animator_duration_scale à 1 (0 avec « Réduire les animations ») et chaque
# famille se coupe dans Réglages > Animations (device/Accessibility.applyAnimationScale, Prefs.animation).
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
# Écran allumé tant que la tablette est branchée
adb shell settings put global stay_on_while_plugged_in 7
# DuraSpeed (MediaTek) coupé : sinon il arrête Mate dès qu'il passe en arrière-plan
adb shell settings put system setting.duraspeed.enabled 0
adb shell settings put global setting.duraspeed.enabled 0
```

DuraSpeed est actif d'usine au niveau le plus fort (`dumpsys duraspeed config` : `PolicyLevel: 6`, nettoyage 5 min après l'extinction de l'écran, liste blanche vide). `dumpsys duraspeed addwhitelist` ne survit pas à un redémarrage, et la liste blanche durable ne se règle que depuis son écran de réglages : on le coupe. Vérifier : `adb shell dumpsys duraspeed status` → `false`, aussi après un redémarrage.

L'appli **Caméra** du système n'est plus utilisée (le mode Caméra passe par Camera2) : elle peut être désactivée comme les autres.
Ne pas désactiver Google Play Services / WebView sans tester.

## 2. Repartir d'une tablette propre (sans MDM du constructeur)

Le MDM du constructeur (`com.hisqool.devicemanager`) est installé dans `/data` : une réinitialisation
d'usine l'efface. Mais l'image contient l'**agent de provisionnement du constructeur** (`com.unowhy.sqoolota`, `/product/priv-app`,
UID système, droit `MANAGE_PROFILE_AND_DEVICE_OWNERS`) : dès qu'il a du réseau, il se met à jour
et réinstalle le MDM. Il faut donc que Mate soit device owner **avant** que cet agent ait le
temps d'agir : tout se fait **hors ligne**, le WiFi vient en dernier. Si le WiFi s'est connecté
quand même (réseau mémorisé), le couper aussitôt : `adb shell svc wifi disable`.

1. Paramètres > Système > Options de réinitialisation > Effacer toutes les données.
2. Assistant de démarrage : **ignorer le WiFi**, aucun compte Google, refuser les options.
3. Paramètres > À propos > appuyer 7 fois sur « Numéro de build », puis Options pour les
   développeurs > Débogage USB. Brancher en USB.
4. Vérifier, installer et passer Mate device owner (toujours hors ligne) :

```bash
adb shell dumpsys device_policy | grep -i owner      # rien : plus de MDM
adb shell dumpsys account | grep Accounts            # Accounts: 0
./gradlew installDebug
adb shell dpm set-device-owner app.mate/.kiosk.KioskAdminReceiver
# Accessibilité malgré le kiosque : TalkBack et son raccourci des touches de volume.
adb shell pm grant app.mate android.permission.WRITE_SECURE_SETTINGS
adb shell am start -n app.mate/app.mate.MainActivity
```

   **Accessibilité** : en kiosque, Paramètres est inaccessible, donc ni TalkBack ni la taille du texte ne se règlent dans Android. La permission accordée ci-dessus (seulement par adb : un device owner ne peut pas se la donner) permet à Mate de régler au démarrage le raccourci d'Android sur TalkBack (maintenir les deux touches de volume 3 secondes : utilisable sans voir l'écran, kiosque compris, sans la boîte de première utilisation) et d'activer TalkBack depuis Réglages > Accessibilité. La taille du texte se règle au même endroit (propre à l'appli, sans permission). Sans la permission, la carte le dit et le raccourci reste à régler à la main. Garder TalkBack (`com.google.android.marvin.talkback`, Android Accessibility Suite) : ne jamais le désactiver à l'étape suivante.

5. Désactiver les applis inutiles (réversible : `pm enable`). Garder l'appli Google (`com.google.android.googlequicksearchbox`) : elle fait la reconnaissance vocale du bouton Parler.

```bash
for p in com.android.vending com.android.chrome com.android.calculator2 com.android.contacts \
  com.android.protips com.android.soundrecorder com.android.wallpaper.livepicker \
  com.google.android.apps.docs com.google.android.apps.googleassistant com.google.android.apps.maps \
  com.google.android.apps.nbu.files com.google.android.apps.photos com.google.android.apps.tachyon \
  com.google.android.apps.youtube.music com.google.android.calendar com.google.android.deskclock \
  com.google.android.gm com.google.android.keep \
  com.google.android.videos com.google.android.youtube com.google.android.projection.gearhead \
  com.google.android.apps.wellbeing com.google.android.feedback; do
  adb shell pm disable-user --user 0 $p
done
```

   Garder : Launcher3 (secours hors kiosque), Google TTS, Play Services, WebView, TalkBack.

6. Brancher le WiFi seulement maintenant.

Une fois device owner, Mate **masque l'agent de provisionnement et le MDM du constructeur** (`setApplicationHidden` : invisibles,
inactifs, insensibles à « Réinitialiser les préférences des applis ») et **bloque toute installation et désinstallation d'appli**, adb
compris (restrictions `no_install_apps`, `no_install_unknown_sources`, `no_uninstall_apps`) :
même si quelque chose tentait de réinstaller le MDM, Android refuse. Android ajoute aussi
`no_add_managed_profile` (pas de profil professionnel, donc pas de MDM par ce biais).
La carte **Kiosque** de Réglages est cachée à l'utilisateur : appui long sur le titre « Réglages », puis le
**code technicien** (`4242` par défaut, `-PtechCode=…` à la compilation). Elle disparaît en quittant Réglages.
Micro et caméra sont accordés d'office (`setPermissionGrantState`) : en kiosque, un refus serait sans retour.
Heure et fuseau automatiques imposés à chaque passage en kiosque (`setGlobalSetting` AUTO_TIME / AUTO_TIME_ZONE) : une tablette sortie du stock a souvent une heure fausse.
Autres réglages d'Android de la carte Kiosque (code technicien), car Paramètres est inaccessible : **Bluetooth** (éteint d'usine ; `device/Bluetooth`, permissions `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_CONNECT`, l'appairage d'une enceinte se fait dans le réglage Bluetooth d'Android), **Paysage fixe** (`Prefs.landscapeLocked`, `Screen.applyOrientation` : un seul sens de paysage au lieu des deux du capteur), **Anti-sortie du kiosque** (`Kiosk.lockExit` : `no_factory_reset` et `no_safe_boot`, coupé par défaut, rétabli à chaque passage en kiosque, levé par « Retirer le mode kiosque » ; avec lui, la réinitialisation d'usine de la section 2 passe par le mode recovery matériel), **Menu du bouton marche/arrêt** (`Kiosk.applyLockTaskFeatures` : `LOCK_TASK_FEATURE_GLOBAL_ACTIONS`, coupé par défaut) et le bouton **Redémarrer** (`Kiosk.reboot`, avec confirmation ; le kiosque revient seul au démarrage).
Après une mise à jour de l'appli (adb ou Réglages > Mises à jour), Android l'arrête et le verrouillage avec elle : `kiosk/UpdatedReceiver` (`MY_PACKAGE_REPLACED`) la relance aussitôt (kiosque revenu en ~4 s). Pendant ces secondes, Android affichait son propre accueil (Launcher3), utilisable : en kiosque, Mate masque les accueils du système (`Kiosk.hideLaunchers`, sauf l'accueil de secours de `com.android.settings`, qui attend sur un écran vide) et les rend visibles quand le kiosque est suspendu ou retiré.
Pas d'écran de verrouillage après une mise en veille (`setKeyguardDisabled`, rétabli par « Retirer le mode kiosque »).

**Remettre à neuf** (carte Kiosque, `kiosk/FreshStart`, avec confirmation) : repart comme une tablette neuve, pour tester le premier lancement ou préparer une tablette pour un autre utilisateur. Efface les préférences (ordinateurs appairés, thème, écrans, réglages, eau), les photos du cadre, le cache et les données du WebView, puis relance l'appli dans un nouveau processus (une dizaine de secondes d'écran noir, puis l'écran Bienvenue). Restent : le kiosque (device owner et verrouillage, tenus par Android ; réglages Autoriser les installations, Anti-sortie, Menu marche/arrêt), le WiFi d'Android, les installeurs gardés pour les ordinateurs et le modèle du mot de réveil. `adb shell pm clear` ne marche pas ici : Android protège les données du device owner. L'ordinateur garde l'ancien appairage dans sa liste, sans effet : on réappaire avec un nouveau code.
Essai d'une PR : l'appli d'essai `app.mate.essai` (même clé) est autorisée en lock task avec Mate (`setLockTaskPackages`) et reçoit micro et caméra ; Mate l'installe (choix d'une PR dans la liste des essais de Réglages > Mises à jour, désinstallé d'abord sans confirmation si sa version est plus basse) et l'ouvre, elle se verrouille à son tour et rend la main par « Revenir à la stable ». Mate reste device owner : rien à refaire ici (voir [developpement.md](developpement.md), « Déployer un essai depuis une PR »). « Quitter le kiosque » retire aussi l'essai de la liste : il s'ouvre alors sans verrouillage.
La barre d'état étant masquée, heure, WiFi et batterie sont dans la barre latérale ; luminosité et volume dans Réglages > Tablette.

Pour mettre l'appli à jour : **Réglages > (code technicien) > Autoriser les installations**, `./gradlew installDebug`,
puis désactiver l'interrupteur (le réglage survit au redémarrage de l'appli).

Au prochain lancement, l'appli se verrouille à l'écran (lock task) et devient l'écran d'accueil.
Rien ne se passe tant que la commande `dpm set-device-owner` n'a pas été lancée.

## 3. En sortir

Carte Kiosque (code technicien, voir plus haut) :

- **Réglages > Quitter le kiosque** : déverrouille jusqu'au prochain démarrage de l'appli.
- **Réglages > Retirer le mode kiosque** : lève le blocage des installations et retire le statut device owner (à refaire via adb ensuite).
  ⚠️ Sur une tablette 4.2, l'agent de provisionnement du constructeur reprend la main à la seconde où Mate perd le device owner : il devient profile owner, même WiFi coupé, puis réinstalle le MDM du constructeur dès qu'il a du réseau. `dpm set-device-owner` devient impossible : seule une réinitialisation d'usine hors ligne en sort (constaté le 27/10/2026). Pour installer un APK signé avec une autre clé, ne pas passer par ici : réinitialiser hors ligne et reprendre la section 2. Vérifier d'abord la signature : `apksigner verify --print-certs` sur l'APK installé (`adb shell pm path app.mate`, puis `adb pull`) et sur le nouveau.
- En dernier recours : réinitialisation d'usine.
