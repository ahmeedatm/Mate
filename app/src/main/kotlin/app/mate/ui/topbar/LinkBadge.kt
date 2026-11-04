package app.mate.ui.topbar

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.StringRes
import app.mate.R
import app.mate.net.Link
import app.mate.ui.Look
import app.mate.ui.dp
import app.mate.ui.home.Down
import app.mate.ui.linkMark
import app.mate.ui.themeColor
import app.mate.ui.themeFont

/** Ton de la pastille : discrète tant que l'ordinateur répond, voyante dès qu'il manque. */
internal enum class LinkTone(@AttrRes val dot: Int, @AttrRes val text: Int, @AttrRes val fill: Int?, @AttrRes val stroke: Int?) {
    OK(R.attr.c_ok, R.attr.c_text_dim, null, null),

    /** Premier essai (lancement, autre ordinateur) ou coupure de moins de [app.mate.ui.home.LinkWatch.GRACE_MS]. */
    WAIT(R.attr.c_text_dim, R.attr.c_text_dim, null, null),
    DOWN(R.attr.c_ko, R.attr.c_text, R.attr.c_ko_soft, R.attr.c_ko),
    REFUSED(R.attr.c_warn, R.attr.c_text, R.attr.c_surface_raised, R.attr.c_warn),
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
 * Le point garde la forme de la barre de gauche ([linkMark]) : l'état se lit sans la couleur. Toucher : changer d'ordinateur.
 */
class LinkBadge(context: Context) : LinearLayout(context) {
    private val dot = View(context)
    private val label = TextView(context).apply {
        textSize = 15f
        isSingleLine = true
        typeface = themeFont(context, R.attr.c_font_medium)
    }
    private var tone: LinkTone? = null

    init {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(14), 0)
        isClickable = true
        isFocusable = true
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        addView(dot, LayoutParams(dp(10), dp(10)))
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        visibility = GONE
    }

    fun show(link: Link, down: Down?) {
        val state = linkBadge(link.state, down)
        if (state == null) {
            visibility = GONE
            tone = null
            return
        }
        visibility = VISIBLE
        dot.setBackgroundResource(linkMark(link.state).shape.drawable)
        dot.background.mutate().setTint(themeColor(context, state.tone.dot))
        label.text = if (state.since != null) context.getString(state.text, state.since) else context.getString(state.text)
        label.setTextColor(themeColor(context, state.tone.text))
        background = state.tone.fill?.let { fill ->
            GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(themeColor(context, fill))
                state.tone.stroke?.let { setStroke(dp(1), themeColor(context, it)) }
            }
        }
        val spoken = link.computer?.name?.let { "$it, ${label.text}" } ?: label.text
        contentDescription = context.getString(R.string.link_badge_spoken, spoken)
        // Le ton change (connecté ↔ injoignable…) : la pastille gonfle une fois, pour qu'on le remarque.
        if (tone != null && tone != state.tone) Look.blip(this)
        tone = state.tone
    }
}
