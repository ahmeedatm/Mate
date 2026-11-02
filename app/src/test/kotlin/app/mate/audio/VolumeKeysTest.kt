package app.mate.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeKeysTest {

    @Test
    fun `reglage sur l'ordinateur sans ecran qui les veut, les boutons vont a l'ordinateur`() {
        assertEquals(VolumeKeys.Route.COMPUTER, VolumeKeys.route(onComputer = true, hasHandler = false, hasComputer = true))
    }

    @Test
    fun `un ecran qui veut les boutons passe avant le reglage`() {
        assertEquals(VolumeKeys.Route.SCREEN, VolumeKeys.route(onComputer = true, hasHandler = true, hasComputer = true))
        assertEquals(VolumeKeys.Route.SCREEN, VolumeKeys.route(onComputer = false, hasHandler = true, hasComputer = false))
    }

    @Test
    fun `reglage sur la tablette ou sans ordinateur, le volume reste a la tablette`() {
        assertEquals(VolumeKeys.Route.TABLET, VolumeKeys.route(onComputer = false, hasHandler = false, hasComputer = true))
        assertEquals(VolumeKeys.Route.TABLET, VolumeKeys.route(onComputer = true, hasHandler = false, hasComputer = false))
    }

    @Test
    fun `le pas monte ou descend`() {
        assertEquals(6, VolumeKeys.delta(up = true))
        assertEquals(-6, VolumeKeys.delta(up = false))
    }
}
