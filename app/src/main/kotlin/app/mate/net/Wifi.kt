package app.mate.net

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import app.mate.R
import app.mate.UserFacingException
import app.mate.kiosk.Kiosk
import app.mate.kiosk.KioskAdminReceiver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * WiFi de la tablette, réglé depuis le Mate : en kiosque, Paramètres est inaccessible. Android 10 n'autorise
 * l'ajout de réseaux (API WifiConfiguration) qu'au device owner, ce qu'est le Mate en kiosque ; ailleurs
 * (émulateur, tablette pas encore en kiosque), Réglages ouvre le panneau WiFi du système.
 */
@Suppress("DEPRECATION")
object Wifi {

    /** `level` : 0 à 3 ; `saved` : réseau déjà enregistré (pas de mot de passe à retaper). */
    data class Network(val ssid: String, val secure: Boolean, val sae: Boolean, val level: Int, val saved: Boolean, val connected: Boolean)

    private fun manager(context: Context) = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun canConfigure(context: Context) = Kiosk.isDeviceOwner(context)

    /**
     * Avant de lister : WiFi allumé, permission de localisation accordée et localisation activée (Android ne donne
     * la liste des réseaux, et le nom du réseau actuel, qu'à cette condition). Le device owner se les accorde seul.
     */
    fun prepare(context: Context) {
        if (!canConfigure(context)) return
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, KioskAdminReceiver::class.java)
        runCatching {
            dpm.setPermissionGrantState(admin, context.packageName, Manifest.permission.ACCESS_FINE_LOCATION, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }
        if (!locationOn(context)) {
            runCatching { dpm.setSecureSetting(admin, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_HIGH_ACCURACY.toString()) }
        }
        val wifi = manager(context)
        if (!wifi.isWifiEnabled) wifi.isWifiEnabled = true
    }

    fun locationOn(context: Context) =
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF

    /**
     * Nom du réseau actuel ; "" si connecté sans pouvoir lire le nom (localisation coupée) ; null si pas de WiFi.
     * Connexion lue par ConnectivityManager : sans la localisation, WifiManager répond « pas de réseau » (networkId -1).
     */
    fun current(context: Context): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return null
        val ssid = manager(context).connectionInfo?.ssid?.removeSurrounding("\"")
        return ssid?.takeUnless { it == WifiManager.UNKNOWN_SSID.removeSurrounding("\"") || it == "<unknown ssid>" } ?: ""
    }

    /** Réseaux autour (un par nom, le meilleur signal), du plus fort au plus faible. Lance une recherche et l'attend (10 s au plus). */
    @SuppressLint("MissingPermission")
    suspend fun scan(context: Context): List<Network> {
        val app = context.applicationContext
        val wifi = manager(app)
        val done = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { done.complete(Unit) }
        }
        app.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        try {
            // Refusé si on a cherché trop souvent (Android limite à 4 recherches en 2 min) : on garde la dernière liste.
            if (wifi.startScan()) withTimeoutOrNull(10_000) { done.await() }
        } finally {
            app.unregisterReceiver(receiver)
        }
        val saved = savedIds(wifi).keys
        val current = current(app)
        return wifi.scanResults
            .filter { it.SSID.isNotBlank() && !it.capabilities.contains("EAP") }
            .groupBy { it.SSID }
            .map { (ssid, results) ->
                val best = results.maxByOrNull { it.level }!!
                val caps = best.capabilities
                Network(
                    ssid = ssid,
                    secure = caps.contains("WPA") || caps.contains("WEP") || caps.contains("SAE"),
                    sae = caps.contains("SAE") && !caps.contains("PSK"),
                    level = WifiManager.calculateSignalLevel(best.level, 4),
                    saved = ssid in saved,
                    connected = ssid == current,
                )
            }
            .sortedWith(compareByDescending<Network> { it.connected }.thenByDescending { it.level })
    }

    /** Réseaux enregistrés : nom → identifiant. */
    @SuppressLint("MissingPermission")
    private fun savedIds(wifi: WifiManager): Map<String, Int> =
        runCatching { wifi.configuredNetworks.orEmpty().associate { it.SSID.removeSurrounding("\"") to it.networkId } }.getOrDefault(emptyMap())

    /**
     * Se connecte à `ssid` : réseau enregistré sans `password` → on le réutilise ; sinon on l'enregistre (remplace
     * l'ancien). Attend la connexion (20 s au plus) : false si elle n'aboutit pas (mot de passe faux, trop loin).
     */
    suspend fun connect(context: Context, ssid: String, password: String?, sae: Boolean = false, hidden: Boolean = false): Boolean {
        val wifi = manager(context)
        val existing = savedIds(wifi)[ssid]
        val id = if (existing != null && password == null) {
            existing
        } else {
            existing?.let { wifi.removeNetwork(it) }
            val config = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                hiddenSSID = hidden
                when {
                    password == null -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    sae && Build.VERSION.SDK_INT >= 29 -> { allowedKeyManagement.set(WifiConfiguration.KeyMgmt.SAE); preSharedKey = "\"$password\"" }
                    else -> { allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK); preSharedKey = "\"$password\"" }
                }
            }
            wifi.addNetwork(config).also { if (it == -1) throw UserFacingException(R.string.wifi_refused) }
        }
        // `true` : on ne tente que ce réseau (les autres sont mis de côté le temps de l'essai).
        wifi.enableNetwork(id, true)
        wifi.reconnect()
        repeat(40) {
            delay(500)
            val info = wifi.connectionInfo
            if (info != null && info.networkId == id && info.supplicantState == SupplicantState.COMPLETED && info.ipAddress != 0) {
                restoreOthers(wifi)
                return true
            }
        }
        // Échec : un réseau tout juste ajouté est retiré (mauvais mot de passe), et la tablette revient sur les autres.
        if (existing == null || password != null) wifi.removeNetwork(id)
        restoreOthers(wifi)
        wifi.reconnect()
        return false
    }

    private fun restoreOthers(wifi: WifiManager) {
        savedIds(wifi).values.forEach { wifi.enableNetwork(it, false) }
        wifi.saveConfiguration()
    }

    /** Oublie un réseau enregistré. */
    fun forget(context: Context, ssid: String) {
        val wifi = manager(context)
        savedIds(wifi)[ssid]?.let { wifi.removeNetwork(it); wifi.saveConfiguration() }
    }
}
