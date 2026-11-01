package app.mate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSessionTest {

    private val session = GameSession()
    private val games = mapOf(1 to listOf("Steam", "Minecraft"))

    @Test
    fun `une appli de la liste au premier plan lance le jeu, une seule fois`() {
        assertEquals(GameSession.Step.START, session.onForeground("Steam", "com.valvesoftware.steam", "", games))
        assertTrue(session.active)
        assertEquals(GameSession.Step.NONE, session.onForeground("Steam", "com.valvesoftware.steam", "", games))
    }

    @Test
    fun `une appli hors liste ne fait rien`() {
        assertEquals(GameSession.Step.NONE, session.onForeground("Finder", "com.apple.finder", "", games))
        assertFalse(session.active)
    }

    @Test
    fun `la liste vide ne lance rien`() {
        assertEquals(GameSession.Step.NONE, session.onForeground("Steam", "steam", "", emptyMap<Int, List<String>>()))
    }

    @Test
    fun `le nom se compare sans casse, et Windows par le nom de l'exe`() {
        assertEquals(GameSession.Step.START, session.onForeground("steam", "steam.exe", "", mapOf(1 to listOf("STEAM"))))
        session.reset()
        assertEquals(GameSession.Step.START, session.onForeground("Autre", "minecraft.exe", "", mapOf(1 to listOf("Minecraft.exe"))))
    }

    @Test
    fun `un Mac en francais, le nom du fichier du catalogue reconnait l appli`() {
        val step = session.onForeground("Moniteur d'activité", "com.apple.ActivityMonitor", "Activity Monitor", mapOf(1 to listOf("Activity Monitor")))
        assertEquals(GameSession.Step.START, step)
    }

    @Test
    fun `quitter le jeu attend le delai, y revenir avant l'annule`() {
        session.onForeground("Steam", "steam", "", games)
        assertEquals(GameSession.Step.LEAVE, session.onForeground("Finder", "finder", "", games))
        assertEquals(GameSession.Step.NONE, session.onForeground("Mail", "mail", "", games))
        assertEquals(GameSession.Step.RETURN, session.onForeground("Minecraft", "minecraft", "", games))
        assertFalse(session.endIfLeft())
        assertTrue(session.active)
    }

    @Test
    fun `le delai ecoule, le jeu finit`() {
        session.onForeground("Steam", "steam", "", games)
        session.onForeground("Finder", "finder", "", games)
        assertTrue(session.endIfLeft())
        assertFalse(session.active)
        assertFalse(session.endIfLeft())
    }

    private val pages = mapOf(1 to listOf("Figma"), 2 to listOf("Blender", "Cinema 4D"))

    @Test
    fun `chaque appli mene a sa page`() {
        assertEquals(GameSession.Step.START, session.onForeground("Figma", "com.figma.Desktop", "", pages))
        assertEquals(1, session.target)
        session.reset()
        assertEquals(GameSession.Step.START, session.onForeground("Blender", "org.blenderfoundation.blender", "", pages))
        assertEquals(2, session.target)
    }

    @Test
    fun `passer de Figma a Blender change de page sans finir la session`() {
        session.onForeground("Figma", "figma", "", pages)
        assertEquals(GameSession.Step.START, session.onForeground("Blender", "blender", "", pages))
        assertEquals(2, session.target)
        assertTrue(session.active)
        assertEquals(GameSession.Step.NONE, session.onForeground("Blender", "blender", "", pages))
    }

    @Test
    fun `quitter Figma pour Blender pendant le delai passe sur la page de Blender`() {
        session.onForeground("Figma", "figma", "", pages)
        session.onForeground("Finder", "finder", "", pages)
        assertEquals(GameSession.Step.START, session.onForeground("Blender", "blender", "", pages))
        assertFalse(session.endIfLeft())
        assertEquals(2, session.target)
    }

    @Test
    fun `revenir dans la meme appli pendant le delai garde sa page`() {
        session.onForeground("Figma", "figma", "", pages)
        session.onForeground("Finder", "finder", "", pages)
        assertEquals(GameSession.Step.RETURN, session.onForeground("Figma", "figma", "", pages))
        assertEquals(1, session.target)
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

    @Test
    fun `revenir dans le jeu puis le quitter de nouveau finit bien le jeu`() {
        session.onForeground("Steam", "steam", "", games)
        session.onForeground("Finder", "finder", "", games)
        session.onForeground("Steam", "steam", "", games)
        session.onForeground("Finder", "finder", "", games)
        assertTrue(session.endIfLeft())
    }

    @Test
    fun `apres une bascule refusee, la prochaine appli de jeu relance`() {
        session.onForeground("Steam", "steam", "", games)
        session.reset()
        assertEquals(GameSession.Step.START, session.onForeground("Steam", "steam", "", games))
    }
}
