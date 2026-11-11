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
    fun `la fin de la sequence est celle du dernier trait, l'allumage LCD etant plus court`() {
        val plan = FrameTracerModel.plan(count = 2, lead = 0L, enabled = true)
        assertEquals(60L + FrameTracerModel.TRACE_MS + FrameTracerModel.LINE_FADE_MS, FrameTracerModel.totalMs(plan))
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
    fun `le contenu d'un module est visible tout de suite, le trace passe par-dessus`() {
        assertEquals(1f, FrameTracerModel.contentAlpha(elapsed = 0L, startMs = 0L, lcd = false), 0f)
        assertEquals(1f, FrameTracerModel.contentAlpha(elapsed = 0L, startMs = 420L, lcd = false), 0f)
        val faded = FrameTracerModel.plan(count = 9, lead = 0L, enabled = true).last()
        assertEquals(1f, FrameTracerModel.contentAlpha(elapsed = 0L, startMs = faded.startMs, lcd = false), 0f)
    }

    @Test
    fun `une fenetre LCD s'allume en quatre paliers, entierement en 300 ms au plus`() {
        assertTrue(FrameTracerModel.LCD_MS <= 300L)
        assertEquals(0f, FrameTracerModel.contentAlpha(elapsed = -1L, startMs = 0L, lcd = true), 0f)
        assertEquals(0.25f, FrameTracerModel.contentAlpha(elapsed = 0L, startMs = 0L, lcd = true), 0f)
        assertEquals(0.5f, FrameTracerModel.contentAlpha(elapsed = FrameTracerModel.LCD_MS / 4, startMs = 0L, lcd = true), 0f)
        assertEquals(0.75f, FrameTracerModel.contentAlpha(elapsed = FrameTracerModel.LCD_MS / 2, startMs = 0L, lcd = true), 0f)
        assertEquals(1f, FrameTracerModel.contentAlpha(elapsed = FrameTracerModel.LCD_MS, startMs = 0L, lcd = true), 0f)
    }

    @Test
    fun `les LCD s'allument sans attendre le decalage des cadres, apres l'attente du balayage seulement`() {
        val plan = FrameTracerModel.plan(count = 8, lead = 0L, enabled = true)
        assertTrue(plan.all { it.lcdStartMs == 0L })
        val swept = FrameTracerModel.plan(count = 8, lead = FrameTracerModel.AFTER_SWEEP_MS, enabled = true)
        // Allumés dès que l'aplat découvre l'écran, avant le départ des traits.
        assertTrue(swept.all { it.lcdStartMs == SweepModel.COVER_MS })
        assertTrue(swept.all { it.startMs >= it.lcdStartMs })
    }

    @Test
    fun `le trait n'est dessine que de son depart a son effacement`() {
        // Attente du balayage : rien à dessiner avant le départ du cadre.
        assertFalse(FrameTracerModel.lineVisible(elapsed = 100L, startMs = 440L, traced = true))
        assertFalse(FrameTracerModel.lineVisible(elapsed = 440L, startMs = 440L, traced = true))
        assertTrue(FrameTracerModel.lineVisible(elapsed = 441L, startMs = 440L, traced = true))
        assertTrue(FrameTracerModel.lineVisible(elapsed = 440L + FrameTracerModel.TRACE_MS + 100L, startMs = 440L, traced = true))
        // Après l'effacement du trait, et pour un cadre en fondu : plus rien.
        assertFalse(FrameTracerModel.lineVisible(elapsed = 440L + FrameTracerModel.TRACE_MS + FrameTracerModel.LINE_FADE_MS, startMs = 440L, traced = true))
        assertFalse(FrameTracerModel.lineVisible(elapsed = 500L, startMs = 440L, traced = false))
    }
}
