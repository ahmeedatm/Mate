package app.mate.ui.home

import java.net.URI

/** Logique pure de la tuile « Page web » : adresse acceptée et rythme d'actualisation. */
object WebTile {
    const val DEFAULT_REFRESH_MIN = 5
    private const val MIN_REFRESH_MIN = 1
    private const val MAX_REFRESH_MIN = 60
    private const val MAX_URL = 2000
    private val SCHEMES = setOf("http", "https")

    // « hote:3000 » n'a pas de schéma : un deux-points suivi d'un chiffre est un port.
    private val HAS_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:(?!\\d)")

    /**
     * Adresse saisie ramenée à une URL http(s), ou null si on ne la charge pas : autre schéma (intent:, file:,
     * javascript:), identifiant ou mot de passe dans l'adresse (pas de secret sur la tablette), pas d'hôte.
     */
    fun normalize(input: String): String? {
        val text = input.trim()
        if (text.isEmpty() || text.length > MAX_URL) return null
        val full = if (HAS_SCHEME.containsMatchIn(text)) text else "https://$text"
        val uri = runCatching { URI(full) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in SCHEMES || uri.host.isNullOrBlank() || uri.userInfo != null) return null
        return scheme + full.substring(scheme.length)
    }

    /** Minutes entre deux rechargements, bornées ; null : valeur par défaut. */
    fun refreshMinutes(chosen: Int?) = (chosen ?: DEFAULT_REFRESH_MIN).coerceIn(MIN_REFRESH_MIN, MAX_REFRESH_MIN)
}
