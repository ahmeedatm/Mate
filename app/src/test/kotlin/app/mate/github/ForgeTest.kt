package app.mate.github

import app.mate.net.HomeFeatures
import app.mate.net.parseGitLabPipelines
import app.mate.net.parseHomeFeatures
import app.mate.ui.home.WidgetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForgeTest {

    @Test
    fun `référence courte, point d'exclamation pour GitLab, dièse pour GitHub`() {
        assertEquals("uem!42", changeRef(Forge.GITLAB, "softwares/products/uem", 42))
        assertEquals("Mate#7", changeRef(Forge.GITHUB, "ahmeedatm/Mate", 7))
    }

    @Test
    fun `la carte Connecter ouvre la section de sa forge sur l'ordinateur`() {
        assertEquals("gitlab", connectSection(Forge.GITLAB))
        assertEquals("github", connectSection(Forge.GITHUB))
    }

    @Test
    fun `un run GitHub Actions garde son identifiant au-delà d'un Int`() {
        val answer = parseGitLabPipelines(
            """{"connected":true,"pipelines":[{"project":"org/app","ref":"main","id":12345678901,"pipeline":"failed",
            "durationSec":290,"ageSec":60,"failedJobs":["tests"]}]}""",
        )
        assertEquals(12_345_678_901L, answer.pipelines.single().id)
    }

    @Test
    fun `GitHub et Actions proposés seulement si l'ordinateur offre GitHub`() {
        assertFalse(parseHomeFeatures("""{"gitlab":true}""").github)
        assertTrue(parseHomeFeatures("""{"github":true}""").github)
        for (type in listOf(WidgetType.GITHUB, WidgetType.ACTIONS)) {
            assertFalse(type.offered(HomeFeatures(gitlab = true)))
            assertTrue(type.offered(HomeFeatures(github = true)))
        }
        assertFalse(WidgetType.GITLAB.offered(HomeFeatures(github = true)))
    }
}
