package app.mate.ui.home

import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import app.mate.R
import app.mate.ui.themeFont

/**
 * Briques visuelles du widget Valorant : couleurs d'équipe (turquoise pour la mienne, rouge en face, comme dans le jeu),
 * pastille teintée selon la valeur d'une stat, icône d'agent avec son rang, pastille de groupe. Les chiffres restent
 * écrits : la couleur ne fait que les juger d'un coup d'œil.
 */
object ValorantArt {
    /** Air entre deux colonnes. */
    const val GAP = 6

    /** Opacité des fonds teintés (pastilles, cases de résultat) : 22 %, le chiffre garde son contraste. */
    const val TINT = 0x38

    fun teamHue(ally: Boolean) = if (ally) R.attr.c_hue_cyan else R.attr.c_hue_red

    /** Bon, moyen ou faible : teinte de sens pour une stat (toujours avec son chiffre). */
    enum class Tone(val hue: Int) { GOOD(R.attr.c_ok), MID(R.attr.c_warn), LOW(R.attr.c_ko), NONE(R.attr.c_text_faint) }

    /** K/D : 1,2 et plus bon, sous 0,9 faible. */
    fun kdTone(kd: Double?) = when {
        kd == null -> Tone.NONE
        kd >= 1.2 -> Tone.GOOD
        kd >= 0.9 -> Tone.MID
        else -> Tone.LOW
    }

    /** % de tirs à la tête : 25 % et plus bon, sous 17 % faible. */
    fun hsTone(hs: Int?) = when {
        hs == null -> Tone.NONE
        hs >= 25 -> Tone.GOOD
        hs >= 17 -> Tone.MID
        else -> Tone.LOW
    }

    /** % de victoires de l'acte : 55 % et plus bon, sous 45 % faible. */
    fun wrTone(wr: Int?) = when {
        wr == null -> Tone.NONE
        wr >= 55 -> Tone.GOOD
        wr >= 45 -> Tone.MID
        else -> Tone.LOW
    }

    /** Place dans la partie (0 = premier) : le premier tiers est bon, le dernier faible. */
    fun rankTone(index: Int, count: Int) = when {
        index < 0 || count <= 1 -> Tone.MID
        index < count / 3 -> Tone.GOOD
        index >= count - count / 3 -> Tone.LOW
        else -> Tone.MID
    }

    /**
     * Pastille au fond teinté selon la valeur, chiffre en gros ; `tight` : moins haute (petites formes, dix lignes). Fond
     * opaque sous la teinte : la même couleur sur ma ligne et ailleurs. Second signe que la couleur : bon en gras, faible
     * en maigre (daltoniens, thèmes où vert et jaune se ressemblent).
     */
    fun chip(kit: ValorantKit, value: String, tone: Tone, width: Int, size: Float = 16f, tight: Boolean = false): View =
        kit.text(value, size, if (tone == Tone.NONE) R.attr.c_text_faint else R.attr.c_text, if (tone == Tone.LOW) R.attr.c_font_light else R.attr.c_font_medium).apply {
            gravity = Gravity.CENTER
            if (tone == Tone.GOOD) setTypeface(typeface, Typeface.BOLD)
            background = tinted(kit, tone.hue, if (tone == Tone.NONE) TINT / 3 else TINT)
            setPadding(0, kit.dp(if (tight) 2 else 6), 0, kit.dp(if (tight) 2 else 6))
            layoutParams = LinearLayout.LayoutParams(kit.dp(width), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = kit.dp(GAP) }
        }

    /** Fond arrondi : la surface opaque, puis la teinte par-dessus. */
    fun tinted(kit: ValorantKit, hue: Int, alpha: Int): Drawable {
        fun layer(color: Int) = GradientDrawable().apply {
            cornerRadius = kit.dp(6).toFloat()
            setColor(color)
        }
        return LayerDrawable(arrayOf(layer(kit.color(R.attr.c_surface_raised)), layer(ColorUtils.setAlphaComponent(kit.color(hue), alpha))))
    }

    /** Icône d'agent avec le logo du rang posé dans son coin bas. */
    fun face(kit: ValorantKit, agentId: String?, agent: String?, tier: Int?, size: Int, dim: Boolean = false): View = FrameLayout(kit.context).apply {
        val badge = size * 5 / 9
        addView(kit.agent(agentId, size, dim, agent), FrameLayout.LayoutParams(kit.dp(size), kit.dp(size)))
        addView(kit.rank(tier, badge), FrameLayout.LayoutParams(kit.dp(badge), kit.dp(badge), Gravity.BOTTOM or Gravity.END))
        layoutParams = LinearLayout.LayoutParams(kit.dp(size + badge / 3), kit.dp(size + badge / 4))
    }

    /**
     * Pastille ronde du groupe, avec son numéro (deux joueurs venus ensemble ont le même) : le chiffre suffit là où les
     * teintes du thème se ressemblent. Null pour un joueur seul.
     */
    fun partyDot(kit: ValorantKit, party: Int?): View? = ValorantKit.partyHue(party)?.let { hue ->
        // Chiffre de la couleur du fond : les teintes du thème sont faites pour contraster avec lui.
        kit.text(party.toString(), 12f, R.attr.c_bg, R.attr.c_font_title).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(kit.color(hue))
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(kit.dp(18), kit.dp(18)).apply { marginStart = kit.dp(6) }
        }
    }

    /** Titre d'équipe en capitales espacées, précédé d'une barre de la couleur de l'équipe (le texte garde son contraste). */
    fun teamTitle(kit: ValorantKit, title: Int, ally: Boolean): View {
        val bar = View(kit.context).apply {
            background = GradientDrawable().apply {
                cornerRadius = kit.dp(2).toFloat()
                setColor(kit.color(teamHue(ally)))
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val text = kit.caption(title).apply {
            setTextColor(kit.color(R.attr.c_text))
            typeface = themeFont(kit.context, R.attr.c_font_title)
            letterSpacing = 0.14f
            textSize = 13f
        }
        return kit.row(bar, text).apply { bar.layoutParams = LinearLayout.LayoutParams(kit.dp(4), kit.dp(14)).apply { marginEnd = kit.dp(6) } }
    }
}
