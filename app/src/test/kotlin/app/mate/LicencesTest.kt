package app.mate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Mentions de licences (assets/licences.txt) : à régénérer quand une dépendance change. */
class LicencesTest {

    private val licences = File("src/main/assets/licences.txt").readText()

    @Test
    fun `chaque dependance de l'appli est citee avec sa version`() {
        val declared = Regex("""^\s*implementation\("([^"]+)"\)""", RegexOption.MULTILINE)
            .findAll(File("build.gradle.kts").readText())
            // « @aar » : type d'artefact, absent de l'arbre des dépendances que lit le script.
            .map { it.groupValues[1].substringBefore('@') }
            .toList()
        assertTrue(declared.isNotEmpty())
        val missing = declared.filter { "- $it" !in licences }
        assertEquals("absentes de licences.txt : relancer node scripts/licences-android.mjs", emptyList<String>(), missing)
    }

    @Test
    fun `textes des licences et polices embarquees presents`() {
        assertTrue("Apache 2.0", "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION" in licences)
        assertTrue("OFL 1.1", "SIL OPEN FONT LICENSE Version 1.1" in licences)
        // Préfixe du nom de fichier (res/font/<préfixe><graisse>.ttf) -> nom cité ; le préfixe le plus long l'emporte.
        val cited = mapOf(
            "figtree_" to "Figtree", "atkinson_" to "Atkinson Hyperlegible", "chakra_petch_" to "Chakra Petch", "share_tech_mono_" to "Share Tech Mono",
        )
        for (file in File("src/main/res/font").list()!!) {
            val name = cited.entries.filter { file.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
            assertTrue("police $file", name != null && "- $name :" in licences)
        }
    }
}
