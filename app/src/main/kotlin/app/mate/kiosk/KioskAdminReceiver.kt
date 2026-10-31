package app.mate.kiosk

import android.app.admin.DeviceAdminReceiver

/**
 * Récepteur d'administration : permet de rendre l'appli "device owner" via
 * `adb shell dpm set-device-owner app.mate/.kiosk.KioskAdminReceiver`.
 * Seule classe restée dans l'ancien package après le renommage Mate : le device owner des tablettes déjà en kiosque
 * est ce composant (paquet + nom de classe) ; le déplacer leur ferait perdre le kiosque.
 */
class KioskAdminReceiver : DeviceAdminReceiver()
