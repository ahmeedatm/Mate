package com.unowhy.companion.kiosk

import android.app.admin.DeviceAdminReceiver

/**
 * Récepteur d'administration : permet de rendre l'appli "device owner" via
 * `adb shell dpm set-device-owner com.unowhy.companion/.kiosk.KioskAdminReceiver`.
 * Seule classe restée dans l'ancien package après le renommage UnoMate : le device owner des tablettes déjà en kiosque
 * est ce composant (paquet + nom de classe) ; le déplacer leur ferait perdre le kiosque.
 */
class KioskAdminReceiver : DeviceAdminReceiver()
