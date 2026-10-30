package com.unowhy.unomate.net

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress

/** Le persona de démo de la tablette part dans `X-Demo-Persona`, relu à chaque requête ; sans persona, pas d'en-tête. */
class HttpPersonaTest {

    private val seen = mutableListOf<String?>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            seen += ex.requestHeaders.getFirst("X-Demo-Persona")
            ex.sendResponseHeaders(200, 2)
            ex.responseBody.use { it.write("{}".toByteArray()) }
        }
        start()
    }
    private val url = "http://127.0.0.1:${server.address.port}"

    @After
    fun stop() = server.stop(0)

    @Test
    fun `sans persona aucun en-tete`() {
        Http(url, "", 1_000).get("/health")
        assertNull(seen.single())
    }

    @Test
    fun `le persona est lu a chaque requete`() {
        var persona: String? = null
        val http = Http(url, "", 1_000, persona = { persona })
        http.get("/health")
        persona = "sacha"
        http.get("/health")
        http.delete("/demo")
        persona = null
        http.get("/health")
        assertEquals(listOf(null, "sacha", "sacha", null), seen)
    }
}
