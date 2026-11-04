package app.mate.gitlab

import app.mate.net.GitLabPipeline
import app.mate.net.GitLabPipelines
import app.mate.net.Pipeline
import app.mate.net.ScheduleChoice
import app.mate.net.ScheduleProject
import app.mate.net.ScheduleRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PipelinesLogicTest {

    private fun pipeline(
        status: Pipeline = Pipeline.SUCCESS,
        id: Int = 1,
        project: String = "equipe-demo/facturation",
        ref: String = "main",
        duration: Int? = null,
        age: Int = 0,
        jobs: List<String> = emptyList(),
        title: String = "",
        mrIid: Int? = null,
    ) = GitLabPipeline(project, ref, id.toLong(), status, duration, age, jobs, title, mrIid)

    @Test
    fun `chaque statut a sa propre icone`() {
        assertEquals(PipelineMark.CHECK, markOf(Pipeline.SUCCESS))
        assertEquals(PipelineMark.CROSS, markOf(Pipeline.FAILED))
        assertEquals(PipelineMark.SYNC, markOf(Pipeline.RUNNING))
        assertEquals(PipelineMark.CANCELED, markOf(Pipeline.CANCELED))
        assertEquals(PipelineMark.NONE, markOf(Pipeline.NONE))
        assertEquals(Pipeline.entries.size, Pipeline.entries.map { markOf(it) }.toSet().size)
    }

    @Test
    fun `une duree en secondes, en minutes et secondes, ou en heures et minutes`() {
        assertEquals(Span(SpanUnit.SECONDS, 0), durationSpan(0))
        assertEquals(Span(SpanUnit.SECONDS, 59), durationSpan(59))
        assertEquals(Span(SpanUnit.MINUTES, 1, 0), durationSpan(60))
        assertEquals(Span(SpanUnit.MINUTES, 6, 12), durationSpan(372))
        assertEquals(Span(SpanUnit.MINUTES, 59, 59), durationSpan(3599))
        assertEquals(Span(SpanUnit.HOURS, 1, 5), durationSpan(3900))
        assertEquals(Span(SpanUnit.SECONDS, 0), durationSpan(-5))
    }

    @Test
    fun `un age s'arrondit vers le bas a l'unite la plus grande`() {
        assertEquals(Span(SpanUnit.SECONDS, 20), ageSpan(20))
        assertEquals(Span(SpanUnit.MINUTES, 4), ageSpan(299))
        assertEquals(Span(SpanUnit.HOURS, 1), ageSpan(3600))
        assertEquals(Span(SpanUnit.HOURS, 23), ageSpan(86_399))
        assertEquals(Span(SpanUnit.DAYS, 3), ageSpan(3 * 86_400 + 5000))
        assertEquals(Span(SpanUnit.SECONDS, 0), ageSpan(-1))
    }

    @Test
    fun `en cours, l'age depuis le depart sans duree, termine, duree et age`() {
        assertEquals(PipelineTime.Since(Span(SpanUnit.MINUTES, 4)), timeOf(pipeline(Pipeline.RUNNING, age = 240, duration = 90)))
        assertEquals(
            PipelineTime.Took(Span(SpanUnit.MINUTES, 6, 12), Span(SpanUnit.HOURS, 1)),
            timeOf(pipeline(duration = 372, age = 3700)),
        )
        // Annulé ou sans pipeline, sans durée : l'âge seul.
        assertEquals(PipelineTime.Ago(Span(SpanUnit.HOURS, 2)), timeOf(pipeline(Pipeline.CANCELED, age = 7200)))
    }

    @Test
    fun `le titre d'une planification dit le projet puis sa description, ou la ref sans description`() {
        assertEquals("facturation · Nightly", pipelineTitle(pipeline(title = "Nightly")))
        assertEquals("facturation · main", pipelineTitle(pipeline()))
        assertEquals("uem · Smoke test", pipelineTitle(pipeline(project = "softwares/products/uem", title = "Smoke test")))
    }

    @Test
    fun `le titre d'un pipeline de MR dit le projet, le numero puis le titre de la MR`() {
        assertEquals("uem!92 · Corriger le jeton", pipelineTitle(pipeline(project = "softwares/products/uem", title = "Corriger le jeton", mrIid = 92)))
    }

    @Test
    fun `les jobs en echec ne se montrent que sur un echec`() {
        assertEquals("tests-unitaires, tests-e2e", failedJobsText(pipeline(Pipeline.FAILED, jobs = listOf("tests-unitaires", "tests-e2e"))))
        assertNull(failedJobsText(pipeline(Pipeline.FAILED)))
        assertNull(failedJobsText(pipeline(Pipeline.SUCCESS, jobs = listOf("x"))))
    }

    @Test
    fun `au plus 8 lignes, dans l'ordre du compagnon`() {
        val rows = (1..10).map { pipeline(id = it) }
        assertEquals((1L..8L).toList(), visiblePipelines(rows).map { it.id })
        assertEquals(emptyList<GitLabPipeline>(), visiblePipelines(emptyList()))
    }

    @Test
    fun `une nouvelle lecture ne change que les temps, les lignes restent les memes`() {
        fun read(age: Int, duration: Int?, status: Pipeline = Pipeline.RUNNING) =
            GitLabPipelines(schedules = listOf(pipeline(status, age = age, duration = duration)), mrs = listOf(pipeline(id = 2, age = age)))
        assertEquals(withoutTimes(read(10, null)), withoutTimes(read(40, 5)))
        assertNotEquals(withoutTimes(read(10, null)), withoutTimes(read(10, null, Pipeline.FAILED)))
    }

    private val projects = listOf(
        ScheduleProject("a/b", listOf(ScheduleChoice(1, "Nightly", "main", true), ScheduleChoice(2, "Weekly", "main", true))),
        ScheduleProject("c/d", listOf(ScheduleChoice(3, "Smoke", "master", false))),
    )

    @Test
    fun `le choix envoye suit l'ordre de la liste affichee, pas celui des clics`() {
        val checked = setOf(ScheduleRef("c/d", 3), ScheduleRef("a/b", 2), ScheduleRef("a/b", 1))
        assertEquals(listOf(ScheduleRef("a/b", 1), ScheduleRef("a/b", 2), ScheduleRef("c/d", 3)), scheduleRefsInOrder(projects, checked))
    }

    @Test
    fun `une planification choisie puis supprimee dans GitLab n'est pas renvoyee`() {
        val selected = listOf(ScheduleRef("a/b", 2), ScheduleRef("a/b", 404), ScheduleRef("x/y", 1))
        assertEquals(setOf(ScheduleRef("a/b", 2)), checkedStillListed(projects, selected))
        assertEquals(emptyList<ScheduleRef>(), scheduleRefsInOrder(projects, emptySet()))
    }

    @Test
    fun `au plus 20 planifications, comme le compagnon`() {
        assertEquals(20, MAX_SCHEDULES)
    }
}
