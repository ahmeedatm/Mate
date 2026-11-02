package app.mate.ui.pointer

import app.mate.net.PointerGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroRecorderTest {

    private fun types(recorder: MacroRecorder) = recorder.toKey("Macro").getJSONArray("steps").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }
    }

    @Test fun `un geste seul n'a pas de pause avant lui`() {
        val recorder = MacroRecorder()
        recorder.add(PointerGesture.BACK, 5_000)
        val steps = types(recorder)
        assertEquals(1, steps.size)
        assertEquals("gesture", steps[0].getString("type"))
        assertEquals("back", steps[0].getString("name"))
    }

    @Test fun `la pause réelle entre deux gestes devient une étape attendre arrondie à 100 ms`() {
        val recorder = MacroRecorder()
        recorder.add(PointerGesture.DESKTOP_NEXT, 1_000)
        recorder.add(PointerGesture.SEARCH, 2_537)
        val steps = types(recorder)
        assertEquals(listOf("gesture", "wait", "gesture"), steps.map { it.getString("type") })
        assertEquals(1_500, steps[1].getInt("ms"))
    }

    @Test fun `une pause trop courte pour être voulue est ignorée`() {
        val recorder = MacroRecorder()
        recorder.add(PointerGesture.BACK, 1_000)
        recorder.add(PointerGesture.FORWARD, 1_150)
        assertEquals(listOf("gesture", "gesture"), types(recorder).map { it.getString("type") })
    }

    @Test fun `une longue pause est plafonnée à 10 secondes`() {
        val recorder = MacroRecorder()
        recorder.add(PointerGesture.BACK, 0)
        recorder.add(PointerGesture.FORWARD, 60_000)
        assertEquals(10_000, types(recorder)[1].getInt("ms"))
    }

    @Test fun `la touche garde au plus 20 étapes et refuse le geste de trop`() {
        val recorder = MacroRecorder()
        var accepted = 0
        for (i in 0 until 30) if (recorder.add(PointerGesture.BACK, i * 1_000L)) accepted++
        // Un geste puis (pause + geste) × 9 = 19 étapes ; un 11e geste dépasserait 20.
        assertEquals(10, accepted)
        assertEquals(19, types(recorder).size)
        assertFalse(recorder.add(PointerGesture.BACK, 99_000))
    }

    @Test fun `le nombre de gestes ne compte que les gestes`() {
        val recorder = MacroRecorder()
        assertTrue(recorder.isEmpty)
        recorder.add(PointerGesture.BACK, 0)
        recorder.add(PointerGesture.BACK, 3_000)
        assertEquals(2, recorder.gestures)
    }

    @Test fun `le libellé est celui donné`() {
        val recorder = MacroRecorder()
        recorder.add(PointerGesture.ZOOM_IN, 0)
        assertEquals("Ma macro", recorder.toKey("Ma macro").getString("label"))
    }
}
