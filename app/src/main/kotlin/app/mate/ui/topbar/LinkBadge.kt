package app.mate.ui.topbar

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import app.mate.R
import app.mate.net.Link
import app.mate.ui.Look
import app.mate.ui.dp
import app.mate.ui.home.Down
import app.mate.ui.themeColor
import app.mate.ui.themeFont

/**
 * Ton de la pastille : discrète tant que l'ordinateur répond, voyante dès qu'il manque. Chaque ton a sa forme de point :
 * l'état se lit sans la couleur (plein, demi-disque, croix, anneau).
 */
internal enum class LinkTone(
    @DrawableRes val shape: Int,
    @AttrRes val dot: Int,
    @AttrRes val text: Int,
    @AttrRes val fill: Int?,
    @AttrRes val stroke: Int?,
) {
    OK(R.drawable.dot, R.attr.c_ok, R.attr.c_text_dim, null, null),

    /** Premier essai (lancement, autre ordinateur) ou coupure de moins de [app.mate.ui.home.LinkWatch.GRACE_MS]. */
    WAIT(R.drawable.dot_wait, R.attr.c_text_dim, R.attr.c_text_dim, null, null),
    DOWN(R.drawable.dot_cross, R.attr.c_ko, R.attr.c_text, R.attr.c_ko_soft, R.attr.c_ko),
    REFUSED(R.drawable.dot_ring, R.attr.c_warn, R.attr.c_text, R.attr.c_surface_raised, R.attr.c_warn),
}

/** Ce que dit la pastille : son ton, son texte (`since` : heure du début de la coupure) ; null : rien à montrer. */
internal data class LinkBadgeState(val tone: LinkTone, @StringRes val text: Int, val since: String? = null)

/** Sans ordinateur, l'écran d'accueil couvre tout : pas de pastille. */
internal fun linkBadge(state: Link.State, down: Down?): LinkBadgeState? = when (state) {
    Link.State.NONE -> null
    Link.State.ONLINE -> LinkBadgeState(LinkTone.OK, R.string.link_badge_online)
    Link.State.UNAUTHORIZED -> LinkBadgeState(LinkTone.REFUSED, R.string.link_badge_refused)
    Link.State.OFFLINE -> when {
        down == null -> LinkBadgeState(LinkTone.WAIT, R.string.link_badge_wait)
        down.since() != null -> LinkBadgeState(LinkTone.DOWN, R.string.link_badge_down_since, down.since())
        else -> LinkBadgeState(LinkTone.DOWN, R.string.link_badge_down)
    }
}

/**
 * Pastille de connexion du bandeau du haut, toujours à sa place (hors des éléments choisis, jamais sautée) : point vert
 * et « Ordi connecté » quand l'ordinateur répond ; pastille rouge « Ordi injoignable depuis HH:MM » quand il manque.
 * Toucher : changer d'ordinateur (zone tactile de 48 dp, le dessin en fait 36).
 */
class LinkBadge(context: Context) : LinearLayout(context) {
    private val dot = View(context)
    private val label = TextView(context).apply {
        textSize = 15f
        isSingleLine = true
        maxWidth = dp(260)
        ellipsize = TextUtils.TruncateAt.END
        typeface = themeFont(context, R.attr.c_font_medium)
    }

    /** Dernier état posé : `show` est rappelé toutes les 5 s pendant une coupure, sans rien changer. */
    private var shown: Pair<LinkBadgeState?, String?>? = null

    init {
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        }
        addView(dot, LayoutParams(dp(10), dp(10)))
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        setPadding(dp(12), 0, dp(14), 0)
        visibility = GONE
    }

    fun show(link: Link, down: Down?) {
        val state = linkBadge(link.state, down)
        val key = state to link.computer?.name
        if (key == shown && (state == null || visibility == VISIBLE)) return
        val before = shown?.first?.tone
        shown = key
        if (state == null) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        dot.setBackgroundResource(state.tone.shape)
        dot.background.mutate().setTint(themeColor(context, state.tone.dot))
        label.text = if (state.since != null) context.getString(state.text, state.since) else context.getString(state.text)
        label.setTextColor(themeColor(context, state.tone.text))
        // Pastille de 36 dp dessinée dans une vue de 48 dp : la zone tactile est celle de la vue.
        background = state.tone.fill?.let { fill ->
            val pill = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(themeColor(context, fill))
                state.tone.stroke?.let { setStroke(dp(1), themeColor(context, it)) }
            }
            InsetDrawable(pill, 0, dp(6), 0, dp(6))
        }
        // Poser un fond remplace le remplissage par celui du fond.
        setPadding(dp(12), 0, dp(14), 0)
        val spoken = link.computer?.name?.let { "$it, ${label.text}" } ?: label.text
        contentDescription = context.getString(R.string.link_badge_spoken, spoken)
        // Le ton change (connecté ↔ injoignable…) : la pastille gonfle une fois, pour qu'on le remarque.
        if (before != null && before != state.tone) Look.blip(this)
    }
}
