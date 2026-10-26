package com.unowhy.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModeTest {

    @Test
    fun `Depuis l'ordi garde la cle docs du canal et de l'etat sauvegarde`() {
        assertEquals("docs", Mode.COMPUTER.key)
        assertEquals("DOCS", Mode.COMPUTER.tag)
        // Valeur gardée par une version d'avant le renommage (Mode.DOCS.name).
        assertEquals(Mode.COMPUTER, Mode.of("DOCS"))
        assertEquals(Mode.COMPUTER, Mode.of("docs"))
    }

    @Test
    fun `cles des modes inchangees pour l'ordinateur`() {
        assertEquals(listOf("home", "deck", "docs", "focus", "camera", "settings"), Mode.entries.map { it.key })
    }

    @Test
    fun `mode inconnu donne null`() {
        assertNull(Mode.of("tasks"))
        assertNull(Mode.of("chat"))
    }
}
