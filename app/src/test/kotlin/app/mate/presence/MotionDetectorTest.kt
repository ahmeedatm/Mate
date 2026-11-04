package app.mate.presence

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class MotionDetectorTest {

    private val size = 32 * 24

    private fun flat(value: Int) = ByteArray(size) { value.toByte() }

    /** Image plate avec un bloc de [count] pixels à [value]. */
    private fun withBlock(base: Int, value: Int, count: Int) = flat(base).also { for (i in 0 until count) it[i] = value.toByte() }

    @Test
    fun `la premiere image ne declenche rien`() {
        assertFalse(MotionDetector().moved(flat(100)))
    }

    @Test
    fun `une scene immobile ne bouge pas`() {
        val detector = MotionDetector()
        detector.moved(flat(100))
        assertFalse(detector.moved(flat(100)))
    }

    @Test
    fun `un bruit de capteur ne bouge pas`() {
        val detector = MotionDetector()
        detector.moved(flat(100))
        assertFalse(detector.moved(ByteArray(size) { (100 + it % 5 - 2).toByte() }))
    }

    @Test
    fun `une zone qui change franchement bouge`() {
        val detector = MotionDetector()
        detector.moved(flat(100))
        assertTrue(detector.moved(withBlock(100, 200, size / 10)))
    }

    @Test
    fun `une zone trop petite ne bouge pas`() {
        val detector = MotionDetector()
        detector.moved(flat(100))
        assertFalse(detector.moved(withBlock(100, 200, 3)))
    }

    @Test
    fun `un changement de lumiere sur toute l'image ne bouge pas`() {
        val detector = MotionDetector()
        detector.moved(flat(100))
        assertFalse(detector.moved(flat(160)))
    }

    @Test
    fun `une image de taille differente repart de zero`() {
        val detector = MotionDetector()
        detector.moved(flat(100))
        assertFalse(detector.moved(ByteArray(10)))
    }

    @Test
    fun `reset oublie l'image precedente`() {
        val detector = MotionDetector()
        detector.moved(flat(100))
        detector.reset()
        assertFalse(detector.moved(withBlock(100, 200, size / 10)))
    }

    @Test
    fun `l'image comparee est celle d'avant, pas la premiere`() {
        val detector = MotionDetector()
        detector.moved(flat(100))
        detector.moved(withBlock(100, 200, size / 10))
        assertFalse(detector.moved(withBlock(100, 200, size / 10)))
    }

    @Test
    fun `reduction d'un plan Y avec foulee de ligne et de pixel`() {
        // 8x4, pixels espaces de 2 octets, lignes de 20 octets (4 d'avance) ; moitie gauche a 40, droite a 200.
        val width = 8
        val height = 4
        val rowStride = 20
        val pixelStride = 2
        val bytes = ByteArray(rowStride * height)
        for (y in 0 until height) for (x in 0 until width) bytes[y * rowStride + x * pixelStride] = (if (x < 4) 40 else 200).toByte()
        val out = ByteArray(2)
        MotionDetector.reduce(ByteBuffer.wrap(bytes), rowStride, pixelStride, width, height, out, 2, 1)
        assertArrayEquals(byteArrayOf(40, 200.toByte()), out)
    }

    @Test
    fun `reduction sans depasser la derniere ligne du tampon`() {
        // Dernier pixel du plan = dernier octet du tampon (pas d'avance en fin de ligne).
        val bytes = ByteArray(3 * 2) { 77 }
        val out = ByteArray(1)
        MotionDetector.reduce(ByteBuffer.wrap(bytes), 3, 1, 3, 2, out, 1, 1)
        assertArrayEquals(byteArrayOf(77), out)
    }
}
