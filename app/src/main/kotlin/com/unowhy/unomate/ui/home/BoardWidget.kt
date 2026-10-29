package com.unowhy.unomate.ui.home

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.unowhy.unomate.ui.GitLabView
import com.unowhy.unomate.ui.JiraView
import com.unowhy.unomate.ui.PipelinesView

/**
 * Jira, GitLab et Pipelines sur un profil (anciens onglets du Deck, Deck V2) : les vues autonomes [JiraView], [GitLabView] et [PipelinesView] dans
 * une carte. Leurs colonnes défilent : le widget garde l'appui long et le balayage. Relues seulement tant que le profil
 * est à l'écran ; proposées au catalogue seulement si l'ordinateur les offre ([WidgetType.offered]).
 */
class BoardWidget(
    context: Context,
    host: HomeHost,
    placed: Placed,
    board: View,
    private val activate: (Boolean) -> Unit,
) : HomeWidget(context, host, placed) {
    override val view = OwnerFrameLayout(context, ownsLongPress = true, ownsSwipe = true).asCard(12).apply {
        addView(board, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun setActive(active: Boolean) = activate(active)
}

fun jiraWidget(context: Context, host: HomeHost, placed: Placed): BoardWidget {
    val board = JiraView(context).apply { attach(host.owner) }
    return BoardWidget(context, host, placed, board, board::setActive)
}

fun gitLabWidget(context: Context, host: HomeHost, placed: Placed): BoardWidget {
    val board = GitLabView(context).apply { attach(host.scope) }
    return BoardWidget(context, host, placed, board, board::setActive)
}

fun pipelinesWidget(context: Context, host: HomeHost, placed: Placed): BoardWidget {
    val board = PipelinesView(context).apply { attach(host.scope) }
    return BoardWidget(context, host, placed, board, board::setActive)
}
