package com.unowhy.companion.ui

import android.graphics.Typeface
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.unowhy.companion.MainActivity
import com.unowhy.companion.R
import com.unowhy.companion.agents.AgentsFeed
import com.unowhy.companion.agents.AnswerDraft
import com.unowhy.companion.device.Screen
import com.unowhy.companion.net.AgentAsk
import com.unowhy.companion.net.AgentState
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.HttpException
import com.unowhy.companion.voice.Dictation
import kotlinx.coroutines.launch

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
    /** Fin du délai, en horloge de la tablette (le compagnon donne le temps restant). */
    private var expiresAt = 0L
    private val dictation = Dictation(activity)
    private val card = LinearLayout(context)
    private val tick = object : Runnable {
        override fun run() {
            val left = expiresAt - SystemClock.elapsedRealtime()
            if (left <= 0) return gone()
            countdown?.text = context.getString(R.string.agent_ask_expires, (left / 1000).toInt() + 1)
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
        dictation.release()
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
        if (a.kind == "question") renderQuestion(a) else renderPermission(a)
    }

    private fun header(a: AgentAsk): View = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(View(context).apply { background = dot(context, AgentState.WAITING) }, LinearLayout.LayoutParams(dp(14), dp(14)))
        addView(
            text(22f, R.attr.c_text, bold = true).apply {
                this.text = context.getString(if (a.kind == "question") R.string.agent_ask_question else R.string.agent_ask_permission, a.session)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) },
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
            text = a.text
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundResource(R.drawable.row_bg)
            gravity = Gravity.START
        }
        card.addView(BoundedScrollView(context, dp(200)).apply { addView(command) }, params(top = 16))
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
                activity.showBanner(context.getString(if (allow) R.string.agent_ask_allowed else R.string.agent_ask_denied), 3)
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
        card.addView(text(22f, R.attr.c_text).apply { text = q.question }, params(top = 8))
        if (q.multi) card.addView(text(14f, R.attr.c_text_dim).apply { setText(R.string.agent_ask_multi) }, params(top = 4))
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        q.options.forEachIndexed { i, o ->
            list.addView(
                option(o.label, o.description, selected = i in d.choices(page)) {
                    d.toggle(page, i)
                    // Question simple, pas la dernière : la suivante s'ouvre d'elle-même.
                    if (!q.multi && page < d.size - 1) {
                        page++
                    }
                    render()
                },
                params(top = 8),
            )
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

    private fun sendAnswers() {
        val a = ask ?: return
        val d = draft ?: return
        if (busy || !d.isComplete()) return
        busy = true
        activity.lifecycleScope.launch {
            try {
                BackendClient(context).agents.answerQuestions(a.id, d.build())
                activity.showBanner(context.getString(R.string.agent_ask_sent), 3)
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
        val input = activity.layoutInflater.inflate(R.layout.item_input, null) as EditText
        input.setHint(R.string.agent_ask_other_hint)
        input.setText(d.other(page).orEmpty())
        input.setSelection(input.text.length)
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.agent_ask_other_title)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.agent_ask_ok, null)
            .apply { if (dictation.available) setNeutralButton(R.string.agent_ask_dictate, null) }
            .create()
        dialog.showForTyping(input)
        val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        ok.setOnClickListener {
            d.setOther(page, input.text.toString())
            dialog.dismiss()
            render()
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            val started = dictation.start(
                onPartial = { input.setText(it); input.setSelection(input.text.length) },
                onResult = { input.setText(it); input.setSelection(input.text.length) },
                onError = { activity.showBanner(context.getString(R.string.voice_nothing), 4) },
            )
            if (!started) activity.showBanner(context.getString(R.string.voice_no_mic), 6)
        }
        dialog.setOnDismissListener { dictation.release() }
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
