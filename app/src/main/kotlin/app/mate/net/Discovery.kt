package app.mate.net

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

/**
 * Ordinateur qui a répondu à la recherche sur le WiFi ; `outdated` : compagnon d'avant le HTTPS, à mettre à jour ;
 * `waiting` : sa page de Mate est affichée, il attend une tablette depuis `waitingMs`.
 */
data class FoundComputer(
    val id: String,
    val name: String,
    val os: String,
    val url: String,
    val outdated: Boolean = false,
    val waiting: Boolean = false,
    val waitingMs: Long = Long.MAX_VALUE,
)

/**
 * Recherche des ordinateurs du compagnon sur le réseau local : diffusion UDP "MATE?" sur le port
 * du backend, chaque backend répond avec son identité (voir backend/src/pairing.ts). Pas de permission requise.
 */
object Discovery {
    private const val PROBE = "MATE?"
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
        if (j.optString("service") != "mate" || !j.has("id")) return null
        val tlsPort = j.optInt("tlsPort")
        val url = if (tlsPort > 0) "https://$host:$tlsPort" else "http://$host:${j.optInt("port", port)}"
        return FoundComputer(
            j.getString("id"), j.optString("name", host), j.optString("os"), url, outdated = tlsPort <= 0,
            waiting = j.optBoolean("waiting"), waitingMs = j.optLong("waitingMs", Long.MAX_VALUE),
        )
    }

    /** L'ordinateur à appairer sans rien toucher : parmi ceux qui attendent une tablette, celui dont la page est ouverte depuis le moins longtemps. */
    fun pickWaiting(computers: List<FoundComputer>): FoundComputer? =
        computers.filter { it.waiting && !it.outdated }.minByOrNull { it.waitingMs }

    /** Marge sur `waitingMs` : une page rouverte repart de zéro, une page restée affichée ne recule jamais d'autant. */
    private const val RELEASE_SLACK_MS = 2_000L

    /**
     * Ordinateurs écartés après un échec d'appairage (`blocked` : id → dernier `waitingMs` vu, ou celui attendu à la fin
     * de la tentative). Un ordinateur sort de la liste seulement si sa page a changé : plus au premier plan, rouverte
     * (`waitingMs` reparti de zéro) ou plus de réponse. Un voisin qui répète « j'attends » sans jamais changer reste écarté.
     */
    fun stillBlocked(blocked: Map<String, Long>, found: List<FoundComputer>): Map<String, Long> =
        blocked.mapNotNull { (id, last) ->
            found.firstOrNull { it.id == id }
                ?.takeIf { it.waiting && it.waitingMs + RELEASE_SLACK_MS >= last }
                ?.let { id to maxOf(last, it.waitingMs) }
        }.toMap()

    /** L'ordinateur à appairer : `pickWaiting` hors ceux écartés. */
    fun pickToPair(computers: List<FoundComputer>, blocked: Map<String, Long>): FoundComputer? =
        pickWaiting(computers.filter { it.id !in blocked })

    /** Un ordinateur qui répond sans HTTPS : son compagnon est trop ancien, la tablette doit le dire au lieu de chercher. */
    fun outdatedAmong(computers: List<FoundComputer>): FoundComputer? = computers.firstOrNull { it.outdated }

    /** Adresses de diffusion des réseaux de la tablette (ex. 192.168.1.255) : certaines box ignorent 255.255.255.255. */
    private fun broadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .filter { it.address is Inet4Address }
            .mapNotNull { it.broadcast }
    }.getOrDefault(emptyList())
}
