# Mode kiosque : préparer la tablette

But : la tablette ne fait tourner que Companion, toute la puissance pour l'appli.

## 1. Alléger la tablette (réversible)

```bash
adb shell pm list packages -3                        # applis installées en plus du système
adb shell pm disable-user --user 0 <package>         # désactiver (réversible : pm enable <package>)
# Animations coupées : gros gain de fluidité sur une tablette lente
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
# Écran allumé tant que la tablette est branchée
adb shell settings put global stay_on_while_plugged_in 7
```

L'appli **Caméra** du système n'est plus utilisée (le mode Caméra passe par Camera2) : elle peut être désactivée comme les autres.
Ne pas désactiver Google Play Services / WebView sans tester.

## 2. Repartir d'une tablette propre (sans MDM SQOOL)

Le MDM SQOOL (`com.hisqool.devicemanager`) est installé dans `/data` : une réinitialisation
d'usine l'efface. Mais l'image contient **SqoolOTA** (`com.unowhy.sqoolota`, `/product/priv-app`,
UID système, droit `MANAGE_PROFILE_AND_DEVICE_OWNERS`) : dès qu'il a du réseau, il se met à jour
et réinstalle le MDM. Il faut donc que Companion soit device owner **avant** que SqoolOTA ait le
temps d'agir : tout se fait **hors ligne**, le WiFi vient en dernier. Si le WiFi s'est connecté
quand même (réseau mémorisé), le couper aussitôt : `adb shell svc wifi disable`.

1. Paramètres > Système > Options de réinitialisation > Effacer toutes les données.
2. Assistant de démarrage : **ignorer le WiFi**, aucun compte Google, refuser les options.
3. Paramètres > À propos > appuyer 7 fois sur « Numéro de build », puis Options pour les
   développeurs > Débogage USB. Brancher en USB.
4. Vérifier, installer et passer Companion device owner (toujours hors ligne) :

```bash
adb shell dumpsys device_policy | grep -i owner      # rien : plus de MDM
adb shell dumpsys account | grep Accounts            # Accounts: 0
./gradlew installDebug
adb shell dpm set-device-owner com.unowhy.companion/.kiosk.KioskAdminReceiver
adb shell am start -n com.unowhy.companion/.MainActivity
```

5. Désactiver les applis inutiles (réversible : `pm enable`) :

```bash
for p in com.android.vending com.android.chrome com.android.calculator2 com.android.contacts \
  com.android.protips com.android.soundrecorder com.android.wallpaper.livepicker \
  com.google.android.apps.docs com.google.android.apps.googleassistant com.google.android.apps.maps \
  com.google.android.apps.nbu.files com.google.android.apps.photos com.google.android.apps.tachyon \
  com.google.android.apps.youtube.music com.google.android.calendar com.google.android.deskclock \
  com.google.android.gm com.google.android.googlequicksearchbox com.google.android.keep \
  com.google.android.videos com.google.android.youtube com.google.android.projection.gearhead \
  com.google.android.apps.wellbeing com.google.android.feedback; do
  adb shell pm disable-user --user 0 $p
done
```

   Garder : Launcher3 (secours hors kiosque), Google TTS, Play Services, WebView.

6. Brancher le WiFi seulement maintenant.

Une fois device owner, Companion **masque SqoolOTA et le MDM** (`setApplicationHidden` : invisibles,
inactifs, insensibles à « Réinitialiser les préférences des applis ») et **bloque toute installation et désinstallation d'appli**, adb
compris (restrictions `no_install_apps`, `no_install_unknown_sources`, `no_uninstall_apps`) :
même si quelque chose tentait de réinstaller SQOOL, Android refuse. Android ajoute aussi
`no_add_managed_profile` (pas de profil professionnel, donc pas de MDM par ce biais).
La carte **Kiosque** de Réglages est cachée au client : appui long sur le titre « Réglages », puis le
**code technicien** (`4242` par défaut, `-PtechCode=…` à la compilation). Elle disparaît en quittant Réglages.
Micro et caméra sont accordés d'office (`setPermissionGrantState`) : en kiosque, un refus serait sans retour.
Heure et fuseau automatiques imposés à chaque passage en kiosque (`setGlobalSetting` AUTO_TIME / AUTO_TIME_ZONE) : une tablette sortie du stock a souvent une heure fausse.
Après une mise à jour de l'appli (adb ou Réglages > Mises à jour), Android l'arrête et le verrouillage avec elle : `kiosk/UpdatedReceiver` (`MY_PACKAGE_REPLACED`) la relance aussitôt (kiosque revenu en ~4 s).
Pas d'écran de verrouillage après une mise en veille (`setKeyguardDisabled`, rétabli par « Retirer le mode kiosque »).
La barre d'état étant masquée, heure, WiFi et batterie sont dans la barre latérale ; luminosité et volume dans Réglages > Tablette.

Pour mettre l'appli à jour : **Réglages > (code technicien) > Autoriser les installations**, `./gradlew installDebug`,
puis désactiver l'interrupteur (le réglage survit au redémarrage de l'appli).

Au prochain lancement, l'appli se verrouille à l'écran (lock task) et devient l'écran d'accueil.
Rien ne se passe tant que la commande `dpm set-device-owner` n'a pas été lancée.

## 3. En sortir

Carte Kiosque (code technicien, voir plus haut) :

- **Réglages > Quitter le kiosque** : déverrouille jusqu'au prochain démarrage de l'appli.
- **Réglages > Retirer le mode kiosque** : lève le blocage des installations et retire le statut device owner (à refaire via adb ensuite).
- En dernier recours : réinitialisation d'usine.
