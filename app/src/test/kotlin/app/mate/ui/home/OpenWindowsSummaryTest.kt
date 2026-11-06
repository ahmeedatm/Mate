package app.mate.ui.home

import app.mate.net.ComputerWindow
import app.mate.net.OpenApp
import app.mate.net.OpenWindows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenWindowsSummaryTest {
    private fun window(index: Int, title: String = "f$index", front: Boolean = false, minimized: Boolean = false) =
        ComputerWindow(index, title, minimized, fullscreen = false, display = 1, front = front)

    private val mail = OpenApp("mail", "Mail", listOf(window(1), window(2)))
    private val music = OpenApp("music", "Musique", listOf(window(1, front = true)))
    private val empty = OpenApp("finder", "Finder", emptyList())
    private val data = OpenWindows(listOf(mail, empty, music), emptyList())

    @Test
    fun `l'appli au premier plan passe en tete, les applis sans fenetre disparaissent`() {
        assertEquals(listOf("Musique", "Mail"), OpenWindowsSummary.apps(data).map { it.app.name })
    }

    @Test
    fun `chaque appli garde son nombre de fenetres, et le total les compte toutes`() {
        val apps = OpenWindowsSummary.apps(data)
        assertEquals(listOf(1, 2), apps.map { it.windows })
        assertTrue(apps.first().front)
        assertEquals(3, OpenWindowsSummary.total(data))
    }

    @Test
    fun `la fenetre a mettre devant est celle du premier plan, sinon la premiere non reduite`() {
        assertEquals("f1", OpenWindowsSummary.target(music)?.title)
        val app = OpenApp("a", "A", listOf(window(1, minimized = true), window(2)))
        assertEquals("f2", OpenWindowsSummary.target(app)?.title)
        assertNull(OpenWindowsSummary.target(empty))
    }

    @Test
    fun `seules Pastille, Ligne et Colonne ont la vue resumee`() {
        assertEquals(setOf(ShapeFamily.PILL, ShapeFamily.ROW, ShapeFamily.COLUMN), ShapeFamily.entries.filter { OpenWindowsSummary.compact(it) }.toSet())
    }
}
