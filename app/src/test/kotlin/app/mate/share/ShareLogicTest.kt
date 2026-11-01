package app.mate.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLogicTest {

    private val stamp = "2026-11-01_10-30-00"

    @Test
    fun `garde le nom donne par l'appli`() {
        assertEquals("facture.pdf", ShareLogic.fileName("facture.pdf", "application/pdf", stamp))
    }

    @Test
    fun `retire dossiers, guillemets et retours a la ligne du nom`() {
        assertEquals("evil.txt", ShareLogic.fileName("../../evil.txt", null, stamp))
        assertEquals("a b.pdf", ShareLogic.fileName("a\"\r\n b.pdf", null, stamp))
        assertEquals("c.png", ShareLogic.fileName("dossier\\c.png", null, stamp))
    }

    @Test
    fun `sans nom, en donne un d'apres le type`() {
        assertEquals("Partage $stamp.jpg", ShareLogic.fileName(null, "image/jpeg", stamp))
        assertEquals("Partage $stamp.pdf", ShareLogic.fileName("  ", "application/pdf", stamp))
        assertEquals("Partage $stamp", ShareLogic.fileName("", "video/inconnu", stamp))
    }

    @Test
    fun `un texte partage est un lien seulement s'il en contient un`() {
        assertTrue(ShareLogic.isLink("https://exemple.fr/page"))
        assertTrue(ShareLogic.isLink("Regarde ça https://exemple.fr/page"))
        assertFalse(ShareLogic.isLink("une simple phrase"))
        assertFalse(ShareLogic.isLink(null))
    }

    @Test
    fun `le lien est rangé dans un fichier texte daté`() {
        assertEquals("Lien $stamp.txt", ShareLogic.linkFileName(stamp))
    }

    @Test
    fun `20 Mo au plus, comme l'ordinateur`() {
        assertTrue(ShareLogic.fits(20L * 1024 * 1024))
        assertFalse(ShareLogic.fits(20L * 1024 * 1024 + 1))
    }
}
