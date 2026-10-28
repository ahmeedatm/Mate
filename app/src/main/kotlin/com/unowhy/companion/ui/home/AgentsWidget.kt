package com.unowhy.companion.ui.home

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.unowhy.companion.R
import com.unowhy.companion.agents.AgentsFeed
import com.unowhy.companion.agents.shortList
import com.unowhy.companion.net.AgentState
import com.unowhy.companion.net.AgentsState
import com.unowhy.companion.ui.dot
import com.unowhy.companion.ui.errorText
import com.unowhy.companion.ui.stateColor
import com.unowhy.companion.ui.stateLabel
import java.util.Locale

/**
 * Agents Claude : trois compteurs (travaillent, attendent, finis) et, en grand, la liste courte des sessions qui comptent.
 * Toucher ouvre l'onglet Agents du Deck. Relu tant que la page de l'Accueil est à l'écran.
 */
class AgentsWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val counters = LinearLayout(context)
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val note = label(14f, R.attr.c_text_dim)
    private var observer: ((AgentsFeed.Snapshot) -> Unit)? = null
    private var last: AgentsFeed.Snapshot? = null

    override val view = LinearLayout(context).asCard(16).apply {
        orientation = LinearLayout.VERTICAL
        addView(cardTitle(R.string.agents_title, R.drawable.ic_agents))
        addView(counters, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        addView(note)
        setOnClickListener { host.main?.showAgents() }
    }

    override fun applySize() {
        counters.orientation = if (placed.size == WidgetSize.V) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        last?.let(::render)
    }

    override fun setActive(active: Boolean) {
        observer?.let { AgentsFeed.unobserve(it) }
        observer = null
        if (!active) return
        val listener: (AgentsFeed.Snapshot) -> Unit = {
            last = it
            render(it)
        }
        observer = listener
        AgentsFeed.observe(context, listener)
    }

    private fun render(snapshot: AgentsFeed.Snapshot) {
        val state = snapshot.state
        counters.removeAllViews()
        list.removeAllViews()
        if (state == null) {
            note.text = context.errorText(snapshot.error)
            note.visibility = View.VISIBLE
            return
        }
        note.visibility = View.GONE
        counter(state, AgentState.WORKING, state.counts.working, R.plurals.agent_count_working)
        counter(state, AgentState.WAITING, state.counts.waiting, R.plurals.agent_count_waiting)
        counter(state, AgentState.DONE, state.counts.done + state.counts.limit, R.plurals.agent_count_done)
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
                    addView(View(context).apply { background = dot(context, s.state) }, LinearLayout.LayoutParams(dp(10), dp(10)))
                    addView(
                        label(15f).apply { text = s.name; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) },
                    )
                    addView(label(13f).apply { text = stateLabel(context, s); setTextColor(stateColor(context, s.state)) })
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) },
            )
        }
        val more = state.sessions.count { it.state != AgentState.IDLE } - shown.size
        if (more > 0) {
            list.addView(label(13f, R.attr.c_text_dim).apply { text = context.resources.getQuantityString(R.plurals.agents_more, more, more) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        }
    }

    private fun counter(state: AgentsState, kind: AgentState, n: Int, text: Int) {
        val vertical = placed.size == WidgetSize.V
        counters.addView(
            LinearLayout(context).apply {
                orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = if (vertical) Gravity.START else Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    this.text = String.format(Locale.getDefault(), "%d", n)
                    textSize = if (placed.size == WidgetSize.M || vertical) 24f else 30f
                    setTextColor(if (n > 0) stateColor(context, kind) else color(R.attr.c_text_faint))
                })
                addView(label(if (vertical) 12f else 13f, R.attr.c_text_dim).apply { this.text = context.resources.getQuantityString(text, n) }, LinearLayout.LayoutParams(-2, -2).apply { if (!vertical) marginStart = dp(6) })
            },
            LinearLayout.LayoutParams(if (vertical) ViewGroup.LayoutParams.MATCH_PARENT else 0, ViewGroup.LayoutParams.WRAP_CONTENT, if (vertical) 0f else 1f),
        )
    }

    private companion object {
        const val MAX_ROWS = 4
    }
}
