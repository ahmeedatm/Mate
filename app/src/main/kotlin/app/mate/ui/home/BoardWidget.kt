package app.mate.ui.home

import android.content.Context
import android.view.View
import android.view.ViewGroup
import app.mate.github.Forge
import app.mate.ui.GitLabView
import app.mate.ui.JiraView
import app.mate.ui.PipelinesView
import app.mate.ui.ScheduleChoicesDialog

/**
 * Jira, GitLab, Pipelines, GitHub et Actions sur un profil (anciens onglets du Deck, Deck V2) : les vues autonomes [JiraView], [GitLabView] et [PipelinesView] dans
 * une carte. Leurs colonnes défilent : le widget garde l'appui long et le balayage. Relues seulement tant que le profil
 * est à l'écran ; proposées au catalogue seulement si l'ordinateur les offre ([WidgetType.offered]).
 */
class BoardWidget(
    context: Context,
    host: HomeHost,
    placed: Placed,
    board: View,
    private val activate: (Boolean) -> Unit,
    private val settings: (() -> Unit)? = null,
) : HomeWidget(context, host, placed) {
    override val view = OwnerFrameLayout(context, ownsLongPress = true, ownsSwipe = true).asCard(12).apply {
        addView(board, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun setActive(active: Boolean) = activate(active)

    override val hasSettings get() = settings != null

    override fun showSettings() {
        settings?.invoke()
    }
}

fun jiraWidget(context: Context, host: HomeHost, placed: Placed): BoardWidget {
    val board = JiraView(context).apply { attach(host.owner) }
    // ⚙ : colonnes affichées et « tous les tickets » ; la vue se relit dès qu'il est enregistré.
    return BoardWidget(context, host, placed, board, board::setActive, board::openColumns)
}

/** MR GitLab, ou PR GitHub (`forge`) : même vue. */
fun gitLabWidget(context: Context, host: HomeHost, placed: Placed, forge: Forge = Forge.GITLAB): BoardWidget {
    val board = GitLabView(context, forge = forge).apply { attach(host.scope) }
    return BoardWidget(context, host, placed, board, board::setActive)
}

/** Pipelines GitLab (⚙ : planifications), ou runs GitHub Actions (`forge`) : même vue. */
fun pipelinesWidget(context: Context, host: HomeHost, placed: Placed, forge: Forge = Forge.GITLAB): BoardWidget {
    val board = PipelinesView(context, forge = forge).apply { attach(host.scope) }
    if (forge == Forge.GITHUB) return BoardWidget(context, host, placed, board, board::setActive)
    // ⚙ : choix des planifications ; la vue les relit dès qu'il est enregistré.
    val choices = ScheduleChoicesDialog(context, host.scope, board::reload)
    // Cachée, le widget ferme aussi son choix de planifications (comme JiraView ses fenêtres).
    return BoardWidget(
        context, host, placed, board,
        { active ->
            board.setActive(active)
            if (!active) choices.dismiss()
        },
        choices::show,
    )
}
