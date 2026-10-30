package com.unowhy.unomate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Politique de confidentialité : le même texte dans l'appli et sur la page de l'ordinateur (docs/confidentialite.md). */
class PrivacyTest {

    private val app = File("src/main/assets/confidentialite.txt").readText()

    @Test
    fun `meme texte que celui du compagnon`() {
        assertEquals("copier backend/config/confidentialite.txt dans app/src/main/assets", File("../backend/config/confidentialite.txt").readText(), app)
    }

    @Test
    fun `chaque destinataire de donnees est nomme`() {
        for (recipient in listOf("Google", "MET Norway", "YouTube", "Microsoft", "serveur de mises à jour d'Unowhy")) {
            assertTrue(recipient, recipient in app)
        }
    }
}
