package app.mate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookHaloTest {
    private val darkText = 0.02
    private val lightText = 0.85

    @Test
    fun `texte fonce sur cartes claires, halo`() {
        assertTrue(Look.wantsHalo(darkText, surfaceLuminance = 0.9, ownFill = false))
    }

    @Test
    fun `texte clair sur cartes sombres, halo`() {
        assertTrue(Look.wantsHalo(lightText, surfaceLuminance = 0.02, ownFill = false))
    }

    @Test
    fun `texte de la couleur des cartes, pas de halo`() {
        assertFalse(Look.wantsHalo(lightText, surfaceLuminance = 0.9, ownFill = false))
        assertFalse(Look.wantsHalo(darkText, surfaceLuminance = 0.02, ownFill = false))
    }

    @Test
    fun `texte pose sur son propre fond, pas de halo`() {
        assertFalse(Look.wantsHalo(darkText, surfaceLuminance = 0.9, ownFill = true))
        assertFalse(Look.wantsHalo(lightText, surfaceLuminance = 0.02, ownFill = true))
    }
}
