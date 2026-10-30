package app.mate.ui

import android.graphics.Typeface
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import app.mate.MainActivity
import app.mate.Prefs
import app.mate.R
import app.mate.agents.AgentsFeed
import app.mate.agents.AnswerDraft
import app.mate.device.Screen
import app.mate.net.AgentAsk
import app.mate.net.AgentState
import app.mate.net.BackendClient
import app.mate.net.HttpException
import kotlinx.coroutines.launch

/** Durée (s) des bandeaux de confirmation des agents (« Autorisé », « Envoyé »…) : 6 s au moins, le temps de les lire. */
internal const val AGENT_CONFIRM_SECONDS = 6

/** Défile au-delà de `maxHeight` : une longue commande ou six options ne sortent pas de l'écran. */
private class BoundedScrollView(context: android.content.Context, private val maxHeight: Int) : ScrollView(context) {
    init {
        isVerticalScrollBarEnabled = false
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
    }
}

/**
 * Une demande de Claude à laquelle la tablette peut répondre, ouverte en touchant la lumière de bord (ou une session de
 * l'onglet Agents) : une permission (Autoriser une fois / Refuser ; une commande à risque s'autorise en maintenant le
 * bouton) ou des questions à choix, une page par question, avec « Autre » dicté ou écrit. « Plus tard » ferme sans répondre :
 * le terminal garde la main à la fin du délai. Jamais de « toujours autoriser » ni de changement de mode. Le texte vient de
 * l'agent : affiché tel quel, jamais interprété.
 */
class AgentAskOverlay(private val activity: MainActivity) : FrameLayout(activity) {

    private var ask: AgentAsk? = null
    private var draft: AnswerDraft? = null
    private var page = 0
    private var busy = false
    /** Mode privé levé pour cette demande (bouton Afficher). */
    private var revealed = false
    private val masked get() = Prefs.agentsPrivate(context) && !revealed
    /** Fin du délai, en horloge de la tablette (le compagnon donne le temps restant). */
    private var expiresAt = 0L
    private val card = LinearLayout(context)
    private val tick = object : Runnable {
        override fun run() {
            val left = expiresAt - SystemClock.elapsedRealtime()
            if (left <= 0) return gone()
            // Question posée par Claude avec l'outil MCP : pas de terminal derrière, Claude continue sans réponse.
            val mcp = ask?.tool == "tablette_demander"
            countdown?.text = context.getString(if (mcp) R.string.agent_ask_expires_mcp else R.string.agent_ask_expires, (left / 1000).toInt() + 1)
            postDelayed(this, 1_000)
        }
    }
    private var countdown: TextView? = null

    val isOpen get() = visibility == VISIBLE

    init {
        setBackgroundColor(ColorUtils.setAlphaComponent(themeColor(context, R.attr.c_bg), 0xE6))
        isClickable = true // les touchers ne passent pas à l'écran dessous
        visibility = GONE
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundResource(R.drawable.card_bg)
        card.setPadding(dp(32), dp(24), dp(32), dp(28))
        addView(card, LayoutParams(dp(CARD_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /** Ouvre la demande `id` ; si elle n'existe plus (traitée au clavier, expirée), le dit. */
    fun open(id: String) {
        activity.lifecycleScope.launch {
            val found = try {
                BackendClient(context).agents.ask(id)
            } catch (e: Exception) {
                activity.showBanner(context.errorText(e), 6)
                return@launch
            }
            if (found == null) return@launch gone()
            ask = found
            draft = if (found.kind == "question") AnswerDraft(found.questions) else null
            page = 0
            busy = false
            revealed = false
            expiresAt = SystemClock.elapsedRealtime() + found.expiresInMs
            visibility = VISIBLE
            bringToFront()
            Screen.touched(activity)
            render()
            removeCallbacks(tick)
            post(tick)
        }
    }

    fun close() {
        removeCallbacks(tick)
        visibility = GONE
        ask = null
        draft = null
    }

    private fun gone() {
        close()
        activity.showBanner(context.getString(R.string.agent_ask_gone), 6)
        AgentsFeed.refresh(context)
    }

    private fun render() {
        val a = ask ?: return
        card.removeAllViews()
        countdown = null
        card.addView(header(a))
        when (a.kind) {
            "question" -> renderQuestion(a)
            "plan" -> renderPlan(a)
            else -> renderPermission(a)
        }
    }

    private fun header(a: AgentAsk): View = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(View(context).apply { background = dot(context, AgentState.WAITING) }, LinearLayout.LayoutParams(dp(14), dp(14)))
        addView(
            text(22f, R.attr.c_text, bold = true).apply {
                this.text = context.getString(
                    when (a.kind) {
                        "question" -> R.string.agent_ask_question
                        "plan" -> R.string.agent_ask_plan
                        else -> R.string.agent_ask_permission
                    },
                    a.session,
                )
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) },
        )
        // Parler sans fermer la carte (elle recouvre le bouton Parler) : « autorise », « option deux », « valide »…
        addView(
            androidx.appcompat.widget.AppCompatImageButton(context, null, 0).apply {
                setImageResource(R.drawable.ic_mic)
                setBackgroundResource(R.drawable.round_bg)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                androidx.core.widget.ImageViewCompat.setImageTintList(this, themeColorList(context, R.attr.c_text))
                contentDescription = context.getString(R.string.voice_button)
                setOnClickListener { activity.startVoice() }
            },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) },
        )
        addView(flat(R.string.agent_ask_later) { close() })
    }

    // --- Permission

    private fun renderPermission(a: AgentAsk) {
        card.addView(
            text(14f, R.attr.c_text_dim).apply { text = listOfNotNull(a.tool.takeIf { it.isNotEmpty() }, a.dir.takeIf { it.isNotEmpty() }).joinToString(" · ") },
            params(top = 8),
        )
        // Texte brut de l'agent : jamais de lien ni de mise en forme.
        val command = text(17f, R.attr.c_text).apply {
            typeface = Typeface.MONOSPACE
            text = if (masked) context.getString(R.string.agent_private_hidden) else a.text
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundResource(R.drawable.row_bg)
            gravity = Gravity.START
        }
        card.addView(BoundedScrollView(context, dp(200)).apply { addView(command) }, params(top = 16))
        if (masked) card.addView(revealButton(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        if (a.risky) card.addView(text(15f, R.attr.c_ko, bold = true).apply { setText(R.string.agent_ask_risky) }, params(top = 12))
        val left = text(13f, R.attr.c_text_faint)
        countdown = left
        card.addView(left, params(top = 12))
        val allow: Button = if (a.risky) {
            HoldButton(context).apply { setText(R.string.agent_ask_allow_hold) }
        } else {
            primary(R.string.agent_ask_allow)
        }
        allow.setOnClickListener { answerPermission(allow = true, held = a.risky) }
        card.addView(
            LinearLayout(context).apply {
                gravity = Gravity.END
                addView(flat(R.string.agent_ask_deny) { answerPermission(allow = false, held = false) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)))
                addView(allow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)).apply { marginStart = dp(12) })
            },
            params(top = 20),
        )
    }

    private fun answerPermission(allow: Boolean, held: Boolean) {
        val a = ask ?: return
        if (busy) return
        busy = true
        activity.lifecycleScope.launch {
            try {
                BackendClient(context).agents.answerPermission(a.id, allow, held)
                activity.showBanner(context.getString(if (allow) R.string.agent_ask_allowed else R.string.agent_ask_denied), AGENT_CONFIRM_SECONDS)
                close()
                AgentsFeed.refresh(context)
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    // --- Plan (ExitPlanMode)

    private fun renderPlan(a: AgentAsk) {
        // Markdown affiché brut : pas d'interprétation du texte venu de l'agent.
        val plan = text(16f, R.attr.c_text).apply {
            text = if (masked) context.getString(R.string.agent_private_hidden) else a.text
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundResource(R.drawable.row_bg)
        }
        card.addView(BoundedScrollView(context, dp(340)).apply { addView(plan) }, params(top = 16))
        if (masked) card.addView(revealButton(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        card.addView(text(14f, R.attr.c_text_dim).apply { setText(R.string.agent_plan_note) }, params(top = 12))
        val left = text(13f, R.attr.c_text_faint)
        countdown = left
        card.addView(left, params(top = 8))
        card.addView(
            LinearLayout(context).apply {
                gravity = Gravity.END
                addView(flat(R.string.agent_plan_refuse) { refusePlan() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)))
                addView(
                    primary(R.string.agent_plan_approve).apply { setOnClickListener { answerPlan(approve = true, feedback = null) } },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)).apply { marginStart = dp(12) },
                )
            },
            params(top = 16),
        )
    }

    private fun refusePlan() {
        activity.askAgentText(context.getString(R.string.agent_plan_feedback_title), R.string.agent_plan_feedback_hint, "") { text ->
            answerPlan(approve = false, feedback = text.trim().ifEmpty { null })
        }
    }

    private fun answerPlan(approve: Boolean, feedback: String?) {
        val a = ask ?: return
        if (busy) return
        busy = true
        activity.lifecycleScope.launch {
            try {
                BackendClient(context).agents.answerPlan(a.id, approve, feedback)
                activity.showBanner(context.getString(if (approve) R.string.agent_plan_approved else R.string.agent_plan_refused), AGENT_CONFIRM_SECONDS)
                close()
                AgentsFeed.refresh(context)
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    // --- Questions à choix

    private fun renderQuestion(a: AgentAsk) {
        val d = draft ?: return
        val q = a.questions[page]
        card.addView(
            text(14f, R.attr.c_text_dim).apply {
                text = listOfNotNull(context.getString(R.string.agent_ask_page, page + 1, d.size), q.header.takeIf { it.isNotEmpty() }).joinToString(" · ")
            },
            params(top = 8),
        )
        card.addView(text(22f, R.attr.c_text).apply { text = if (masked) context.getString(R.string.agent_private_hidden) else q.question }, params(top = 8))
        if (masked) card.addView(revealButton(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        if (q.multi) card.addView(text(14f, R.attr.c_text_dim).apply { setText(R.string.agent_ask_multi) }, params(top = 4))
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        q.options.forEachIndexed { i, o ->
            // Masqué : les options gardent leur numéro, on peut répondre « option deux » sans rien montrer.
            val label = if (masked) context.getString(R.string.agent_voice_choice, i + 1) else o.label
            list.addView(option(label, if (masked) "" else o.description, selected = i in d.choices(page)) { tapOption(i) }, params(top = 8))
        }
        val other = d.other(page)
        list.addView(
            if (other != null) option(context.getString(R.string.agent_ask_other_value, other), "", selected = true) { askOther() }
            else option(context.getString(R.string.agent_ask_other), "", selected = false) { askOther() },
            params(top = 8),
        )
        card.addView(BoundedScrollView(context, dp(330)).apply { addView(list) }, params(top = 8))
        val last = page == d.size - 1
        val forward = primary(if (last) R.string.agent_ask_send else R.string.agent_ask_next).apply {
            isEnabled = if (last) d.isComplete() else d.isAnswered(page)
            alpha = if (isEnabled) 1f else 0.4f
            setOnClickListener { if (last) sendAnswers() else { page++; render() } }
        }
        val left = text(13f, R.attr.c_text_faint)
        countdown = left
        card.addView(left, params(top = 12))
        card.addView(
            LinearLayout(context).apply {
                gravity = Gravity.END
                if (page > 0) addView(flat(R.string.agent_ask_back) { page--; render() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)))
                addView(forward, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)).apply { marginStart = dp(12) })
            },
            params(top = 12),
        )
    }

    private fun tapOption(i: Int) {
        val a = ask ?: return
        val d = draft ?: return
        d.toggle(page, i)
        // Question simple, pas la dernière : la suivante s'ouvre d'elle-même.
        if (!a.questions[page].multi && page < d.size - 1) page++
        render()
    }

    private fun revealButton() = flat(R.string.agent_private_show) {
        revealed = true
        render()
    }

    /** Commande vocale sur la carte ouverte (voir MainActivity.agentVoice) ; rend la phrase à afficher. */
    fun voice(action: String, choice: Int?): String {
        val a = ask ?: return context.getString(R.string.agent_voice_none)
        if (a.kind == "plan") {
            return when (action) {
                "allow", "send" -> {
                    answerPlan(approve = true, feedback = null)
                    context.getString(R.string.agent_plan_approved)
                }
                "deny" -> {
                    answerPlan(approve = false, feedback = null)
                    context.getString(R.string.agent_plan_refused)
                }
                else -> context.getString(R.string.agent_voice_permission)
            }
        }
        if (a.kind != "question") {
            return when (action) {
                "allow", "send" -> if (a.risky) context.getString(R.string.agent_voice_risky) else {
                    answerPermission(allow = true, held = false)
                    context.getString(R.string.agent_ask_allowed)
                }
                "deny" -> {
                    answerPermission(allow = false, held = false)
                    context.getString(R.string.agent_ask_denied)
                }
                else -> context.getString(R.string.agent_voice_permission)
            }
        }
        val d = draft ?: return context.getString(R.string.agent_voice_none)
        return when (action) {
            "choice" -> {
                val n = choice ?: 0
                if (n !in 1..a.questions[page].options.size) return context.getString(R.string.agent_voice_no_choice, n)
                tapOption(n - 1)
                context.getString(R.string.agent_voice_choice, n)
            }
            "allow", "send" -> when {
                page == d.size - 1 && d.isComplete() -> {
                    sendAnswers()
                    context.getString(R.string.agent_ask_sent)
                }
                page < d.size - 1 && d.isAnswered(page) -> {
                    page++
                    render()
                    context.getString(R.string.agent_voice_next)
                }
                else -> context.getString(R.string.agent_voice_pick)
            }
            "deny" -> {
                close()
                context.getString(R.string.agent_voice_later)
            }
            else -> context.getString(R.string.agent_voice_question)
        }
    }

    private fun sendAnswers() {
        val a = ask ?: return
        val d = draft ?: return
        if (busy || !d.isComplete()) return
        busy = true
        activity.lifecycleScope.launch {
            try {
                BackendClient(context).agents.answerQuestions(a.id, d.build())
                activity.showBanner(context.getString(R.string.agent_ask_sent), AGENT_CONFIRM_SECONDS)
                close()
                AgentsFeed.refresh(context)
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    /** « Autre » : texte écrit, ou dicté par le bouton Dicter (sans reconnaissance, l'écriture seule). */
    private fun askOther() {
        val d = draft ?: return
        activity.askAgentText(context.getString(R.string.agent_ask_other_title), R.string.agent_ask_other_hint, d.other(page).orEmpty()) { text ->
            d.setOther(page, text)
            render()
        }
    }

    private fun failed(e: Exception) {
        busy = false
        if (e is HttpException && e.code == 404) return gone()
        activity.showBanner(context.errorText(e), 6)
    }

    // --- Éléments

    private fun params(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun text(size: Float, colorAttr: Int, bold: Boolean = false) = TextView(context).apply {
        textSize = size
        setTextColor(themeColor(context, colorAttr))
        if (bold) typeface = Typeface.create(typeface, Typeface.BOLD)
    }

    private fun primary(label: Int) = Button(context, null, 0, R.style.PrimaryButton).apply {
        setText(label)
        textSize = 18f
        setPadding(dp(28), 0, dp(28), 0)
    }

    private fun flat(label: Int, onClick: () -> Unit) = Button(context, null, 0, R.style.ActionButton).apply {
        setText(label)
        textSize = 16f
        setOnClickListener { onClick() }
    }

    private fun option(label: String, description: String, selected: Boolean, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        minimumHeight = dp(60)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(10), dp(18), dp(10))
        setBackgroundResource(R.drawable.option_bg)
        isSelected = selected
        addView(text(18f, R.attr.c_text, bold = true).apply { text = label })
        if (description.isNotEmpty()) addView(text(14f, R.attr.c_text_dim).apply { text = description })
        setOnClickListener { onClick() }
    }

    private companion object {
        const val CARD_WIDTH_DP = 760
    }
}
