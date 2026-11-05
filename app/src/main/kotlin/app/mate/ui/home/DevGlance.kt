package app.mate.ui.home

import app.mate.gitlab.PipelineMark
import app.mate.gitlab.markOf
import app.mate.net.GitLabMr
import app.mate.net.GitLabMrs
import app.mate.net.GitLabPipeline
import app.mate.net.GitLabPipelines
import app.mate.net.JiraIssue
import app.mate.net.JiraIssues

/**
 * Ce qu'une petite forme d'un widget Dev montre (Pastille, Ligne, Colonne) : un compteur, l'état qui mène (icône et
 * couleur) et les éléments du plus urgent au moins urgent. Les grandes formes gardent le tableau complet.
 */
data class Glance<T>(val count: Int, val lead: PipelineMark, val rows: List<T>)

/** Du plus grave au moins grave : l'état qui mène une liste est le premier de cet ordre qu'on y trouve. */
private val SEVERITY = listOf(PipelineMark.CROSS, PipelineMark.SYNC, PipelineMark.CHECK, PipelineMark.CANCELED)

private fun worst(marks: List<PipelineMark>) = SEVERITY.firstOrNull { it in marks } ?: PipelineMark.NONE

/** MR GitLab ou PR GitHub : le compteur dit celles à relire ; les lignes suivent l'ordre des colonnes du tableau. */
fun mrGlance(mrs: GitLabMrs): Glance<GitLabMr> {
    val rows = (mrs.mine + mrs.review).sortedBy { it.column.ordinal }
    return Glance(mrs.review.size, worst(rows.map { markOf(it.pipeline) }), rows)
}

/** Pipelines GitLab ou runs Actions : le plus récent en tête, son état mène ; le compteur dit les échecs. */
fun pipelineGlance(pipelines: GitLabPipelines): Glance<GitLabPipeline> {
    val rows = (pipelines.schedules + pipelines.mrs + pipelines.pipelines).sortedBy { it.ageSec }
    return Glance(rows.count { markOf(it.status) == PipelineMark.CROSS }, rows.firstOrNull()?.let { markOf(it.status) } ?: PipelineMark.NONE, rows)
}

/** Jira : mes tickets ouverts (ni terminés, ni à quelqu'un d'autre), une fois chacun, dans l'ordre des colonnes. */
fun jiraGlance(issues: JiraIssues): Glance<JiraIssue> {
    val rows = issues.columns.flatMap { it.issues }.filter { !it.done && it.assignee == null }.distinctBy { it.key }
    return Glance(rows.size, PipelineMark.NONE, rows)
}
