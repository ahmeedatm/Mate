package app.mate.focus

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Le temps est celui de `clock`, avancé à la main : sur la JVM, ni SystemClock ni Handler ne tournent. */
class SleepTimerTest {

    private var now = 1_000L
    private var fired = 0

    @Before
    fun setUp() {
        SleepTimer.clock = { now }
        SleepTimer.cancel()
    }

    @After
    fun tearDown() = SleepTimer.cancel()

    @Test
    fun `les durees proposees sont 15, 30 et 60 minutes`() {
        assertEquals(listOf(15, 30, 60), SleepTimer.CHOICES)
    }

    @Test
    fun `au repos, aucun minuteur`() {
        assertNull(SleepTimer.state.minutes)
    }

    @Test
    fun `start fixe la duree et le temps restant`() {
        SleepTimer.start(30) { fired++ }
        assertEquals(30, SleepTimer.state.minutes)
        assertEquals(30 * 60_000L, SleepTimer.state.remainingMs)
        now += 5 * 60_000L
        assertEquals(25 * 60_000L, SleepTimer.state.remainingMs)
    }

    @Test
    fun `a l'echeance la pause part une seule fois et le minuteur se vide`() {
        SleepTimer.start(15) { fired++ }
        now += 15 * 60_000L - 1
        SleepTimer.tick()
        assertEquals(0, fired)
        now += 1
        SleepTimer.tick()
        SleepTimer.tick()
        assertEquals(1, fired)
        assertNull(SleepTimer.state.minutes)
    }

    @Test
    fun `cancel annule sans rien lancer`() {
        SleepTimer.start(60) { fired++ }
        SleepTimer.cancel()
        now += 61 * 60_000L
        SleepTimer.tick()
        assertEquals(0, fired)
        assertNull(SleepTimer.state.minutes)
    }

    @Test
    fun `un nouveau choix remplace le precedent`() {
        SleepTimer.start(15) { fired += 10 }
        SleepTimer.start(60) { fired++ }
        now += 15 * 60_000L
        SleepTimer.tick()
        assertEquals(0, fired)
        now += 45 * 60_000L
        SleepTimer.tick()
        assertEquals(1, fired)
    }

    @Test
    fun `les observateurs sont prevenus`() {
        val seen = mutableListOf<Int?>()
        val listener: (SleepTimer.State) -> Unit = { seen += it.minutes }
        SleepTimer.observe(listener)
        SleepTimer.start(30) { }
        SleepTimer.cancel()
        SleepTimer.unobserve(listener)
        SleepTimer.start(15) { }
        assertEquals(listOf(null, 30, null), seen)
    }

    @Test
    fun `sans ecran le reveil attend l'echeance, avec un ecran il suit la seconde`() {
        assertEquals(900_000L, SleepTimer.nextDelay(900_000L, watched = false))
        assertEquals(501L, SleepTimer.nextDelay(900_500L, watched = true))
    }
}
