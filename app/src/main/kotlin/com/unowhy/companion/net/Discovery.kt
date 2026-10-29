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

/** Ordinateur qui a répondu à la recherche sur le WiFi. */
data class FoundComputer(val id: String, val name: String, val os: String, val url: String)

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
                val j = runCatching { JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8)) }.getOrNull() ?: continue
                if (j.optString("service") != "unowhy-companion" || !j.has("id")) continue
                val host = packet.address.hostAddress ?: continue
                // HTTPS (`tlsPort`) en WiFi ; un ancien compagnon n'en a pas : HTTP, refusé ensuite (à mettre à jour).
                val url = if (j.optInt("tlsPort") > 0) "https://$host:${j.getInt("tlsPort")}" else "http://$host:${j.optInt("port", port)}"
                found[j.getString("id")] = FoundComputer(j.getString("id"), j.optString("name", host), j.optString("os"), url)
            }
        }
        found.values.toList()
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
