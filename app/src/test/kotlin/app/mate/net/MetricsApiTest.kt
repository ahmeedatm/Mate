package app.mate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsApiTest {
    @Test
    fun readsLabelsAndValuesInOrder() {
        val metrics = MetricsApi.parse("""{"items":[{"label":"Dossier","value":"1,2 Go"},{"label":"Build","value":"vert"}]}""")
        assertEquals(listOf("Dossier", "Build"), metrics.map { it.label })
        assertEquals(listOf("1,2 Go", "vert"), metrics.map { it.value })
    }

    @Test
    fun failedCommandKeepsItsLabelWithoutValue() {
        val metric = MetricsApi.parse("""{"items":[{"label":"Build","value":null}]}""").single()
        assertEquals("Build", metric.label)
        assertNull(metric.value)
    }

    @Test
    fun noCommandWrittenMeansNoMetric() {
        assertTrue(MetricsApi.parse("""{"items":[]}""").isEmpty())
    }

    @Test
    fun keepsFourMetricsAtMost() {
        val items = (1..6).joinToString(",") { """{"label":"M$it","value":"$it"}""" }
        assertEquals(4, MetricsApi.parse("""{"items":[$items]}""").size)
    }
}
