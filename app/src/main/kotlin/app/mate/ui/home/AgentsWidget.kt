package app.mate.ui.home

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.mate.Prefs
import app.mate.R
import app.mate.agents.AgentsFeed
import app.mate.agents.sessionTitle
import app.mate.agents.shortList
import app.mate.agents.without
import app.mate.net.AgentState
import app.mate.ui.AgentStatusIcon
import app.mate.ui.AgentsView
import app.mate.ui.errorText
import app.mate.ui.stateColor
import app.mate.ui.stateLabel
import app.mate.ui.themeFont
import app.mate.ui.themeRes
import java.util.Locale

/**
 * Agents Claude. Pastille : le nombre de sessions qui attendent. Ligne : les compteurs (travaillent, surveillent,
 * attendent, finis) côte à côte ; Colonne : empilés, puis dès 1 × 3 la liste courte des sessions qui comptent ; 2 cases
 * de large (2 × 2, 2 × 3, 2 × 4) : compteurs et liste. Toucher ouvre le tableau en plein écran. À partir de 3 cases de
 * large et 2 de haut, le tableau lui-même ([AgentsView]) : ses colonnes défilent, le widget garde l'appui long et le
 * balayage ; sur 2 lignes seulement il se resserre (`compact`). Relu tant que la page de l'Accueil est à l'écran.
 */
class AgentsWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    /** Thème à fenêtre LCD (Instrument) : compteurs sur écran LCD, états par DEL, chiffres en Departure Mono (#243). Les autres thèmes gardent leur rendu. */
    private val instrument = KeyCaps.applies(themeRes(context, R.attr.c_lcd_overlay))
    private val counters = LinearLayout(context)
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val note = label(14f, R.attr.c_text_dim)
    private val board = AgentsView(context, instrument = instrument).apply { attach(host.owner) }
    private var observer: ((AgentsFeed.Snapshot) -> Unit)? = null
    private var last: AgentsFeed.Snapshot? = null
    private var active = false

    /** (affiché, plein écran) de l'abonnement en cours : un appel qui ne change rien ne relance pas de lecture. */
    private var followed: Pair<Boolean, Boolean>? = null

    /** Compteurs et liste d'un Agents M, L ou V. */
    private val summary = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(cardTitle(R.string.agents_title))
        val countersView = if (instrument) {
            LcdPanel(context).apply {
                setPadding(dp(10), dp(8), dp(10), dp(8))
                addView(counters, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        } else {
            counters
        }
        addView(countersView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        addView(note)
    }

    /** Pastille (1 × 1) : le nombre de sessions qui attendent, comme les autres widgets Dev ([DevGlanceView]). */
    private val pill = DevGlanceView(context, R.string.agents_title, digits = instrument)

    private val full get() = placed.size.w >= 3 && family != ShapeFamily.ROW
    private val small get() = family == ShapeFamily.PILL

    private val listRows get() = agentsListRows(placed.size)

    /** Aperçu du catalogue : l'exemple reste, sans abonnement. */
    private var previewing = false

    override val view = object : OwnerFrameLayout(context) {
        // Seul le tableau garde les gestes de la grille ; les compteurs laissent l'appui long au mode Modifier.
        override val ownsLongPress get() = full
        override val ownsSwipe get() = full
    }.asCard(16).apply {
        addView(summary, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(board, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(pill, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun applySize() {
        view.asCard(if (full) 12 else 16)
        summary.visibility = if (full || small) View.GONE else View.VISIBLE
        pill.visibility = if (small) View.VISIBLE else View.GONE
        if (full) {
            view.setOnClickListener(null)
            view.isClickable = false
        } else {
            view.setOnClickListener { host.overlays.agentsBoard(host.owner) }
        }
        board.compact = placed.size.h < 3
        counters.orientation = if (family == ShapeFamily.COLUMN) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        last?.let(::render)
        if (previewing) board.visibility = if (full) View.VISIBLE else View.GONE else follow()
    }

    override fun showPreview(sample: WidgetSample) {
        val state = (sample as? AgentsSample)?.state ?: return
        previewing = true
        last = AgentsFeed.Snapshot(state, null, System.currentTimeMillis())
        board.showSample(state)
        applySize()
    }

    override fun setActive(active: Boolean) {
        this.active = active
        follow()
    }

    /** Un seul abonné à la fois : le tableau dès 3 cases de large, les compteurs sinon ; aucun hors du profil affiché. */
    private fun follow() {
        if (followed == active to full) return
        followed = active to full
        observer?.let { AgentsFeed.unobserve(it) }
        observer = null
        board.setActive(active && full)
        if (!active || full) return
        val listener: (AgentsFeed.Snapshot) -> Unit = {
            last = it
            render(it)
        }
        observer = listener
        AgentsFeed.observe(context, listener)
    }

    private fun render(snapshot: AgentsFeed.Snapshot) {
        val state = snapshot.state?.without(Prefs.agentsHiddenProjects(context))
        counters.removeAllViews()
        list.removeAllViews()
        if (state == null) {
            note.text = context.errorText(snapshot.error)
            note.visibility = View.VISIBLE
            pill.show(null, 1, 1, note.text.toString())
            return
        }
        note.visibility = View.GONE
        val waiting = state.counts.waiting + state.counts.limit
        if (family == ShapeFamily.PILL) pill.show(GlanceContent(waiting, context.resources.getQuantityString(R.plurals.agent_count_waiting, waiting), emptyList()), 1, 1)
        counter(AgentState.WORKING, false, state.counts.working, R.plurals.agent_count_working)
        counter(AgentState.WORKING, true, state.counts.monitoring, R.plurals.agent_count_monitoring)
        counter(AgentState.WAITING, false, waiting, R.plurals.agent_count_waiting)
        counter(AgentState.DONE, false, state.counts.done + state.counts.idle, R.plurals.agent_count_done)
        if (listRows == 0) return
        val shown = shortList(state.sessions, listRows)
        if (shown.isEmpty()) {
            note.text = context.getString(R.string.agents_empty)
            note.visibility = View.VISIBLE
        }
        for (s in shown) {
            list.addView(
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(app.mate.ui.AgentStatusIcon(context).apply { if (instrument) asLed(); bind(s.state, s.monitoring) }, LinearLayout.LayoutParams(dp(16), dp(16)))
                    addView(
                        label(15f).apply { text = sessionTitle(s, Prefs.agentsPrivate(context)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) },
                    )
                    addView(label(13f).apply { text = stateLabel(context, s); setTextColor(app.mate.ui.sessionColor(context, s)) })
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) },
            )
        }
        state.quota?.let { q ->
            fun pct(w: app.mate.net.QuotaWindow?) = w?.let { context.getString(R.string.agent_quota_value, it.used) } ?: "?"
            list.addView(label(13f, R.attr.c_text_dim).apply { text = context.getString(R.string.agent_quota_line, pct(q.fiveHour), pct(q.sevenDay)); if (instrument) typeface = themeFont(context, R.attr.c_font_display) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        }
        val more = state.sessions.count { it.state != AgentState.IDLE } - shown.size
        if (more > 0) {
            list.addView(label(13f, R.attr.c_text_dim).apply { text = context.resources.getQuantityString(R.plurals.agents_more, more, more) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        }
    }

    /** Un compteur : icône de l'état (la roue tourne, l'écran pulse), nombre, libellé. */
    private fun counter(kind: AgentState, monitoring: Boolean, n: Int, text: Int) {
        val vertical = family == ShapeFamily.COLUMN
        val color = when {
            n == 0 -> color(R.attr.c_text_faint)
            monitoring -> color(R.attr.c_agent_monitor)
            else -> stateColor(context, kind)
        }
        counters.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    AgentStatusIcon(context).apply {
                        if (instrument) asLed(R.attr.c_mx_led_off)
                        bind(if (n == 0) AgentState.IDLE else kind, monitoring)
                    },
                    LinearLayout.LayoutParams(dp(16), dp(16)),
                )
                val size = if (listRows > 0 && !vertical) 26f else 20f
                addView(
                    (if (instrument) lcdText(context, size) else TextView(context).apply { textSize = size; setTextColor(color) }).apply {
                        this.text = String.format(Locale.getDefault(), "%d", n)
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) },
                )
                if (placed.size != WidgetSize.M) {
                    val labelSize = if (vertical) 12f else 13f
                    addView(
                        (if (instrument) lcdText(context, labelSize) else label(labelSize, R.attr.c_text_dim)).apply {
                            this.text = context.resources.getQuantityString(text, n)
                            if (instrument) {
                                maxLines = 1
                                ellipsize = android.text.TextUtils.TruncateAt.END
                            }
                        },
                        if (instrument) LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) } else LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) },
                    )
                }
            },
            LinearLayout.LayoutParams(if (vertical) ViewGroup.LayoutParams.MATCH_PARENT else 0, ViewGroup.LayoutParams.WRAP_CONTENT, if (vertical) 0f else 1f),
        )
    }
}

/** Sessions listées sous les compteurs d'Agents : aucune en Pastille, en Ligne et en 1 × 2, plus la forme est haute plus il y en a. */
internal fun agentsListRows(size: WidgetSize): Int = when {
    size.family == ShapeFamily.COLUMN -> 2 * (size.h - 2)
    size.w == 2 && size.h >= 2 -> 2 * size.h
    else -> 0
}
