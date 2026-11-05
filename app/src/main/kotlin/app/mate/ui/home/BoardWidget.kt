package app.mate.ui.home

import android.content.Context
import android.view.View
import android.view.ViewGroup
import app.mate.MainActivity
import app.mate.R
import app.mate.github.Forge
import app.mate.gitlab.pipelineTitle
import app.mate.gitlab.shortProject
import app.mate.gitlab.timeOf
import app.mate.net.BackendClient
import app.mate.net.GitLabMrs
import app.mate.net.GitLabPipelines
import app.mate.net.JiraIssues
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
 * est à l'écran ; proposées au catalogue seulement si l'ordinateur les offre ([WidgetType.offered]). Pastille, Ligne et
 * Colonne : le résumé ([DevGlanceView]) ; Bloc, Grand et Plein écran : le tableau.
 */
class BoardWidget(
    context: Context,
    host: HomeHost,
    placed: Placed,
    private val board: View,
    private val activate: (Boolean) -> Unit,
    private val settings: (() -> Unit)? = null,
    /** Petites formes (Pastille, Ligne, Colonne) et aperçu : de quoi remplir [DevGlanceView] et le tableau. */
    private val glance: Glancer<*>,
) : HomeWidget(context, host, placed) {
    private val small get() = family == ShapeFamily.PILL || family == ShapeFamily.ROW || family == ShapeFamily.COLUMN
    private val compact = DevGlanceView(context, glance.title)
    private var active = false
    private var last: Pair<GlanceContent?, String?>? = null

    /** Numéro de la dernière lecture lancée : la réponse d'une lecture dépassée est ignorée. */
    private var lastRead = 0

    /** Aperçu du catalogue : l'exemple reste, ni lecture ni boucle. */
    private var previewing = false

    override val view = object : OwnerFrameLayout(context) {
        // Le tableau défile : il garde l'appui long et le balayage ; une petite forme les laisse à la grille.
        override val ownsLongPress get() = !small
        override val ownsSwipe get() = !small
    }.asCard(12).apply {
        addView(board, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(compact, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun applySize() {
        view.asCard(if (small) 16 else 12)
        compact.visibility = if (small) View.VISIBLE else View.GONE
        if (previewing) {
            board.visibility = if (small) View.GONE else View.VISIBLE
        } else {
            activate(active && !small)
            // Passé d'une grande forme à une petite sans rien lu encore : une lecture tout de suite.
            if (active && small && last == null) host.scope.launch { load() }
        }
        // Avant la première lecture, le titre seul : la carte n'est jamais vide.
        val (content, note) = last ?: (null to "")
        compact.show(content, placed.size.w, placed.size.h, note)
    }

    override fun showPreview(sample: WidgetSample) {
        last = glance.preview(sample) ?: return
        previewing = true
        applySize()
    }

    override fun setActive(active: Boolean) {
        val started = active && !this.active
        this.active = active
        if (previewing) return
        activate(active && !small)
        // Seulement quand la page arrive à l'écran : ensuite, la boucle de l'Accueil relit chaque minute.
        if (started && small) host.scope.launch { load() }
    }

    override suspend fun load() {
        if (previewing || !active || !small) return
        val id = ++lastRead
        val shown = runCatchingCancellable { glance.load() }.fold({ it }, { null to context.errorText(it) })
        if (id != lastRead || !active || !small) return
        last = shown
        compact.show(shown.first, placed.size.w, placed.size.h, shown.second)
    }

    override val hasSettings get() = settings != null

    override fun showSettings() {
        settings?.invoke()
    }
}

/**
 * Ce qu'un tableau montre hors de sa forme pleine : son titre, la lecture de ses données (réseau), leur résumé pour
 * [DevGlanceView] (contenu, ou phrase à la place : à connecter, vide) et, pour l'aperçu, l'exemple à mettre dans le tableau.
 */
class Glancer<T>(
    val title: Int,
    private val fetch: suspend () -> T,
    private val summary: (T) -> Pair<GlanceContent?, String?>,
    private val sampleOf: (WidgetSample) -> T?,
    private val showInBoard: (T) -> Unit,
) {
    suspend fun load() = summary(fetch())

    /** Aperçu : l'exemple dans le tableau, et son résumé ; null si l'exemple n'est pas celui de ce tableau. */
    fun preview(sample: WidgetSample): Pair<GlanceContent?, String?>? = sampleOf(sample)?.let {
        showInBoard(it)
        summary(it)
    }
}

/** Ouvre un élément d'une ligne sur l'ordinateur, et le dit dans le bandeau : ouvert, ou l'erreur (ordinateur injoignable…). */
private fun openOnComputer(context: Context, host: HomeHost, open: suspend () -> Unit) {
    host.scope.launch {
        val text = runCatchingCancellable { open() }.fold({ context.getString(R.string.opened_on_computer) }, { context.errorText(it) })
        (context as? MainActivity)?.showBanner(text, BANNER_SECONDS)
    }
}

/** Durée du bandeau après un toucher sur une ligne. */
private const val BANNER_SECONDS = 3

fun jiraWidget(context: Context, host: HomeHost, placed: Placed): BoardWidget {
    val board = JiraView(context).apply { attach(host.owner) }
    val glance = Glancer(R.string.widget_jira, { BackendClient(context).jira.issues() }, { jiraSummary(context, host, it) }, { (it as? JiraSample)?.issues }, board::showSample)
    // ⚙ : colonnes affichées et « tous les tickets » ; la vue se relit dès qu'il est enregistré.
    return BoardWidget(context, host, placed, board, board::setActive, board::openColumns, glance)
}

/** Jira en petite forme : mes tickets ouverts en compteur et en lignes, chacun avec sa clé et sa colonne. */
private fun jiraSummary(context: Context, host: HomeHost, issues: JiraIssues): Pair<GlanceContent?, String?> {
    if (!issues.connected) return null to context.getString(R.string.jira_connect)
    val glance = jiraGlance(issues)
    if (glance.rows.isEmpty()) return null to context.getString(R.string.jira_empty)
    val lines = glance.rows.map { issue ->
        val column = issues.columns.first { c -> c.issues.any { it.key == issue.key } }.title
        GlanceLine(issue.summary, "${issue.key} · $column", Pipeline.NONE) {
            openOnComputer(context, host) { BackendClient(context).jira.openOnComputer(issue.key) }
        }
    }
    return GlanceContent(glance.count, context.resources.getQuantityString(R.plurals.glance_open, glance.count), lines) to null
}

/** MR GitLab, ou PR GitHub (`forge`) : même vue. */
fun gitLabWidget(context: Context, host: HomeHost, placed: Placed, forge: Forge = Forge.GITLAB): BoardWidget {
    val board = GitLabView(context, forge = forge).apply { attach(host.scope) }
    val title = if (forge == Forge.GITHUB) R.string.widget_github else R.string.widget_gitlab
    val glance = Glancer(title, { BackendClient(context).forge(forge).mrs() }, { mrsSummary(context, host, forge, it) }, { (it as? MrsSample)?.mrs }, board::showSample)
    return BoardWidget(context, host, placed, board, board::setActive, glance = glance)
}

/** MR (ou PR) en petite forme : à relire en compteur, toutes en lignes, chacune avec l'état de son pipeline et sa colonne. */
private fun mrsSummary(context: Context, host: HomeHost, forge: Forge, mrs: GitLabMrs): Pair<GlanceContent?, String?> {
    val texts = forgeTexts(forge)
    if (!mrs.connected) return null to context.getString(texts.connect)
    val glance = mrGlance(mrs)
    if (glance.rows.isEmpty()) return null to context.getString(texts.empty)
    val lines = glance.rows.map { mr ->
        GlanceLine(mr.title, "${shortProject(mr.project)} · ${context.getString(mrColumnTitle(mr.column, forge))}", mr.pipeline) {
            openOnComputer(context, host) { BackendClient(context).forge(forge).open(mr.project, mr.iid) }
        }
    }
    return GlanceContent(glance.count, context.getString(R.string.glance_to_review), lines) to null
}

/** Pipelines GitLab (⚙ : planifications), ou runs GitHub Actions (`forge`) : même vue. */
fun pipelinesWidget(context: Context, host: HomeHost, placed: Placed, forge: Forge = Forge.GITLAB): BoardWidget {
    val board = PipelinesView(context, forge = forge).apply { attach(host.scope) }
    val title = if (forge == Forge.GITHUB) R.string.widget_actions else R.string.widget_pipelines
    val glance = Glancer(title, { BackendClient(context).forge(forge).pipelines() }, { pipelinesSummary(context, host, forge, it) }, { (it as? PipelinesSample)?.pipelines }, board::showSample)
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

/** Pipelines (ou runs Actions) en petite forme : les échecs en compteur, les derniers runs en lignes, les échecs en tête. */
private fun pipelinesSummary(context: Context, host: HomeHost, forge: Forge, pipelines: GitLabPipelines): Pair<GlanceContent?, String?> {
    val texts = forgeTexts(forge)
    if (!pipelines.connected) return null to context.getString(texts.connect)
    val glance = pipelineGlance(pipelines)
    if (glance.rows.isEmpty()) return null to context.getString(texts.boardEmpty)
    val lines = glance.rows.map { p ->
        GlanceLine(pipelineTitle(p), pipelineTimeText(context, timeOf(p)), p.status) {
            openOnComputer(context, host) { BackendClient(context).forge(forge).openPipeline(p.project, p.id) }
        }
    }
    return GlanceContent(glance.count, context.getString(R.string.glance_failed), lines) to null
}
