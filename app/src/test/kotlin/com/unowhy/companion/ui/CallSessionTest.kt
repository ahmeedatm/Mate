package com.unowhy.companion.ui

import com.unowhy.companion.net.DeckPage
import com.unowhy.companion.net.DeckTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSessionTest {

    private var clock = 1_000L
    private val session = CallSession { clock }

    @Test
    fun `un appel commence une fois, le meme appel ne recommence pas`() {
        assertTrue(session.start("Microsoft Teams"))
        assertFalse(session.start("Microsoft Teams"))
        assertTrue(session.active)
        assertEquals("Microsoft Teams", session.app)
    }

    @Test
    fun `pas de bascule pendant une saisie ou en plein ecran`() {
        session.start("Discord")
        assertTrue(session.shouldSwitch(typing = false, fullScreen = false))
        assertFalse(session.shouldSwitch(typing = true, fullScreen = false))
        assertFalse(session.shouldSwitch(typing = false, fullScreen = true))
        session.end()
        assertFalse(session.shouldSwitch(typing = false, fullScreen = false))
    }

    @Test
    fun `tick finit l'appel a l'heure de fin de l'evenement`() {
        session.start("Zoom", endAtMs = 5_000L)
        clock = 4_999L
        assertFalse(session.tick())
        clock = 5_000L
        assertTrue(session.tick())
        assertFalse(session.active)
        assertFalse(session.tick())
    }

    @Test
    fun `l'ordinateur prend le relais d'un Rejoindre, plus d'heure de fin`() {
        session.start("Microsoft Teams", endAtMs = 5_000L)
        session.start("Microsoft Teams")
        clock = 9_000L
        assertFalse(session.tick())
        assertTrue(session.active)
    }

    @Test
    fun `returnTo revient au profil d'avant si l'utilisateur est reste sur Reunion`() {
        assertEquals(0, session.returnTo(currentPage = 2, callPage = 2, previousPage = 0))
    }

    @Test
    fun `returnTo ne bouge pas si l'utilisateur est parti ailleurs`() {
        assertNull(session.returnTo(currentPage = 1, callPage = 2, previousPage = 0))
        assertNull(session.returnTo(currentPage = null, callPage = 2, previousPage = 0))
    }

    @Test
    fun `end apres changement d'ordinateur rend active faux`() {
        session.start("Discord")
        session.end()
        assertFalse(session.active)
        assertNull(session.app)
    }

    @Test
    fun `type de reunion rejointe vers le nom de l'appli`() {
        assertEquals("Microsoft Teams", CallSession.appOfKind("teams"))
        assertEquals("Google Meet", CallSession.appOfKind("meet"))
        assertEquals("Zoom", CallSession.appOfKind("zoom"))
        assertEquals("Webex", CallSession.appOfKind("webex"))
        assertEquals("Réunion", CallSession.appOfKind(null))
    }

    private fun page(title: String, app: String?, meeting: Boolean = false, tiles: List<DeckTile> = emptyList()) =
        DeckPage(title, tiles, false, false, meeting, app, false, true, false)

    private val pages = listOf(
        page("Apps", null),
        page("Microsoft Teams", "Microsoft Teams"),
        page("Réunion", "Microsoft Teams", meeting = true),
        page("Discord", "Discord"),
        page("Raccourcis", null, tiles = listOf(DeckTile("r-2", "Couper le micro", "system", null, null, symbol = "mic_off"))),
    )

    @Test
    fun `page de l'appel, la Reunion pour Teams, la sienne pour Discord`() {
        assertEquals("Réunion", CallSession.pageFor("Microsoft Teams", pages)?.title)
        assertEquals("Discord", CallSession.pageFor("Discord", pages)?.title)
        assertNull(CallSession.pageFor("Zoom", pages))
        assertNull(CallSession.pageFor(null, pages))
    }

    @Test
    fun `appli sans page, la touche Couper le micro du deck`() {
        val fallback = CallSession.fallbackPage(pages)!!
        assertEquals(listOf("r-2"), fallback.tiles.map { it.id })
        assertNull(CallSession.fallbackPage(pages.take(4)))
    }

    @Test
    fun `appel de l'ordinateur fini pendant une coupure, la relecture sans appel le termine`() {
        session.start("Discord")
        assertTrue(session.endsOnResync(emptyList()))
        assertFalse(session.endsOnResync(listOf("Discord")))
    }

    @Test
    fun `reunion rejointe sans detection, la relecture ne la termine pas`() {
        session.start("Zoom", endAtMs = 9_000L)
        assertFalse(session.endsOnResync(emptyList()))
        session.end()
        assertFalse(session.endsOnResync(emptyList()))
    }

    @Test
    fun `secours micro, seulement une commande systeme, jamais un bouton d'appli`() {
        val withPress = listOf(
            page("Réunion", "Microsoft Teams", meeting = true, tiles = listOf(DeckTile("m-1", "Micro", "press", null, null, symbol = "mic_off"))),
            page("Raccourcis", null, tiles = listOf(DeckTile("r-2", "Couper le micro", "system", null, null, symbol = "mic_off"))),
        )
        assertEquals(listOf("r-2"), CallSession.fallbackPage(withPress)!!.tiles.map { it.id })
    }
}
