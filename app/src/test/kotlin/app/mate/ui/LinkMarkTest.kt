package app.mate.ui

import app.mate.R
import app.mate.net.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pastille de connexion : chaque état a sa forme et son mot, lisibles sans distinguer le vert du rouge. */
class LinkMarkTest {
    @Test
    fun `trois etats d un ordinateur trois formes`() {
        val shapes = listOf(Link.State.ONLINE, Link.State.UNAUTHORIZED, Link.State.OFFLINE).map { linkMark(it).shape }
        assertEquals(listOf(LinkShape.OK, LinkShape.ALERT, LinkShape.CROSS), shapes)
    }

    @Test
    fun `etat ecrit a cote du nom`() {
        assertEquals(R.string.link_state_online, linkMark(Link.State.ONLINE).label)
        assertEquals(R.string.link_state_unauthorized, linkMark(Link.State.UNAUTHORIZED).label)
        assertEquals(R.string.link_state_offline, linkMark(Link.State.OFFLINE).label)
    }

    @Test
    fun `sans ordinateur le nom dit deja Aucun`() {
        val mark = linkMark(Link.State.NONE)
        assertNull(mark.label)
        assertEquals(LinkShape.CROSS, mark.shape)
        assertEquals(R.string.no_computer, mark.spoken)
    }

    @Test
    fun `TalkBack garde les phrases longues`() {
        assertEquals(R.string.online, linkMark(Link.State.ONLINE).spoken)
        assertEquals(R.string.unauthorized, linkMark(Link.State.UNAUTHORIZED).spoken)
        assertEquals(R.string.offline, linkMark(Link.State.OFFLINE).spoken)
    }

    @Test
    fun `ordinateur verrouille, point d exclamation et mot verrouille`() {
        val mark = linkMark(Link.State.ONLINE, locked = true)
        assertEquals(LinkShape.ALERT, mark.shape)
        assertEquals(R.string.link_state_locked, mark.label)
        assertEquals(R.string.computer_locked_spoken, mark.spoken)
    }

    @Test
    fun `injoignable prime sur verrouille`() {
        assertEquals(linkMark(Link.State.OFFLINE), linkMark(Link.State.OFFLINE, locked = true))
    }

    @Test
    fun `chaque signe est un disque perce de son symbole, teintable d une seule couleur`() {
        for (name in listOf("link_sign_ok", "link_sign_alert", "link_sign_cross")) {
            val xml = File("src/main/res/drawable/$name.xml").readText()
            assertTrue("$name : le symbole doit etre perce dans le disque", "evenOdd" in xml)
        }
    }
}
