package app.mate.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraChoiceTest {

    @Test
    fun `la plus petite image d'au moins 160 sur 120`() {
        val sizes = listOf(1280 to 720, 640 to 480, 320 to 240, 176 to 144, 160 to 120)
        assertEquals(160 to 120, CameraChoice.smallest(sizes))
    }

    @Test
    fun `les images trop petites sont ecartees`() {
        assertEquals(176 to 144, CameraChoice.smallest(listOf(128 to 96, 176 to 144, 320 to 240)))
        assertEquals(240 to 160, CameraChoice.smallest(listOf(240 to 160, 160 to 100)))
    }

    @Test
    fun `aucune image assez grande ou aucune image`() {
        assertNull(CameraChoice.smallest(listOf(128 to 96)))
        assertNull(CameraChoice.smallest(emptyList()))
    }

    @Test
    fun `la plus petite taille se juge sur la surface`() {
        assertEquals(200 to 120, CameraChoice.smallest(listOf(160 to 300, 200 to 120)))
    }

    @Test
    fun `la cadence au plafond le plus bas`() {
        assertEquals(15 to 15, CameraChoice.lowestRate(listOf(30 to 30, 15 to 15, 5 to 30)))
    }

    @Test
    fun `a plafond egal, le plancher le plus haut`() {
        assertEquals(15 to 15, CameraChoice.lowestRate(listOf(7 to 15, 15 to 15, 10 to 15)))
    }

    @Test
    fun `aucune cadence proposee`() {
        assertNull(CameraChoice.lowestRate(emptyList()))
    }
}
