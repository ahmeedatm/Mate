package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenPreviewTest {

    @Test
    fun `une image toutes les 1,5 s tant que tout va bien`() {
        assertEquals(1_500L, ScreenPreview.nextDelayMs(failures = 0))
    }

    @Test
    fun `chaque echec de suite double l'attente, jusqu'a 10 s`() {
        assertEquals(listOf(1_500L, 3_000L, 6_000L, 10_000L, 10_000L), (0..4).map { ScreenPreview.nextDelayMs(it) })
        assertEquals(10_000L, ScreenPreview.nextDelayMs(failures = 500))
    }

    @Test
    fun `l'image sans reduction quand la vue n'est pas encore mesuree`() {
        assertEquals(1, ScreenPreview.sampleSize(640, 360, viewWidth = 0, viewHeight = 0))
    }

    @Test
    fun `l'image est reduite tant qu'elle reste au moins aussi grande que la vue`() {
        // Vue de 300 x 170 (image calee dedans : 300 x 169) : 640 x 360 reduit par 2 donne 320 x 180, par 4 trop petit.
        assertEquals(2, ScreenPreview.sampleSize(640, 360, viewWidth = 300, viewHeight = 170))
        assertEquals(4, ScreenPreview.sampleSize(640, 360, viewWidth = 150, viewHeight = 85))
        assertEquals(1, ScreenPreview.sampleSize(640, 360, viewWidth = 640, viewHeight = 400))
        assertEquals(1, ScreenPreview.sampleSize(640, 360, viewWidth = 1200, viewHeight = 700))
    }

    @Test
    fun `la hauteur de la vue limite aussi la reduction`() {
        // Vue large mais basse : l'image se cale sur la hauteur (100 x 178 serait trop petit a 160 de large).
        assertEquals(2, ScreenPreview.sampleSize(640, 360, viewWidth = 900, viewHeight = 170))
    }

    @Test
    fun `la reduction est une puissance de deux et jamais zero`() {
        for (w in listOf(1, 7, 100, 640)) for (v in listOf(1, 9, 90, 700)) {
            val s = ScreenPreview.sampleSize(w, w / 2 + 1, v, v)
            assertTrue("$w x $v : $s", s >= 1 && s and (s - 1) == 0)
        }
    }

    @Test
    fun `l'aperçu n'est dans aucun modele, ne s'agrandit pas et s'ajoute sur ordinateur seulement`() {
        for (template in Template.entries) {
            assertFalse("$template", template.build().widgets.any { it.type == WidgetType.SCREEN })
        }
        assertFalse(WidgetType.SCREEN.zoomable)
        assertTrue(WidgetType.SCREEN.computer)
        assertEquals("screen", WidgetType.SCREEN.key)
    }

    @Test
    fun `recadree, l'image garde assez de pixels pour remplir la vue`() {
        // Colonne de 140 × 400 px : l'image de 640 × 360 est agrandie pour la hauteur, aucune réduction.
        assertEquals(1, ScreenPreview.sampleSize(640, 360, viewWidth = 140, viewHeight = 400, crop = true))
        // Pastille de 150 × 85 px : remplie par une image de 151 × 85, réduite par 4 au plus.
        assertEquals(4, ScreenPreview.sampleSize(640, 360, viewWidth = 150, viewHeight = 85, crop = true))
        // Bande de 600 × 100 px : la largeur commande, une réduction de 2 laisserait 320 px.
        assertEquals(1, ScreenPreview.sampleSize(640, 360, viewWidth = 600, viewHeight = 100, crop = true))
    }

    @Test
    fun `seules Pastille, Ligne et Colonne recadrent l'image`() {
        assertEquals(setOf(ShapeFamily.PILL, ShapeFamily.ROW, ShapeFamily.COLUMN), ShapeFamily.entries.filter { ScreenPreview.crops(it) }.toSet())
    }
}
