package com.unowhy.unomate.away

import com.unowhy.unomate.FakeContext
import com.unowhy.unomate.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AwayLogicTest {

    private val start = 1_000_000L

    @Test
    fun `le micro est coupe par nous seulement s'il ne l'etait pas`() {
        assertTrue(AwayLogic.begin(start, micAlreadyMuted = false, focusRunning = false).micMutedByUs)
        assertFalse(AwayLogic.begin(start, micAlreadyMuted = true, focusRunning = false).micMutedByUs)
    }

    @Test
    fun `le Focus est mis en pause par nous seulement s'il tournait`() {
        assertTrue(AwayLogic.begin(start, micAlreadyMuted = false, focusRunning = true).focusPausedByUs)
        assertFalse(AwayLogic.begin(start, micAlreadyMuted = false, focusRunning = false).focusPausedByUs)
    }

    @Test
    fun `la reprise rouvre le micro seulement si c'est nous qui l'avions coupe`() {
        assertTrue(AwayLogic.shouldUnmute(AwayState(start, micMutedByUs = true, focusPausedByUs = false)))
        assertFalse(AwayLogic.shouldUnmute(AwayState(start, micMutedByUs = false, focusPausedByUs = false)))
    }

    @Test
    fun `la reprise relance le Focus seulement si c'est nous qui l'avions mis en pause et qu'il l'est encore`() {
        val ours = AwayState(start, micMutedByUs = false, focusPausedByUs = true)
        val notOurs = ours.copy(focusPausedByUs = false)
        val total = 25 * 60_000L
        val left = 10 * 60_000L
        assertTrue(AwayLogic.shouldResumeFocus(ours, running = false, remainingMs = left, durationMs = total))
        // Il ne tournait pas avant l'absence.
        assertFalse(AwayLogic.shouldResumeFocus(notOurs, running = false, remainingMs = left, durationMs = total))
        // L'utilisateur l'a déjà relancé : pas de bascule, qui l'arrêterait.
        assertFalse(AwayLogic.shouldResumeFocus(ours, running = true, remainingMs = left, durationMs = total))
        // Appli relancée pendant l'absence : chrono entier, pas un chrono interrompu.
        assertFalse(AwayLogic.shouldResumeFocus(ours, running = false, remainingMs = total, durationMs = total))
    }

    @Test
    fun `les minutes d'absence sont entieres et jamais negatives`() {
        val state = AwayState(start, micMutedByUs = true, focusPausedByUs = false)
        assertEquals(0, AwayLogic.minutes(state, start))
        assertEquals(0, AwayLogic.minutes(state, start + 59_999))
        assertEquals(1, AwayLogic.minutes(state, start + 60_000))
        assertEquals(12, AwayLogic.minutes(state, start + 12 * 60_000 + 30_000))
        assertEquals(135, AwayLogic.minutes(state, start + 135 * 60_000L))
        // Horloge reculée.
        assertEquals(0, AwayLogic.minutes(state, start - 5 * 60_000))
    }

    @Test
    fun `l'absence survit a la recreation de l'activite et s'efface a la reprise`() {
        val context = FakeContext()
        assertNull(Prefs.away(context))
        val state = AwayState(start, micMutedByUs = true, focusPausedByUs = false)
        Prefs.setAway(context, state)
        assertEquals(state, Prefs.away(context))
        val other = AwayState(start + 1, micMutedByUs = false, focusPausedByUs = true)
        Prefs.setAway(context, other)
        assertEquals(other, Prefs.away(context))
        Prefs.setAway(context, null)
        assertNull(Prefs.away(context))
    }
}
