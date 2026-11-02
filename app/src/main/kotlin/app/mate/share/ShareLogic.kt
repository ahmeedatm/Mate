package app.mate.share

import java.io.InputStream
import java.io.OutputStream

/** Règles pures de « Partager » > Mate (ShareActivity) : URI acceptées, nombre, noms de fichiers, liens, taille. */
object ShareLogic {
    /** Même plafond que l'ordinateur (backend/src/server.ts) : au-delà il refuse le fichier. */
    const val MAX_BYTES = 20L * 1024 * 1024

    /** Fichiers au plus par envoi : le reste est refusé (message), pour ne pas remplir le cache ni l'ordinateur. */
    const val MAX_FILES = 10

    private const val OWN_AUTHORITY = "app.mate"

    /** Fin du flux dépassée : le fichier fait plus de [MAX_BYTES]. */
    class TooLarge : Exception()

    private val EXTENSIONS = mapOf(
        "image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif", "image/webp" to "webp",
        "application/pdf" to "pdf", "text/plain" to "txt",
    )
    private val LINK = Regex("https?://\\S+")

    /**
     * Une autre appli donne un `content://` ; ni `file://` (lirait un fichier de Mate), ni les fournisseurs de Mate
     * (`app.mate…`, essai compris : l'autorité peut porter un numéro d'utilisateur, `10@app.mate…`).
     */
    fun acceptsUri(scheme: String?, authority: String?): Boolean {
        if (scheme == null || authority == null || !scheme.equals("content", ignoreCase = true)) return false
        return !authority.substringAfterLast('@').startsWith(OWN_AUTHORITY, ignoreCase = true)
    }

    /** Garde les [MAX_FILES] premiers ; rend aussi le nombre refusé. */
    fun <T> limitCount(items: List<T>): Pair<List<T>, Int> = items.take(MAX_FILES) to (items.size - MAX_FILES).coerceAtLeast(0)

    /** Copie au plus [max] octets ; en lit un de plus, pas davantage, puis lève [TooLarge] sans attendre la fin du flux. */
    fun copyBounded(input: InputStream, output: OutputStream, max: Long = MAX_BYTES): Long {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), max - total + 1).toInt())
            if (n < 0) return total
            total += n
            if (total > max) throw TooLarge()
            output.write(buffer, 0, n)
        }
    }

    fun fits(size: Long): Boolean = size <= MAX_BYTES

    /** Nom du fichier tel que l'appli l'annonce, sans dossier ni caractère qui casserait l'envoi ; sinon `Partage <date>.<ext>`. */
    fun fileName(displayName: String?, mimeType: String?, stamp: String): String {
        val clean = displayName.orEmpty()
            .replace(Regex("[\"\\r\\n]"), "")
            .substringAfterLast('/').substringAfterLast('\\')
            .trim()
        if (clean.isNotEmpty() && clean != "." && clean != "..") return clean
        val ext = EXTENSIONS[mimeType]
        return if (ext == null) "Partage $stamp" else "Partage $stamp.$ext"
    }

    fun isLink(text: String?): Boolean = text != null && LINK.containsMatchIn(text)

    fun linkFileName(stamp: String): String = "Lien $stamp.txt"
}
