package app.mate.ui.home

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import app.mate.Prefs
import app.mate.R
import app.mate.ui.dp
import app.mate.ui.themeColor
import app.mate.ui.themeRes

/** Réglages > Thème > Widgets de l'Accueil : comment la carte d'un widget se pose sur le fond d'écran (#214). */
enum class HomeCardStyle(val label: Int) {
    /** Un léger voile du ton de la carte, sans filet (par défaut). */
    VEIL(R.string.home_card_veil),

    /** La carte du thème, filet retiré. */
    FLAT(R.string.home_card_flat),

    /** Ni fond ni filet ; l'appui du thème allume un halo. */
    BARE(R.string.home_card_bare),
}

/**
 * Fond des widgets de l'Accueil : la tuile du thème (`c_home_card_bg`) passée au style choisi. Le rayon reste celui du
 * thème. Une carte de la couleur du fond (Contraste élevé) n'existe que par son filet : elle reste telle quelle.
 */
object HomeCard {
    /** Opacité du voile au repos, sur `c_surface` (0x6B : 42 %). */
    const val VEIL_ALPHA = 0x6B

    /** Style appliqué, ou `null` quand la tuile du thème est gardée (carte de la couleur du fond). */
    fun look(style: HomeCardStyle, bg: Int, surface: Int): HomeCardStyle? = style.takeIf { surface != bg }

    /**
     * La tuile du thème, filet retiré partout ; Voile et À même le fond ne changent que sa forme au repos. L'appui garde
     * celle du thème (halo rose de Hush, translucide du Rétro).
     */
    fun background(context: Context): Drawable {
        val tile = ContextCompat.getDrawable(context, themeRes(context, R.attr.c_home_card_bg))!!.mutate()
        val surface = themeColor(context, R.attr.c_surface)
        val style = look(Prefs.homeCardStyle(context), themeColor(context, R.attr.c_bg), surface) ?: return tile
        val shapes = (tile.constantState as? DrawableContainer.DrawableContainerState)?.children?.filterIsInstance<GradientDrawable>()
            ?: listOfNotNull(tile as? GradientDrawable)
        shapes.forEach { it.setStroke(0, Color.TRANSPARENT) }
        tile.state = intArrayOf()
        val rest = tile.current as? GradientDrawable ?: return tile
        // Forme au repos sans coins (Hush) : le rayon de l'appui, pour que le voile ait la forme du halo.
        if (rest.cornerRadius == 0f) rest.cornerRadius = shapes.maxOf { it.cornerRadius }
        when (style) {
            HomeCardStyle.FLAT -> Unit
            HomeCardStyle.VEIL -> rest.setColor(ColorUtils.setAlphaComponent(surface, VEIL_ALPHA))
            HomeCardStyle.BARE -> rest.setColor(Color.TRANSPARENT)
        }
        return tile
    }

    /** Pastilles des Réglages, une par style ; un choix recrée l'écran, comme le thème. */
    fun bindChoice(row: LinearLayout, recreate: () -> Unit) {
        val context = row.context
        val current = Prefs.homeCardStyle(context)
        for (style in HomeCardStyle.entries) {
            val chip = Button(context, null, 0, R.style.Chip).apply {
                setText(style.label)
                isSelected = style == current
                setOnClickListener {
                    if (style == current) return@setOnClickListener
                    Prefs.setHomeCardStyle(context, style)
                    recreate()
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(0, context.dp(48), 1f).apply { if (row.childCount > 0) marginStart = context.dp(10) })
        }
    }
}
