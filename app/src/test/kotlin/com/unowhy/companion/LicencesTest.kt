package com.unowhy.companion

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
            .map { it.groupValues[1] }
            .toList()
        assertTrue(declared.isNotEmpty())
        val missing = declared.filter { "- $it" !in licences }
        assertEquals("absentes de licences.txt : relancer node scripts/licences-android.mjs", emptyList<String>(), missing)
    }

    @Test
    fun `textes des licences et polices embarquees presents`() {
        assertTrue("Apache 2.0", "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION" in licences)
        assertTrue("OFL 1.1", "SIL OPEN FONT LICENSE Version 1.1" in licences)
        val fonts = File("src/main/res/font").list()!!.map { it.substringBefore('_') }.toSet()
        val cited = mapOf("outfit" to "Outfit", "nunito" to "Nunito", "abril" to "Abril Fatface", "atkinson" to "Atkinson Hyperlegible")
        for (font in fonts) assertTrue("police $font", cited[font]?.let { "- $it :" in licences } == true)
    }
}
