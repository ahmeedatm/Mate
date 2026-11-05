package app.mate.ui.home

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CurvePlanTest {

    @Test
    fun `autant d'heures que la place en laisse, au plus celles qu'on a`() {
        assertEquals(3, CurvePlan.count(length = 100f, minStep = 34f, available = 12))
        assertEquals(3, CurvePlan.count(length = 1000f, minStep = 34f, available = 3))
    }

    @Test
    fun `pas la place pour deux heures, ou moins de deux heures, rien`() {
        assertEquals(0, CurvePlan.count(length = 20f, minStep = 34f, available = 12))
        assertEquals(0, CurvePlan.count(length = -40f, minStep = 34f, available = 12))
        assertEquals(0, CurvePlan.count(length = 500f, minStep = 34f, available = 1))
    }

    @Test
    fun `la plus froide en bas, la plus chaude en haut`() {
        assertArrayEquals(floatArrayOf(100f, 50f, 0f), CurvePlan.place(listOf(5.0, 7.0, 9.0), low = 100f, high = 0f), 0.01f)
    }

    @Test
    fun `courbe plate, au milieu`() {
        assertArrayEquals(floatArrayOf(50f, 50f), CurvePlan.place(listOf(8.0, 8.2), low = 100f, high = 0f), 0.01f)
    }

    @Test
    fun `une etiquette sur deux quand elles se touchent`() {
        assertEquals(1, CurvePlan.every(labelWidth = 30f, step = 60f))
        assertEquals(2, CurvePlan.every(labelWidth = 30f, step = 20f))
        assertEquals(1, CurvePlan.every(labelWidth = 30f, step = 0f))
    }

    @Test
    fun `la derniere heure a son etiquette, sans coller a la precedente`() {
        // Une sur deux, 4 heures : 0 et 3 ; la 2 serait collée à la 3.
        assertEquals(listOf(0, 3), (0 until 4).filter { CurvePlan.labeled(it, count = 4, every = 2) })
        assertEquals(listOf(0, 2, 4), (0 until 5).filter { CurvePlan.labeled(it, count = 5, every = 2) })
        assertEquals(listOf(0, 1, 2), (0 until 3).filter { CurvePlan.labeled(it, count = 3, every = 1) })
    }
}
