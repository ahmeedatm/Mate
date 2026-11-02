package app.mate.ui.home

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.Mail
import app.mate.net.runCatchingCancellable
import app.mate.ui.errorText
import kotlinx.coroutines.launch

/**
 * Nombre de mails non lus (boîte de réception de tous les comptes Microsoft et Google connectés sur l'ordinateur) ;
 * un toucher ouvre la boîte sur l'ordinateur. Les jetons restent sur l'ordinateur : la tablette ne reçoit que des nombres.
 * Relu chaque minute.
 */
class MailWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val count = label(if (placed.size == WidgetSize.S) 36f else 52f, R.attr.c_text, light = true).apply {
        gravity = Gravity.CENTER
        maxLines = 1
    }
    private val status = label(14f, R.attr.c_text_dim).apply {
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    override val view = LinearLayout(context).asCard(16).apply {
        orientation = LinearLayout.VERTICAL
        addView(cardTitle(R.string.widget_mail), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setOnClickListener { open() }
        isClickable = false // rien à ouvrir tant qu'aucun compte n'a répondu
    }

    private var mail: Mail? = null
    private var opening = false

    override fun setActive(active: Boolean) {
        if (active) host.scope.launch { load() }
    }

    override suspend fun load() {
        runCatchingCancellable { BackendClient(context).mail.unread() }
            .onSuccess { mail = it; render(null) }
            .onFailure { render(context.errorText(it)) }
    }

    /** `error` : phrase de l'échec de la dernière lecture ; le dernier nombre reçu reste affiché dessus. */
    private fun render(error: String?) {
        val current = mail
        val note = when (mailNote(current, error != null)) {
            MailNote.ERROR -> error
            MailNote.NO_ACCOUNT -> context.getString(R.string.widget_mail_empty)
            MailNote.ACCOUNT_ERROR -> current?.accounts?.firstNotNullOfOrNull { it.error }
            null -> context.resources.getQuantityString(R.plurals.widget_mail_unread, current?.total ?: 0)
        }
        val shown = current?.takeIf { it.hasCount }?.total
        count.text = shown?.toString() ?: "–"
        status.text = note
        status.visibility = if (note == null) View.GONE else View.VISIBLE
        val target = current?.target
        view.isClickable = target != null
        // Lu à voix haute : le nombre, puis le geste (le tiret seul ne dit rien).
        view.contentDescription =
            if (target != null && shown != null) context.getString(R.string.widget_mail_a11y, shown) else note
    }

    private fun open() {
        val target = mail?.target ?: return
        if (opening) return
        opening = true
        host.scope.launch {
            runCatchingCancellable { BackendClient(context).mail.open(target.provider) }
                .onFailure { Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show() }
            opening = false
        }
    }
}
