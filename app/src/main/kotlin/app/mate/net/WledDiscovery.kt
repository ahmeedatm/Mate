package app.mate.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * WLED du WiFi, trouvées par mDNS (`_wled._tcp`, NsdManager d'Android : pas de dépendance) puis lues (`/json/info`) et
 * retenues ([WledDevices]) : une WLED dont l'adresse IP change est retrouvée à sa MAC.
 */
object WledDiscovery {
    private const val SERVICE = "_wled._tcp"
    private const val LISTEN_MS = 4_000L
    private const val RESOLVE_MS = 3_000L

    /** Une recherche au plus tous les 10 minutes en tâche de fond (Accueil de Maison). */
    private const val EVERY_MS = 10 * 60_000L

    @Volatile private var lastRun = 0L

    /** Cherche si la dernière recherche date ; true si une WLED nouvelle ou déplacée a été retenue. */
    suspend fun refreshIfStale(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (lastRun != 0L && now - lastRun < EVERY_MS) return false
        lastRun = now
        return refresh(context)
    }

    /** Cherche maintenant (bouton des Réglages). @return true si la liste retenue a changé. */
    suspend fun refresh(context: Context): Boolean {
        val before = WledDevices.all(context)
        for (host in find(context)) {
            val device = runCatchingCancellable { WledApi.info(host) }.getOrNull() ?: continue
            WledDevices.save(context, device)
        }
        return WledDevices.all(context) != before
    }

    /** Adresses des WLED qui s'annoncent sur le WiFi. Vide si mDNS n'est pas disponible. */
    private suspend fun find(context: Context): List<String> {
        val nsd = context.applicationContext.getSystemService(NsdManager::class.java) ?: return emptyList()
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                found.trySend(info)
            }
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                found.close()
            }
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        }
        val services = mutableListOf<NsdServiceInfo>()
        try {
            nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
            withTimeoutOrNull(LISTEN_MS) { for (s in found) services += s }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        // Une résolution à la fois : avant l'API 34, NsdManager refuse d'en mener deux ensemble.
        return services.distinctBy { it.serviceName }.mapNotNull { resolve(nsd, it) }.distinct()
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): String? = withTimeoutOrNull(RESOLVE_MS) {
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onServiceResolved(service: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(service.host?.hostAddress?.takeIf { '.' in it })
                    }
                    override fun onResolveFailed(service: NsdServiceInfo, code: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        }
    }
}
