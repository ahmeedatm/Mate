package app.mate.ui.home

/**
 * Deux doigts posés sur un même widget l'ouvrent en grand ([WidgetGrid]) ; posés sur le widget agrandi, ils le rendent
 * à sa taille ([WidgetZoom]). Contrairement à un double toucher, rien ne part chez le widget : le premier doigt y est
 * annulé avant d'être levé. Testé par `TwoFingerZoomTest`.
 */
object TwoFingerZoom {
    /** Le widget à agrandir : celui sous les deux doigts s'ils sont sur le même, null sinon (ou si aucun ne s'y prête). */
    fun <T : Any> target(first: T?, second: T?): T? = first?.takeIf { it == second }
}
