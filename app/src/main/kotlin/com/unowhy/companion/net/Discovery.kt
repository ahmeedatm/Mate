package com.unowhy.companion.net

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** Ordinateur qui a répondu à la recherche sur le WiFi ; `outdated` : compagnon d'avant le HTTPS, à mettre à jour. */
data class FoundComputer(val id: String, val name: String, val os: String, val url: String, val outdated: Boolean = false)

/**
 * Recherche des ordinateurs du compagnon sur le réseau local : diffusion UDP "UNOWHY-COMPANION?" sur le port
 * du backend, chaque backend répond avec son identité (voir backend/src/pairing.ts). Pas de permission requise.
 */
object Discovery {
    private const val PROBE = "UNOWHY-COMPANION?"
    const val DEFAULT_PORT = 3000

    suspend fun find(timeoutMs: Long = 1_500, port: Int = DEFAULT_PORT): List<FoundComputer> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, FoundComputer>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 200
            val probe = PROBE.toByteArray()
            val targets = broadcastAddresses() + InetAddress.getByName("255.255.255.255")
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            var nextSend = 0L
            val buffer = ByteArray(2048)
            while (SystemClock.elapsedRealtime() < deadline) {
                // Renvoyée toutes les 500 ms : un paquet UDP peut se perdre en WiFi.
                if (SystemClock.elapsedRealtime() >= nextSend) {
                    targets.forEach { runCatching { socket.send(DatagramPacket(probe, probe.size, it, port)) } }
                    nextSend = SystemClock.elapsedRealtime() + 500
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val host = packet.address.hostAddress ?: continue
                val computer = parse(String(packet.data, 0, packet.length, Charsets.UTF_8), host, port) ?: continue
                found[computer.id] = computer
            }
        }
        found.values.toList()
    }

    /**
     * Réponse d'un ordinateur, null si ce n'en est pas une. HTTPS (`tlsPort`) en WiFi ; un compagnon d'avant le HTTPS
     * n'en a pas : il ne sait pas appairer cette tablette (`outdated`).
     */
    fun parse(reply: String, host: String, port: Int = DEFAULT_PORT): FoundComputer? {
        val j = runCatching { JSONObject(reply) }.getOrNull() ?: return null
        if (j.optString("service") != "unowhy-companion" || !j.has("id")) return null
        val tlsPort = j.optInt("tlsPort")
        val url = if (tlsPort > 0) "https://$host:$tlsPort" else "http://$host:${j.optInt("port", port)}"
        return FoundComputer(j.getString("id"), j.optString("name", host), j.optString("os"), url, outdated = tlsPort <= 0)
    }

    /** Adresses de diffusion des réseaux de la tablette (ex. 192.168.1.255) : certaines box ignorent 255.255.255.255. */
    private fun broadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .filter { it.address is Inet4Address }
            .mapNotNull { it.broadcast }
    }.getOrDefault(emptyList())
}
