package app.mate.share

/** Règles pures de « Partager » > Mate (ShareActivity) : noms de fichiers, liens, taille. */
object ShareLogic {
    /** Même plafond que l'ordinateur (backend/src/server.ts) : au-delà il refuse le fichier. */
    const val MAX_BYTES = 20L * 1024 * 1024

    private val EXTENSIONS = mapOf(
        "image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif", "image/webp" to "webp",
        "application/pdf" to "pdf", "text/plain" to "txt",
    )
    private val LINK = Regex("https?://\\S+")

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
