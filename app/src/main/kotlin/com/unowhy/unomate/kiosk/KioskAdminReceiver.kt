package com.unowhy.unomate.kiosk

import android.app.admin.DeviceAdminReceiver

/**
 * Récepteur d'administration : permet de rendre l'appli "device owner" via
 * `adb shell dpm set-device-owner com.unowhy.unomate/.kiosk.KioskAdminReceiver`.
 */
class KioskAdminReceiver : DeviceAdminReceiver()
