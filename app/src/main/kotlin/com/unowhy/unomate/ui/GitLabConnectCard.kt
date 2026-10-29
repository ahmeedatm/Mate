package com.unowhy.unomate.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.unowhy.unomate.R
import com.unowhy.unomate.net.BackendClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Carte « Connecter GitLab » (GitLab et Pipelines, quand l'ordinateur n'a pas de compte connecté) : le bouton ouvre la
 * connexion dans le navigateur de l'ordinateur, le texte dit alors d'autoriser l'accès. La vue qui l'affiche relit
 * toutes les 3 s en attendant et la cache une fois reliée. `compact` : prend toute la largeur (widget L), sinon 460 dp.
 */
class GitLabConnectCard(
    context: Context,
    private val scope: () -> CoroutineScope,
    private val fail: (Exception) -> Unit,
    compact: Boolean = false,
) : LinearLayout(context) {
    private val box = LinearLayout(context)
    private val text = TextView(context)
    private val button = Button(context, null, 0, R.style.PrimaryButton)

    /** « Connecter GitLab » touché : le texte dit d'autoriser l'accès dans le navigateur de l'ordinateur. */
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
            setText(R.string.gitlab_connect)
            setOnClickListener { connect() }
        }
        box.addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        box.addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)).apply { topMargin = dp(if (compact) 10 else 16) })
        addView(box, LayoutParams(if (compact) LayoutParams.MATCH_PARENT else dp(460), LayoutParams.WRAP_CONTENT))
    }

    /** Affiche la carte : `expired` = le compte a été connecté mais GitLab refuse de le renouveler. */
    fun show(expired: Boolean) {
        text.setText(
            when {
                waiting -> R.string.gitlab_connect_waiting
                expired -> R.string.gitlab_reconnect_text
                else -> R.string.gitlab_connect_text
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
                BackendClient(context).system.openLocalPage("gitlab")
                waiting = true
                text.setText(R.string.gitlab_connect_waiting)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }
}
