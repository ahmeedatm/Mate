package app.mate.ui.home

import app.mate.net.HaEntity
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Contraste sous lequel la couleur d'une lampe se perd sur le fond de la case : l'icône garde alors l'accent. */
private const val MIN_TINT_CONTRAST = 2.0

private const val OPAQUE = -0x1000000

/** Luminosité (%) à écrire à côté de « Allumé » : seulement pour une lumière allumée qui la donne. */
internal fun haBrightness(e: HaEntity): Int? = e.light?.brightness?.takeIf { e.on && e.available }

/** Teinte de l'icône d'une lumière allumée : sa vraie couleur, sauf si elle se perd sur [surface] ; sinon [accent]. */
internal fun haTint(e: HaEntity, accent: Int, surface: Int): Int {
    val rgb = e.light?.rgb?.takeIf { e.on && e.available } ?: return accent
    val color = OPAQUE or rgb
    return if (contrast(color, surface) >= MIN_TINT_CONTRAST) color else accent
}

/** Encre ([a] ou [b]) la plus lisible sur [fill] : le chiffre posé sur la jauge, quelle que soit la couleur de la lampe. */
internal fun readableInk(fill: Int, a: Int, b: Int): Int = if (contrast(fill, a) >= contrast(fill, b)) a else b

/** Rapport de contraste WCAG entre deux couleurs opaques. */
private fun contrast(x: Int, y: Int): Double {
    val lx = luminance(x)
    val ly = luminance(y)
    return (max(lx, ly) + 0.05) / (min(lx, ly) + 0.05)
}

private fun luminance(color: Int): Double {
    fun channel(shift: Int): Double {
        val c = (color shr shift and 0xFF) / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}
