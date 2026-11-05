package app.mate.ui.home

import app.mate.net.GitLabMr
import app.mate.net.GitLabMrs
import app.mate.net.GitLabPipeline
import app.mate.net.GitLabPipelines
import app.mate.net.JiraIssue
import app.mate.net.JiraIssues
import app.mate.net.Pipeline

/**
 * Ce qu'une petite forme d'un widget Dev montre (Pastille, Ligne, Colonne) : un compteur, l'état qui mène (icône et
 * couleur) et les éléments du plus urgent au moins urgent. Les grandes formes gardent le tableau complet.
 */
data class DevGlance<T>(val count: Int, val lead: Pipeline, val rows: List<T>)

/** Du plus grave au moins grave : l'état qui mène une liste est le premier de cet ordre qu'on y trouve. */
private val SEVERITY = listOf(Pipeline.FAILED, Pipeline.RUNNING, Pipeline.SUCCESS, Pipeline.CANCELED)

private fun worst(states: List<Pipeline>) = SEVERITY.firstOrNull { it in states } ?: Pipeline.NONE

/** MR GitLab ou PR GitHub : le compteur dit celles à relire ; les lignes suivent l'ordre des colonnes du tableau. */
fun mrGlance(mrs: GitLabMrs): DevGlance<GitLabMr> {
    val rows = (mrs.mine + mrs.review).sortedBy { it.column.ordinal }
    return DevGlance(mrs.review.size, worst(rows.map { it.pipeline }), rows)
}

/** Pipelines GitLab ou runs Actions : le plus récent en tête, son état mène ; le compteur dit les échecs. */
fun pipelineGlance(pipelines: GitLabPipelines): DevGlance<GitLabPipeline> {
    val rows = (pipelines.schedules + pipelines.mrs + pipelines.pipelines).sortedBy { it.ageSec }
    return DevGlance(rows.count { it.status == Pipeline.FAILED }, rows.firstOrNull()?.status ?: Pipeline.NONE, rows)
}

/** Jira : mes tickets ouverts (ni terminés, ni à quelqu'un d'autre), une fois chacun, dans l'ordre des colonnes. */
fun jiraGlance(issues: JiraIssues): DevGlance<JiraIssue> {
    val rows = issues.columns.flatMap { it.issues }.filter { !it.done && it.assignee == null }.distinctBy { it.key }
    return DevGlance(rows.size, Pipeline.NONE, rows)
}
