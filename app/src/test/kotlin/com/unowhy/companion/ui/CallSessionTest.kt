package com.unowhy.companion.ui

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
}
