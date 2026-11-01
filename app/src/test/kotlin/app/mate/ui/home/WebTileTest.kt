package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebTileTest {

    @Test
    fun `une adresse complete est gardee telle quelle`() {
        assertEquals("https://grafana.local/d/abc?kiosk", WebTile.normalize("https://grafana.local/d/abc?kiosk"))
    }

    @Test
    fun `sans schema, https est ajoute et le schema est mis en minuscules`() {
        assertEquals("https://exemple.fr/tableau", WebTile.normalize("  exemple.fr/tableau "))
        assertEquals("http://192.168.1.20:3000/", WebTile.normalize("HTTP://192.168.1.20:3000/"))
    }

    @Test
    fun `autre chose que http ou https est refuse`() {
        assertNull(WebTile.normalize("intent://x#Intent;end"))
        assertNull(WebTile.normalize("javascript:alert(1)"))
        assertNull(WebTile.normalize("file:///sdcard/a.html"))
    }

    @Test
    fun `une adresse avec identifiant ou mot de passe est refusee`() {
        assertNull(WebTile.normalize("https://moi:secret@exemple.fr/"))
    }

    @Test
    fun `vide ou sans hote donne rien`() {
        assertNull(WebTile.normalize(""))
        assertNull(WebTile.normalize("   "))
        assertNull(WebTile.normalize("https://"))
    }

    @Test
    fun `l'actualisation choisie est bornee`() {
        assertEquals(WebTile.DEFAULT_REFRESH_MIN, WebTile.refreshMinutes(null))
        assertEquals(1, WebTile.refreshMinutes(0))
        assertEquals(60, WebTile.refreshMinutes(500))
        assertEquals(5, WebTile.refreshMinutes(5))
    }
}
