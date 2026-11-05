package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogueModelTest {

    /** Horloge en haut à gauche (2 × 2) : la moitié droite et le bas sont libres. */
    private fun layout() = HomeLayout(mutableListOf(Placed(WidgetType.CLOCK, WidgetSize.L, 0, 0, 0)), 2)

    @Test
    fun `une forme qui tient a la case touchee s y pose`() {
        val choice = layout().shapeChoices(WidgetType.WEATHER, 0, 2, 0).first { it.size == WidgetSize.L }
        assertTrue(choice.atCase)
        assertEquals(Fit(WidgetSize.L, 2, 0), choice.spot)
    }

    @Test
    fun `une case pres du bord recule pour laisser tenir la forme`() {
        val choice = layout().shapeChoices(WidgetType.WEATHER, 0, 3, 3).first { it.size == WidgetSize.L }
        assertTrue(choice.atCase)
        assertEquals(Fit(WidgetSize.L, 2, 2), choice.spot)
    }

    @Test
    fun `une forme sans place a la case propose la premiere place libre`() {
        // Bandeau 4 × 1 touché en (2, 0) : la rangée 0 est prise à gauche, la première rangée libre est la 2.
        val choice = layout().shapeChoices(WidgetType.CLOCK, 0, 2, 0).first { it.size == WidgetSize.W }
        assertFalse(choice.atCase)
        assertEquals(Fit(WidgetSize.W, 0, 2), choice.spot)
    }

    @Test
    fun `une forme qui ne tient nulle part n a pas de place`() {
        val full = HomeLayout(mutableListOf(Placed(WidgetType.CLOCK, WidgetSize.FULL, 0, 0, 0)), 1)
        full.shapeChoices(WidgetType.CLOCK, 0, 1, 1).forEach {
            assertFalse(it.atCase)
            assertNull(it.spot)
        }
    }

    @Test
    fun `seules les formes du widget sont proposees, rangees par famille`() {
        val sizes = layout().shapeChoices(WidgetType.WEATHER, 0, 2, 0).map { it.size }
        assertEquals(listOf(WidgetSize.S, WidgetSize.M, WidgetSize.L), sizes)
    }

    @Test
    fun `les 16 formes une fois chacune, par famille, de la plus petite a la plus grande`() {
        assertEquals(WidgetSize.entries.toSet(), SHAPE_ORDER.toSet())
        assertEquals(WidgetSize.entries.size, SHAPE_ORDER.size)
        assertEquals(ShapeFamily.entries.toList(), SHAPE_ORDER.map { it.family }.distinct())
        SHAPE_ORDER.zipWithNext().filter { (a, b) -> a.family == b.family }.forEach { (a, b) ->
            assertTrue("$a avant $b", a.w * a.h <= b.w * b.h)
        }
    }

    @Test
    fun `la fiche s ouvre sur la forme par defaut du widget`() {
        WidgetType.entries.forEach { type ->
            val choices = layout().shapeChoices(type, 0, 2, 0)
            assertEquals(type.sizes.first(), choices[openingShape(type)].size)
        }
    }

    @Test
    fun `chaque widget est range une seule fois, suggestions comprises`() {
        val shelves = catalogueShelves { true }
        assertEquals(WidgetType.entries.size, shelves.sumOf { it.second.size })
        assertEquals(WidgetType.entries.toSet(), shelves.flatMap { it.second }.toSet())
        assertEquals(CatalogueShelf.SUGGESTIONS, shelves.first().first)
        assertEquals(SUGGESTED.size, shelves.first().second.size)
    }

    @Test
    fun `un rayon sans widget propose disparait`() {
        val dev = CatalogueShelf.DEV.types.toSet()
        val shelves = catalogueShelves { it !in dev }
        assertFalse(shelves.any { it.first == CatalogueShelf.DEV })
        assertTrue(shelves.flatMap { it.second }.none { it in dev })
    }

    @Test
    fun `une suggestion non proposee laisse sa place`() {
        val shelves = catalogueShelves { it != SUGGESTED.first() }
        assertEquals(SUGGESTED.drop(1), shelves.first().second)
    }

    @Test
    fun `sans case touchee, toute forme qui tient quelque part est posable`() {
        val choice = layout().shapeChoices(WidgetType.CLOCK, 0, -1, -1).first { it.size == WidgetSize.L }
        assertTrue(choice.atCase)
        assertEquals(Fit(WidgetSize.L, 2, 0), choice.spot)
    }

    @Test
    fun `l autre page ne gene pas`() {
        val choice = layout().shapeChoices(WidgetType.CLOCK, 1, 0, 0).first { it.size == WidgetSize.L }
        assertTrue(choice.atCase)
        assertEquals(Fit(WidgetSize.L, 0, 0), choice.spot)
    }
}
