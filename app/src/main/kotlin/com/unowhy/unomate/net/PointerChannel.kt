package com.unowhy.unomate.net

import android.content.Context
import android.util.Log
import com.unowhy.unomate.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.max

/** Bornes du compagnon (backend/src/pointer.ts) : au-delà, le paquet est ignoré, donc on découpe. */
const val POINTER_MAX_MOVE = 2000
const val POINTER_MAX_SCROLL = 50

/** Un envoi au pavé : déplacement en pixels ou défilement en crans. */
sealed class PointerMessage {
    data class Move(val dx: Int, val dy: Int) : PointerMessage()
    data class Scroll(val sx: Int, val sy: Int) : PointerMessage()
}

/** Où envoyer : l'ordinateur actif. `viaUsb` : adb reverse sur 127.0.0.1, il n'y a pas de socket UDP de ce côté. */
data class PointerTarget(val host: String, val port: Int, val viaUsb: Boolean) {
    companion object {
        fun parse(url: String): PointerTarget {
            val uri = URI(url)
            val host = (uri.host ?: url.substringAfter("://").substringBefore('/').substringBeforeLast(':')).trim('[', ']')
            val port = if (uri.port > 0) uri.port else 80
            return PointerTarget(host, port, host == "127.0.0.1" || host == "::1" || host.equals("localhost", ignoreCase = true))
        }
    }
}

/** Partie pure : format des paquets et découpage. */
object PointerPackets {
    const val HEADER = "UNOWHY-POINTER\n"

    /**
     * `UNOWHY-POINTER\n<signature>\n<seq>\n<json>`, signature = HMAC-SHA256 de `<seq>\n<json>` par le jeton : l'UDP
     * n'est pas chiffré, le jeton n'y passe jamais (même calcul que `pointerSignature` du backend, src/pointer.ts).
     */
    fun encode(token: String, seq: Long, message: PointerMessage): ByteArray {
        val json = when (message) {
            is PointerMessage.Move -> """{"dx":${message.dx},"dy":${message.dy}}"""
            is PointerMessage.Scroll -> """{"sx":${message.sx},"sy":${message.sy}}"""
        }
        return "$HEADER${signature(token, seq, json)}\n$seq\n$json".toByteArray(Charsets.UTF_8)
    }

    /** Vide sans jeton (ordinateur en USB sans jeton : rien à vérifier, comme en HTTP). */
    fun signature(token: String, seq: Long, json: String): String {
        if (token.isEmpty()) return ""
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
        return mac.doFinal("$seq\n$json".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** Coupe (a, b) en morceaux dont aucune composante ne dépasse `max`, dans le même sens. */
    fun split(a: Int, b: Int, max: Int, make: (Int, Int) -> PointerMessage): List<PointerMessage> {
        val n = max(chunks(a, max), chunks(b, max))
        var ra = a
        var rb = b
        return (0 until n).map {
            val ca = ra.coerceIn(-max, max)
            val cb = rb.coerceIn(-max, max)
            ra -= ca
            rb -= cb
            make(ca, cb)
        }
    }

    private fun chunks(v: Int, max: Int) = ((abs(v.toLong()) + max - 1) / max).toInt()
}

/** Cumule les déplacements et défilements entre deux envois ; sûr entre threads. */
class PointerCoalescer {
    private var dx = 0
    private var dy = 0
    private var sx = 0
    private var sy = 0

    @Synchronized fun addMove(x: Int, y: Int) {
        dx += x
        dy += y
    }

    @Synchronized fun addScroll(x: Int, y: Int) {
        sx += x
        sy += y
    }

    /** Ce qui est cumulé, découpé selon les bornes, puis remis à zéro. Déplacements avant défilements. */
    @Synchronized fun drain(): List<PointerMessage> {
        val out = ArrayList<PointerMessage>()
        if (dx != 0 || dy != 0) out += PointerPackets.split(dx, dy, POINTER_MAX_MOVE) { a, b -> PointerMessage.Move(a, b) }
        if (sx != 0 || sy != 0) out += PointerPackets.split(sx, sy, POINTER_MAX_SCROLL) { a, b -> PointerMessage.Scroll(a, b) }
        dx = 0
        dy = 0
        sx = 0
        sy = 0
        return out
    }
}

/**
 * Envoi du pavé tactile à l'ordinateur actif. Déplacements et défilement : cumulés puis envoyés au plus toutes les
 * [FLUSH_MS] ms, en UDP (quelques octets, aucune réponse attendue) ; en HTTP si l'ordinateur est en 127.0.0.1 (USB, pas
 * d'UDP) ou si l'envoi UDP lève une erreur. Clics et boutons : toujours en HTTP, dans l'ordre, après ce qui est déjà
 * cumulé. Tout part d'un seul fil (Dispatchers.IO) ; les appels (`move`, `click`…) ne bloquent pas.
 */
class PointerChannel(private val target: PointerTarget, private val token: String, private val api: PointerApi) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val coalescer = PointerCoalescer()
    private val ops = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private var seq = 0L
    private var http = target.viaUsb

    init {
        scope.launch { run() }
    }

    fun move(dx: Int, dy: Int) = coalescer.addMove(dx, dy)

    fun scroll(sx: Int, sy: Int) = coalescer.addScroll(sx, sy)

    fun click(button: PointerButton) = enqueue { api.button(button, "click") }

    fun button(button: PointerButton, down: Boolean) = enqueue { api.button(button, if (down) "down" else "up") }

    /** Geste de pavé moderne : HTTP, fiable, dans l'ordre après ce qui est déjà cumulé. */
    fun gesture(gesture: PointerGesture) = enqueue { api.gesture(gesture) }

    /** Arrête l'envoi et ferme le socket ; ce qui est cumulé et pas parti est abandonné. */
    fun close() {
        ops.close()
        scope.cancel()
        socket?.close()
        socket = null
    }

    private fun enqueue(op: suspend () -> Unit) {
        ops.trySend(op)
    }

    private suspend fun run() {
        var last = System.nanoTime()
        while (scope.isActive) {
            val received = withTimeoutOrNull(FLUSH_MS) { ops.receiveCatching() }
            if (received != null && received.isClosed) return
            val op = received?.getOrNull()
            if (op != null) {
                // Un clic part après les déplacements déjà cumulés.
                flush()
                guarded(op)
            }
            val now = System.nanoTime()
            if (op == null || now - last >= FLUSH_MS * 1_000_000) {
                flush()
                last = now
            }
        }
    }

    private suspend fun flush() {
        val messages = coalescer.drain()
        if (messages.isEmpty()) return
        // Mesure de latence : durée d'envoi de ce paquet de déplacements (débogage seulement).
        val start = System.nanoTime()
        for (message in messages) guarded { send(message) }
        Log.d("Pad", "flush " + messages.size + " en " + (System.nanoTime() - start) / 1000 + " µs")
    }

    private suspend fun send(message: PointerMessage) {
        if (!http) {
            try {
                val s = socket ?: DatagramSocket().also { socket = it }
                val a = address ?: InetAddress.getByName(target.host).also { address = it }
                val bytes = PointerPackets.encode(token, ++seq, message)
                s.send(DatagramPacket(bytes, bytes.size, a, target.port))
                return
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                http = true
            }
        }
        when (message) {
            is PointerMessage.Move -> api.move(message.dx, message.dy)
            is PointerMessage.Scroll -> api.scroll(message.sx, message.sy)
        }
    }

    /** Un envoi raté (réseau, ordinateur injoignable) est perdu : le suivant repart, rien ne s'arrête. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Perdu ; le détail n'a rien à faire dans le journal.
        }
    }

    companion object {
        const val FLUSH_MS = 16L

        /** Canal vers l'ordinateur actif (adresse et jeton de Prefs). */
        fun create(context: Context): PointerChannel =
            PointerChannel(PointerTarget.parse(Prefs.backendUrl(context)), Prefs.token(context), BackendClient(context).pointer)
    }
}
