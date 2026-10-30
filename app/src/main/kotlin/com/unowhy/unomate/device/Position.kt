package com.unowhy.unomate.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Position de la tablette, pour trouver sa ville (météo) : à la ville près, rien de plus. Ordre : une position récente
 * déjà connue du système, puis le WiFi (quelques secondes ; passe par les services Google de la tablette), puis le GPS
 * (posée sur un bureau, la tablette capte mal les satellites). En kiosque, l'autorisation est accordée d'office
 * (net/Wifi.kt) ; sans elle, ou sans service de localisation, null : on choisit sa ville à la main.
 */
object Position {
    /** Plus ancienne, la tablette a pu changer de ville (rangée, emportée). */
    private const val RECENT_MS = 6 * 3_600_000L
    private const val NETWORK_TIMEOUT_MS = 20_000L
    private const val GPS_TIMEOUT_MS = 45_000L

    @SuppressLint("MissingPermission") // vérifiée juste avant
    suspend fun find(context: Context): Location? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val enabled = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { it in enabled }
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { now - it.time < RECENT_MS }
            .maxByOrNull { it.time }
            ?.let { return it }
        for ((provider, timeout) in listOf(LocationManager.NETWORK_PROVIDER to NETWORK_TIMEOUT_MS, LocationManager.GPS_PROVIDER to GPS_TIMEOUT_MS)) {
            if (provider in enabled) single(manager, provider, timeout)?.let { return it }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private suspend fun single(manager: LocationManager, provider: String, timeoutMs: Long): Location? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            // Android 7 à 10 : les quatre méthodes sont à écrire (elles n'ont de version par défaut qu'à partir d'Android 11).
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    manager.removeUpdates(this)
                    if (cont.isActive) cont.resume(location)
                }

                @Deprecated("Android 7 à 10 l'appellent encore")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            cont.invokeOnCancellation { manager.removeUpdates(listener) }
            runCatching { manager.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }
}
