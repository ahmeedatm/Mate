package app.mate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSessionTest {

    private val session = GameSession()
    private val games = listOf("Steam", "Minecraft")

    @Test
    fun `une appli de la liste au premier plan lance le jeu, une seule fois`() {
        assertEquals(GameSession.Step.START, session.onForeground("Steam", "com.valvesoftware.steam", games))
        assertTrue(session.active)
        assertEquals(GameSession.Step.NONE, session.onForeground("Steam", "com.valvesoftware.steam", games))
    }

    @Test
    fun `une appli hors liste ne fait rien`() {
        assertEquals(GameSession.Step.NONE, session.onForeground("Finder", "com.apple.finder", games))
        assertFalse(session.active)
    }

    @Test
    fun `la liste vide ne lance rien`() {
        assertEquals(GameSession.Step.NONE, session.onForeground("Steam", "steam", emptyList()))
    }

    @Test
    fun `le nom se compare sans casse, et Windows par le nom de l'exe`() {
        assertEquals(GameSession.Step.START, session.onForeground("steam", "steam.exe", listOf("STEAM")))
        session.reset()
        assertEquals(GameSession.Step.START, session.onForeground("Autre", "minecraft.exe", listOf("Minecraft.exe")))
    }

    @Test
    fun `quitter le jeu attend le delai, y revenir avant l'annule`() {
        session.onForeground("Steam", "steam", games)
        assertEquals(GameSession.Step.LEAVE, session.onForeground("Finder", "finder", games))
        assertEquals(GameSession.Step.NONE, session.onForeground("Mail", "mail", games))
        assertEquals(GameSession.Step.RETURN, session.onForeground("Minecraft", "minecraft", games))
        assertFalse(session.endIfLeft())
        assertTrue(session.active)
    }

    @Test
    fun `le delai ecoule, le jeu finit`() {
        session.onForeground("Steam", "steam", games)
        session.onForeground("Finder", "finder", games)
        assertTrue(session.endIfLeft())
        assertFalse(session.active)
        assertFalse(session.endIfLeft())
    }

    @Test
    fun `pas de bascule pendant une saisie, en plein ecran ni en appel`() {
        assertTrue(session.shouldSwitch(typing = false, fullScreen = false, inCall = false))
        assertFalse(session.shouldSwitch(typing = true, fullScreen = false, inCall = false))
        assertFalse(session.shouldSwitch(typing = false, fullScreen = true, inCall = false))
        assertFalse(session.shouldSwitch(typing = false, fullScreen = false, inCall = true))
    }

    @Test
    fun `retour a la page d'avant seulement si l'ecran de jeu est encore affiche`() {
        assertEquals(0, session.returnTo(currentPage = 2, gamePage = 2, previousPage = 0))
        assertEquals(null, session.returnTo(currentPage = 1, gamePage = 2, previousPage = 0))
    }
}
