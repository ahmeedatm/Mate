package com.unowhy.companion.ui.home

import android.content.Context
import android.widget.FrameLayout

/**
 * Une vue qui garde un geste de la grille des profils : [WidgetGrid] ne l'utilise pas (appui long = mode Modifier,
 * balayage = changer de profil) quand il commence sur elle.
 */
interface GestureOwner {
    /** La grille n'entre pas en mode Modifier à l'appui long qui commence ici. */
    val ownsLongPress: Boolean

    /** La grille ne change pas de profil au balayage qui commence ici. */
    val ownsSwipe: Boolean
}

open class OwnerFrameLayout(
    context: Context,
    override val ownsLongPress: Boolean = true,
    override val ownsSwipe: Boolean = false,
) : FrameLayout(context), GestureOwner

object GestureRules {
    /** Faux sur un curseur ou chez un propriétaire d'appui long. */
    fun allowLongPress(owner: GestureOwner?, onSlider: Boolean) = !onSlider && owner?.ownsLongPress != true

    /** Faux sur un curseur, chez un propriétaire de balayage, ou en mode étroit (un seul profil à la fois). */
    fun allowSwipe(owner: GestureOwner?, onSlider: Boolean, narrow: Boolean) = !narrow && !onSlider && owner?.ownsSwipe != true
}
