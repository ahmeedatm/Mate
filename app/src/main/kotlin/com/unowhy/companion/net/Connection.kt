package com.unowhy.companion.net

import android.content.Context
import com.unowhy.companion.Computer
import com.unowhy.companion.Computers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** État de la liaison avec l'ordinateur actif (pastille de la barre latérale). */
data class Link(val computer: Computer?, val state: State) {
    enum class State { ONLINE, OFFLINE, UNAUTHORIZED, NONE }
}

/**
 * Choix de l'ordinateur joignable : l'actif s'il répond, sinon le même à une nouvelle adresse (trouvé sur le WiFi),
 * sinon un autre ordinateur enregistré qui répond (rentré à la maison : le PC de la maison prend le relais).
 */
object Connection {

    sealed class Probe {
        class Ok(val computer: Computer) : Probe()
        object Unauthorized : Probe()
        /** Un autre ordinateur répond à cette adresse (adresse réattribuée sur un autre réseau). */
        object Mismatch : Probe()
    }

    /** Lève une exception si l'ordinateur ne répond pas. */
    suspend fun probe(context: Context, computer: Computer, connectTimeoutMs: Int = 5_000): Probe {
        val identity = try {
            BackendClient(computer.url, computer.token, connectTimeoutMs).pairing.identity()
        } catch (e: HttpException) {
            if (e.code == 401) return Probe.Unauthorized else throw e
        }
        var current = computer
        if (identity.id != null && identity.id != computer.id) {
            if (!computer.provisional) return Probe.Mismatch
            current = Computers.adopt(context, computer, identity.id, identity.name, identity.os)
        }
        return if (identity.auth) Probe.Ok(current) else Probe.Unauthorized
    }

    /**
     * Ordinateur à piloter maintenant (devenu actif), ou null si aucun ne répond.
     * `allowSwitch` faux : seulement l'actif (à son adresse, ou à la nouvelle trouvée sur le WiFi).
     */
    suspend fun resolve(context: Context, allowSwitch: Boolean = true): Computer? = coroutineScope {
        if (Computers.all(context).isEmpty()) return@coroutineScope null
        // Adresses à jour d'après le WiFi (DHCP : l'IP d'un ordinateur change d'un jour à l'autre).
        val found = runCatching { Discovery.find() }.getOrDefault(emptyList())
        for (f in found) {
            Computers.all(context).firstOrNull { it.id == f.id && it.url != f.url }?.let { Computers.save(context, it.copy(url = f.url)) }
        }
        val activeId = Computers.active(context)?.id
        val candidates = Computers.all(context).filter { allowSwitch || it.id == activeId }.sortedByDescending { if (it.id == activeId) Long.MAX_VALUE else it.lastUsedMs }
        val probes = candidates.map { c -> async { runCatching { probe(context, c, connectTimeoutMs = 2_000) }.getOrNull() } }.awaitAll()
        val ok = probes.filterIsInstance<Probe.Ok>().firstOrNull()?.computer ?: return@coroutineScope null
        Computers.setActive(context, ok.id)
        ok
    }
}
