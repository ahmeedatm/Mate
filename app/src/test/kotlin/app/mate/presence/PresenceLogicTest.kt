package app.mate.presence

import app.mate.presence.PresenceLogic.Change
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceLogicTest {

    private val delay = 5 * 60_000L
    private val logic = PresenceLogic(delay).also { it.start(0) }

    @Test
    fun `ecran allume tant que le delai n'est pas ecoule`() {
        assertEquals(Change.NONE, logic.onFrame(moved = false, busy = false, now = delay - 1))
        assertFalse(logic.asleep)
    }

    @Test
    fun `mise en veille apres le delai sans mouvement`() {
        assertEquals(Change.SLEEP, logic.onFrame(moved = false, busy = false, now = delay))
        assertTrue(logic.asleep)
    }

    @Test
    fun `la veille n'est signalee qu'une fois`() {
        logic.onFrame(false, false, delay)
        assertEquals(Change.NONE, logic.onFrame(false, false, delay + 500))
    }

    @Test
    fun `un mouvement repousse la veille`() {
        logic.onFrame(moved = true, busy = false, now = delay - 1)
        assertEquals(Change.NONE, logic.onFrame(false, false, delay + 1))
        assertEquals(Change.SLEEP, logic.onFrame(false, false, 2 * delay - 1))
    }

    @Test
    fun `un toucher repousse la veille`() {
        assertEquals(Change.NONE, logic.onTouch(delay - 1))
        assertEquals(Change.NONE, logic.onFrame(false, false, delay + 1))
        assertEquals(Change.SLEEP, logic.onFrame(false, false, 2 * delay - 1))
    }

    @Test
    fun `une seule image en mouvement ne reveille pas`() {
        logic.onFrame(false, false, delay)
        assertEquals(Change.NONE, logic.onFrame(true, false, delay + 500))
        assertTrue(logic.asleep)
    }

    @Test
    fun `deux images en mouvement de suite reveillent`() {
        logic.onFrame(false, false, delay)
        logic.onFrame(true, false, delay + 500)
        assertEquals(Change.WAKE, logic.onFrame(true, false, delay + 1000))
        assertFalse(logic.asleep)
    }

    @Test
    fun `une image immobile entre deux mouvements casse la serie`() {
        logic.onFrame(false, false, delay)
        logic.onFrame(true, false, delay + 500)
        logic.onFrame(false, false, delay + 1000)
        assertEquals(Change.NONE, logic.onFrame(true, false, delay + 1500))
        assertTrue(logic.asleep)
    }

    @Test
    fun `un toucher reveille tout de suite`() {
        logic.onFrame(false, false, delay)
        assertEquals(Change.WAKE, logic.onTouch(delay + 100))
        assertFalse(logic.asleep)
    }

    @Test
    fun `apres le reveil, le delai repart de l'arrivee`() {
        logic.onFrame(false, false, delay)
        logic.onTouch(delay + 100)
        assertEquals(Change.NONE, logic.onFrame(false, false, delay + 100 + delay - 1))
        assertEquals(Change.SLEEP, logic.onFrame(false, false, delay + 100 + delay))
    }

    @Test
    fun `jamais de veille quand l'ecran sert`() {
        assertEquals(Change.NONE, logic.onFrame(moved = false, busy = true, now = delay))
        assertFalse(logic.asleep)
        // Le delai ne compte qu'a partir de la fin de l'occupation.
        assertEquals(Change.NONE, logic.onFrame(false, false, delay + delay - 1))
        assertEquals(Change.SLEEP, logic.onFrame(false, false, delay + delay))
    }

    @Test
    fun `un appel pendant la veille reveille`() {
        logic.onFrame(false, false, delay)
        assertEquals(Change.WAKE, logic.onFrame(moved = false, busy = true, now = delay + 500))
        assertFalse(logic.asleep)
    }

    @Test
    fun `camera perdue pendant la veille reveille`() {
        logic.onFrame(false, false, delay)
        assertEquals(Change.WAKE, logic.onLost(delay + 500))
        assertEquals(Change.NONE, logic.onLost(delay + 600))
    }

    @Test
    fun `start remet tout a zero`() {
        logic.onFrame(false, false, delay)
        logic.start(delay + 10)
        assertFalse(logic.asleep)
        assertEquals(Change.NONE, logic.onFrame(false, false, delay + 10 + delay - 1))
    }

    @Test
    fun `le demarrage de la camera est permis au depart`() {
        assertTrue(logic.canStart(0))
    }

    @Test
    fun `camera tombee, pas de nouvel essai avant une minute`() {
        logic.onCameraFailed(1_000)
        assertFalse(logic.canStart(1_000))
        assertFalse(logic.canStart(1_000 + PresenceLogic.RETRY_MS - 1))
        assertTrue(logic.canStart(1_000 + PresenceLogic.RETRY_MS))
    }

    @Test
    fun `la panne n'est signalee qu'une fois tant qu'aucune image n'arrive`() {
        assertTrue(logic.onCameraFailed(0))
        assertFalse(logic.onCameraFailed(PresenceLogic.RETRY_MS))
        logic.onFrame(false, false, PresenceLogic.RETRY_MS + 500)
        assertTrue(logic.onCameraFailed(2 * PresenceLogic.RETRY_MS))
    }

    @Test
    fun `attente avant demarrage, jamais raccourcie`() {
        logic.holdOff(0, 2_500)
        assertFalse(logic.canStart(2_499))
        assertTrue(logic.canStart(2_500))
        logic.onCameraFailed(0)
        logic.holdOff(10, 2_500)
        assertFalse(logic.canStart(PresenceLogic.RETRY_MS - 1))
        assertTrue(logic.canStart(PresenceLogic.RETRY_MS))
    }

    @Test
    fun `delai par defaut`() {
        assertEquals(5 * 60_000L, PresenceLogic.SLEEP_DELAY_MS)
    }
}
