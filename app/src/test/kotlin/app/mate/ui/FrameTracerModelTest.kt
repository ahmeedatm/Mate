package app.mate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameTracerModelTest {

    @Test
    fun `chaque cadre se trace en 440 ms, 60 ms apres le precedent`() {
        val plan = FrameTracerModel.plan(count = 3, lead = 0L, enabled = true)
        assertEquals(listOf(0L, 60L, 120L), plan.map { it.startMs })
        assertTrue(plan.all { it.traced })
        assertEquals(440L, FrameTracerModel.TRACE_MS)
    }

    @Test
    fun `huit cadres au plus sont traces, les suivants arrivent en fondu`() {
        val plan = FrameTracerModel.plan(count = 10, lead = 0L, enabled = true)
        assertEquals(8, plan.count { it.traced })
        assertFalse(plan[8].traced)
        assertFalse(plan[9].traced)
        // Le fondu part avec le dernier cadre tracé, pas plus tard.
        assertEquals(plan[7].startMs, plan[8].startMs)
        assertEquals(plan[7].startMs, plan[9].startMs)
    }

    @Test
    fun `l'attente du balayage decale tout le plan`() {
        val plan = FrameTracerModel.plan(count = 2, lead = 440L, enabled = true)
        assertEquals(listOf(440L, 500L), plan.map { it.startMs })
    }

    @Test
    fun `coupe, aucun plan et tout reste visible`() {
        assertTrue(FrameTracerModel.plan(count = 5, lead = 0L, enabled = false).isEmpty())
        assertFalse(FrameTracerModel.enabled(instrument = true, homeOn = false, reduced = false))
        assertFalse(FrameTracerModel.enabled(instrument = true, homeOn = true, reduced = true))
        assertFalse(FrameTracerModel.enabled(instrument = false, homeOn = true, reduced = false))
        assertTrue(FrameTracerModel.enabled(instrument = true, homeOn = true, reduced = false))
    }

    @Test
    fun `contenu a +300 ms, LCD a +700 ms, fin apres l'allumage`() {
        assertEquals(300L, FrameTracerModel.CONTENT_DELAY_MS)
        assertEquals(700L, FrameTracerModel.LCD_DELAY_MS)
        val plan = FrameTracerModel.plan(count = 2, lead = 0L, enabled = true)
        // dernier cadre à 60 ms + allumage LCD (700 + 300)
        assertEquals(60L + 700L + FrameTracerModel.LCD_MS, FrameTracerModel.totalMs(plan))
        assertEquals(0L, FrameTracerModel.totalMs(emptyList()))
    }

    @Test
    fun `avancement du trace borne entre 0 et 1`() {
        assertEquals(0f, FrameTracerModel.progress(elapsed = 50L, startMs = 100L), 0f)
        assertEquals(0.5f, FrameTracerModel.progress(elapsed = 320L, startMs = 100L), 0.01f)
        assertEquals(1f, FrameTracerModel.progress(elapsed = 900L, startMs = 100L), 0f)
    }

    @Test
    fun `le trait s'efface apres le trace, en 260 ms`() {
        assertEquals(1f, FrameTracerModel.lineAlpha(elapsed = 440L, startMs = 0L), 0f)
        assertEquals(0.5f, FrameTracerModel.lineAlpha(elapsed = 570L, startMs = 0L), 0.01f)
        assertEquals(0f, FrameTracerModel.lineAlpha(elapsed = 700L, startMs = 0L), 0f)
    }

    @Test
    fun `l'alpha du contenu monte en fondu, celui d'un LCD par quatre paliers`() {
        assertEquals(0f, FrameTracerModel.contentAlpha(elapsed = 299L, startMs = 0L, lcd = false), 0f)
        assertEquals(1f, FrameTracerModel.contentAlpha(elapsed = 700L, startMs = 0L, lcd = false), 0f)
        assertEquals(0f, FrameTracerModel.contentAlpha(elapsed = 699L, startMs = 0L, lcd = true), 0f)
        assertEquals(0.25f, FrameTracerModel.contentAlpha(elapsed = 700L, startMs = 0L, lcd = true), 0f)
        assertEquals(0.5f, FrameTracerModel.contentAlpha(elapsed = 800L, startMs = 0L, lcd = true), 0f)
        assertEquals(0.75f, FrameTracerModel.contentAlpha(elapsed = 900L, startMs = 0L, lcd = true), 0f)
        assertEquals(1f, FrameTracerModel.contentAlpha(elapsed = 1000L, startMs = 0L, lcd = true), 0f)
    }

    @Test
    fun `un cadre en fondu montre son contenu des son depart`() {
        val faded = FrameTracerModel.plan(count = 9, lead = 0L, enabled = true).last()
        assertEquals(0f, FrameTracerModel.contentAlpha(elapsed = 419L, startMs = faded.startMs, lcd = false, traced = false), 0f)
        assertEquals(1f, FrameTracerModel.contentAlpha(elapsed = 420L + FrameTracerModel.FADE_MS, startMs = faded.startMs, lcd = false, traced = false), 0f)
    }
}
