package app.mate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeEntryTest {

    private val context = FakeContext()
    private val bureau = Computer("id-bureau", "Bureau", "http://192.168.1.20:3000", "jeton-b", "mac", lastUsedMs = 1)

    @Test
    fun `Maison choisie, aucun ordinateur n'est pilote et la liste reste celle des PC`() {
        Computers.save(context, bureau, makeActive = true)
        Computers.setHome(context)
        assertTrue(Computers.homeActive(context))
        assertNull(Computers.active(context))
        assertEquals(listOf("id-bureau"), Computers.all(context).map { it.id })
    }

    @Test
    fun `choisir un PC quitte Maison`() {
        Computers.save(context, bureau)
        Computers.setHome(context)
        Computers.setActive(context, "id-bureau")
        assertFalse(Computers.homeActive(context))
        assertEquals("id-bureau", Computers.active(context)?.id)
    }

    @Test
    fun `un PC appaire depuis Maison devient l'actif`() {
        Computers.setHome(context)
        Computers.save(context, bureau, makeActive = true)
        assertFalse(Computers.homeActive(context))
    }

    @Test
    fun `Maison a ses propres ecrans, a part de ceux des PC`() {
        Computers.save(context, bureau, makeActive = true)
        assertEquals("top_bar@id-bureau", ComputerConfig.key(context, "top_bar"))
        Computers.setHome(context)
        assertEquals("top_bar@${Computers.HOME}", ComputerConfig.key(context, "top_bar"))
    }

    @Test
    fun `sans rien choisi, Maison n'est pas active`() {
        assertFalse(Computers.homeActive(context))
    }
}
