package app.mate.ui.home

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.mate.R
import app.mate.net.BackendClient
import app.mate.ui.IconCache
import app.mate.ui.dp
import app.mate.ui.themeColor
import app.mate.ui.themeFont
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Morceaux communs du widget Valorant : icône d'agent et logo de rang (servis par l'ordinateur, gardés par [IconCache]),
 * textes à chiffres alignés, chiffres au format français, couleur du trait de groupe.
 */
class ValorantKit(val context: Context, private val scope: CoroutineScope) {
    fun dp(value: Int) = context.dp(value)
    fun color(attr: Int) = themeColor(context, attr)
    fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    /** Texte sur une ligne, coupé par « … » ; chiffres de même largeur (les colonnes restent droites). */
    fun text(size: Float, colorAttr: Int = R.attr.c_text, font: Int = R.attr.c_font_regular) = TextView(context).apply {
        textSize = size
        setTextColor(color(colorAttr))
        typeface = themeFont(context, font)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        fontFeatureSettings = "tnum"
    }

    fun text(value: String, size: Float, colorAttr: Int = R.attr.c_text, font: Int = R.attr.c_font_regular) =
        text(size, colorAttr, font).apply { text = value }

    /** Étiquette de colonne : petite, en capitales espacées, discrète. */
    fun caption(id: Int, gravity: Int = Gravity.START) = text(12f, R.attr.c_text_dim).apply {
        setText(id)
        isAllCaps = true
        letterSpacing = 0.06f
        this.gravity = gravity
    }

    /**
     * Icône carrée de l'agent ; `id` null : case vide (pas encore choisi). `dim` : survolé mais pas verrouillé, estompé.
     * Pendant le téléchargement, un carré de la couleur du relief tient la place.
     */
    fun agent(id: String?, size: Int, dim: Boolean = false, label: String? = null): View = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        background = GradientDrawable().apply {
            cornerRadius = dp(size / 6).toFloat()
            if (id == null) setStroke(dp(1), color(R.attr.c_stroke)) else setColor(color(R.attr.c_surface_raised))
        }
        alpha = if (dim) DIM_ALPHA else 1f
        contentDescription = label
        importantForAccessibility = if (label == null) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_YES
        layoutParams = ViewGroup.LayoutParams(dp(size), dp(size))
        if (id != null) {
            val cached = IconCache.peekAgent(id)
            if (cached != null) setImageBitmap(cached) else scope.launch { IconCache.loadAgent(BackendClient(context), id)?.let(::setImageBitmap) }
        }
    }

    /** Logo du rang ; le nom du rang est dans `contentDescription` (et dit à voix haute), la couleur ne porte pas le sens seule. */
    fun rank(tier: Int?, size: Int): View = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = rankName(tier)
        layoutParams = ViewGroup.LayoutParams(dp(size), dp(size))
        val t = tier ?: 0
        val cached = IconCache.peekRank(t)
        if (cached != null) setImageBitmap(cached) else scope.launch { IconCache.loadRank(BackendClient(context), t)?.let(::setImageBitmap) }
    }

    /** Nom du rang d'un tier Riot (0 à 2 non classé, 3 à 26 par trois, 27 Radiant), comme `rankName` du compagnon. */
    fun rankName(tier: Int?): String = when {
        (tier ?: 0) >= RADIANT -> string(R.string.valorant_radiant)
        else -> valorantRankName(context.resources.getStringArray(R.array.valorant_divisions).toList(), tier) ?: string(R.string.valorant_unranked)
    }

    /** Trait vertical de la couleur du groupe (deux joueurs venus ensemble ont le même) ; rien pour un joueur seul. */
    fun partyBar(party: Int?, height: Int = ViewGroup.LayoutParams.MATCH_PARENT): View = View(context).apply {
        val hue = partyHue(party)
        background = hue?.let { GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(color(it)) } }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(PARTY_BAR_DP), height)
    }

    /** Ligne horizontale (enfants alignés au centre). */
    fun row(vararg children: View, gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        this.gravity = gravity
        children.forEach { addView(it) }
    }

    fun column(vararg children: View, gravity: Int = Gravity.START) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        this.gravity = gravity
        children.forEach { addView(it) }
    }

    /** Vue posée dans un FrameLayout au coin `gravity` (logo de rang sur l'icône d'agent). */
    fun overlay(base: View, badge: View, gravity: Int, baseSize: Int, badgeSize: Int) = FrameLayout(context).apply {
        addView(base, FrameLayout.LayoutParams(dp(baseSize), dp(baseSize)))
        addView(badge, FrameLayout.LayoutParams(dp(badgeSize), dp(badgeSize), gravity))
        layoutParams = ViewGroup.LayoutParams(dp(baseSize + badgeSize / 3), dp(baseSize + badgeSize / 3))
    }

    fun space(width: Int) = View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(width), 1) }

    fun lp(width: Int, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) = LinearLayout.LayoutParams(width, height, weight)

    /** Fond de « ma » ligne : la teinte douce de l'accent, et l'accent lui-même au bord (repère qui ne tient pas qu'à la couleur). */
    fun mine(view: View) {
        view.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(color(R.attr.c_accent_soft))
        }
    }

    companion object {
        const val DIM_ALPHA = 0.4f
        const val PARTY_BAR_DP = 3
        private const val RADIANT = 27

        /** Teintes de groupe, dans l'ordre des numéros ; au-delà de 4 groupes, elles recommencent. */
        private val PARTY_HUES = listOf(R.attr.c_hue_cyan, R.attr.c_hue_orange, R.attr.c_hue_violet, R.attr.c_hue_yellow)

        fun partyHue(party: Int?): Int? = party?.takeIf { it > 0 }?.let { PARTY_HUES[(it - 1) % PARTY_HUES.size] }

        /** K/D à deux décimales, à la française ; « — » si pas encore lu. */
        fun kd(value: Double?): String = value?.let { String.format(Locale.FRANCE, "%.2f", it) } ?: DASH

        fun pct(value: Int?): String = value?.let { "$it %" } ?: DASH

        fun number(value: Int?): String = value?.toString() ?: DASH

        /** RR gagnés ou perdus, avec le signe (moins typographique). */
        fun signed(value: Int): String = if (value > 0) "+$value" else if (value < 0) "−${-value}" else "0"

        /** Nom du rang, ou null pour non classé et Radiant (textes à part). */
        fun valorantRankName(divisions: List<String>, tier: Int?): String? {
            if (tier == null || tier < 3 || tier >= RADIANT) return null
            return "${divisions[(tier - 3) / 3]} ${(tier - 3) % 3 + 1}"
        }

        /** Avance dans le rang (0 à 1) ; null pour Immortel et Radiant (le RR n'y plafonne pas à 100) et sans RR. */
        fun rrProgress(tier: Int, rr: Int?): Float? = if (rr == null || tier >= IMMORTAL) null else (rr.coerceIn(0, 100) / 100f)

        const val DASH = "—"
        private const val IMMORTAL = 24
    }
}
