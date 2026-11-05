package app.mate.ui.home

import app.mate.github.Forge
import app.mate.net.AgentCounts
import app.mate.net.AgentQuota
import app.mate.net.AgentSession
import app.mate.net.AgentState
import app.mate.net.AgentsState
import app.mate.net.GitLabMr
import app.mate.net.GitLabMrs
import app.mate.net.GitLabPipeline
import app.mate.net.GitLabPipelines
import app.mate.net.JiraColumn
import app.mate.net.JiraIssue
import app.mate.net.JiraIssues
import app.mate.net.Metric
import app.mate.net.MrColumn
import app.mate.net.Pipeline
import app.mate.net.QuotaWindow

/** Aperçu des MR GitLab ou des PR GitHub. */
class MrsSample(val mrs: GitLabMrs) : WidgetSample

/** Aperçu des pipelines GitLab ou des runs GitHub Actions. */
class PipelinesSample(val pipelines: GitLabPipelines) : WidgetSample

/** Aperçu de Jira. */
class JiraSample(val issues: JiraIssues) : WidgetSample

/** Aperçu des Agents Claude. */
class AgentsSample(val state: AgentsState) : WidgetSample

/** Aperçu des Chiffres. */
class MetricsSample(val metrics: List<Metric>) : WidgetSample

/** Données d'exemple des widgets Dev (aperçu du catalogue) : sans réseau, sans ordinateur, crédibles. */
fun sampleMrs(): GitLabMrs {
    fun mr(iid: Int, project: String, title: String, column: MrColumn, pipeline: Pipeline, author: String = "moi") =
        GitLabMr(project, iid, title, column == MrColumn.DRAFT, author, pipeline, 1, 2, emptyList(), "", false, "", column)
    return GitLabMrs(
        mine = listOf(
            mr(412, "equipe/facturation", "Arrondi des remises au centime", MrColumn.MERGE, Pipeline.SUCCESS),
            mr(408, "equipe/api", "Pagination des factures par curseur", MrColumn.FIX, Pipeline.FAILED),
            mr(415, "equipe/app-mobile", "Écran de paiement hors ligne", MrColumn.DRAFT, Pipeline.RUNNING),
        ),
        review = listOf(
            mr(97, "equipe/api", "Limiter les exports CSV à 10 000 lignes", MrColumn.REVIEW, Pipeline.SUCCESS, "Eliot"),
            mr(221, "equipe/facturation", "Relances par mail à J+7", MrColumn.REVIEW, Pipeline.RUNNING, "Bruno"),
        ),
    )
}

/** Derniers runs d'exemple : planifications et pipelines de MR (GitLab), ou liste à plat (GitHub Actions). */
fun samplePipelines(forge: Forge): GitLabPipelines {
    fun run(id: Long, project: String, ref: String, status: Pipeline, duration: Int?, age: Int, title: String, mr: Int? = null) =
        GitLabPipeline(project, ref, id, status, duration, age, if (status == Pipeline.FAILED) listOf("tests:api") else emptyList(), title, mr)
    val runs = listOf(
        run(9081, "equipe/api", "main", Pipeline.RUNNING, null, 240, "Déploiement de recette"),
        run(9077, "equipe/facturation", "main", Pipeline.FAILED, 372, 1_800, "Tests de nuit"),
        run(9070, "equipe/api", "pagination-curseur", Pipeline.SUCCESS, 455, 5_400, "Pagination des factures par curseur", 408),
        run(9064, "equipe/app-mobile", "main", Pipeline.SUCCESS, 610, 10_800, "Build iOS et Android"),
    )
    return if (forge == Forge.GITHUB) GitLabPipelines(pipelines = runs.map { it.copy(mrIid = null) })
    else GitLabPipelines(schedules = runs.filter { it.mrIid == null }, mrs = runs.filter { it.mrIid != null })
}

/** Tableau Jira d'exemple : trois colonnes, un ticket d'un collègue et un ticket terminé que la petite forme ne compte pas. */
fun sampleJira(): JiraIssues {
    fun issue(key: String, summary: String, done: Boolean = false, assignee: String? = null) = JiraIssue(key, summary, "Tâche", null, done, assignee)
    return JiraIssues(
        boardsChosen = true,
        columns = listOf(
            JiraColumn("En cours", listOf(issue("FAC-212", "Pagination des factures par curseur"), issue("FAC-219", "Relance des impayés", assignee = "Léa"))),
            JiraColumn("À faire", listOf(issue("FAC-224", "Export comptable au format FEC"), issue("API-88", "Limiter les appels du webhook Stripe"))),
            JiraColumn("Terminé", listOf(issue("FAC-201", "Avoirs partiels", done = true))),
        ),
    )
}

/** Quatre sessions Claude d'exemple : une qui attend une réponse, deux qui travaillent (dont une surveille un pipeline), une finie. */
fun sampleAgents(): AgentsState {
    val now = 1_793_900_000_000L
    fun session(id: String, title: String, state: AgentState, minutes: Int, project: String, waitingFor: String? = null, monitoring: Boolean = false) =
        AgentSession(id, id, project, "main", state, now - minutes * 60_000L, waitingFor, null, emptyList(), minutes * 60_000L, null, false, title = title, monitoring = monitoring, project = project)
    val sessions = listOf(
        session("s1", "Migration des factures vers l'API v2", AgentState.WAITING, 3, "facturation", waitingFor = "Bash"),
        session("s2", "Tests de la pagination par curseur", AgentState.WORKING, 12, "api"),
        session("s3", "Surveiller le pipeline de recette", AgentState.WORKING, 25, "api", monitoring = true),
        session("s4", "Écran de paiement hors ligne", AgentState.DONE, 40, "app-mobile"),
    )
    return AgentsState(sessions, AgentCounts(working = 1, waiting = 1, done = 1, limit = 0, idle = 0, monitoring = 1), sessions.size, true, now, AgentQuota(QuotaWindow(42, null), QuotaWindow(18, null)))
}

/** Chiffres d'exemple, comme ceux qu'on écrit dans metrics.json. */
fun sampleMetrics(): List<Metric> = listOf(
    Metric("Build", "vert"),
    Metric("Tests", "1 248"),
    Metric("Dossier projets", "18 Go"),
    Metric("Tickets ouverts", "7"),
)
