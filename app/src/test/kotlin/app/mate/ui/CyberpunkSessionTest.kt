package app.mate.ui

import app.mate.net.CyberpunkPresence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CyberpunkSessionTest {
    private var now = 0L
    private val session = CyberpunkSession { now }

    private fun game(inGame: Boolean, id: String = "s1") =
        CyberpunkPresence(available = true, gameFound = true, modInstalled = true, running = true, inGame = inGame, session = id)

    private fun ctx(active: String? = "mac", typing: Boolean = false, inCall: Boolean = false, open: Boolean = false) =
        CyberpunkSession.Context(active, typing, inCall, open)

    @Test
    fun nothingHappensWithoutGame() {
        assertEquals(CyberpunkSession.Step.NONE, session.decide(mapOf("atlas" to game(false)), ctx()).step)
        assertEquals(CyberpunkSession.Step.NONE, session.decide(mapOf("atlas" to null), ctx()).step)
    }

    @Test
    fun opensAndSwitchesToTheComputerInGame() {
        val d = session.decide(mapOf("mac" to null, "atlas" to game(true)), ctx(active = "mac"))
        assertEquals(CyberpunkSession.Step.OPEN, d.step)
        assertEquals("atlas", d.computerId)
        assertEquals("atlas", d.switchTo)
    }

    @Test
    fun noSwitchWhenAlreadyOnTheGameComputer() {
        val d = session.decide(mapOf("atlas" to game(true)), ctx(active = "atlas"))
        assertEquals(CyberpunkSession.Step.OPEN, d.step)
        assertNull(d.switchTo)
    }

    @Test
    fun nothingWhileTypingOrInCallThenRetries() {
        val p = mapOf("atlas" to game(true))
        assertEquals(CyberpunkSession.Step.NONE, session.decide(p, ctx(typing = true)).step)
        assertEquals(CyberpunkSession.Step.NONE, session.decide(p, ctx(inCall = true)).step)
        assertEquals(CyberpunkSession.Step.OPEN, session.decide(p, ctx()).step)
    }

    @Test
    fun staysOpenWhileTheGameRuns() {
        val p = mapOf("atlas" to game(true))
        session.decide(p, ctx())
        assertEquals(CyberpunkSession.Step.NONE, session.decide(p, ctx(active = "atlas", open = true)).step)
    }

    @Test
    fun closesAfterTenSecondsAndGivesTheComputerBack() {
        session.decide(mapOf("atlas" to game(true)), ctx(active = "mac"))
        val off = mapOf("atlas" to game(false))
        now = 1_000
        assertEquals(CyberpunkSession.Step.NONE, session.decide(off, ctx(active = "atlas", open = true)).step)
        now = 9_999
        assertEquals(CyberpunkSession.Step.NONE, session.decide(off, ctx(active = "atlas", open = true)).step)
        now = 10_000
        val d = session.decide(off, ctx(active = "atlas", open = true))
        assertEquals(CyberpunkSession.Step.CLOSE, d.step)
        assertEquals("mac", d.returnTo)
    }

    @Test
    fun aBlipDoesNotCloseIt() {
        session.decide(mapOf("atlas" to game(true)), ctx(active = "mac"))
        now = 8_000
        session.decide(mapOf("atlas" to game(false)), ctx(active = "atlas", open = true))
        now = 9_000
        session.decide(mapOf("atlas" to game(true)), ctx(active = "atlas", open = true))
        now = 15_000
        assertEquals(CyberpunkSession.Step.NONE, session.decide(mapOf("atlas" to game(false)), ctx(active = "atlas", open = true)).step)
    }

    @Test
    fun keepsTheComputerTheUserChoseInTheMeantime() {
        session.decide(mapOf("atlas" to game(true)), ctx(active = "mac"))
        session.decide(mapOf("atlas" to game(false)), ctx(active = "pc2", open = true))
        now = 10_000
        val d = session.decide(mapOf("atlas" to game(false)), ctx(active = "pc2", open = true))
        assertEquals(CyberpunkSession.Step.CLOSE, d.step)
        assertNull(d.returnTo)
    }

    @Test
    fun noReturnWhenNoSwitchHappened() {
        session.decide(mapOf("atlas" to game(true)), ctx(active = "atlas"))
        session.decide(mapOf("atlas" to game(false)), ctx(active = "atlas", open = true))
        now = 10_000
        assertNull(session.decide(mapOf("atlas" to game(false)), ctx(active = "atlas", open = true)).returnTo)
    }

    @Test
    fun manualCloseDoesNotReopenInTheSameSession() {
        val p = mapOf("atlas" to game(true))
        session.decide(p, ctx(active = "mac"))
        assertEquals(CyberpunkSession.Step.NONE, session.decide(p, ctx(active = "atlas", open = false)).step)
        assertEquals(CyberpunkSession.Step.NONE, session.decide(p, ctx(active = "atlas", open = false)).step)
    }

    @Test
    fun aNewSessionReopens() {
        session.decide(mapOf("atlas" to game(true, "s1")), ctx(active = "mac"))
        session.decide(mapOf("atlas" to game(true, "s1")), ctx(active = "atlas", open = false))
        assertEquals(CyberpunkSession.Step.OPEN, session.decide(mapOf("atlas" to game(true, "s2")), ctx(active = "atlas", open = false)).step)
    }

    @Test
    fun manualCloseThenGameEndStillGivesTheComputerBack() {
        session.decide(mapOf("atlas" to game(true)), ctx(active = "mac"))
        session.decide(mapOf("atlas" to game(true)), ctx(active = "atlas", open = false))
        session.decide(mapOf("atlas" to game(false)), ctx(active = "atlas", open = false))
        now = 10_000
        val d = session.decide(mapOf("atlas" to game(false)), ctx(active = "atlas", open = false))
        assertEquals(CyberpunkSession.Step.CLOSE, d.step)
        assertEquals("mac", d.returnTo)
    }

    @Test
    fun closeWaitsForTypingAndCalls() {
        session.decide(mapOf("atlas" to game(true)), ctx(active = "mac"))
        session.decide(mapOf("atlas" to game(false)), ctx(active = "atlas", open = true))
        now = 20_000
        assertEquals(CyberpunkSession.Step.NONE, session.decide(mapOf("atlas" to game(false)), ctx(active = "atlas", open = true, typing = true)).step)
        assertEquals(CyberpunkSession.Step.CLOSE, session.decide(mapOf("atlas" to game(false)), ctx(active = "atlas", open = true)).step)
    }

    @Test
    fun afterCloseTheNextSessionOpensAgain() {
        session.decide(mapOf("atlas" to game(true)), ctx(active = "mac"))
        session.decide(mapOf("atlas" to game(false)), ctx(active = "atlas", open = true))
        now = 10_000
        session.decide(mapOf("atlas" to game(false)), ctx(active = "atlas", open = true))
        assertEquals(CyberpunkSession.Step.OPEN, session.decide(mapOf("atlas" to game(true, "s1")), ctx(active = "mac")).step)
    }

    @Test
    fun prefersTheActiveComputerWhenTwoAreInGame() {
        val d = session.decide(mapOf("a" to game(true), "b" to game(true)), ctx(active = "b"))
        assertEquals("b", d.computerId)
    }
    @Test
    fun noReturnIconWhileThePageIsOpenOrWithoutGame() {
        assertNull(session.resumeOn)
        session.decide(mapOf("atlas" to game(true)), ctx(active = "mac"))
        session.decide(mapOf("atlas" to game(true)), ctx(active = "atlas", open = true))
        assertNull(session.resumeOn)
    }

    @Test
    fun manualCloseShowsTheReturnIconAtOnceUntilTheGameEnds() {
        val p = mapOf("atlas" to game(true))
        session.decide(p, ctx(active = "mac"))
        session.closedByHand("atlas")
        assertEquals("atlas", session.resumeOn)
        session.decide(p, ctx(active = "mac", open = false))
        assertEquals("atlas", session.resumeOn)
        // Un trou de sondage ne la cache pas ; la fin de la partie, si.
        now = 5_000
        session.decide(mapOf("atlas" to game(false)), ctx(active = "mac", open = false))
        assertEquals("atlas", session.resumeOn)
        now = 15_000
        session.decide(mapOf("atlas" to game(false)), ctx(active = "mac", open = false))
        assertNull(session.resumeOn)
    }

    @Test
    fun reopeningFromTheIconHidesItAndClosingAgainShowsIt() {
        val p = mapOf("atlas" to game(true))
        session.decide(p, ctx(active = "mac"))
        session.decide(p, ctx(active = "atlas", open = false))
        assertEquals("atlas", session.resumeOn)
        assertEquals(CyberpunkSession.Step.NONE, session.decide(p, ctx(active = "atlas", open = true)).step)
        assertNull(session.resumeOn)
        assertEquals(CyberpunkSession.Step.NONE, session.decide(p, ctx(active = "atlas", open = false)).step)
        assertEquals("atlas", session.resumeOn)
    }

    @Test
    fun aNewSessionThatReopensHidesTheIcon() {
        session.decide(mapOf("atlas" to game(true, "s1")), ctx(active = "mac"))
        session.decide(mapOf("atlas" to game(true, "s1")), ctx(active = "atlas", open = false))
        session.decide(mapOf("atlas" to game(true, "s2")), ctx(active = "atlas", open = false))
        assertNull(session.resumeOn)
    }
}
