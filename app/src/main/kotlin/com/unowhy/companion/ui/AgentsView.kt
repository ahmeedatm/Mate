package com.unowhy.companion.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.unowhy.companion.MainActivity
import com.unowhy.companion.R
import com.unowhy.companion.agents.Age
import com.unowhy.companion.agents.AgentsFeed
import com.unowhy.companion.agents.Waiting
import com.unowhy.companion.agents.age
import com.unowhy.companion.agents.elapsedMs
import com.unowhy.companion.agents.waitingKind
import com.unowhy.companion.net.AgentSession
import com.unowhy.companion.net.AgentState
import com.unowhy.companion.net.BackendClient
import kotlinx.coroutines.launch

/** Couleur d'un état : ambre (attend), vert (fini), bleu (limite), gris moyen (travaille), gris pâle (inactif). */
fun stateColor(context: Context, state: AgentState): Int = themeColor(
    context,
    when (state) {
        AgentState.WAITING -> R.attr.c_agent_wait
        AgentState.DONE -> R.attr.c_agent_done
        AgentState.LIMIT -> R.attr.c_agent_limit
        AgentState.WORKING -> R.attr.c_text_dim
        AgentState.IDLE -> R.attr.c_text_faint
    },
)

fun stateLabel(context: Context, session: AgentSession): String = context.getString(
    when (session.state) {
        AgentState.WORKING -> R.string.agent_state_working
        AgentState.WAITING -> if (waitingKind(session.waitingFor) == Waiting.PERMISSION) R.string.agent_waiting_permission else R.string.agent_waiting_input
        AgentState.DONE -> R.string.agent_state_done
        AgentState.LIMIT -> R.string.agent_state_limit
        AgentState.IDLE -> R.string.agent_state_idle
    },
)

fun ageLabel(context: Context, ms: Long): String = when (val a = age(ms)) {
    Age.Now -> context.getString(R.string.agent_age_now)
    is Age.Minutes -> context.getString(R.string.duration_minutes, a.minutes)
    is Age.Hours -> context.getString(R.string.agent_age_hours, a.hours, a.minutes)
}

/** Petite pastille ronde de la couleur de l'état. */
fun dot(context: Context, state: AgentState): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(stateColor(context, state))
    }

/**
 * Onglet Agents du Deck : compteurs (travaillent, attendent, finis) puis une carte par session, les plus pressantes
 * d'abord (le compagnon les trie). Le texte vient de l'agent : affiché tel quel, tronqué par le compagnon. Toucher
 * une session l'amène devant sur l'ordinateur.
 */
class AgentsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    private val counters = LinearLayout(context)
    private val list = LinearLayout(context)
    private val status = TextView(context)
    private val rows = LinkedHashMap<String, View>()
    private var signature: List<Any?> = emptyList()
    private var observer: ((AgentsFeed.Snapshot) -> Unit)? = null
    private lateinit var owner: LifecycleOwner

    init {
        orientation = VERTICAL
        visibility = GONE
        counters.orientation = HORIZONTAL
        addView(counters, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        status.apply {
            textSize = 15f
            setTextColor(themeColor(context, R.attr.c_text_dim))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        list.orientation = VERTICAL
        addView(
            ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                addView(list)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) },
        )
    }

    fun attach(owner: LifecycleOwner) {
        this.owner = owner
    }

    /** Affiché et relu en boucle (par AgentsFeed), ou caché. */
    fun setActive(active: Boolean) {
        visibility = if (active) VISIBLE else GONE
        observer?.let { AgentsFeed.unobserve(it) }
        observer = null
        if (!active) return
        val listener: (AgentsFeed.Snapshot) -> Unit = ::render
        observer = listener
        AgentsFeed.observe(context, listener)
    }

    private fun render(snapshot: AgentsFeed.Snapshot) {
        val state = snapshot.state
        if (state == null) {
            status.text = context.errorText(snapshot.error)
            status.visibility = VISIBLE
            return
        }
        val counts = state.counts
        showCounters(counts.working, counts.waiting, counts.done + counts.limit)
        status.text = when {
            state.sessions.isEmpty() -> context.getString(R.string.agents_empty)
            !state.hooks -> context.getString(R.string.agents_hooks_hint)
            else -> context.getString(R.string.agents_focus_hint)
        }
        status.visibility = VISIBLE
        val sig = state.sessions.map { listOf(it.id, it.state, it.name, it.branch, it.waitingFor, it.tool, it.lines, it.workedMs, it.limit, it.canFocus, it.askId) }
        if (sig != signature) {
            signature = sig
            list.removeAllViews()
            rows.clear()
            for (s in state.sessions) {
                val row = LayoutInflater.from(context).inflate(R.layout.item_agent, list, false)
                bind(row, s)
                row.setOnClickListener { open(s) }
                rows[s.id] = row
                list.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { if (list.childCount > 0) topMargin = dp(8) })
            }
        }
        for (s in state.sessions) {
            rows[s.id]?.findViewById<TextView>(R.id.agent_age)?.text = ageLabel(context, elapsedMs(s.since, state.now, snapshot.readAtMs, android.os.SystemClock.elapsedRealtime()))
        }
    }

    private fun bind(row: View, s: AgentSession) {
        row.findViewById<View>(R.id.agent_dot).background = dot(context, s.state)
        row.findViewById<TextView>(R.id.agent_name).text = s.name
        row.findViewById<TextView>(R.id.agent_where).text = listOfNotNull(s.dir.takeIf { it != s.name }, s.branch).joinToString(" · ")
        row.findViewById<TextView>(R.id.agent_status).apply {
            text = listOfNotNull(
                stateLabel(context, s),
                context.getString(R.string.agent_answer_hint).takeIf { s.askId != null },
                s.workedMs?.takeIf { s.state == AgentState.DONE || s.state == AgentState.LIMIT }?.let { context.getString(R.string.agent_worked, ageLabel(context, it)) },
                s.tool?.takeIf { s.state == AgentState.WORKING }?.let { context.getString(R.string.agent_tool, it) },
            ).joinToString(" · ")
            setTextColor(stateColor(context, s.state))
        }
        val lines = if (s.state == AgentState.LIMIT) listOfNotNull(s.limit) + s.lines.filter { it != s.limit } else s.lines
        row.findViewById<TextView>(R.id.agent_lines).apply {
            text = lines.joinToString("\n")
            visibility = if (lines.isEmpty()) GONE else VISIBLE
        }
    }

    private fun showCounters(working: Int, waiting: Int, done: Int) {
        counters.removeAllViews()
        listOf(
            Triple(working, R.plurals.agent_count_working, AgentState.WORKING),
            Triple(waiting, R.plurals.agent_count_waiting, AgentState.WAITING),
            Triple(done, R.plurals.agent_count_done, AgentState.DONE),
        ).forEach { (n, label, state) ->
            counters.addView(
                TextView(context).apply {
                    text = context.getString(R.string.agent_counter, n, context.resources.getQuantityString(label, n))
                    textSize = 16f
                    setTextColor(if (n > 0) stateColor(context, state) else themeColor(context, R.attr.c_text_faint))
                    compoundDrawablePadding = dp(8)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(dot(context, state).apply { setSize(dp(10), dp(10)) }, null, null, null)
                    gravity = Gravity.CENTER_VERTICAL
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(24) },
            )
        }
    }

    /** Une demande à laquelle répondre : sa carte. Sinon, amène la session devant sur l'ordinateur (terminal Orca, ou Orca). */
    private fun open(s: AgentSession) {
        val ask = s.askId
        if (ask != null) return (context as? MainActivity)?.openAgentAsk(ask) ?: Unit
        if (!s.canFocus) return
        owner.lifecycleScope.launch {
            try {
                BackendClient(context).agents.focus(s.id)
                (context as? MainActivity)?.showBanner(context.getString(R.string.agents_focused, s.name), 3)
            } catch (e: Exception) {
                (context as? MainActivity)?.showBanner(context.errorText(e), 6)
            }
        }
    }
}
