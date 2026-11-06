package app.mate.ui.home

import app.mate.ui.home.PresentKey.END
import app.mate.ui.home.PresentKey.MIC
import app.mate.ui.home.PresentKey.NEXT
import app.mate.ui.home.PresentKey.PREVIOUS
import app.mate.ui.home.PresentKey.START
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentLayoutTest {

    private fun keysOf(size: WidgetSize) = planPresent(size).rows.flatMap { row -> row.cells.flatMap { it.keys } }

    @Test
    fun `chaque forme garde Suivante, le geste principal`() {
        for (size in WidgetSize.entries) assertTrue(size.name, NEXT in keysOf(size))
    }

    @Test
    fun `pastille, Suivante seule, sans titre ni chrono`() {
        val plan = planPresent(WidgetSize.S)
        assertEquals(listOf(NEXT), keysOf(WidgetSize.S))
        assertFalse(plan.header)
    }

    @Test
    fun `ligne, plus large, plus de touches, Suivante toujours la plus large`() {
        assertEquals(listOf(PREVIOUS, NEXT), keysOf(WidgetSize.M))
        assertEquals(listOf(PREVIOUS, NEXT, MIC), keysOf(WidgetSize.M3))
        assertEquals(listOf(START, PREVIOUS, NEXT, END, MIC), keysOf(WidgetSize.W))
        for (size in listOf(WidgetSize.M, WidgetSize.M3, WidgetSize.W)) {
            val cells = planPresent(size).rows.single().cells
            assertEquals(size.name, cells.maxBy { it.weight }.keys, listOf(NEXT))
            assertTrue(planPresent(size).sideHeader)
        }
    }

    @Test
    fun `colonne, Suivante en haut et la plus grande, puis Precedente, puis le reste`() {
        val rows = planPresent(WidgetSize.V3).rows
        assertEquals(listOf(NEXT), rows[0].cells.single().keys)
        assertEquals(listOf(PREVIOUS), rows[1].cells.single().keys)
        assertEquals(listOf(START, END, MIC), rows[2].cells.flatMap { it.keys })
        assertTrue(rows[0].weight > rows[1].weight)
        assertEquals("1 × 3 : le nom sous l'icône", PresentStyle.STACKED, rows[2].cells.first().style)
        assertEquals("1 × 2 : icône seule, la place manque", PresentStyle.ICON, planPresent(WidgetSize.V).rows[2].cells.first().style)
        assertEquals("Précédente tient dans une colonne basse", PresentStyle.INLINE, rows[1].cells.single().style)
    }

    @Test
    fun `bloc et grand en hauteur, les cinq touches sur deux rangees`() {
        for (size in listOf(WidgetSize.L, WidgetSize.L3, WidgetSize.L23, WidgetSize.L33, WidgetSize.T, WidgetSize.T3)) {
            assertEquals(size.name, listOf(PREVIOUS, NEXT, START, END, MIC), keysOf(size))
            assertEquals(size.name, 2, planPresent(size).rows.size)
        }
    }

    @Test
    fun `grand en largeur, une rangee, Debut et Fin empiles`() {
        for (size in listOf(WidgetSize.XL, WidgetSize.XL3)) {
            val row = planPresent(size).rows.single()
            assertEquals(size.name, listOf(START, END), row.cells.single { it.keys.size == 2 }.keys)
        }
    }

    @Test
    fun `pastille, le nom sous l'icone a partir de 90 dp de haut, icone de 32 a 96 dp`() {
        assertEquals(PillKey(withText = false, iconDp = 89), presentPill(89))
        assertEquals(PillKey(withText = true, iconDp = 54), presentPill(90))
        assertEquals(PillKey(withText = true, iconDp = 96), presentPill(200))
        assertEquals(PillKey(withText = false, iconDp = 32), presentPill(20))
    }

    @Test
    fun `plein ecran, le chrono et les touches plus grands qu'en bloc`() {
        val full = planPresent(WidgetSize.FULL)
        val block = planPresent(WidgetSize.L)
        assertTrue(full.chronoSp > block.chronoSp)
        assertTrue(full.scale > block.scale)
        assertEquals(listOf(PREVIOUS, NEXT, START, END, MIC), keysOf(WidgetSize.FULL))
    }
}
