package com.unowhy.companion.installers

/** Page d'accueil servie sur le WiFi par la tablette : l'installeur de l'OS du visiteur d'abord, l'autre en lien. */
object InstallerPage {

    private val STEPS = mapOf(
        Os.MAC to listOf(
            "Télécharge le zip, ouvre-le, puis ouvre « Compagnon Unowhy ».",
            "Si macOS refuse : Réglages Système > Confidentialité et sécurité, puis « Ouvrir quand même ».",
            "Accorde l'Accessibilité quand l'appli la demande (Réglages Système > Confidentialité et sécurité > Accessibilité).",
            "La tablette trouve l'ordinateur : touche-le et saisis le code affiché.",
        ),
        Os.WINDOWS to listOf(
            "Télécharge et lance l'installeur (pas besoin d'être administrateur).",
            "Si SmartScreen bloque : « Informations complémentaires », puis « Exécuter quand même ».",
            "La tablette trouve l'ordinateur : touche-le et saisis le code affiché.",
        ),
    )

    private val NAMES = mapOf(Os.MAC to "Mac", Os.WINDOWS to "PC Windows")

    private const val STYLE = "body{font:18px/1.5 system-ui,sans-serif;max-width:640px;margin:40px auto;padding:0 20px;color:#1c1b1f}" +
        "a.dl{display:block;margin:16px 0;padding:18px;border-radius:14px;background:#3b5bdb;color:#fff;text-align:center;text-decoration:none;font-weight:600}" +
        ".card{margin:24px 0;padding:4px 20px 12px;border:1px solid #ddd;border-radius:14px}.wait{padding:16px;border-radius:14px;background:#fff4d6}"

    /** `visitor` : OS détecté (null = inconnu, tout est présenté à égalité). `available` : installeurs téléchargés. */
    fun render(visitor: Os?, available: List<Installer>): String {
        val body = StringBuilder("<h1>Compagnon Unowhy</h1><p>Installe le compagnon sur ton ordinateur : la tablette le pilote.</p>")
        if (available.isEmpty()) {
            body.append("<p class=\"wait\">Les installeurs ne sont pas encore sur la tablette : elle les télécharge sur le WiFi. Reviens dans quelques minutes.</p>")
        } else {
            val first = available.firstOrNull { it.os == visitor }
            val shown = if (first != null) listOf(first) else available
            if (visitor != null && first == null) {
                body.append("<p class=\"wait\">L'installeur ${NAMES.getValue(visitor)} n'est pas encore sur la tablette.</p>")
            }
            shown.forEach { body.append(card(it)) }
            val others = available - shown.toSet()
            if (others.isNotEmpty()) {
                body.append("<p>Autre ordinateur : ")
                body.append(others.joinToString(" · ") { "<a href=\"/${esc(it.file)}\">${NAMES.getValue(it.os)} (${esc(it.file)})</a>" })
                body.append("</p>")
            }
        }
        return "<!doctype html><html lang=\"fr\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Compagnon Unowhy</title>" +
            "<style>$STYLE</style></head><body>$body</body></html>"
    }

    private fun card(i: Installer): String {
        val steps = STEPS.getValue(i.os).joinToString("") { "<li>${esc(it)}</li>" }
        return "<div class=\"card\"><h2>Pour ton ${NAMES.getValue(i.os)}</h2>" +
            "<a class=\"dl\" href=\"/${esc(i.file)}\">Télécharger ${esc(i.file)} (${megabytes(i.size)} Mo)</a><ol>$steps</ol></div>"
    }

    private fun megabytes(size: Long) = (size + 500_000) / 1_000_000

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
