package app.mate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ordre `page` : seules des pages web s'ouvrent en plein écran, jamais un fichier, du javascript: ni un intent. */
class PageOverlayTest {
    @Test
    fun `http et https avec un hote passent`() {
        for (url in listOf("http://127.0.0.1:4300/a/", "https://exemple.fr/", "HTTP://192.168.1.5:4300/b/index.html")) {
            assertTrue(url, PageOverlay.isPageUrl(url))
        }
    }

    @Test
    fun `le reste est refuse`() {
        for (url in listOf("file:///sdcard/x.html", "javascript:alert(1)", "intent://x#Intent;end", "content://a/b", "http://", "pas une url", "")) {
            assertFalse(url, PageOverlay.isPageUrl(url))
        }
    }
}
