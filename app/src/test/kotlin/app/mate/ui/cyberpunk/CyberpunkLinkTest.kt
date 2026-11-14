package app.mate.ui.cyberpunk

import app.mate.ui.cyberpunk.CyberpunkLink.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

class CyberpunkLinkTest {
    @Test
    fun liveWhileThePollsSucceed() {
        assertEquals(Mode.LIVE, CyberpunkLink.decide(hasLive = true, misses = 0))
    }

    @Test
    fun oneMissedPollKeepsTheLastStateWithoutBanner() {
        assertEquals(Mode.LIVE, CyberpunkLink.decide(hasLive = true, misses = 1))
    }

    @Test
    fun twoMissedPollsKeepTheLastStateWithABanner() {
        assertEquals(Mode.DEGRADED, CyberpunkLink.decide(hasLive = true, misses = 2))
    }

    @Test
    fun threeMissedPollsMeanNoLongerInGame() {
        assertEquals(Mode.AWAY, CyberpunkLink.decide(hasLive = true, misses = 3))
        assertEquals(Mode.AWAY, CyberpunkLink.decide(hasLive = true, misses = 10))
    }

    @Test
    fun neverLiveIsAwayFromTheStart() {
        assertEquals(Mode.AWAY, CyberpunkLink.decide(hasLive = false, misses = 0))
        assertEquals(Mode.AWAY, CyberpunkLink.decide(hasLive = false, misses = 1))
    }

    @Test
    fun onlyAliveModesAreTouchable() {
        assertEquals(true, Mode.LIVE.shown)
        assertEquals(true, Mode.DEGRADED.shown)
        assertEquals(false, Mode.AWAY.shown)
    }
}
