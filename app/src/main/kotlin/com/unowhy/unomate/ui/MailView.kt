package com.unowhy.unomate.ui

import android.content.Context
import android.text.format.DateUtils
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.unowhy.unomate.Prefs
import com.unowhy.unomate.R
import com.unowhy.unomate.net.BackendClient
import com.unowhy.unomate.net.MailMessage
import com.unowhy.unomate.net.MailSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Page Outlook du Deck, au-dessus des touches : compteurs du courrier, les 5 derniers non lus et le prochain rendez-vous du
 * compte Microsoft relié sur l'ordinateur. Lecture seule. Sans compte (ou sans l'accès au courrier) : un bouton ouvre la page
 * de l'ordinateur pour le relier. Relu toutes les minutes tant que la page est affichée, plus vite pendant qu'on relie le compte.
 */
class MailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val counters: View
    private val unread: TextView
    private val junk: TextView
    private val drafts: TextView
    private val sent: TextView
    private val recent: LinearLayout
    private val next: TextView
    private val message: TextView
    private val connect: Button
    private val privateButton: ImageButton

    private lateinit var scope: CoroutineScope
    private var shown = false
    private var active = false
    private var poll: Job? = null
    private var summary: MailSummary? = null
    private var error: String? = null

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_mail, this, true)
        counters = findViewById(R.id.mail_counters)
        unread = findViewById(R.id.mail_unread)
        junk = findViewById(R.id.mail_junk)
        drafts = findViewById(R.id.mail_drafts)
        sent = findViewById(R.id.mail_sent)
        recent = findViewById(R.id.mail_recent)
        next = findViewById(R.id.mail_next)
        message = findViewById(R.id.mail_message)
        connect = findViewById(R.id.mail_connect)
        privateButton = findViewById(R.id.mail_private)
        privateButton.setOnClickListener {
            Prefs.setAgentsPrivate(context, !Prefs.agentsPrivate(context))
            render()
        }
        connect.setOnClickListener { openSetup() }
        visibility = GONE
    }

    fun attach(scope: CoroutineScope) {
        this.scope = scope
    }

    /** Page affichée : Outlook (carte visible) ou une autre (masquée, plus de lecture). */
    fun bind(show: Boolean) {
        if (show == shown) return
        shown = show
        visibility = if (show) VISIBLE else GONE
        if (show) {
            summary = null
            error = null
            render()
        }
        restart()
    }

    /** Deck à l'écran ou non : on ne relit le courrier que s'il est visible. */
    fun setActive(active: Boolean) {
        this.active = active
        restart()
    }

    private fun restart() {
        poll?.cancel()
        if (!shown || !active) return
        poll = scope.launch {
            while (isActive) {
                try {
                    summary = BackendClient(context).mail.summary()
                    error = null
                } catch (e: Exception) {
                    error = context.errorText(e)
                }
                render()
                // Compte pas encore relié : on revoit vite le résultat une fois la page de l'ordinateur remplie.
                delay(if (summary is MailSummary.Ok) POLL_MS else SETUP_POLL_MS)
            }
        }
    }

    private fun openSetup() {
        scope.launch {
            try {
                BackendClient(context).system.openLocalPage("agenda")
                Toast.makeText(context, R.string.mail_setup_opened, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, context.errorText(e), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun render() {
        val data = summary
        val hidden = Prefs.agentsPrivate(context)
        privateButton.isSelected = hidden
        privateButton.setImageResource(if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
        val ok = data as? MailSummary.Ok
        counters.visibility = if (ok != null) VISIBLE else GONE
        recent.visibility = if (ok != null) VISIBLE else GONE
        next.visibility = if (ok != null) VISIBLE else GONE
        val text = when {
            ok != null -> null
            data is MailSummary.NoAccount -> context.getString(R.string.mail_no_account)
            data is MailSummary.Reconnect -> context.getString(R.string.mail_reconnect)
            else -> error
        }
        message.visibility = if (text != null) VISIBLE else GONE
        message.text = text
        connect.visibility = if (data is MailSummary.NoAccount || data is MailSummary.Reconnect) VISIBLE else GONE
        connect.setText(if (data is MailSummary.Reconnect) R.string.mail_reconnect_button else R.string.mail_connect)
        if (ok == null) return

        unread.text = context.getString(R.string.mail_count, ok.unread)
        junk.text = context.getString(R.string.mail_count, ok.junk)
        drafts.text = context.getString(R.string.mail_count, ok.drafts)
        sent.text = context.getString(R.string.mail_count, ok.sent)
        showRecent(ok.recent, hidden)
        next.text = ok.next?.let {
            val whenText = DateUtils.formatDateTime(context, it.startMs, FORMAT_NEXT)
            if (hidden) context.getString(R.string.mail_next_private, whenText) else context.getString(R.string.mail_next, it.title, whenText)
        } ?: context.getString(R.string.mail_next_none)
    }

    private fun showRecent(messages: List<MailMessage>, hidden: Boolean) {
        recent.removeAllViews()
        if (messages.isEmpty()) {
            recent.addView(row(context.getString(R.string.mail_no_unread), null, null, bold = false))
        } else if (hidden) {
            // Mode privé : ni expéditeurs ni objets, seulement qu'il y en a.
            recent.addView(row(context.getString(R.string.mail_private_hidden), null, null, bold = false))
        } else {
            for (m in messages) {
                recent.addView(row(m.from, m.subject.ifBlank { context.getString(R.string.mail_no_subject) }, time(m.receivedMs), bold = true))
            }
        }
    }

    private fun row(from: String, subject: String?, time: String?, bold: Boolean): View {
        val view = LayoutInflater.from(context).inflate(R.layout.item_mail, recent, false)
        view.findViewById<TextView>(R.id.mail_from).apply {
            text = from
            if (!bold) {
                setTextColor(themeColor(context, R.attr.c_text_dim))
                setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
        view.findViewById<TextView>(R.id.mail_subject).text = subject.orEmpty()
        view.findViewById<TextView>(R.id.mail_time).text = time.orEmpty()
        return view
    }

    /** Heure pour un message du jour, date courte sinon. */
    private fun time(ms: Long): String =
        DateUtils.formatDateTime(context, ms, if (DateUtils.isToday(ms)) DateUtils.FORMAT_SHOW_TIME else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

    private companion object {
        const val POLL_MS = 60_000L
        const val SETUP_POLL_MS = 8_000L
        /** « mar. 29 oct., 11:00 » : le jour compte, un rendez-vous peut être dans la semaine. */
        const val FORMAT_NEXT = DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL
    }
}
