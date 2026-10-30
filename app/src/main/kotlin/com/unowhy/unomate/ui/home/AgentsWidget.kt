package com.unowhy.unomate.ui.home

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.unowhy.unomate.Prefs
import com.unowhy.unomate.R
import com.unowhy.unomate.agents.AgentsFeed
import com.unowhy.unomate.agents.sessionTitle
import com.unowhy.unomate.agents.shortList
import com.unowhy.unomate.agents.without
import com.unowhy.unomate.net.AgentState
import com.unowhy.unomate.ui.AgentStatusIcon
import com.unowhy.unomate.ui.AgentsView
import com.unowhy.unomate.ui.errorText
import com.unowhy.unomate.ui.stateColor
import com.unowhy.unomate.ui.stateLabel
import java.util.Locale

/**
 * Agents Claude : les compteurs (travaillent, surveillent, attendent, finis) et, en L, la liste courte des sessions qui
 * comptent ; toucher ouvre le tableau en plein écran. À partir de 3 cases de large (3 × 2, Très grand, 3 × 4, plein
 * écran), le tableau lui-même ([AgentsView]) : ses colonnes défilent, le widget garde l'appui long et le balayage ; sur
 * 2 lignes seulement il se resserre (`compact`). Relu tant que la page de l'Accueil est à l'écran.
 */
class AgentsWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val counters = LinearLayout(context)
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val note = label(14f, R.attr.c_text_dim)
    private val board = AgentsView(context).apply { attach(host.owner) }
    private var observer: ((AgentsFeed.Snapshot) -> Unit)? = null
    private var last: AgentsFeed.Snapshot? = null
    private var active = false

    /** (affiché, plein écran) de l'abonnement en cours : un appel qui ne change rien ne relance pas de lecture. */
    private var followed: Pair<Boolean, Boolean>? = null

    /** Compteurs et liste d'un Agents M, L ou V. */
    private val summary = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(cardTitle(R.string.agents_title))
        addView(counters, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        addView(note)
    }

    private val full get() = placed.size.w >= 3

    override val view = object : OwnerFrameLayout(context) {
        // Seul le tableau garde les gestes de la grille ; les compteurs laissent l'appui long au mode Modifier.
        override val ownsLongPress get() = full
        override val ownsSwipe get() = full
    }.asCard(16).apply {
        addView(summary, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(board, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun applySize() {
        view.asCard(if (full) 12 else 16)
        summary.visibility = if (full) View.GONE else View.VISIBLE
        if (full) {
            view.setOnClickListener(null)
            view.isClickable = false
        } else {
            view.setOnClickListener { host.overlays.agentsBoard(host.owner) }
        }
        board.compact = placed.size.h < 3
        counters.orientation = if (placed.size == WidgetSize.V) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        last?.let(::render)
        follow()
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
            return
        }
        note.visibility = View.GONE
        counter(AgentState.WORKING, false, state.counts.working, R.plurals.agent_count_working)
        counter(AgentState.WORKING, true, state.counts.monitoring, R.plurals.agent_count_monitoring)
        counter(AgentState.WAITING, false, state.counts.waiting + state.counts.limit, R.plurals.agent_count_waiting)
        counter(AgentState.DONE, false, state.counts.done + state.counts.idle, R.plurals.agent_count_done)
        if (placed.size != WidgetSize.L) return
        val shown = shortList(state.sessions, MAX_ROWS)
        if (shown.isEmpty()) {
            note.text = context.getString(R.string.agents_empty)
            note.visibility = View.VISIBLE
        }
        for (s in shown) {
            list.addView(
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(com.unowhy.unomate.ui.AgentStatusIcon(context).apply { bind(s.state, s.monitoring) }, LinearLayout.LayoutParams(dp(16), dp(16)))
                    addView(
                        label(15f).apply { text = sessionTitle(s, Prefs.agentsPrivate(context)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) },
                    )
                    addView(label(13f).apply { text = stateLabel(context, s); setTextColor(com.unowhy.unomate.ui.sessionColor(context, s)) })
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) },
            )
        }
        state.quota?.let { q ->
            fun pct(w: com.unowhy.unomate.net.QuotaWindow?) = w?.let { context.getString(R.string.agent_quota_value, it.used) } ?: "?"
            list.addView(label(13f, R.attr.c_text_dim).apply { text = context.getString(R.string.agent_quota_line, pct(q.fiveHour), pct(q.sevenDay)) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        }
        val more = state.sessions.count { it.state != AgentState.IDLE } - shown.size
        if (more > 0) {
            list.addView(label(13f, R.attr.c_text_dim).apply { text = context.resources.getQuantityString(R.plurals.agents_more, more, more) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        }
    }

    /** Un compteur : icône de l'état (la roue tourne, l'écran pulse), nombre, libellé. */
    private fun counter(kind: AgentState, monitoring: Boolean, n: Int, text: Int) {
        val vertical = placed.size == WidgetSize.V
        val color = when {
            n == 0 -> color(R.attr.c_text_faint)
            monitoring -> color(R.attr.c_agent_monitor)
            else -> stateColor(context, kind)
        }
        counters.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(AgentStatusIcon(context).apply { bind(if (n == 0) AgentState.IDLE else kind, monitoring) }, LinearLayout.LayoutParams(dp(16), dp(16)))
                addView(
                    TextView(context).apply {
                        this.text = String.format(Locale.getDefault(), "%d", n)
                        textSize = if (placed.size == WidgetSize.L) 26f else 20f
                        setTextColor(color)
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) },
                )
                if (placed.size != WidgetSize.M) {
                    addView(
                        label(if (vertical) 12f else 13f, R.attr.c_text_dim).apply { this.text = context.resources.getQuantityString(text, n) },
                        LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) },
                    )
                }
            },
            LinearLayout.LayoutParams(if (vertical) ViewGroup.LayoutParams.MATCH_PARENT else 0, ViewGroup.LayoutParams.WRAP_CONTENT, if (vertical) 0f else 1f),
        )
    }

    private companion object {
        const val MAX_ROWS = 4
    }
}
