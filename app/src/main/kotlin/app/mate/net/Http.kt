package app.mate.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** Réponse 423 : ordinateur verrouillé (backend/src/screen-lock.ts). MainActivity y pose l'écran bloquant. */
object ComputerLocked {
    @Volatile var listener: (() -> Unit)? = null
}

/** `reason` : message de l'ordinateur, rédigé pour être affiché (voir ui/errorText). */
class HttpException(val code: Int, val reason: String) : IOException("HTTP $code $reason".trim())

/**
 * Plomberie HTTP partagée par les clients de domaine (DeckApi, SystemApi…). HttpURLConnection + org.json,
 * inclus dans Android, pour garder l'APK sans dépendance réseau.
 *
 * `trust` : certificat de l'ordinateur épinglé à l'appairage (HTTPS en WiFi) ; null pour un serveur à certificat
 * classique (mises à jour). Le jeton ne part jamais en HTTP en clair hors de la tablette elle-même (USB).
 *
 * `persona` : persona de la démo choisi sur la tablette, lu à chaque requête et envoyé en `X-Demo-Persona` ; sans lui,
 * l'ordinateur répond avec ses vraies données (backend/src/demo-mock.ts).
 */
class Http(
    private val baseUrl: String, private val token: String, private val connectTimeoutMs: Int,
    private val headers: Map<String, String> = emptyMap(),
    private val trust: PinnedTrust? = null,
    private val persona: () -> String? = { null },
) {
    private val cleartextRefused = token.isNotEmpty() && baseUrl.startsWith("http://") && !isLocalUrl(baseUrl)

    suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun get(path: String, timeoutMs: Int = 60_000): String = text(request("GET", path, timeoutMs = timeoutMs))

    fun delete(path: String): String = text(request("DELETE", path))

    fun postJson(path: String, body: JSONObject): String =
        text(request("POST", path, body.toString().toByteArray(), JSON))

    fun putJson(path: String, body: JSONObject): String =
        text(request("PUT", path, body.toString().toByteArray(), JSON))

    fun postFile(path: String, field: String, file: File, mimeType: String): String {
        val boundary = "----companion" + UUID.randomUUID().toString().replace("-", "")
        val head = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"$field\"; filename=\"${file.name}\"\r\n" +
            "Content-Type: $mimeType\r\n\r\n"
        val body = head.toByteArray() + file.readBytes() + "\r\n--$boundary--\r\n".toByteArray()
        return text(request("POST", path, body, "multipart/form-data; boundary=$boundary"))
    }

    /** Image ou fichier ; null sur 404 (l'ordinateur n'en a pas). */
    fun bytesOrNull(path: String, timeoutMs: Int = 60_000): ByteArray? =
        try { bytes(request("GET", path, timeoutMs = timeoutMs)) } catch (e: HttpException) { if (e.code == 404) null else throw e }

    fun text(conn: HttpURLConnection): String =
        try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }

    private fun bytes(conn: HttpURLConnection): ByteArray =
        try { conn.inputStream.use { it.readBytes() } } finally { conn.disconnect() }

    /** Ouvre la connexion et vérifie le code HTTP ; l'appelant lit puis déconnecte. */
    fun request(method: String, path: String, body: ByteArray? = null, contentType: String? = null, timeoutMs: Int = 60_000): HttpURLConnection {
        // DELETE sans corps : Android y met quand même un type de contenu, que Fastify refuse (415).
        if (method == "DELETE" && body == null) return request(method, path, "{}".toByteArray(), "application/json", timeoutMs)
        if (cleartextRefused) throw InsecureComputerException()
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) trust?.apply(conn)
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = timeoutMs
        if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
        headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
        persona()?.let { conn.setRequestProperty(PERSONA_HEADER, it) }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            val error = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            // Fastify : { error: "Internal Server Error", message: <la vraie raison> } ; nos erreurs : { error }.
            val message = runCatching { JSONObject(error).let { it.optString("message").ifEmpty { it.optString("error") } } }.getOrNull()
            if (code == 423) ComputerLocked.listener?.invoke()
            throw HttpException(code, message ?: error.take(200))
        }
        return conn
    }

    private companion object {
        const val JSON = "application/json; charset=utf-8"
        const val PERSONA_HEADER = "X-Demo-Persona"
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }
