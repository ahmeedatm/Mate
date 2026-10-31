package app.mate

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
        // WiFi : HTTPS, port 3001 par défaut ; la tablette elle-même (USB) : HTTP, port 3000.
        assertEquals("https://192.168.1.20:3001", Computers.normalizeUrl(" 192.168.1.20 "))
        assertEquals("https://192.168.1.20:4001", Computers.normalizeUrl("192.168.1.20:4001/"))
        assertEquals("https://mac.local:3001", Computers.normalizeUrl("https://mac.local"))
        assertEquals("https://[fe80::1]:3001", Computers.normalizeUrl("[fe80::1]"))
        assertEquals("https://[fe80::1]:8080", Computers.normalizeUrl("[fe80::1]:8080"))
        assertEquals("http://127.0.0.1:3000", Computers.normalizeUrl("127.0.0.1"))
        assertEquals("http://localhost:3021", Computers.normalizeUrl("localhost:3021"))
        assertEquals("http://10.0.2.2:3000", Computers.normalizeUrl("http://10.0.2.2:3000"))
    }

    @Test
    fun `l'essai reprend les ordinateurs et l'actif de la stable`() {
        Computers.save(context, bureau, makeActive = true)
        Computers.save(context, maison)
        val trial = FakeContext()
        assertTrue(Computers.importIfEmpty(trial, Computers.export(context)))
        assertEquals(Computers.all(context), Computers.all(trial))
        assertEquals("jeton-b", Computers.active(trial)?.token)
        assertEquals("id-bureau", Computers.active(trial)?.id)
    }

    @Test
    fun `l'essai garde ses propres ordinateurs une fois repris`() {
        Computers.save(context, bureau)
        val trial = FakeContext()
        Computers.save(trial, maison)
        assertFalse(Computers.importIfEmpty(trial, Computers.export(context)))
        assertEquals(listOf("id-maison"), Computers.all(trial).map { it.id })
    }

    @Test
    fun `stable sans ordinateur, rien n'est repris`() {
        val trial = FakeContext()
        assertFalse(Computers.importIfEmpty(trial, Computers.export(context)))
        assertEquals(emptyList<Computer>(), Computers.all(trial))
    }

    @Test
    fun `address retire le schema`() {
        assertEquals("192.168.1.20:3000", bureau.address)
    }

    @Test
    fun `reappairage d'un ordinateur en HTTP sur le WiFi passe en HTTPS sur le port suivant`() {
        assertTrue(bureau.needsRepair)
        assertEquals("https://192.168.1.20:3001", bureau.pairUrl)
    }

    @Test
    fun `reappairage, une adresse HTTPS ou locale reste telle quelle`() {
        assertEquals("https://192.168.1.20:3001", bureau.copy(url = "https://192.168.1.20:3001").pairUrl)
        assertEquals("http://127.0.0.1:3000", bureau.copy(url = "http://127.0.0.1:3000").pairUrl)
    }

    @Test
    fun `reappairage avec un port HTTP personnalise`() {
        assertEquals("https://192.168.1.20:4001", bureau.copy(url = "http://192.168.1.20:4000").pairUrl)
    }
}
