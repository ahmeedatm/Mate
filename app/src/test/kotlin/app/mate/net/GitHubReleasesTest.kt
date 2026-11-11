package app.mate.net

import app.mate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class GitHubReleasesTest {

    /** Réponse HTTP jouée sans réseau : code, en-tête Location, corps. */
    private class FakeConnection(url: String, private val code: Int, private val location: String? = null, private val body: String = "") :
        HttpURLConnection(URL(url)) {
        val headers = mutableMapOf<String, String>()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getHeaderField(name: String?): String? = if (name.equals("Location", true)) location else null
        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun setRequestProperty(key: String, value: String) { headers[key] = value }
    }

    /** Store dont chaque adresse demandée est notée et répondue par `answer`. */
    private fun store(answer: (String) -> FakeConnection): Pair<GitHubStore, MutableList<FakeConnection>> {
        val opened = mutableListOf<FakeConnection>()
        return GitHubStore("ahmeedatm/Mate", "essais", now = { 1234L }) { url -> answer(url).also { opened += it } } to opened
    }

    @Test
    fun `lien de telechargement public d'un fichier de la release`() {
        assertEquals(
            "https://github.com/ahmeedatm/Mate/releases/download/essais/companion-0.3.1-essai.7.apk",
            GitHubStore.downloadUrl("ahmeedatm/Mate", "essais", "companion-0.3.1-essai.7.apk"),
        )
        assertEquals(
            "https://github.com/ahmeedatm/Mate/releases/download/latest/Mate%20Mac%2B.zip",
            GitHubStore.downloadUrl("ahmeedatm/Mate", "latest", "Mate Mac+.zip"),
        )
    }

    @Test
    fun `le manifeste est demande avec un parametre anti-cache, sans jeton`() {
        val (store, opened) = store { FakeConnection(it, 200, body = """{"essais":[]}""") }
        assertEquals("""{"essais":[]}""", store.manifest())
        assertEquals("https://github.com/ahmeedatm/Mate/releases/download/essais/manifest.json?t=1234", opened.single().url.toString())
        assertEquals(null, opened.single().headers["Authorization"])
    }

    @Test
    fun `suit la redirection vers le stockage de GitHub`() {
        val storage = "https://release-assets.githubusercontent.com/github-production-release-asset/1?sig=a"
        val (store, opened) = store { url ->
            if (url.startsWith("https://github.com/")) FakeConnection(url, 302, location = storage) else FakeConnection(url, 200, body = "apk")
        }
        val conn = store.open("a.apk", 1000)
        assertEquals("apk", conn.inputStream.bufferedReader().readText())
        assertEquals(listOf("https://github.com/ahmeedatm/Mate/releases/download/essais/a.apk", storage), opened.map { it.url.toString() })
    }

    @Test
    fun `refuse une redirection hors HTTPS`() {
        val (store, _) = store { FakeConnection(it, 302, location = "http://release-assets.githubusercontent.com/a") }
        assertThrows(IOException::class.java) { store.open("a.apk", 1000) }
    }

    @Test
    fun `un refus de GitHub remonte avec son code`() {
        val (store, _) = store { FakeConnection(it, 403) }
        assertEquals(403, assertThrows(GitHubException::class.java) { store.manifest() }.code)
    }

    @Test
    fun `manifeste absent = rien de publie, autre refus = erreur`() {
        assertEquals(emptyList<Int>(), orNothingPublished<Int> { throw GitHubException(404) })
        assertEquals(listOf(1), orNothingPublished { listOf(1) })
        assertEquals(403, assertThrows(GitHubException::class.java) { orNothingPublished<Int> { throw GitHubException(403) } }.code)
    }

    @Test
    fun `chaque refus de GitHub a sa phrase`() {
        assertEquals(R.string.github_rate_limited, GitHubException(403).textRes())
        assertEquals(R.string.github_rate_limited, GitHubException(429).textRes())
        assertEquals(R.string.github_file_missing, GitHubException(404).textRes())
        assertEquals(R.string.github_down, GitHubException(502).textRes())
        assertEquals(R.string.update_server_unreachable, GitHubException(GitHubException.NETWORK).textRes())
    }

    @Test
    fun `redirection absolue ou relative, https seulement`() {
        val from = "https://github.com/ahmeedatm/Mate/releases/download/essais/a.apk"
        assertEquals("https://objects.githubusercontent.com/a?sig=1", GitHubStore.redirectTarget(from, "https://objects.githubusercontent.com/a?sig=1"))
        assertEquals("https://github.com/autre", GitHubStore.redirectTarget(from, "/autre"))
        assertThrows(IOException::class.java) { GitHubStore.redirectTarget(from, "http://objects.githubusercontent.com/a") }
        assertThrows(IOException::class.java) { GitHubStore.redirectTarget(from, null) }
        assertThrows(IOException::class.java) { GitHubStore.redirectTarget(from, "") }
    }
}
