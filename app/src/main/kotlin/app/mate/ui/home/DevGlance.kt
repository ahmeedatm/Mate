package app.mate.ui.home

import app.mate.net.GitLabMr
import app.mate.net.GitLabMrs
import app.mate.net.GitLabPipeline
import app.mate.net.GitLabPipelines
import app.mate.net.JiraIssue
import app.mate.net.JiraIssues
import app.mate.net.Pipeline

/**
 * Ce qu'une petite forme d'un widget Dev montre (Pastille, Ligne, Colonne) : un compteur et les éléments du plus urgent
 * au moins urgent. Les grandes formes gardent le tableau complet.
 */
data class DevGlance<T>(val count: Int, val rows: List<T>)

/** MR GitLab ou PR GitHub : le compteur dit celles à relire ; les lignes suivent l'ordre des colonnes du tableau. */
fun mrGlance(mrs: GitLabMrs): DevGlance<GitLabMr> {
    val rows = (mrs.mine + mrs.review).sortedBy { it.column.ordinal }
    return DevGlance(mrs.review.size, rows)
}

/**
 * Pipelines GitLab ou runs Actions : le compteur dit les échecs, et ils passent en tête (une Ligne n'a la place que de
 * deux ou trois runs : un échec ancien ne doit pas disparaître sous les réussites récentes) ; puis le plus récent.
 */
fun pipelineGlance(pipelines: GitLabPipelines): DevGlance<GitLabPipeline> {
    val rows = (pipelines.schedules + pipelines.mrs + pipelines.pipelines).sortedWith(compareBy({ it.status != Pipeline.FAILED }, { it.ageSec }))
    return DevGlance(rows.count { it.status == Pipeline.FAILED }, rows)
}

/** Jira : mes tickets ouverts (ni terminés, ni à quelqu'un d'autre), une fois chacun, dans l'ordre des colonnes. */
fun jiraGlance(issues: JiraIssues): DevGlance<JiraIssue> {
    val rows = issues.columns.flatMap { it.issues }.filter { !it.done && it.assignee == null }.distinctBy { it.key }
    return DevGlance(rows.size, rows)
}
