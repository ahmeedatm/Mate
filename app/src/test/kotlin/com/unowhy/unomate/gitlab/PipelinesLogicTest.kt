package com.unowhy.unomate.gitlab

import com.unowhy.unomate.net.GitLabPipeline
import com.unowhy.unomate.net.Pipeline
import org.junit.Assert.assertEquals
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
    ) = GitLabPipeline(project, ref, id.toLong(), status, duration, age, jobs)

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
    fun `le titre dit le projet puis la ref, le projet est le dernier segment du chemin`() {
        assertEquals("facturation · main", pipelineTitle(pipeline()))
        assertEquals("uem · fix/FACT-236-arrondi-tva", pipelineTitle(pipeline(project = "softwares/products/uem", ref = "fix/FACT-236-arrondi-tva")))
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
}
