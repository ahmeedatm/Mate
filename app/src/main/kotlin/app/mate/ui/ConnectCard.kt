package app.mate.ui

import android.content.Context
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import app.mate.R
import app.mate.github.Forge
import app.mate.net.BackendClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Service à relier depuis la page de l'ordinateur : sa section (`POST /local/open`) et les textes de la carte. */
class ConnectTexts(
    val section: String,
    @param:StringRes val button: Int,
    @param:StringRes val text: Int,
    @param:StringRes val reconnectText: Int,
    @param:StringRes val waiting: Int,
)

/** Carte de la forge (GitLab ou GitHub). */
fun forgeConnect(forge: Forge) = forgeTexts(forge).let { ConnectTexts(forge.key, it.connect, it.connectText, it.reconnectText, it.connectWaiting) }

/** Carte de Jira : jeton d'API à coller dans la page de l'ordinateur. */
val JIRA_CONNECT = ConnectTexts("jira", R.string.jira_connect, R.string.jira_connect_text, R.string.jira_reconnect_text, R.string.jira_connect_waiting)

/**
 * Carte « Connecter GitLab », « Connecter GitHub » ou « Connecter Jira » (GitLab, Pipelines, GitHub, Actions et Jira, quand
 * l'ordinateur n'a pas de compte connecté) : le bouton ouvre la section du service dans la page ou le navigateur de
 * l'ordinateur, le texte dit alors quoi y faire. La vue qui l'affiche relit toutes les 3 s en attendant et la cache une
 * fois reliée. `compact` : prend toute la largeur (widget L), sinon 460 dp.
 */
class ConnectCard(
    context: Context,
    private val texts: ConnectTexts,
    private val scope: () -> CoroutineScope,
    private val fail: (Exception) -> Unit,
    compact: Boolean = false,
) : LinearLayout(context) {
    private val box = LinearLayout(context)
    private val text = TextView(context)
    private val button = Button(context, null, 0, R.style.PrimaryButton)

    /** Bouton touché : le texte dit quoi faire sur l'ordinateur. */
    private var waiting = false

    init {
        gravity = Gravity.CENTER
        visibility = GONE
        box.apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.row_bg)
            setPadding(dp(if (compact) 16 else 24), dp(if (compact) 14 else 20), dp(if (compact) 16 else 24), dp(if (compact) 14 else 20))
        }
        text.apply {
            textSize = if (compact) 15f else 16f
            gravity = Gravity.CENTER
            setTextColor(themeColor(context, R.attr.c_text))
        }
        button.apply {
            setText(texts.button)
            setOnClickListener { connect() }
        }
        box.addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        box.addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)).apply { topMargin = dp(if (compact) 10 else 16) })
        addView(box, LayoutParams(if (compact) LayoutParams.MATCH_PARENT else dp(460), LayoutParams.WRAP_CONTENT))
    }

    /** Affiche la carte : `expired` = le compte a été connecté mais le service refuse de le renouveler. */
    fun show(expired: Boolean) {
        text.setText(
            when {
                waiting -> texts.waiting
                expired -> texts.reconnectText
                else -> texts.text
            },
        )
    }

    /** Compte relié : la prochaine déconnexion repart du texte de départ. */
    fun reset() {
        waiting = false
    }

    private fun connect() {
        scope().launch {
            try {
                BackendClient(context).system.openLocalPage(texts.section)
                waiting = true
                text.setText(texts.waiting)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }
}
