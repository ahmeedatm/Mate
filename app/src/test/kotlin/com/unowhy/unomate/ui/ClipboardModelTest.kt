package com.unowhy.unomate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardModelTest {

    @Test
    fun `texte court inchange`() {
        assertEquals("bonjour", displayText("bonjour"))
    }

    @Test
    fun `texte pile a la limite inchange`() {
        val text = "a".repeat(10_000)
        assertEquals(text, displayText(text))
    }

    @Test
    fun `texte trop long coupe a 10000 avec des points de suspension`() {
        val shown = displayText("a".repeat(10_001))
        assertEquals("a".repeat(10_000) + "…", shown)
    }

    @Test
    fun `limite personnalisee`() {
        assertEquals("abc…", displayText("abcdef", max = 3))
    }

    @Test
    fun `ne coupe pas une paire de substitution en deux`() {
        // 😀 = 2 char : couper entre les deux laisserait une moitié invalide.
        assertEquals("a…", displayText("a😀b", max = 2))
    }

    @Test
    fun `lien seul`() {
        assertEquals("https://x.fr/a", linkOf(" https://x.fr/a "))
        assertEquals("http://x.fr", linkOf("http://x.fr"))
        assertEquals("https://x.fr/a", linkOf("\nhttps://x.fr/a\n"))
    }

    @Test
    fun `pas un lien`() {
        assertNull(linkOf("voir https://x.fr"))
        assertNull(linkOf("ftp://x"))
        assertNull(linkOf("https://x.fr et https://y.fr"))
        assertNull(linkOf("https://"))
        assertNull(linkOf(""))
        assertNull(linkOf("bonjour"))
    }

    @Test
    fun `ligne d'historique sur une ligne, espaces reduits`() {
        assertEquals("bonjour le monde", historyLine("  bonjour\n\tle   monde ", secret = false, revealed = false))
        assertEquals("abc…", historyLine("abcdef", secret = false, revealed = false, max = 3))
    }

    @Test
    fun `code masque tant qu'il n'est pas devoile, masque de longueur fixe`() {
        assertEquals(SECRET_MASK, historyLine("482913", secret = true, revealed = false))
        assertEquals(SECRET_MASK, historyLine("un-mot-de-passe-tres-long-42", secret = true, revealed = false))
        assertEquals("482913", historyLine("482913", secret = true, revealed = true))
    }
}
