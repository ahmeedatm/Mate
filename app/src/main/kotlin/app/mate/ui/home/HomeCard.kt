package app.mate.ui.home

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import app.mate.Prefs
import app.mate.R
import app.mate.ui.dp
import app.mate.ui.themeBool
import app.mate.ui.themeColor
import app.mate.ui.themeRes

/** Réglages > Thème > Widgets de l'Accueil : comment la carte d'un widget se pose sur le fond d'écran (#214). */
enum class HomeCardStyle(val label: Int) {
    /** « Cadre » : un léger voile du ton de la carte, sans filet (par défaut) ; le cadre de console du thème s'il en a un (Instrument). */
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

    /** Le filet du panneau est gardé : thème à filet (Instrument) et style Voile seulement ; « Sans filet » et « À même le fond » le retirent. */
    fun keepsOutline(themeOutline: Boolean, style: HomeCardStyle): Boolean = themeOutline && style == HomeCardStyle.VEIL

    /** Le cadre de console du thème (`c_home_card_frame`, 0 = aucun) se pose au choix Cadre seulement. */
    fun framed(style: HomeCardStyle, frame: Int): Boolean = frame != 0 && style == HomeCardStyle.VEIL

    /** Le cadre de console est posé dans le thème courant. */
    fun framed(context: Context): Boolean = framed(Prefs.homeCardStyle(context), themeRes(context, R.attr.c_home_card_frame))

    /** Marge intérieure d'une carte encadrée : le cadre (9 dp) mord sur la carte, le contenu garde au moins [FRAME_PAD_DP]. */
    fun padding(px: Int, framed: Boolean, min: Int): Int = if (framed) maxOf(px, min) else px

    /** Marge minimale sous le cadre de console : son épaisseur (9 dp) et 7 dp d'air. */
    const val FRAME_PAD_DP = 16

    /** Style appliqué dans le thème courant (`look` sur les couleurs du thème). */
    fun look(context: Context): HomeCardStyle? =
        look(Prefs.homeCardStyle(context), themeColor(context, R.attr.c_bg), themeColor(context, R.attr.c_surface))

    /** Plaque de la console de mixage (`c_mx_plate`) : retirée À même le fond, sinon son bord se voit sur le fond d'écran (#230). */
    fun plate(style: HomeCardStyle?, plate: Int): Int = if (style == HomeCardStyle.BARE) Color.TRANSPARENT else plate

    /** Rail des curseurs (`c_mx_slot`) : sans plaque, un rail de la couleur du fond (thèmes sombres) prend celle du filet. */
    fun slot(style: HomeCardStyle?, slot: Int, bg: Int, stroke: Int): Int = if (style == HomeCardStyle.BARE && slot == bg) stroke else slot

    /**
     * La tuile du thème, filet retiré partout ; Voile et À même le fond ne changent que sa forme au repos. L'appui garde
     * celle du thème (halo rose de Hush, translucide du Rétro). Un thème au panneau à filet (`c_home_card_outline`,
     * Instrument) garde son filet au Voile seulement : sur l'alu, le voile seul ne se verrait pas, mais « Sans filet » le retire.
     */
    fun background(context: Context): Drawable {
        val tile = tile(context)
        val frame = themeRes(context, R.attr.c_home_card_frame)
        if (!framed(Prefs.homeCardStyle(context), frame)) return tile
        // Le cadre par-dessus la tuile, y compris l'afficheur des widgets LCD (tuile gardée telle quelle par look).
        return LayerDrawable(arrayOf(tile, ContextCompat.getDrawable(context, frame)!!))
    }

    private fun tile(context: Context): Drawable {
        val tile = ContextCompat.getDrawable(context, themeRes(context, R.attr.c_home_card_bg))!!.mutate()
        val surface = themeColor(context, R.attr.c_surface)
        val style = look(context) ?: return tile
        val shapes = (tile.constantState as? DrawableContainer.DrawableContainerState)?.children?.filterIsInstance<GradientDrawable>()
            ?: listOfNotNull(tile as? GradientDrawable)
        val outline = keepsOutline(themeBool(context, R.attr.c_home_card_outline), style)
        if (!outline) shapes.forEach { it.setStroke(0, Color.TRANSPARENT) }
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
