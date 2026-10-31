package app.mate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerChannelTest {

    @Test
    fun `paquet de deplacement signe, sans le jeton`() {
        val text = String(PointerPackets.encode("tok", 7, PointerMessage.Move(-3, 12)), Charsets.UTF_8)
        val json = "{\"dx\":-3,\"dy\":12}"
        assertEquals("MATE-POINTER\n${PointerPackets.signature("tok", 7, json)}\n7\n$json", text)
        assertFalse(text.contains("\ntok\n"))
    }

    @Test
    fun `paquet de defilement`() {
        val text = String(PointerPackets.encode("tok", 8, PointerMessage.Scroll(0, -2)), Charsets.UTF_8)
        assertTrue(text.endsWith("\n8\n{\"sx\":0,\"sy\":-2}"))
    }

    @Test
    fun `signature identique a celle du backend`() {
        // Valeur calculée par pointerSignature (backend/src/pointer.ts).
        assertEquals(
            "44929821dd4090e39ee3d44ec21f3420d4c5365631d7e135460fde5db3087da6",
            PointerPackets.signature("jeton", 7, "{\"dx\":5,\"dy\":-3}"),
        )
    }

    @Test
    fun `sans jeton la ligne reste presente et vide`() {
        val parts = String(PointerPackets.encode("", 1, PointerMessage.Move(1, 1)), Charsets.UTF_8).split("\n")
        assertEquals(4, parts.size)
        assertEquals("", parts[1])
    }

    @Test
    fun `le cumul additionne les deplacements`() {
        val c = PointerCoalescer()
        c.addMove(3, -1)
        c.addMove(4, 2)
        assertEquals(listOf<PointerMessage>(PointerMessage.Move(7, 1)), c.drain())
        assertTrue(c.drain().isEmpty())
    }

    @Test
    fun `defilement cumule a part du deplacement`() {
        val c = PointerCoalescer()
        c.addScroll(0, 1)
        c.addMove(5, 5)
        c.addScroll(0, 2)
        assertEquals(listOf(PointerMessage.Move(5, 5), PointerMessage.Scroll(0, 3)), c.drain())
    }

    @Test
    fun `un cumul nul n envoie rien`() {
        val c = PointerCoalescer()
        c.addMove(5, 5)
        c.addMove(-5, -5)
        assertTrue(c.drain().isEmpty())
    }

    @Test
    fun `un deplacement hors borne est decoupe`() {
        val c = PointerCoalescer()
        c.addMove(4500, -100)
        val out = c.drain().map { it as PointerMessage.Move }
        assertEquals(3, out.size)
        assertTrue(out.all { kotlin.math.abs(it.dx) <= POINTER_MAX_MOVE && kotlin.math.abs(it.dy) <= POINTER_MAX_MOVE })
        assertEquals(4500, out.sumOf { it.dx })
        assertEquals(-100, out.sumOf { it.dy })
    }

    @Test
    fun `un defilement negatif hors borne est decoupe`() {
        val c = PointerCoalescer()
        c.addScroll(0, -120)
        val out = c.drain().map { it as PointerMessage.Scroll }
        assertEquals(3, out.size)
        assertTrue(out.all { kotlin.math.abs(it.sy) <= POINTER_MAX_SCROLL })
        assertEquals(-120, out.sumOf { it.sy })
    }

    @Test
    fun `la borne exacte tient en un paquet`() {
        val c = PointerCoalescer()
        c.addMove(POINTER_MAX_MOVE, -POINTER_MAX_MOVE)
        assertEquals(1, c.drain().size)
    }

    @Test
    fun `cible usb en loopback`() {
        assertTrue(PointerTarget.parse("http://127.0.0.1:3737").viaUsb)
        assertTrue(PointerTarget.parse("http://localhost:3737").viaUsb)
        assertEquals(3737, PointerTarget.parse("http://127.0.0.1:3737").port)
    }

    @Test
    fun `cible reseau local`() {
        val t = PointerTarget.parse("http://192.168.1.20:3737")
        assertFalse(t.viaUsb)
        assertEquals("192.168.1.20", t.host)
        assertEquals(3737, t.port)
    }
}
