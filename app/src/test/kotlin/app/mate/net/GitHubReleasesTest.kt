package app.mate.net

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GitHubReleasesTest {

    private val release = """{"id":42,"tag_name":"latest","assets":[
        {"id":1001,"name":"manifest.json","size":300},
        {"id":1002,"name":"companion-0.3.0.apk","size":4200000},
        {"id":1003,"name":"Mate-Mac.zip","size":9000}]}"""

    @Test
    fun `parseRelease donne l'id de la release et celui de chaque asset par son nom`() {
        val parsed = parseRelease(release)
        assertEquals(42L, parsed.id)
        assertEquals(mapOf("manifest.json" to 1001L, "companion-0.3.0.apk" to 1002L, "Mate-Mac.zip" to 1003L), parsed.assets)
        assertEquals(1002L, parsed.assets["companion-0.3.0.apk"])
        assertEquals(null, parsed.assets["absent.apk"])
    }

    @Test
    fun `parseRelease sans asset`() {
        assertEquals(emptyMap<String, Long>(), parseRelease("""{"id":7,"assets":[]}""").assets)
    }

    @Test
    fun `parseAssets lit la liste paginee`() {
        assertEquals(mapOf("a.apk" to 5L), parseAssets(JSONArray("""[{"id":5,"name":"a.apk"}]""")))
    }

    @Test
    fun `adresses de l'API`() {
        assertEquals("https://api.github.com/repos/ahmeedatm/Mate/releases/tags/essais", GitHubStore.releaseUrl("ahmeedatm/Mate", "essais"))
        assertEquals("https://api.github.com/repos/ahmeedatm/Mate/releases/assets/1002", GitHubStore.assetUrl("ahmeedatm/Mate", 1002))
        assertEquals(
            "https://api.github.com/repos/ahmeedatm/Mate/releases/42/assets?per_page=100&page=2",
            GitHubStore.assetsUrl("ahmeedatm/Mate", 42, 2),
        )
    }

    @Test
    fun `le jeton ne part qu'a api github en https`() {
        assertTrue(GitHubStore.sendsToken("https://api.github.com/repos/ahmeedatm/Mate/releases/assets/1"))
        assertFalse(GitHubStore.sendsToken("http://api.github.com/repos/x"))
        assertFalse(GitHubStore.sendsToken("https://objects.githubusercontent.com/github-production-release-asset/1?X-Amz-Signature=a"))
        assertFalse(GitHubStore.sendsToken("https://api.github.com.evil.example/repos/x"))
        assertFalse(GitHubStore.sendsToken("https://evil.example/@api.github.com/"))
    }

    @Test
    fun `redirection absolue ou relative, https seulement`() {
        val from = "https://api.github.com/repos/ahmeedatm/Mate/releases/assets/1002"
        assertEquals("https://objects.githubusercontent.com/a?sig=1", GitHubStore.redirectTarget(from, "https://objects.githubusercontent.com/a?sig=1"))
        assertEquals("https://api.github.com/autre", GitHubStore.redirectTarget(from, "/autre"))
        assertThrows(IOException::class.java) { GitHubStore.redirectTarget(from, "http://objects.githubusercontent.com/a") }
        assertThrows(IOException::class.java) { GitHubStore.redirectTarget(from, null) }
        assertThrows(IOException::class.java) { GitHubStore.redirectTarget(from, "") }
    }
}
