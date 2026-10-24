package com.unowhy.companion.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import com.unowhy.companion.net.Wifi
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * État de la tablette que le kiosque cache (barre d'état masquée, pas de réglages rapides) :
 * batterie, WiFi, adresse. Lu à la demande, sans permission particulière.
 */
object Tablet {

    /** `level` en %, `charging` : branchée (secteur ou USB), `health` : BatteryManager.BATTERY_HEALTH_*. */
    data class Battery(val level: Int, val charging: Boolean, val plug: Int, val health: Int) {
        /** Batterie usée, trop chaude… : à signaler (les batteries des 4.2 vieillissent). */
        val healthIssue: Boolean get() = health != BatteryManager.BATTERY_HEALTH_GOOD && health != BatteryManager.BATTERY_HEALTH_UNKNOWN
        val low: Boolean get() = level <= LOW_LEVEL && !charging
    }

    const val LOW_LEVEL = 15

    /** Dernier état connu (diffusion persistante d'Android : pas besoin d'attendre un changement). */
    fun battery(context: Context): Battery? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::battery)

    fun battery(intent: Intent): Battery {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val plug = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return Battery((level * 100 / scale).coerceIn(0, 100), plug != 0, plug, intent.getIntExtra(BatteryManager.EXTRA_HEALTH, 0))
    }

    /** Force du signal WiFi de 0 à 4 ; null si pas de WiFi. */
    fun wifiLevel(context: Context): Int? {
        if (Wifi.current(context) == null) return null
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION") // calculateSignalLevel(int) : API 30
        return WifiManager.calculateSignalLevel(wifi.connectionInfo.rssi, 5)
    }

    /** Adresse IPv4 de la tablette sur le réseau local (utile pour le dépannage). */
    fun ipAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address }?.hostAddress
    }.getOrNull()
}
