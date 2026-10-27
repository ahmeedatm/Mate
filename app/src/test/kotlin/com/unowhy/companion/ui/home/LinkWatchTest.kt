package com.unowhy.companion.ui.home

import com.unowhy.companion.net.Link.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class LinkWatchTest {

    private fun at(hour: Int, minute: Int, second: Int = 0): Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 27, hour, minute, second)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `en ligne rien a dire`() {
        val watch = LinkWatch()
        watch.onLink("a", State.ONLINE, at(13, 0))
        assertNull(watch.down("Ordi de test", at(13, 5)))
    }

    @Test
    fun `coupure dite avec le nom et l heure du debut`() {
        val watch = LinkWatch()
        watch.onLink("a", State.ONLINE, at(13, 0))
        watch.onLink("a", State.OFFLINE, at(13, 26, 5))
        val down = watch.down("Ordi de test", at(13, 27))
        assertEquals(Down("Ordi de test", at(13, 26, 5)), down)
        assertEquals("13:26", down?.since())
    }

    @Test
    fun `l heure reste celle du debut malgre les essais suivants`() {
        val watch = LinkWatch()
        watch.onLink("a", State.ONLINE, at(13, 0))
        watch.onLink("a", State.OFFLINE, at(13, 26))
        watch.onLink("a", State.OFFLINE, at(13, 30))
        assertEquals("13:26", watch.down("Ordi", at(13, 31))?.since())
    }

    @Test
    fun `rien avant le delai de grace`() {
        val watch = LinkWatch()
        watch.onLink("a", State.ONLINE, at(13, 0))
        watch.onLink("a", State.OFFLINE, at(13, 26))
        assertNull(watch.down("Ordi", at(13, 26) + LinkWatch.GRACE_MS - 1))
        assertEquals("13:26", watch.down("Ordi", at(13, 26) + LinkWatch.GRACE_MS)?.since())
    }

    @Test
    fun `retour a la normale des que l ordinateur repond`() {
        val watch = LinkWatch()
        watch.onLink("a", State.ONLINE, at(13, 0))
        watch.onLink("a", State.OFFLINE, at(13, 26))
        watch.onLink("a", State.ONLINE, at(13, 40))
        assertNull(watch.down("Ordi", at(13, 40)))
    }

    @Test
    fun `une nouvelle coupure repart de son propre debut`() {
        val watch = LinkWatch()
        watch.onLink("a", State.ONLINE, at(13, 0))
        watch.onLink("a", State.OFFLINE, at(13, 26))
        watch.onLink("a", State.ONLINE, at(13, 40))
        watch.onLink("a", State.OFFLINE, at(14, 0))
        assertEquals("14:00", watch.down("Ordi", at(14, 5))?.since())
    }

    @Test
    fun `un autre ordinateur ne reprend pas la coupure du precedent`() {
        val watch = LinkWatch()
        watch.onLink("a", State.ONLINE, at(13, 0))
        watch.onLink("a", State.OFFLINE, at(13, 26))
        watch.onLink("b", State.ONLINE, at(13, 40))
        watch.onLink("b", State.OFFLINE, at(13, 50))
        assertNull(watch.down("Autre", at(13, 50, 5)))
        assertEquals("13:50", watch.down("Autre", at(13, 51))?.since())
    }

    @Test
    fun `jamais vu en ligne la coupure est dite sans heure`() {
        val watch = LinkWatch()
        watch.onLink("a", State.OFFLINE, at(13, 26))
        val down = watch.down("Ordi", at(13, 27))
        assertEquals(Down("Ordi", null), down)
        assertNull(down?.since())
    }

    @Test
    fun `un ordinateur vu en ligne ne rend pas son heure a un autre jamais vu`() {
        val watch = LinkWatch()
        watch.onLink("a", State.ONLINE, at(13, 0))
        watch.onLink("b", State.OFFLINE, at(13, 50))
        assertNull(watch.down("Autre", at(13, 51))?.since())
    }

    @Test
    fun `jeton refuse ou aucun ordinateur ne sont pas une coupure`() {
        val watch = LinkWatch()
        watch.onLink("a", State.UNAUTHORIZED, at(13, 0))
        assertNull(watch.down("Ordi", at(13, 5)))
        watch.onLink(null, State.NONE, at(13, 6))
        assertNull(watch.down(null, at(13, 10)))
    }
}
