package com.unowhy.unomate.net

import com.sun.net.httpserver.HttpServer
import com.unowhy.unomate.Computer
import com.unowhy.unomate.Computers
import com.unowhy.unomate.FakeContext
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket

/** Ordinateurs simulés par de petits serveurs HTTP locaux (GET /health). */
class ConnectionTest {

    private val context = FakeContext()
    private val servers = mutableListOf<HttpServer>()

    /** Backend qui se présente comme `id` et accepte le jeton `token` ; `status` : code HTTP de /health. */
    private fun backend(id: String?, token: String = "jeton", status: Int = 200): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization") == "Bearer $token"
            val body = JSONObject().put("name", "Ordi $id").put("os", "windows").put("auth", auth)
                .apply { if (id != null) put("id", id) }.toString().toByteArray()
            ex.sendResponseHeaders(status, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}"
    }

    /** Adresse où rien n'écoute : connexion refusée. */
    private fun deadUrl() = ServerSocket(0).use { "http://127.0.0.1:${it.localPort}" }

    @After
    fun stop() {
        servers.forEach { it.stop(0) }
    }

    private fun computer(id: String, url: String, lastUsedMs: Long = 0) = Computer(id, "Ordi $id", url, "jeton", "windows", lastUsedMs)

    @Test
    fun `probe ok quand l'ordinateur se reconnait`() = runBlocking {
        val c = computer("a", backend("a"))
        val probe = Connection.probe(context, c)
        assertTrue(probe is Connection.Probe.Ok)
        assertEquals("a", (probe as Connection.Probe.Ok).computer.id)
    }

    @Test
    fun `probe refuse un jeton inconnu`() = runBlocking {
        val c = computer("a", backend("a", token = "autre"))
        assertEquals(Connection.Probe.Unauthorized, Connection.probe(context, c))
    }

    @Test
    fun `probe 401 d'un ancien backend`() = runBlocking {
        val c = computer("a", backend(null, status = 401))
        assertEquals(Connection.Probe.Unauthorized, Connection.probe(context, c))
    }

    @Test
    fun `probe signale un autre ordinateur a cette adresse`() = runBlocking {
        val c = computer("a", backend("b"))
        assertEquals(Connection.Probe.Mismatch, Connection.probe(context, c))
    }

    @Test
    fun `probe adopte l'id d'un ordinateur provisoire`() = runBlocking {
        val url = backend("vrai")
        val provisoire = computer(Computer.PROVISIONAL + url, url)
        Computers.save(context, provisoire, makeActive = true)
        val probe = Connection.probe(context, provisoire) as Connection.Probe.Ok
        assertEquals("vrai", probe.computer.id)
        assertEquals(listOf("vrai"), Computers.all(context).map { it.id })
    }

    @Test(expected = IOException::class)
    fun `probe leve une exception si personne ne repond`() {
        runBlocking { Connection.probe(context, computer("a", deadUrl())) }
    }

    @Test
    fun `resolve sans ordinateur`() = runBlocking {
        assertNull(Connection.resolve(context) { emptyList() })
    }

    @Test
    fun `resolve garde l'actif s'il repond`() = runBlocking {
        Computers.save(context, computer("a", backend("a")), makeActive = true)
        Computers.save(context, computer("b", backend("b"), lastUsedMs = Long.MAX_VALUE / 2))
        assertEquals("a", Connection.resolve(context) { emptyList() }?.id)
    }

    @Test
    fun `resolve bascule vers un autre ordinateur qui repond`() = runBlocking {
        Computers.save(context, computer("a", deadUrl()), makeActive = true)
        Computers.save(context, computer("b", backend("b")))
        assertEquals("b", Connection.resolve(context) { emptyList() }?.id)
        assertEquals("b", Computers.active(context)?.id)
    }

    @Test
    fun `resolve sans bascule n'essaie que l'actif`() = runBlocking {
        Computers.save(context, computer("a", deadUrl()), makeActive = true)
        Computers.save(context, computer("b", backend("b")))
        assertNull(Connection.resolve(context, allowSwitch = false) { emptyList() })
        assertEquals("a", Computers.active(context)?.id)
    }

    @Test
    fun `resolve suit la nouvelle adresse trouvee sur le WiFi`() = runBlocking {
        Computers.save(context, computer("a", deadUrl()), makeActive = true)
        val newUrl = backend("a")
        val found = Connection.resolve(context, allowSwitch = false) { listOf(FoundComputer("a", "Ordi a", "windows", newUrl)) }
        assertEquals("a", found?.id)
        assertEquals(newUrl, Computers.active(context)?.url)
    }

    @Test
    fun `un ordinateur epingle ne prend jamais une adresse en clair venue de la recherche`() = runBlocking {
        val url = "https://192.168.1.20:3001"
        Computers.save(context, computer("a", url).copy(pin = "aa".repeat(32)), makeActive = true)
        Connection.resolve(context, allowSwitch = false) { listOf(FoundComputer("a", "Intrus", "windows", "http://192.168.1.66:3000")) }
        assertEquals(url, Computers.active(context)?.url)
    }

    @Test
    fun `appaire en clair sur le WiFi, a reappairer sans envoyer le jeton`() = runBlocking {
        assertEquals(Connection.Probe.Unauthorized, Connection.probe(context, computer("a", "http://192.168.1.20:3000")))
    }

    @Test
    fun `resolve ignore une recherche WiFi en echec`() = runBlocking {
        Computers.save(context, computer("a", backend("a")), makeActive = true)
        assertEquals("a", Connection.resolve(context) { throw IOException("pas de WiFi") }?.id)
    }
}
