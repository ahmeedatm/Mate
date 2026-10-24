package com.unowhy.companion.kiosk

import android.app.admin.DeviceAdminReceiver

/**
 * Récepteur d'administration : permet de rendre l'appli "device owner" via
 * `adb shell dpm set-device-owner com.unowhy.companion/.kiosk.KioskAdminReceiver`.
 */
class KioskAdminReceiver : DeviceAdminReceiver()
