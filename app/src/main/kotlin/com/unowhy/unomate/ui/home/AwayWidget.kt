package com.unowhy.unomate.ui.home

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import com.unowhy.unomate.Prefs
import com.unowhy.unomate.R
import com.unowhy.unomate.away.AwayLogic
import com.unowhy.unomate.away.AwayState
import com.unowhy.unomate.focus.FocusTimer
import com.unowhy.unomate.net.BackendClient
import com.unowhy.unomate.net.runCatchingCancellable
import com.unowhy.unomate.ui.errorText
import kotlinx.coroutines.launch

/**
 * « Je m'absente / Reprendre ». Je m'absente : coupe le micro de l'ordinateur (s'il ne l'est pas déjà), met le Focus en
 * pause (pause, pas arrêt) et demande à l'ordinateur de garder ses fenêtres. Reprendre : rouvre le micro et relance le
 * Focus seulement si c'est l'absence qui les avait coupés, et remet les fenêtres. L'absence est gardée dans les
 * préférences ([Prefs.away]) : elle survit à la recréation de l'activité. Les minutes se mettent à jour chaque minute.
 * S : le bouton seul ; M : titre et bouton ; L : en plus ce que fait le bouton, ou la durée de l'absence en grand.
 */
class AwayWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    override val view = LinearLayout(context).asCard(16).apply { orientation = LinearLayout.VERTICAL }

    private val title = cardTitle(R.string.widget_away)
    private val status = label(14f, R.attr.c_text_dim)
    private val leave = button(R.style.ActionButton, R.string.away_leave) { leave() }
    private val back = button(R.style.PrimaryButton, R.string.away_resume) { resume() }

    private var busy = false

    override fun applySize() {
        view.removeAllViews()
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val small = placed.size == WidgetSize.S
        val large = placed.size == WidgetSize.L
        view.setPadding(dp(if (small) 10 else 16), dp(if (small) 10 else 16), dp(if (small) 10 else 16), dp(if (small) 10 else 16))
        status.apply {
            textSize = if (large) 22f else 14f
            maxLines = if (large) 3 else if (small) 2 else 1
            ellipsize = TextUtils.TruncateAt.END
        }
        for (b in listOf(leave, back)) {
            b.textSize = if (large) 22f else if (small) 14f else 18f
            // Une case est trop étroite pour l'icône et les marges d'un bouton ordinaire.
            b.setPadding(dp(if (small) 4 else 20), 0, dp(if (small) 4 else 20), 0)
            b.compoundDrawablePadding = dp(if (small) 0 else 10)
            b.setCompoundDrawablesRelativeWithIntrinsicBounds(if (small) 0 else if (b === leave) R.drawable.ic_cafe else R.drawable.ic_play, 0, 0, 0)
        }
        if (!small) view.addView(title, LinearLayout.LayoutParams(match, wrap))
        view.addView(status, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(if (large) 8 else 2) })
        for (b in listOf(leave, back)) {
            view.addView(b, LinearLayout.LayoutParams(match, 0, 1f).apply { topMargin = dp(if (small) 0 else 8) })
        }
        render()
    }

    override fun onMinute() = render()

    override suspend fun load() = render()

    override fun setActive(active: Boolean) {
        if (active) render()
    }

    private fun button(style: Int, text: Int, onClick: () -> Unit) = Button(context, null, 0, style).apply {
        setText(text)
        minHeight = dp(56)
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun render() {
        val away = Prefs.away(context)
        leave.visibility = if (away == null) View.VISIBLE else View.GONE
        back.visibility = if (away == null) View.GONE else View.VISIBLE
        leave.isEnabled = !busy
        back.isEnabled = !busy
        status.text = if (away == null) context.getString(R.string.away_hint) else elapsed(away)
        // Hors absence, la phrase d'aide n'est montrée qu'en L : ailleurs la place va au bouton.
        status.visibility = if (away == null && placed.size != WidgetSize.L) View.GONE else View.VISIBLE
    }

    private fun elapsed(away: AwayState): String {
        val minutes = AwayLogic.minutes(away, System.currentTimeMillis())
        return when {
            minutes < 1 -> context.getString(R.string.away_since_now)
            minutes < 60 -> context.getString(R.string.away_since_min, minutes)
            else -> context.getString(R.string.away_since_hour, minutes / 60, minutes % 60)
        }
    }

    private fun fail(e: Throwable) = Toast.makeText(context, context.errorText(e), Toast.LENGTH_LONG).show()

    private fun leave() {
        if (busy) return
        busy = true
        render()
        host.scope.launch {
            try {
                val client = BackendClient(context)
                // Le micro d'abord : sans réponse de l'ordinateur, rien ne change et l'absence ne commence pas.
                val micMuted = runCatchingCancellable { client.system.audio().micMuted }.getOrElse { return@launch fail(it) }
                if (!micMuted) runCatchingCancellable { client.system.setAudio(micMuted = true) }.onFailure { return@launch fail(it) }
                val focusRunning = FocusTimer.state.running
                if (focusRunning) FocusTimer.toggle()
                Prefs.setAway(context, AwayLogic.begin(System.currentTimeMillis(), micMuted, focusRunning))
                busy = false
                render()
                // Les fenêtres se lisent en quelques secondes sur Mac : l'absence a déjà commencé.
                runCatchingCancellable { client.away.start() }.onFailure { fail(it) }
            } finally {
                busy = false
                render()
            }
        }
    }

    private fun resume() {
        val away = Prefs.away(context) ?: return render()
        if (busy) return
        busy = true
        render()
        host.scope.launch {
            try {
                val client = BackendClient(context)
                // Les erreurs se disent mais ne retiennent pas l'absence : sinon le bouton resterait bloqué ordinateur éteint.
                if (AwayLogic.shouldUnmute(away)) runCatchingCancellable { client.system.setAudio(micMuted = false) }.onFailure { fail(it) }
                val focus = FocusTimer.state
                if (AwayLogic.shouldResumeFocus(away, focus.running, focus.remainingMs, focus.durationMs)) FocusTimer.toggle()
                Prefs.setAway(context, null)
                busy = false
                render()
                runCatchingCancellable { client.away.resume() }.onFailure { fail(it) }
            } finally {
                busy = false
                render()
            }
        }
    }
}
