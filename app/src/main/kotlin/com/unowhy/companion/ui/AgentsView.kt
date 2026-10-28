package com.unowhy.companion.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.unowhy.companion.MainActivity
import com.unowhy.companion.Prefs
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Couleur d'un état, comme dans Orca : orange (travaille), rouge (attend), vert (fini), bleu (limite), gris (inactif). */
fun stateColor(context: Context, state: AgentState): Int = themeColor(
    context,
    when (state) {
        AgentState.WAITING -> R.attr.c_agent_wait
        AgentState.DONE -> R.attr.c_agent_done
        AgentState.LIMIT -> R.attr.c_agent_limit
        AgentState.WORKING -> R.attr.c_agent_work
        AgentState.IDLE -> R.attr.c_text_faint
    },
)

/** Couleur d'une session : jaune quand elle surveille une tâche de fond. */
fun sessionColor(context: Context, session: AgentSession): Int =
    if (session.state == AgentState.WORKING && session.monitoring) themeColor(context, R.attr.c_agent_monitor) else stateColor(context, session.state)

fun stateLabel(context: Context, session: AgentSession): String = context.getString(
    when (session.state) {
        AgentState.WORKING -> if (session.monitoring) R.string.agent_state_monitoring else R.string.agent_state_working
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
    private val quota = LinearLayout(context)
    private val list = LinearLayout(context)
    private val status = TextView(context)
    private val rows = LinkedHashMap<String, View>()
    private var signature: List<Any?> = emptyList()
    private val clock = SimpleDateFormat("H 'h' mm", Locale.FRENCH)
    private var observer: ((AgentsFeed.Snapshot) -> Unit)? = null
    private lateinit var owner: LifecycleOwner

    init {
        orientation = VERTICAL
        visibility = GONE
        counters.orientation = HORIZONTAL
        counters.gravity = Gravity.CENTER_VERTICAL
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(counters, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                // Mode privé : pour le direct, rien de ce que fait l'agent ne s'affiche (commandes, questions, résumés).
                addView(
                    Button(context, null, 0, R.style.Chip).apply {
                        setText(R.string.agents_private)
                        isSelected = Prefs.agentsPrivate(context)
                        setOnClickListener {
                            Prefs.setAgentsPrivate(context, !isSelected)
                            isSelected = !isSelected
                            signature = emptyList()
                            AgentsFeed.last?.let(::render)
                        }
                    },
                    LayoutParams(LayoutParams.WRAP_CONTENT, dp(40)),
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        quota.orientation = HORIZONTAL
        quota.gravity = Gravity.CENTER_VERTICAL
        quota.visibility = GONE
        addView(quota, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
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
        showQuota(state.quota)
        status.text = when {
            state.sessions.isEmpty() -> context.getString(R.string.agents_empty)
            !state.hooks -> context.getString(R.string.agents_hooks_hint)
            else -> context.getString(R.string.agents_focus_hint)
        }
        status.visibility = VISIBLE
        val sig = state.sessions.map { listOf(it.id, it.state, it.name, it.branch, it.waitingFor, it.tool, it.lines, it.workedMs, it.limit, it.canFocus, it.askId, it.canSend, it.resumeAt, it.resumeArmed, it.canInterrupt, it.title, it.ticket, it.mr, it.monitoring) }
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

    /** « Travaillé 4 min » ; sous la minute, « moins d'1 min » (pas « à l'instant »). */
    private fun workedLabel(ms: Long): String =
        if (age(ms) == Age.Now) context.getString(R.string.agent_worked_short) else context.getString(R.string.agent_worked, ageLabel(context, ms))

    private fun bind(row: View, s: AgentSession) {
        row.findViewById<AgentStatusIcon>(R.id.agent_status_icon).bind(s.state, s.monitoring)
        row.findViewById<TextView>(R.id.agent_name).text = s.title
        row.findViewById<TextView>(R.id.agent_where).text = listOfNotNull(
            context.getString(R.string.agent_kind_claude),
            s.ticket,
            s.mr?.let { context.getString(R.string.agent_mr, it) },
            s.branch ?: s.dir,
        ).joinToString(" · ")
        row.findViewById<TextView>(R.id.agent_status).apply {
            text = listOfNotNull(
                stateLabel(context, s),
                context.getString(R.string.agent_answer_hint).takeIf { s.askId != null },
                s.workedMs?.takeIf { s.state == AgentState.DONE || s.state == AgentState.LIMIT }?.let { workedLabel(it) },
                s.tool?.takeIf { s.state == AgentState.WORKING && !Prefs.agentsPrivate(context) }?.let { context.getString(R.string.agent_tool, it) },
            ).joinToString(" · ")
            setTextColor(sessionColor(context, s))
        }
        bindActions(row, s)
        val shown = if (s.state == AgentState.LIMIT) listOfNotNull(s.limit) + s.lines.filter { it != s.limit } else s.lines
        val lines = if (Prefs.agentsPrivate(context) && shown.isNotEmpty()) listOf(context.getString(R.string.agent_private_hidden)) else shown
        row.findViewById<TextView>(R.id.agent_lines).apply {
            text = lines.joinToString("\n")
            visibility = if (lines.isEmpty()) GONE else VISIBLE
        }
    }

    /** Boutons d'une session : « continue » et un message écrit ou dicté (session au repos dans un terminal Orca), reprise après une limite. */
    private fun bindActions(row: View, s: AgentSession) {
        val box = row.findViewById<LinearLayout>(R.id.agent_actions)
        box.removeAllViews()
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (s.canInterrupt) actions += context.getString(R.string.agent_interrupt) to { confirmInterrupt(s) }
        if (s.canSend && (s.state == AgentState.DONE || s.state == AgentState.IDLE)) {
            actions += context.getString(R.string.agent_continue) to { send(s, "continue") }
            actions += context.getString(R.string.agent_write) to { write(s) }
        }
        val at = s.resumeAt
        if (s.state == AgentState.LIMIT && s.canSend && at != null) {
            val time = clock.format(Date(at))
            actions += context.getString(if (s.resumeArmed) R.string.agent_resume_armed else R.string.agent_resume_at, time) to { resume(s, !s.resumeArmed, time) }
        }
        box.visibility = if (actions.isEmpty()) GONE else VISIBLE
        for ((label, onClick) in actions) {
            box.addView(
                Button(context, null, 0, R.style.Chip).apply {
                    text = label
                    setBackgroundResource(R.drawable.agent_action_bg)
                    setOnClickListener { onClick() }
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(8) },
            )
        }
    }

    /** Interrompre se confirme : le travail en cours s'arrête. */
    private fun confirmInterrupt(s: AgentSession) {
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.agent_interrupt_title, s.name))
            .setMessage(R.string.agent_interrupt_text)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.agent_interrupt) { _, _ ->
                call(s) {
                    BackendClient(context).agents.interrupt(s.id)
                    (context as? MainActivity)?.showBanner(context.getString(R.string.agent_interrupted, s.name), 3)
                }
            }
            .showImmersive()
    }

    /** Quota du compte (5 h, 7 j) : barre, pourcentage et heure de reprise ; caché tant que la statusline ne le donne pas. */
    private fun showQuota(q: com.unowhy.companion.net.AgentQuota?) {
        quota.removeAllViews()
        val windows = listOfNotNull(q?.fiveHour?.let { R.string.agent_quota_5h to it }, q?.sevenDay?.let { R.string.agent_quota_7d to it })
        quota.visibility = if (windows.isEmpty()) GONE else VISIBLE
        for ((label, w) in windows) {
            val color = themeColor(
                context,
                when {
                    w.used >= 100 -> R.attr.c_agent_limit
                    w.used >= 80 -> R.attr.c_agent_wait
                    else -> R.attr.c_agent_done
                },
            )
            quota.addView(TextView(context).apply {
                setText(label)
                textSize = 14f
                setTextColor(themeColor(context, R.attr.c_text_dim))
            })
            quota.addView(
                LinearLayout(context).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(3).toFloat()
                        setColor(themeColor(context, R.attr.c_surface_raised))
                    }
                    addView(View(context).apply { background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(color) } }, LayoutParams(0, LayoutParams.MATCH_PARENT, w.used.coerceIn(0, 100).toFloat()))
                    addView(View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, (100 - w.used.coerceIn(0, 100)).toFloat()))
                },
                LayoutParams(dp(120), dp(6)).apply { marginStart = dp(8) },
            )
            val at = w.resetsAt
            quota.addView(
                TextView(context).apply {
                    text = if (at != null && w.used >= 80) context.getString(R.string.agent_quota_reset, w.used, clock.format(Date(at)))
                    else context.getString(R.string.agent_quota_value, w.used)
                    textSize = 14f
                    setTextColor(color)
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8); marginEnd = dp(24) },
            )
        }
    }

    private fun write(s: AgentSession) {
        (context as? MainActivity)?.askAgentText(context.getString(R.string.agent_write_title, s.name), R.string.agent_write_hint, "") { text ->
            if (text.isNotBlank()) send(s, text.trim())
        }
    }

    private fun send(s: AgentSession, text: String) = call(s) {
        BackendClient(context).agents.send(s.id, text)
        (context as? MainActivity)?.showBanner(context.getString(R.string.agent_sent, s.name), 3)
    }

    private fun resume(s: AgentSession, armed: Boolean, time: String) = call(s) {
        BackendClient(context).agents.resume(s.id, armed)
        (context as? MainActivity)?.showBanner(
            if (armed) context.getString(R.string.agent_resume_armed_banner, s.name, time) else context.getString(R.string.agent_resume_cancelled),
            4,
        )
    }

    /** Une action sur une session, puis relecture ; l'échec est dit en une phrase (le compagnon dit pourquoi). */
    private fun call(s: AgentSession, action: suspend () -> Unit) {
        owner.lifecycleScope.launch {
            try {
                action()
                AgentsFeed.refresh(context)
            } catch (e: Exception) {
                (context as? MainActivity)?.showBanner(context.errorText(e), 6)
            }
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
