package app.mate.ui

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * Image fixe derrière tous les écrans (attribut c_wallpaper : Spider-Man, absent ailleurs : la vue reste masquée).
 * Le logo n'est net que si l'écran laisse libre le milieu (Accueil dont aucun widget ne traverse la colonne vide,
 * WidgetGrid) ; sinon l'image passe en filigrane ([DIM]) pour que les textes posés dessus restent lisibles.
 */
class Wallpaper(context: Context, attrs: AttributeSet? = null) : AppCompatImageView(context, attrs) {
    init {
        if (drawable == null) visibility = GONE
    }

    var centerFree = true
        set(value) {
            if (field == value) return
            field = value
            imageAlpha = if (value) 255 else (DIM * 255).toInt()
        }

    companion object {
        /** Opacité de l'image quand le milieu est occupé : 4,5:1 au moins sur le logo (SpiderManPaletteTest). */
        const val DIM = 0.3f
    }
}
