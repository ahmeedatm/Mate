package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La règle des widgets (#178) : chaque widget accepte les 16 formes de la grille et a un aperçu à données d'exemple.
 * Ce qui manque encore est dans les listes d'exceptions, une par famille de widgets (`WidgetGaps*.kt`, à côté de ce
 * fichier) : chaque issue de famille vide la sienne, la règle est tenue quand elles sont toutes vides.
 */
class WidgetRulesTest {

    private val gaps = GAPS_HEURE + GAPS_LISTES + GAPS_SON + GAPS_ORDINATEUR + GAPS_APPELS + GAPS_DEV + GAPS_PERSO

    @Test
    fun `seize formes, chaque rectangle de 1 x 1 a 4 x 4 une fois`() {
        val shapes = WidgetSize.entries.map { it.w to it.h }
        assertEquals(16, shapes.size)
        assertEquals((1..COLS).flatMap { w -> (1..ROWS).map { h -> w to h } }.toSet(), shapes.toSet())
    }

    @Test
    fun `chaque forme a sa famille`() {
        fun family(w: Int, h: Int) = WidgetSize.entries.single { it.w == w && it.h == h }.family
        assertEquals(ShapeFamily.PILL, family(1, 1))
        for (w in 2..4) assertEquals("$w × 1", ShapeFamily.ROW, family(w, 1))
        for (h in 2..4) assertEquals("1 × $h", ShapeFamily.COLUMN, family(1, h))
        for ((w, h) in listOf(2 to 2, 3 to 2, 2 to 3, 3 to 3)) assertEquals("$w × $h", ShapeFamily.BLOCK, family(w, h))
        for ((w, h) in listOf(2 to 4, 3 to 4, 4 to 2, 4 to 3)) assertEquals("$w × $h", ShapeFamily.LARGE, family(w, h))
        assertEquals(ShapeFamily.FULL, family(4, 4))
    }

    @Test
    fun `chaque widget accepte les 16 formes, sauf exception`() {
        val excused = gaps.filter { it.shapes }.map { it.type }.toSet()
        for (type in WidgetType.entries - excused) {
            assertEquals("${type.key} : formes manquantes", emptyList<WidgetSize>(), WidgetSize.entries - type.sizes.toSet())
        }
    }

    @Test
    fun `chaque widget a un apercu, sauf exception`() {
        val excused = gaps.filter { it.preview }.map { it.type }.toSet()
        for (type in WidgetType.entries - excused) assertNotNull("${type.key} : pas d'aperçu (WidgetSamples)", WidgetSamples.of(type))
    }

    @Test
    fun `une exception tenue se retire de la liste`() {
        for (gap in gaps) {
            assertTrue("${gap.type.key} : une exception sans rien d'excusé", gap.shapes || gap.preview)
            assertTrue("${gap.type.key} : raison vide", gap.reason.isNotBlank())
            if (gap.shapes) assertTrue("${gap.type.key} a déjà les 16 formes : retirer shapes", type16(gap.type).not())
            if (gap.preview) assertTrue("${gap.type.key} a déjà un aperçu : retirer preview", WidgetSamples.of(gap.type) == null)
        }
    }

    @Test
    fun `un widget au plus une fois dans les exceptions`() {
        val dup = gaps.groupBy { it.type }.filterValues { it.size > 1 }.keys
        assertEquals(emptySet<WidgetType>(), dup)
    }

    private fun type16(type: WidgetType) = type.sizes.toSet().size == WidgetSize.entries.size
}
