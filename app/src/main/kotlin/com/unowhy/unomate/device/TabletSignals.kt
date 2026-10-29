package com.unowhy.unomate.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager

/**
 * Minute, batterie et WiFi de la tablette, reçus une fois pour toute l'appli (le kiosque masque la barre d'état
 * d'Android) : MainActivity s'en sert pour ses tâches de chaque minute, les éléments du bandeau du haut pour s'afficher.
 * Ouvert par [start] (la batterie, diffusion persistante, arrive tout de suite), fermé par [stop].
 */
class TabletSignals(private val context: Context) {
    private val minuteListeners = mutableListOf<() -> Unit>()
    private val batteryListeners = mutableListOf<(Tablet.Battery) -> Unit>()
    private val wifiListeners = mutableListOf<() -> Unit>()

    /** Dernier état reçu : un élément ajouté au bandeau s'affiche sans attendre le prochain changement. */
    var battery: Tablet.Battery? = null
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> Tablet.battery(intent).let { state ->
                    battery = state
                    batteryListeners.toList().forEach { it(state) }
                }
                Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> minuteListeners.toList().forEach { it() }
                else -> wifiListeners.toList().forEach { it() }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            @Suppress("DEPRECATION") // suffisant pour un récepteur déclaré dans le code
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        context.registerReceiver(receiver, filter)
    }

    fun stop() = context.unregisterReceiver(receiver)

    fun onMinute(listener: () -> Unit) = minuteListeners.add(listener)

    fun onBattery(listener: (Tablet.Battery) -> Unit) = batteryListeners.add(listener)

    fun onWifi(listener: () -> Unit) = wifiListeners.add(listener)

    fun remove(listener: Any) {
        minuteListeners.remove(listener)
        batteryListeners.remove(listener)
        wifiListeners.remove(listener)
    }
}
