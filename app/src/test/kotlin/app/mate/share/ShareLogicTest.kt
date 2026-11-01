package app.mate.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

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

    @Test
    fun `n'accepte que les URI content d'une autre appli`() {
        assertTrue(ShareLogic.acceptsUri("content", "com.android.providers.media.documents"))
        assertTrue(ShareLogic.acceptsUri("CONTENT", "media"))
        assertFalse(ShareLogic.acceptsUri("file", ""))
        assertFalse(ShareLogic.acceptsUri("http", "exemple.fr"))
        assertFalse(ShareLogic.acceptsUri("android.resource", "app.mate"))
        assertFalse(ShareLogic.acceptsUri(null, "media"))
        assertFalse(ShareLogic.acceptsUri("content", null))
    }

    @Test
    fun `refuse les URI des fournisseurs de Mate, avec ou sans identifiant d'utilisateur`() {
        assertFalse(ShareLogic.acceptsUri("content", "app.mate.fileprovider"))
        assertFalse(ShareLogic.acceptsUri("content", "app.mate.essai.provider"))
        assertFalse(ShareLogic.acceptsUri("content", "APP.MATE.computers"))
        assertFalse(ShareLogic.acceptsUri("content", "10@app.mate.fileprovider"))
        assertTrue(ShareLogic.acceptsUri("content", "com.exemple.app.mate"))
    }

    @Test
    fun `10 fichiers au plus par envoi, le surplus est compté à part`() {
        val (kept, surplus) = ShareLogic.limitCount((1..13).toList())
        assertEquals((1..10).toList(), kept)
        assertEquals(3, surplus)
        assertEquals(0, ShareLogic.limitCount((1..10).toList()).second)
    }

    @Test
    fun `la copie bornée copie tout ce qui tient`() {
        val data = ByteArray(1000) { it.toByte() }
        val out = ByteArrayOutputStream()
        assertEquals(1000L, ShareLogic.copyBounded(ByteArrayInputStream(data), out, 1000))
        assertTrue(data.contentEquals(out.toByteArray()))
    }

    @Test
    fun `la copie bornée s'arrête à la limite plus un octet, sans lire la fin du flux`() {
        var read = 0L
        val endless = object : InputStream() {
            override fun read(): Int {
                read++
                return 1
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                read += len
                return len
            }
        }
        assertThrows(ShareLogic.TooLarge::class.java) { ShareLogic.copyBounded(endless, ByteArrayOutputStream(), 100_000) }
        assertEquals(100_001L, read)
    }

    @Test
    fun `un fichier d'exactement la limite passe, un octet de plus non`() {
        assertEquals(50L, ShareLogic.copyBounded(ByteArrayInputStream(ByteArray(50)), ByteArrayOutputStream(), 50))
        assertThrows(ShareLogic.TooLarge::class.java) {
            ShareLogic.copyBounded(ByteArrayInputStream(ByteArray(51)), ByteArrayOutputStream(), 50)
        }
    }
}
