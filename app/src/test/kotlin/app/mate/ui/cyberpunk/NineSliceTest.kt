package app.mate.ui.cyberpunk

import org.junit.Assert.assertEquals
import org.junit.Test

class NineSliceTest {
    @Test
    fun cutsNineZonesKeepingTheCorners() {
        val z = NineSlice.zones(srcW = 100, srcH = 60, dstW = 300, dstH = 200, margin = 10)
        assertEquals(9, z.size)
        // Coin haut gauche : 10 x 10 pris et posé tel quel.
        assertEquals(NineSlice.Zone(0, 0, 10, 10, 0, 0, 10, 10), z[0])
        // Centre : étiré.
        assertEquals(NineSlice.Zone(10, 10, 90, 50, 10, 10, 290, 190), z[4])
        // Coin bas droit.
        assertEquals(NineSlice.Zone(90, 50, 100, 60, 290, 190, 300, 200), z[8])
    }

    @Test
    fun marginShrinksWhenTheTargetIsTooSmall() {
        val z = NineSlice.zones(srcW = 100, srcH = 100, dstW = 12, dstH = 40, margin = 10)
        assertEquals(6, z[0].dr)
        assertEquals(6, z[8].dl)
        assertEquals(12, z[8].dr)
    }
}
