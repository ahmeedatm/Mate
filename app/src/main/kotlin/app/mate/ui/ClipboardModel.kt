package app.mate.ui

// Presse-papiers partagé : logique pure de l'écran, sans vue (testable sur la JVM).

private val LINK = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

/**
 * Texte montré à l'écran : coupé à `max` caractères puis « … ». Le texte complet reste celui qu'on copie sur la
 * tablette ; ici on évite seulement de dessiner un document entier.
 */
fun displayText(text: String, max: Int = 10_000): String {
    if (text.length <= max) return text
    // Ne coupe pas une paire de substitution (emoji) en deux.
    val end = if (max > 0 && text[max - 1].isHighSurrogate()) max - 1 else max
    return text.substring(0, end) + "…"
}

/** Masque d'un texte secret de l'historique : toujours la même longueur, pour ne pas trahir celle du code. */
const val SECRET_MASK = "••••••••"

/** Ligne de l'historique : retours à la ligne et espaces répétés réduits à un espace, coupée à `max` caractères. */
fun historyLine(text: String, secret: Boolean, revealed: Boolean, max: Int = 200): String {
    if (secret && !revealed) return SECRET_MASK
    return displayText(text.trim().replace(Regex("\\s+"), " "), max)
}

/** Le texte entier est une seule URL http(s) (espaces autour ignorés) → l'URL, sinon null. */
fun linkOf(text: String): String? = text.trim().takeIf { LINK.matches(it) }
