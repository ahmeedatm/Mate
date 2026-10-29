package com.unowhy.companion.device

import android.bluetooth.BluetoothAdapter

/**
 * Bluetooth malgré le kiosque (Paramètres inaccessible) : éteint d'usine sur la tablette 4.2, il faut pourtant le
 * rallumer pour une enceinte. Permissions BLUETOOTH et BLUETOOTH_ADMIN (normales, accordées à l'installation).
 * `enable`/`disable` sont dépréciées depuis Android 13 ; la cible est Android 10.
 */
@Suppress("DEPRECATION")
object Bluetooth {

    private fun adapter(): BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    fun available(): Boolean = adapter() != null

    /** Allumé, ou en train de s'allumer (l'adaptateur met quelques secondes à répondre). */
    fun isOn(): Boolean = adapter()?.let { it.isEnabled || it.state == BluetoothAdapter.STATE_TURNING_ON } ?: false

    /** Demande l'allumage ou l'extinction ; false si pas de Bluetooth ou si Android refuse. */
    fun set(on: Boolean): Boolean {
        val adapter = adapter() ?: return false
        return try {
            if (on) adapter.enable() else adapter.disable()
        } catch (_: SecurityException) {
            false
        }
    }
}
