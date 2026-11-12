package app.mate.ui.home

import java.util.Locale

/** Textes et grille des capuchons du widget Fenêtres en thème Instrument (« A · Patch de régie », #243). */
object WindowCaps {
    /** Un capuchon de fenêtre : largeur mini, et espace entre deux capuchons. */
    const val MIN_DP = 110
    const val GAP_DP = 6

    /** « CHROME · 2 » : l'appli en capitales et son nombre de fenêtres. */
    fun appLabel(name: String, windows: Int): String = "${name.uppercase(Locale.FRENCH)} · $windows"

    /** « ÉCRAN 1 · PRINCIPAL » : le LCD en tête d'un écran. */
    fun screenTitle(label: String, role: String): String = "${label.uppercase(Locale.FRENCH)} · ${role.uppercase(Locale.FRENCH)}"

    /** Colonnes de capuchons qui tiennent dans [widthPx] : autant que la largeur le permet, une au moins. */
    fun columns(widthPx: Int, density: Float): Int {
        val cell = (MIN_DP + GAP_DP) * density
        return ((widthPx + GAP_DP * density) / cell).toInt().coerceAtLeast(1)
    }
}
