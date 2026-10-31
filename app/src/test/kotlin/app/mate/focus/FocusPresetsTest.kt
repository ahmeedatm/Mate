package app.mate.focus

import app.mate.FakeContext
import app.mate.Prefs
import app.mate.focus.FocusPresets.Preset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusPresetsTest {

    @Test
    fun `le pas se cale sur ses multiples`() {
        assertEquals(35, FocusPresets.stepFocus(30, up = true))
        assertEquals(25, FocusPresets.stepFocus(30, up = false))
        // Durée venue de la voix (23 min) : − / + retombent sur la grille.
        assertEquals(25, FocusPresets.stepFocus(23, up = true))
        assertEquals(20, FocusPresets.stepFocus(23, up = false))
        assertEquals(6, FocusPresets.stepPause(5, up = true))
        assertEquals(4, FocusPresets.stepPause(5, up = false))
    }

    @Test
    fun `les bornes ne sont jamais depassees`() {
        assertEquals(5, FocusPresets.stepFocus(5, up = false))
        assertEquals(120, FocusPresets.stepFocus(120, up = true))
        assertEquals(1, FocusPresets.stepPause(1, up = false))
        assertEquals(30, FocusPresets.stepPause(30, up = true))
        assertEquals(Preset(120, 1), FocusPresets.bounded(500, 0))
    }

    @Test
    fun `seuls les trois preglages sont fixes`() {
        assertTrue(FocusPresets.isFixed(Preset(50, 10)))
        assertFalse(FocusPresets.isFixed(Preset(50, 5)))
        assertFalse(FocusPresets.isFixed(FocusPresets.DEFAULT_CUSTOM))
    }

    @Test
    fun `durees gardees dans les preferences`() {
        val context = FakeContext()
        assertEquals(Preset(25, 5), Prefs.focusPreset(context))
        assertEquals(FocusPresets.DEFAULT_CUSTOM, Prefs.focusCustom(context))
        Prefs.setFocusPreset(context, Preset(40, 8))
        Prefs.setFocusCustom(context, Preset(45, 10))
        assertEquals(Preset(40, 8), Prefs.focusPreset(context))
        assertEquals(Preset(45, 10), Prefs.focusCustom(context))
    }
}
