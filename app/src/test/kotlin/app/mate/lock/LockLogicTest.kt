package app.mate.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LockLogicTest {

    @Test
    fun `verrou a la main d'abord, celui de l'ordinateur ensuite`() {
        assertEquals(LockKind.NONE, LockLogic.kind(tablet = false, computer = false))
        assertEquals(LockKind.COMPUTER, LockLogic.kind(tablet = false, computer = true))
        assertEquals(LockKind.TABLET, LockLogic.kind(tablet = true, computer = true))
        assertEquals(LockKind.TABLET, LockLogic.kind(tablet = true, computer = false))
    }

    @Test
    fun `blocage par l'ordinateur coupe, seul le verrou a la main reste`() {
        assertEquals(LockKind.NONE, LockLogic.kind(tablet = false, computer = true, blockOnComputer = false))
        assertEquals(LockKind.TABLET, LockLogic.kind(tablet = true, computer = true, blockOnComputer = false))
    }

    @Test
    fun `verrou renforce apres trente secondes sans l'ordinateur verrouille`() {
        assertTrue(LockLogic.lockAway(enabled = true, hasCode = true, computerLocked = true, offlineMs = 30_000))
        assertTrue(LockLogic.lockAway(enabled = true, hasCode = true, computerLocked = true, offlineMs = 600_000))
        assertFalse(LockLogic.lockAway(enabled = true, hasCode = true, computerLocked = true, offlineMs = 29_999))
        assertFalse(LockLogic.lockAway(enabled = false, hasCode = true, computerLocked = true, offlineMs = 600_000))
        assertFalse(LockLogic.lockAway(enabled = true, hasCode = false, computerLocked = true, offlineMs = 600_000))
        assertFalse(LockLogic.lockAway(enabled = true, hasCode = true, computerLocked = false, offlineMs = 600_000))
    }

    @Test
    fun `code a 4 chiffres seulement`() {
        assertTrue(LockLogic.validCode("0042"))
        assertFalse(LockLogic.validCode("042"))
        assertFalse(LockLogic.validCode("12345"))
        assertFalse(LockLogic.validCode("12a4"))
        assertFalse(LockLogic.validCode("١٢٣٤"))
    }

    @Test
    fun `empreinte salee, jamais le code en clair`() {
        val h = LockLogic.hash("1234", "sel")
        assertEquals(64, h.length)
        assertFalse(h.contains("1234"))
        assertEquals(h, LockLogic.hash("1234", "sel"))
        assertNotEquals(h, LockLogic.hash("1234", "autre"))
        assertNotEquals(h, LockLogic.hash("1235", "sel"))
    }

    @Test
    fun `cinq codes faux de suite, trente secondes d'attente`() {
        val attempts = LockAttempts()
        repeat(4) { attempts.fail(1_000) }
        assertEquals(0, attempts.waitLeft(1_000))
        attempts.fail(1_000)
        assertEquals(30_000, attempts.waitLeft(1_000))
        assertEquals(10_000, attempts.waitLeft(21_000))
        assertEquals(0, attempts.waitLeft(31_000))
        // Le compte repart de zéro après l'attente, et après un bon code.
        repeat(4) { attempts.fail(31_000) }
        attempts.reset()
        attempts.fail(31_000)
        assertEquals(0, attempts.waitLeft(31_000))
    }
}
