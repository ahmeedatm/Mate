package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeFillTest {

    @Test
    fun `le doigt pose ne fait pas sauter le volume`() {
        assertEquals(45, volumeAfterDrag(start = 45, downY = 300f, y = 300f, height = 600))
    }

    @Test
    fun `glisser toute la hauteur vers le haut ajoute 100 points`() {
        assertEquals(70, volumeAfterDrag(start = 40, downY = 300f, y = 120f, height = 600))
    }

    @Test
    fun `le volume reste entre 0 et 100`() {
        assertEquals(100, volumeAfterDrag(start = 90, downY = 500f, y = 0f, height = 600))
        assertEquals(0, volumeAfterDrag(start = 10, downY = 0f, y = 500f, height = 600))
    }

    @Test
    fun `une vue sans hauteur garde le volume de depart`() {
        assertEquals(30, volumeAfterDrag(start = 30, downY = 0f, y = 50f, height = 0))
    }
}
