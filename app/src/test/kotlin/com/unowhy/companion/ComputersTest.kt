package com.unowhy.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputersTest {

    private val context = FakeContext()
    private val bureau = Computer("id-bureau", "Bureau", "http://192.168.1.20:3000", "jeton-b", "mac", lastUsedMs = 1)
    private val maison = Computer("id-maison", "Maison", "http://192.168.0.5:3000", "jeton-m", "windows", lastUsedMs = 2)

    @Test
    fun `aucun ordinateur au depart`() {
        assertEquals(emptyList<Computer>(), Computers.all(context))
        assertNull(Computers.active(context))
    }

    @Test
    fun `save garde la liste et remplace un meme id`() {
        Computers.save(context, bureau)
        Computers.save(context, maison)
        Computers.save(context, bureau.copy(name = "Bureau 2"))
        assertEquals(setOf("Bureau 2", "Maison"), Computers.all(context).map { it.name }.toSet())
    }

    @Test
    fun `sans actif choisi, le plus recemment utilise est l'actif`() {
        Computers.save(context, bureau)
        Computers.save(context, maison)
        assertEquals("id-maison", Computers.active(context)?.id)
    }

    @Test
    fun `setActive change l'actif et la date d'utilisation`() {
        Computers.save(context, bureau)
        Computers.save(context, maison)
        Computers.setActive(context, "id-bureau")
        val active = Computers.active(context)!!
        assertEquals("id-bureau", active.id)
        assertTrue(active.lastUsedMs > maison.lastUsedMs)
    }

    @Test
    fun `rename ignore un nom vide`() {
        Computers.save(context, bureau)
        Computers.rename(context, "id-bureau", "  Salon  ")
        assertEquals("Salon", Computers.all(context).single().name)
        Computers.rename(context, "id-bureau", "   ")
        assertEquals("Salon", Computers.all(context).single().name)
    }

    @Test
    fun `remove retire l'ordinateur et l'actif`() {
        Computers.save(context, bureau, makeActive = true)
        Computers.save(context, maison)
        Computers.remove(context, "id-bureau")
        assertEquals(listOf("id-maison"), Computers.all(context).map { it.id })
        assertEquals("id-maison", Computers.active(context)?.id)
    }

    @Test
    fun `un vrai ordinateur remplace le provisoire de meme adresse`() {
        val provisoire = bureau.copy(id = Computer.PROVISIONAL + bureau.url)
        assertTrue(provisoire.provisional)
        Computers.save(context, provisoire)
        Computers.save(context, bureau)
        assertEquals(listOf("id-bureau"), Computers.all(context).map { it.id })
    }

    @Test
    fun `adopt donne son vrai id au provisoire et garde l'actif`() {
        val provisoire = bureau.copy(id = Computer.PROVISIONAL + bureau.url, name = "Mac")
        Computers.save(context, provisoire, makeActive = true)
        val adopted = Computers.adopt(context, provisoire, "id-vrai", "MacBook", "mac")
        assertEquals("id-vrai", adopted.id)
        assertEquals("MacBook", adopted.name)
        assertFalse(adopted.provisional)
        assertEquals("id-vrai", Computers.active(context)?.id)
        assertEquals(1, Computers.all(context).size)
    }

    @Test
    fun `adopt garde le nom choisi d'un ordinateur deja connu`() {
        Computers.save(context, bureau.copy(name = "Mon bureau"))
        val provisoire = Computer(Computer.PROVISIONAL + "http://10.0.0.9:3000", "Mac", "http://10.0.0.9:3000", "jeton-neuf", "mac")
        Computers.save(context, provisoire)
        val adopted = Computers.adopt(context, provisoire, "id-bureau", "MacBook", "mac")
        assertEquals("Mon bureau", adopted.name)
        assertEquals("http://10.0.0.9:3000", adopted.url)
        assertEquals("jeton-neuf", adopted.token)
        assertEquals(1, Computers.all(context).size)
    }

    @Test
    fun `migration de l'ancienne URL unique`() {
        context.getSharedPreferences("companion", 0).edit().putString("backend_url", "http://10.0.0.2:3000").putString("token", "t").apply()
        val c = Computers.all(context).single()
        assertTrue(c.provisional)
        assertEquals("http://10.0.0.2:3000", c.url)
        assertEquals("t", c.token)
        assertFalse(context.getSharedPreferences("companion", 0).contains("backend_url"))
    }

    @Test
    fun `normalizeUrl ajoute schema et port`() {
        assertEquals("http://192.168.1.20:3000", Computers.normalizeUrl(" 192.168.1.20 "))
        assertEquals("http://192.168.1.20:4000", Computers.normalizeUrl("192.168.1.20:4000/"))
        assertEquals("https://mac.local:3000", Computers.normalizeUrl("https://mac.local"))
        assertEquals("http://[fe80::1]:3000", Computers.normalizeUrl("[fe80::1]"))
        assertEquals("http://[fe80::1]:8080", Computers.normalizeUrl("[fe80::1]:8080"))
    }

    @Test
    fun `address retire le schema`() {
        assertEquals("192.168.1.20:3000", bureau.address)
    }
}
