package app.mate.ui.home

import android.content.Context
import android.view.View
import android.view.ViewGroup
import app.mate.R
import app.mate.github.Forge
import app.mate.gitlab.pipelineTitle
import app.mate.gitlab.shortProject
import app.mate.gitlab.timeOf
import app.mate.net.BackendClient
import app.mate.net.Pipeline
import app.mate.net.runCatchingCancellable
import app.mate.ui.GitLabView
import app.mate.ui.JiraView
import app.mate.ui.PipelinesView
import app.mate.ui.ScheduleChoicesDialog
import app.mate.ui.errorText
import app.mate.ui.forgeTexts
import app.mate.ui.mrColumnTitle
import app.mate.ui.pipelineTimeText
import kotlinx.coroutines.launch

/**
 * Jira, GitLab, Pipelines, GitHub et Actions sur un profil (anciens onglets du Deck, Deck V2) : les vues autonomes [JiraView], [GitLabView] et [PipelinesView] dans
 * une carte. Leurs colonnes défilent : le widget garde l'appui long et le balayage. Relues seulement tant que le profil
 * est à l'écran ; proposées au catalogue seulement si l'ordinateur les offre ([WidgetType.offered]).
 */
class BoardWidget(
    context: Context,
    host: HomeHost,
    placed: Placed,
    private val board: View,
    private val activate: (Boolean) -> Unit,
    private val settings: (() -> Unit)? = null,
    /** Petites formes (Pastille, Ligne, Colonne) : de quoi remplir [DevGlanceView] ; null : le tableau dans toutes les formes. */
    private val glance: Glancer? = null,
) : HomeWidget(context, host, placed) {
    private val small get() = glance != null && (placed.size.w == 1 || placed.size.h == 1)
    private val compact = glance?.let { DevGlanceView(context, it.title) }
    private var active = false
    private var last: Pair<GlanceContent?, String?>? = null

    override val view = object : OwnerFrameLayout(context) {
        // Le tableau défile : il garde l'appui long et le balayage ; une petite forme les laisse à la grille.
        override val ownsLongPress get() = !small
        override val ownsSwipe get() = !small
    }.asCard(12).apply {
        addView(board, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        compact?.let { addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
    }

    override fun applySize() {
        view.asCard(if (small) 16 else 12)
        compact?.visibility = if (small) View.VISIBLE else View.GONE
        setActive(active)
        last?.let { (content, note) -> compact?.show(content, placed.size.w, placed.size.h, note) }
    }

    override fun setActive(active: Boolean) {
        this.active = active
        activate(active && !small)
        if (active && small) host.scope.launch { load() }
    }

    override suspend fun load() {
        val source = glance ?: return
        if (!active || !small) return
        val shown = runCatchingCancellable { source.load() }.fold({ it }, { null to context.errorText(it) })
        last = shown
        compact?.show(shown.first, placed.size.w, placed.size.h, shown.second)
    }

    override val hasSettings get() = settings != null

    override fun showSettings() {
        settings?.invoke()
    }
}

/** Ce qu'une petite forme d'un tableau montre : son titre, et sa lecture (contenu, ou phrase à la place : à connecter, vide). */
class Glancer(val title: Int, val load: suspend () -> Pair<GlanceContent?, String?>)

fun jiraWidget(context: Context, host: HomeHost, placed: Placed): BoardWidget {
    val board = JiraView(context).apply { attach(host.owner) }
    // ⚙ : colonnes affichées et « tous les tickets » ; la vue se relit dès qu'il est enregistré.
    return BoardWidget(context, host, placed, board, board::setActive, board::openColumns, Glancer(R.string.widget_jira) { jiraIssuesGlance(context, host) })
}

/** Jira en petite forme : mes tickets ouverts en compteur et en lignes, chacun avec sa clé et sa colonne. */
private suspend fun jiraIssuesGlance(context: Context, host: HomeHost): Pair<GlanceContent?, String?> {
    val issues = if (glanceDemo(context)) sampleJira() else BackendClient(context).jira.issues()
    if (!issues.connected) return null to context.getString(R.string.jira_connect)
    val glance = jiraGlance(issues)
    if (glance.rows.isEmpty()) return null to context.getString(R.string.jira_empty)
    val lines = glance.rows.map { issue ->
        val column = issues.columns.first { c -> c.issues.any { it.key == issue.key } }.title
        GlanceLine(issue.summary, "${issue.key} · $column", Pipeline.NONE) {
            host.scope.launch { runCatchingCancellable { BackendClient(context).jira.openOnComputer(issue.key) } }
        }
    }
    return GlanceContent(glance.count, context.getString(R.string.glance_open), lines) to null
}

/** MR GitLab, ou PR GitHub (`forge`) : même vue. */
fun gitLabWidget(context: Context, host: HomeHost, placed: Placed, forge: Forge = Forge.GITLAB): BoardWidget {
    val board = GitLabView(context, forge = forge).apply { attach(host.scope) }
    val title = if (forge == Forge.GITHUB) R.string.widget_github else R.string.widget_gitlab
    return BoardWidget(context, host, placed, board, board::setActive, glance = Glancer(title) { mrsGlance(context, host, forge) })
}

/** MR (ou PR) en petite forme : à relire en compteur, toutes en lignes, chacune avec l'état de son pipeline et sa colonne. */
private suspend fun mrsGlance(context: Context, host: HomeHost, forge: Forge): Pair<GlanceContent?, String?> {
    val texts = forgeTexts(forge)
    val mrs = if (glanceDemo(context)) sampleMrs() else BackendClient(context).forge(forge).mrs()
    if (!mrs.connected) return null to context.getString(texts.connect)
    val glance = mrGlance(mrs)
    if (glance.rows.isEmpty()) return null to context.getString(texts.empty)
    val lines = glance.rows.map { mr ->
        GlanceLine(mr.title, "${shortProject(mr.project)} · ${context.getString(mrColumnTitle(mr.column, forge))}", mr.pipeline) {
            host.scope.launch { runCatchingCancellable { BackendClient(context).forge(forge).open(mr.project, mr.iid) } }
        }
    }
    return GlanceContent(glance.count, context.getString(R.string.glance_to_review), lines) to null
}

/** shortcut: données d'exemple forcées pour les captures des variantes (#185) ; remplacé par l'aperçu de la règle (#178). */
private fun glanceDemo(context: Context) = context.getSharedPreferences("companion", Context.MODE_PRIVATE).getBoolean("glance_demo", false)

/** Pipelines GitLab (⚙ : planifications), ou runs GitHub Actions (`forge`) : même vue. */
fun pipelinesWidget(context: Context, host: HomeHost, placed: Placed, forge: Forge = Forge.GITLAB): BoardWidget {
    val board = PipelinesView(context, forge = forge).apply { attach(host.scope) }
    val title = if (forge == Forge.GITHUB) R.string.widget_actions else R.string.widget_pipelines
    val glance = Glancer(title) { pipelinesGlance(context, host, forge) }
    if (forge == Forge.GITHUB) return BoardWidget(context, host, placed, board, board::setActive, glance = glance)
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
        glance,
    )
}

/** Pipelines (ou runs Actions) en petite forme : les échecs en compteur, les derniers runs en lignes, le plus récent en tête. */
private suspend fun pipelinesGlance(context: Context, host: HomeHost, forge: Forge): Pair<GlanceContent?, String?> {
    val texts = forgeTexts(forge)
    val pipelines = if (glanceDemo(context)) samplePipelines(forge) else BackendClient(context).forge(forge).pipelines()
    if (!pipelines.connected) return null to context.getString(texts.connect)
    val glance = pipelineGlance(pipelines)
    if (glance.rows.isEmpty()) return null to context.getString(texts.boardEmpty)
    val lines = glance.rows.map { p ->
        GlanceLine(pipelineTitle(p), pipelineTimeText(context, timeOf(p)), p.status) {
            host.scope.launch { runCatchingCancellable { BackendClient(context).forge(forge).openPipeline(p.project, p.id) } }
        }
    }
    return GlanceContent(glance.count, context.getString(R.string.glance_failed), lines) to null
}
