package app.mate.ui.home

import app.mate.net.GitLabMr
import app.mate.net.GitLabMrs
import app.mate.net.GitLabPipeline
import app.mate.net.GitLabPipelines
import app.mate.net.JiraColumn
import app.mate.net.JiraIssue
import app.mate.net.JiraIssues
import app.mate.net.MrColumn
import app.mate.net.Pipeline
import org.junit.Assert.assertEquals
import org.junit.Test

class DevGlanceTest {

    private fun mr(iid: Int, column: MrColumn, pipeline: Pipeline = Pipeline.SUCCESS) =
        GitLabMr("equipe/api", iid, "MR $iid", false, "moi", pipeline, 0, 0, emptyList(), "", false, "", column)

    private fun run(id: Int, status: Pipeline, age: Int) = GitLabPipeline("equipe/api", "main", id.toLong(), status, null, age, emptyList())

    private fun issue(key: String, done: Boolean = false, assignee: String? = null) = JiraIssue(key, "Ticket $key", "Tâche", null, done, assignee)

    @Test
    fun `MR - le compteur dit celles a relire, les lignes suivent l'ordre du tableau`() {
        val glance = mrGlance(
            GitLabMrs(
                mine = listOf(mr(1, MrColumn.DRAFT), mr(2, MrColumn.MERGE)),
                review = listOf(mr(3, MrColumn.REVIEW), mr(4, MrColumn.REVIEW)),
            ),
        )
        assertEquals(2, glance.count)
        assertEquals(listOf(2, 3, 4, 1), glance.rows.map { it.iid })
    }

    @Test
    fun `MR - rien d'ouvert, rien a montrer`() {
        val glance = mrGlance(GitLabMrs(emptyList(), emptyList()))
        assertEquals(0, glance.count)
        assertEquals(emptyList<GitLabMr>(), glance.rows)
    }

    @Test
    fun `Pipelines - les echecs en tete (le compteur les annonce), puis le plus recent`() {
        val glance = pipelineGlance(
            GitLabPipelines(
                schedules = listOf(run(1, Pipeline.SUCCESS, 3600), run(2, Pipeline.FAILED, 600)),
                mrs = listOf(run(3, Pipeline.RUNNING, 60)),
            ),
        )
        assertEquals(listOf(2L, 3L, 1L), glance.rows.map { it.id })
        assertEquals(1, glance.count)
    }

    @Test
    fun `Actions - la liste a plat de GitHub compte aussi`() {
        val glance = pipelineGlance(GitLabPipelines(pipelines = listOf(run(7, Pipeline.SUCCESS, 120), run(8, Pipeline.FAILED, 30))))
        assertEquals(listOf(8L, 7L), glance.rows.map { it.id })
    }

    @Test
    fun `Jira - mes tickets ouverts seulement, une fois chacun, dans l'ordre des colonnes`() {
        val glance = jiraGlance(
            JiraIssues(
                boardsChosen = true,
                columns = listOf(
                    JiraColumn("À faire", listOf(issue("A-1"), issue("A-2", assignee = "Eliot"))),
                    JiraColumn("En cours", listOf(issue("A-3"), issue("A-1"))),
                    JiraColumn("Terminé", listOf(issue("A-4", done = true))),
                ),
            ),
        )
        assertEquals(listOf("A-1", "A-3"), glance.rows.map { it.key })
        assertEquals(2, glance.count)
    }
}
