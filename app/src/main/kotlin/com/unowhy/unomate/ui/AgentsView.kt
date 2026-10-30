package com.unowhy.unomate.ui

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
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.unowhy.unomate.MainActivity
import com.unowhy.unomate.Prefs
import com.unowhy.unomate.R
import com.unowhy.unomate.agents.Age
import com.unowhy.unomate.agents.AgentsFeed
import com.unowhy.unomate.agents.Column
import com.unowhy.unomate.agents.Waiting
import com.unowhy.unomate.agents.age
import com.unowhy.unomate.agents.columnCounts
import com.unowhy.unomate.agents.columnOf
import com.unowhy.unomate.agents.daysUntil
import com.unowhy.unomate.agents.elapsedMs
import com.unowhy.unomate.agents.sessionTitle
import com.unowhy.unomate.agents.waitingKind
import com.unowhy.unomate.net.AgentSession
import com.unowhy.unomate.net.AgentState
import com.unowhy.unomate.net.BackendClient
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
 * Onglet Agents du Deck : un tableau de quatre colonnes (travaillent, surveillent, vous concerne, finis), une carte par
 * session, les plus pressantes en haut (le compagnon les trie). Le texte vient de l'agent : affiché tel quel, tronqué par
 * le compagnon. Toucher une session l'amène devant sur l'ordinateur, ou ouvre sa demande.
 * [compact] : place comptée (widget de 2 lignes) : usage Claude sur une ligne, sans l'astuce du bas, pour laisser la
 * place aux cartes. Sans lecture (ordinateur injoignable, Windows), aucune colonne : le message seul, au milieu.
 */
class AgentsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    private val quota = LinearLayout(context)
    private val board = LinearLayout(context)
    /** Pour chaque colonne : son en-tête (icône, nom, nombre) et la liste de ses cartes. */
    private val headers = HashMap<Column, TextView>()
    private val columns = HashMap<Column, LinearLayout>()
    private val status = TextView(context)
    private val privateButton = Button(context, null, 0, R.style.Chip)
    private val rows = LinkedHashMap<String, View>()
    private var signature: List<Any?> = emptyList()
    private val clock = SimpleDateFormat("H 'h' mm", Locale.FRENCH)
    private val weekday = SimpleDateFormat("EEEE", Locale.FRENCH)
    private val dateDay = SimpleDateFormat("d MMMM", Locale.FRENCH)
    private var observer: ((AgentsFeed.Snapshot) -> Unit)? = null
    private var active = false
    private lateinit var owner: LifecycleOwner
    private var last: AgentsFeed.Snapshot? = null

    var compact = false
        set(value) {
            if (field == value) return
            field = value
            last?.let(::render)
        }

    init {
        orientation = VERTICAL
        visibility = GONE
        quota.orientation = HORIZONTAL
        quota.gravity = Gravity.CENTER_VERTICAL
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                // Usage Claude du compte à gauche (caché tant qu'il est inconnu), mode privé à droite.
                addView(quota, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                // Mode privé : pour le direct, rien de ce que fait l'agent ne s'affiche (commandes, questions, résumés).
                addView(
                    privateButton.apply {
                        setText(R.string.agents_private)
                        isSelected = Prefs.agentsPrivate(context)
                        setOnClickListener {
                            Prefs.setAgentsPrivate(context, !isSelected)
                            isSelected = !isSelected
                            // Toutes les vues ouvertes (tableau, widget, onglet) se redessinent tout de suite, sans attendre la lecture suivante.
                            AgentsFeed.replay()
                        }
                    },
                    LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)),
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        status.apply {
            textSize = 14f
            setTextColor(themeColor(context, R.attr.c_text_dim))
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
        }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        board.orientation = HORIZONTAL
        for ((i, column) in Column.entries.withIndex()) {
            val cards = LinearLayout(context).apply { orientation = VERTICAL }
            val header = TextView(context).apply {
                textSize = 16f
                typeface = themeFont(context, R.attr.c_font_medium)
                gravity = Gravity.CENTER_VERTICAL
                compoundDrawablePadding = dp(8)
                // Colonne étroite (widget de 3 cases) : le titre se coupe, il ne sort pas de la colonne.
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            headers[column] = header
            columns[column] = cards
            board.addView(
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(AgentStatusIcon(context).apply { tag = column }, LayoutParams(dp(18), dp(18)))
                        addView(header, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
                    }, LayoutParams(LayoutParams.MATCH_PARENT, dp(36)))
                    addView(
                        ScrollView(context).apply {
                            isVerticalScrollBarEnabled = false
                            addView(cards)
                        },
                        LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) },
                    )
                },
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) marginStart = dp(12) },
            )
        }
        addView(board, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })
    }

    /** En-têtes des colonnes : l'icône de l'état (la roue tourne, l'écran pulse), le nom, le nombre. */
    private fun showHeaders(counts: Map<Column, Int>) {
        for (column in Column.entries) {
            val n = counts[column] ?: 0
            val (state, monitoring, label) = when (column) {
                Column.WORKING -> Triple(AgentState.WORKING, false, R.string.agent_col_working)
                Column.MONITORING -> Triple(AgentState.WORKING, true, R.string.agent_col_monitoring)
                Column.YOU -> Triple(AgentState.WAITING, false, R.string.agent_col_you)
                Column.DONE -> Triple(AgentState.DONE, false, R.string.agent_col_done)
            }
            val color = when {
                n == 0 -> themeColor(context, R.attr.c_text_faint)
                monitoring -> themeColor(context, R.attr.c_agent_monitor)
                else -> stateColor(context, state)
            }
            headers[column]?.apply {
                text = context.getString(R.string.agent_col_header, context.getString(label), n)
                setTextColor(color)
            }
            board.findViewWithTag<AgentStatusIcon>(column)?.bind(if (n == 0) AgentState.IDLE else state, monitoring)
        }
    }

    fun attach(owner: LifecycleOwner) {
        this.owner = owner
    }

    // La largeur donne celle des jauges : elles sont redessinées quand elle change.
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) last?.let { showQuota(it.state?.quota, it.state?.now ?: 0L) }
    }

    /** Affiché et relu en boucle (par AgentsFeed), ou caché. */
    fun setActive(active: Boolean) {
        visibility = if (active) VISIBLE else GONE
        if (this.active == active) return
        this.active = active
        observer?.let { AgentsFeed.unobserve(it) }
        observer = null
        if (!active) return
        val listener: (AgentsFeed.Snapshot) -> Unit = ::render
        observer = listener
        AgentsFeed.observe(context, listener)
    }

    /** Le message seul prend la place des colonnes (rien à lire), ou reprend sa hauteur d'une ligne. */
    private fun statusAlone(alone: Boolean) {
        board.visibility = if (alone) GONE else VISIBLE
        (status.layoutParams as LayoutParams).apply {
            height = if (alone) 0 else LayoutParams.WRAP_CONTENT
            weight = if (alone) 1f else 0f
        }
        status.requestLayout()
    }

    private fun render(snapshot: AgentsFeed.Snapshot) {
        last = snapshot
        privateButton.isSelected = Prefs.agentsPrivate(context)
        val state = snapshot.state
        if (state == null) {
            status.text = context.errorText(snapshot.error)
            status.visibility = VISIBLE
            statusAlone(true)
            return
        }
        statusAlone(false)
        showHeaders(columnCounts(state.counts))
        showQuota(state.quota, state.now)
        status.text = when {
            state.sessions.isEmpty() -> context.getString(R.string.agents_empty)
            !state.hooks -> context.getString(R.string.agents_hooks_hint)
            else -> context.getString(R.string.agents_focus_hint)
        }
        // Place comptée : l'astuce du bas n'apprend rien de neuf, les cartes passent avant.
        status.visibility = if (compact && state.sessions.isNotEmpty() && state.hooks) GONE else VISIBLE
        // Le mode privé change le rendu : il fait partie de la signature, sinon une autre vue (tableau ouvert depuis un widget) le laisse périmé.
        val sig = listOf(Prefs.agentsPrivate(context), state.sessions.map { listOf(it.id, it.state, it.name, it.branch, it.waitingFor, it.tool, it.lines, it.workedMs, it.limit, it.canFocus, it.askId, it.canSend, it.resumeAt, it.resumeArmed, it.canInterrupt, it.title, it.ticket, it.mr, it.monitoring) }, state.voice)
        if (sig != signature) {
            signature = sig
            columns.values.forEach { it.removeAllViews() }
            rows.clear()
            for (s in state.sessions) {
                val list = columns.getValue(columnOf(s))
                val row = LayoutInflater.from(context).inflate(R.layout.item_agent, list, false)
                bind(row, s)
                row.setOnClickListener { open(s) }
                rows[s.id] = row
                list.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { if (list.childCount > 0) topMargin = dp(8) })
            }
            for ((_, list) in columns) {
                if (list.childCount == 0) {
                    list.addView(TextView(context).apply {
                        setText(R.string.agent_col_empty)
                        textSize = 14f
                        setTextColor(themeColor(context, R.attr.c_text_faint))
                        setPadding(dp(4), dp(8), 0, 0)
                    })
                }
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
        row.findViewById<TextView>(R.id.agent_name).text = sessionTitle(s, Prefs.agentsPrivate(context))
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
            if (AgentsFeed.last?.state?.voice == true) actions += context.getString(R.string.agent_talk) to { talk(s) }
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
                    if (label == context.getString(R.string.agent_talk)) {
                        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_mic, 0, 0, 0)
                        TextViewCompat.setCompoundDrawableTintList(this, textColors)
                        compoundDrawablePadding = dp(6)
                    }
                    setOnClickListener { onClick() }
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)).apply { marginEnd = dp(8) },
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
                    (context as? MainActivity)?.showBanner(context.getString(R.string.agent_interrupted, s.name), AGENT_CONFIRM_SECONDS)
                }
            }
            .showImmersive()
    }

    /**
     * Usage Claude du compte (5 h, 7 j) : une jauge par fenêtre, pourcentage, barre et heure de reprise, puis l'âge de la
     * lecture si elle date. Caché tant qu'aucune source (Orca, statusline) ne l'a donné. `now` = heure de l'ordinateur.
     */
    private fun showQuota(q: com.unowhy.unomate.net.AgentQuota?, now: Long) {
        quota.removeAllViews()
        val windows = listOfNotNull(q?.fiveHour?.let { R.string.agent_quota_5h to it }, q?.sevenDay?.let { R.string.agent_quota_7d to it })
        quota.visibility = if (windows.isEmpty()) INVISIBLE else VISIBLE
        if (q == null || windows.isEmpty()) return
        quota.addView(
            TextView(context).apply {
                setText(R.string.agent_quota_title)
                textSize = 15f
                typeface = themeFont(context, R.attr.c_font_medium)
                setTextColor(themeColor(context, R.attr.c_text))
                // Icône neutre, jamais le logo d'Anthropic (marque déposée, pas de licence pour un produit vendu).
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_agents, 0, 0, 0)
                TextViewCompat.setCompoundDrawableTintList(this, themeColorList(context, R.attr.c_text_dim))
                compoundDrawablePadding = dp(8)
                gravity = Gravity.CENTER_VERTICAL
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(24) },
        )
        // Jusqu'à 220 dp par jauge, moins si la place manque (widget de 3 cases) : titre et bouton gardent la leur.
        val meterWidth = ((width - dp(260) - windows.size * dp(24)) / windows.size).coerceIn(dp(120), dp(220))
        for ((label, w) in windows) quota.addView(meter(label, w, now), LayoutParams(meterWidth, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(24) })
        // Orca ou la statusline n'ont pas relu depuis un moment : le dire plutôt que montrer un chiffre comme s'il était frais.
        if (q.ageMs >= 10 * 60_000) {
            quota.addView(TextView(context).apply {
                text = context.getString(R.string.agent_quota_age, ageLabel(context, q.ageMs))
                textSize = 13f
                setTextColor(themeColor(context, R.attr.c_text_dim))
            })
        }
    }

    /** Une fenêtre de quota : nom et pourcentage, barre, heure de reprise (jour en plus si ce n'est pas aujourd'hui). */
    private fun meter(label: Int, w: com.unowhy.unomate.net.QuotaWindow, now: Long): View {
        val used = w.used.coerceIn(0, 100)
        val color = themeColor(
            context,
            when {
                used >= 100 -> R.attr.c_agent_limit
                used >= 80 -> R.attr.c_agent_wait
                else -> R.attr.c_agent_done
            },
        )
        val dim = themeColor(context, R.attr.c_text_dim)
        return LinearLayout(context).apply {
            orientation = VERTICAL
            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.BOTTOM
                    addView(
                        TextView(context).apply {
                            setText(label)
                            textSize = 14f
                            setTextColor(dim)
                        },
                        LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(TextView(context).apply {
                        text = context.getString(R.string.agent_quota_value, used)
                        textSize = 22f
                        typeface = themeFont(context, R.attr.c_font_display)
                        setTextColor(color)
                        includeFontPadding = false
                    })
                },
            )
            addView(
                LinearLayout(context).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(4).toFloat()
                        setColor(themeColor(context, R.attr.c_surface_raised))
                    }
                    if (used > 0) addView(View(context).apply { background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(color) } }, LayoutParams(0, LayoutParams.MATCH_PARENT, used.toFloat()))
                    if (used < 100) addView(View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, (100 - used).toFloat()))
                },
                LayoutParams(LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(6) },
            )
            val at = w.resetsAt
            if (at != null && !compact) {
                val time = clock.format(Date(at))
                addView(
                    TextView(context).apply {
                        text = when (val days = daysUntil(at, now)) {
                            0 -> context.getString(R.string.agent_quota_reset_today, time)
                            1 -> context.getString(R.string.agent_quota_reset_tomorrow, time)
                            else -> context.getString(R.string.agent_quota_reset_day, (if (days < 7) weekday else dateDay).format(Date(at)), time)
                        }
                        textSize = 13f
                        setTextColor(dim)
                    },
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) },
                )
            }
        }
    }

    /** Micro : la boîte « Écrire » s'ouvre en écoute, le texte transcrit s'y relit avant l'envoi. */
    private fun talk(s: AgentSession) {
        (context as? MainActivity)?.askAgentText(context.getString(R.string.agent_write_title, s.name), R.string.agent_write_hint, "", listenNow = true) { text ->
            if (text.isNotBlank()) send(s, text.trim())
        }
    }

    private fun write(s: AgentSession) {
        (context as? MainActivity)?.askAgentText(context.getString(R.string.agent_write_title, s.name), R.string.agent_write_hint, "") { text ->
            if (text.isNotBlank()) send(s, text.trim())
        }
    }

    private fun send(s: AgentSession, text: String) = call(s) {
        BackendClient(context).agents.send(s.id, text)
        (context as? MainActivity)?.showBanner(context.getString(R.string.agent_sent, s.name), AGENT_CONFIRM_SECONDS)
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

    /** Une demande à laquelle répondre : sa carte. Sinon, amène la session devant sur l'ordinateur (terminal Orca, ou Orca). */
    private fun open(s: AgentSession) {
        val ask = s.askId
        if (ask != null) return (context as? MainActivity)?.openAgentAsk(ask) ?: Unit
        if (!s.canFocus) return
        owner.lifecycleScope.launch {
            try {
                BackendClient(context).agents.focus(s.id)
                (context as? MainActivity)?.showBanner(context.getString(R.string.agents_focused, s.name), AGENT_CONFIRM_SECONDS)
            } catch (e: Exception) {
                (context as? MainActivity)?.showBanner(context.errorText(e), 6)
            }
        }
    }
}
