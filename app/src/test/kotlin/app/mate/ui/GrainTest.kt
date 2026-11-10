package app.mate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrainTest {
    private val light = 0x40FFF8F2
    private val dark = 0x20000000

    @Test
    fun `deux couleurs transparentes, pas de grain`() {
        assertFalse(Grain.active(0x00000000, 0x00000000))
        assertFalse(Grain.active(0x00FFFFFF, 0x00123456))
    }

    @Test
    fun `reglage eteint, pas de grain meme si le theme a ses couleurs`() {
        assertFalse(Grain.shown(false, light, dark))
        assertTrue(Grain.shown(true, light, dark))
        assertFalse(Grain.shown(true, 0, 0))
    }

    @Test
    fun `une seule couleur visible suffit`() {
        assertTrue(Grain.active(light, 0))
        assertTrue(Grain.active(0, dark))
    }

    @Test
    fun `la tuile ne contient que les deux couleurs et du vide`() {
        val pixels = Grain.pixels(light, dark)
        assertEquals(Grain.TILE * Grain.TILE, pixels.size)
        assertTrue(pixels.all { it == 0 || it == light || it == dark })
    }

    @Test
    fun `la densite est proche de 73 pour cent`() {
        val pixels = Grain.pixels(light, dark)
        val density = pixels.count { it != 0 }.toFloat() / pixels.size
        assertEquals(0.73f, density, 0.02f)
    }

    @Test
    fun `la tuile est toujours la meme`() {
        assertTrue(Grain.pixels(light, dark).contentEquals(Grain.pixels(light, dark)))
    }
}
