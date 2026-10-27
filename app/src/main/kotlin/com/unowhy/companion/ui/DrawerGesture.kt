package com.unowhy.companion.ui

/** Règles du glisser depuis le bord gauche (menu Cadran), en dp et dp/s. Valeurs de docs/ui-ux/da/accueil.html. */
object DrawerGesture {
    /** Course du panneau : 300 de large + 12 de marge + 18 pour qu'il sorte entièrement. */
    const val WIDTH_DP = 330f

    /** Part ouverte (0 à 1) après un déplacement de `dx` depuis le bord. */
    fun progress(dx: Float, width: Float = WIDTH_DP): Float = (dx / width).coerceIn(0f, 1f)

    /**
     * Au lâcher : ouvert si le toucher est un simple appui, passé 40 % ou lancé à plus de 600 dp/s ; jamais si le
     * doigt part vers la gauche (plus de 300 dp/s).
     */
    fun shouldOpen(progress: Float, velocityX: Float): Boolean =
        (progress < 0.02f || progress > 0.4f || velocityX > 600f) && velocityX > -300f
}
