package com.unowhy.unomate.installers

import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Petit serveur HTTP sans dépendance : `GET /` (page d'accueil, voir [InstallerPage]), `GET /installers.json` (liste lue
 * par le panneau de l'appli de l'ordinateur pour se mettre à jour) et `GET /<fichier>` pour les installeurs de
 * `available()`, rien d'autre. Chemins comparés aux noms annoncés : jamais de nom venu du client
 * ouvert tel quel. Pas de jeton : le contenu est public, la tablette ne garde aucun secret.
 */
class InstallerHttpServer(private val port: Int, private val dir: File, private val available: () -> List<Installer>) {

    @Volatile private var socket: ServerSocket? = null
    private val pool = ThreadPoolExecutor(0, MAX_CLIENTS, 30, TimeUnit.SECONDS, SynchronousQueue()) { r ->
        Thread(r, "installers-http").apply { isDaemon = true }
    }

    /** Port réellement écouté (`port` 0 = au choix du système, pour les tests). */
    val boundPort: Int? get() = socket?.localPort

    /** @return false si le port est déjà pris (l'autre appli UnoMate, ou une autre) : journal seulement. */
    fun start(): Boolean {
        if (socket != null) return true
        val server = try { ServerSocket(port) } catch (e: java.io.IOException) {
            Log.w(TAG, "Port $port indisponible, installeurs non servis", e)
            return false
        }
        socket = server
        Thread({
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: java.io.IOException) { break }
                try { pool.execute { handle(client) } } catch (e: RejectedExecutionException) { client.close() }
            }
        }, "installers-accept").apply { isDaemon = true }.start()
        return true
    }

    fun stop() {
        socket?.close()
        socket = null
    }

    private fun handle(client: Socket) {
        client.use {
            it.soTimeout = READ_TIMEOUT_MS
            it.keepAlive = true
            try {
                respond(it.getOutputStream(), parse(readHead(it.getInputStream())))
            } catch (e: java.io.IOException) {
                Log.i(TAG, "Client parti : ${e.message}")
            }
        }
    }

    private fun respond(out: OutputStream, request: Request?) {
        if (request == null) return reply(out, 400, "Bad Request", "Requête illisible.")
        if (request.method != "GET" && request.method != "HEAD") return reply(out, 405, "Method Not Allowed", "Méthode refusée.")
        val installers = available()
        val file = installers.firstOrNull { "/" + it.file == request.path }
        when {
            request.path == "/" || request.path == "/index.html" ->
                reply(out, 200, "OK", InstallerPage.render(InstallerPlan.osOf(request.userAgent), installers), head = request.method == "HEAD")
            request.path == "/installers.json" ->
                reply(out, 200, "OK", InstallerPlan.toJson(installers), head = request.method == "HEAD", type = "application/json")
            file != null -> sendFile(out, file, head = request.method == "HEAD")
            else -> reply(out, 404, "Not Found", "Introuvable.")
        }
    }

    private fun reply(
        out: OutputStream,
        code: Int,
        reason: String,
        text: String,
        head: Boolean = false,
        type: String = if (code == 200) "text/html" else "text/plain",
    ) {
        val body = text.toByteArray(Charsets.UTF_8)
        out.write(header(code, reason, "Content-Type: $type; charset=utf-8", body.size.toLong(), "Cache-Control: no-store").toByteArray())
        if (!head) out.write(body)
        out.flush()
    }

    private fun sendFile(out: OutputStream, installer: Installer, head: Boolean) {
        val file = File(dir, installer.file)
        val type = if (installer.os == Os.MAC) "application/zip" else "application/octet-stream"
        out.write(header(200, "OK", "Content-Type: $type", file.length(), "Content-Disposition: attachment; filename=\"${installer.file}\"").toByteArray())
        if (!head) file.inputStream().use { it.copyTo(out, 64 * 1024) }
        out.flush()
    }

    private fun header(code: Int, reason: String, type: String, length: Long, extra: String) =
        "HTTP/1.1 $code $reason\r\n$type\r\nContent-Length: $length\r\n$extra\r\nConnection: close\r\n\r\n"

    class Request(val method: String, val path: String, val userAgent: String?)

    internal companion object {
        const val TAG = "InstallerServer"
        const val MAX_CLIENTS = 8
        const val READ_TIMEOUT_MS = 10_000
        private const val MAX_HEAD = 16 * 1024

        /** Lignes de l'en-tête de requête, jusqu'à la ligne vide ; null si trop long ou fermé avant. */
        fun readHead(input: InputStream): List<String>? {
            val raw = StringBuilder()
            while (raw.length < MAX_HEAD) {
                val b = input.read()
                if (b < 0) return null
                raw.append(b.toChar())
                if (raw.endsWith("\r\n\r\n") || raw.endsWith("\n\n")) return raw.lines().filter { it.isNotEmpty() }
            }
            return null
        }

        /** « GET /chemin?x HTTP/1.1 » + en-têtes → méthode, chemin sans paramètres, `User-Agent`. */
        fun parse(head: List<String>?): Request? {
            val parts = head?.firstOrNull()?.split(" ") ?: return null
            if (parts.size != 3 || !parts[2].startsWith("HTTP/") || !parts[1].startsWith("/")) return null
            val agent = head.drop(1).firstOrNull { it.startsWith("user-agent:", ignoreCase = true) }?.substringAfter(':')?.trim()
            return Request(parts[0], parts[1].substringBefore('?'), agent)
        }
    }
}
