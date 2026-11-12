package app.mate.ui

import app.mate.Computer
import app.mate.R
import app.mate.net.Link
import app.mate.ui.ComputerLamp.Glyph
import app.mate.ui.ComputerLamp.Led
import app.mate.ui.ComputerLamp.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Témoin de l'ordinateur (Instrument) : chaque état a sa forme, pas seulement sa couleur, et TalkBack le dit. */
class ComputerLampTest {
    private val mac = Computer("m", "MacBook", "http://127.0.0.1:3000", "t", "mac")
    private val pc = Computer("p", "PC du salon", "http://127.0.0.1:3001", "t", "windows")

    private fun lamp(state: Link.State, computer: Computer? = mac, locked: Boolean = false, connecting: Boolean = false, all: List<Computer> = listOfNotNull(computer)) =
        ComputerLamp.of(Link(computer, state, connecting), locked, all)

    @Test
    fun `en ligne diode verte allumee sous le logo de l ordi`() {
        val look = lamp(Link.State.ONLINE)
        assertEquals(Glyph.MAC, look.glyph)
        assertEquals(Tone.FULL, look.tone)
        assertEquals(Led.ON, look.led)
        assertEquals(R.attr.c_ok, look.color)
        assertEquals(R.string.online, look.spoken)
    }

    @Test
    fun `logo windows pour un pc`() {
        assertEquals(Glyph.WINDOWS, lamp(Link.State.ONLINE, pc).glyph)
    }

    @Test
    fun `connexion en cours diode ambre qui respire`() {
        val look = lamp(Link.State.OFFLINE, connecting = true)
        assertEquals(Led.BREATHING, look.led)
        assertEquals(Tone.DIM, look.tone)
        assertEquals(R.attr.c_warn, look.color)
        assertEquals(R.string.link_connecting_spoken, look.spoken)
    }

    @Test
    fun `injoignable logo barre et diode creuse`() {
        val look = lamp(Link.State.OFFLINE)
        assertEquals(Tone.CROSSED, look.tone)
        assertEquals(Led.RING, look.led)
        assertEquals(R.attr.c_ko, look.color)
        assertEquals(R.string.offline, look.spoken)
    }

    @Test
    fun `jeton refuse triangle et session verrouillee cadenas`() {
        assertEquals(Led.ALERT, lamp(Link.State.UNAUTHORIZED).led)
        assertEquals(R.string.unauthorized, lamp(Link.State.UNAUTHORIZED).spoken)
        val locked = lamp(Link.State.ONLINE, locked = true)
        assertEquals(Led.LOCK, locked.led)
        assertEquals(R.attr.c_warn, locked.color)
        assertEquals(R.string.computer_locked_spoken, locked.spoken)
    }

    @Test
    fun `aucun ordinateur socle vide et maison`() {
        val none = lamp(Link.State.NONE, computer = null)
        assertEquals(Glyph.EMPTY, none.glyph)
        assertEquals(Led.RING, none.led)
        assertEquals(R.attr.c_text_faint, none.color)
        val home = lamp(Link.State.HOME, computer = null)
        assertEquals(Glyph.HOME, home.glyph)
        assertEquals(Led.ON, home.led)
    }

    @Test
    fun `chaque etat a sa forme sans compter la couleur`() {
        val shapes = listOf(
            lamp(Link.State.ONLINE),
            lamp(Link.State.OFFLINE, connecting = true),
            lamp(Link.State.OFFLINE),
            lamp(Link.State.UNAUTHORIZED),
            lamp(Link.State.ONLINE, locked = true),
            lamp(Link.State.NONE, computer = null),
        ).map { Triple(it.glyph, it.tone, it.led) }
        assertEquals(shapes.size, shapes.toSet().size)
    }

    @Test
    fun `points sous le logo seulement avec plusieurs ordinateurs`() {
        assertEquals(0, lamp(Link.State.ONLINE).dots)
        val two = lamp(Link.State.ONLINE, pc, all = listOf(mac, pc))
        assertEquals(2, two.dots)
        assertEquals(1, two.activeDot)
        val many = (1..8).map { mac.copy(id = "m$it") }
        val last = lamp(Link.State.ONLINE, many.last(), all = many)
        assertEquals(ComputerLamp.MAX_DOTS, last.dots)
        assertEquals(ComputerLamp.MAX_DOTS - 1, last.activeDot)
        assertNotEquals(-1, last.activeDot)
    }

    @Test
    fun `maison avec plusieurs ordinateurs aucun point actif`() {
        val home = lamp(Link.State.HOME, computer = null, all = listOf(mac, pc))
        assertEquals(2, home.dots)
        assertEquals(-1, home.activeDot)
    }
}
