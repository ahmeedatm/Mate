package app.mate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WledApiTest {

    @Test
    fun `l'etat d'une WLED devient une lumiere reglable, luminosite en pourcents et couleur du premier segment`() {
        val e = parseWledState(
            "wled:a1b2c3d4e5f6",
            "WLED bureau",
            """{"on":true,"bri":102,"seg":[{"id":0,"col":[[0,80,255],[0,0,0],[0,0,0]]},{"id":1,"col":[[255,0,0]]}]}""",
        )
        assertEquals("wled:a1b2c3d4e5f6", e.id)
        assertEquals("WLED bureau", e.name)
        assertTrue(e.on)
        assertTrue(e.available)
        assertFalse(e.isScene)
        assertEquals(HaLight(color = true, brightness = 40, rgb = 0x0050FF), e.light)
    }

    @Test
    fun `une WLED eteinte n'a pas de luminosite, sa couleur reste lue`() {
        val e = parseWledState("wled:x", "Ruban", """{"on":false,"bri":255,"seg":[{"col":[[255,160,0]]}]}""")
        assertFalse(e.on)
        assertNull(e.light!!.brightness)
        assertEquals(0xFFA000, e.light!!.rgb)
    }

    @Test
    fun `un etat incomplet ne casse rien`() {
        val e = parseWledState("wled:x", "Ruban", """{"on":true}""")
        assertTrue(e.on)
        assertEquals(HaLight(color = true, brightness = 0, rgb = null), e.light)
    }

    @Test
    fun `le reglage s'ecrit en bri sur 255 et en couleur du premier segment, et allume la lumiere`() {
        val body = wledStateBody(brightness = 40, rgb = 0x0050FF)
        assertTrue(body.getBoolean("on"))
        assertEquals(102, body.getInt("bri"))
        val col = body.getJSONArray("seg").getJSONObject(0).getJSONArray("col").getJSONArray(0)
        assertEquals(listOf(0, 80, 255), (0 until col.length()).map(col::getInt))
    }

    @Test
    fun `un reglage de luminosite seule ne touche pas la couleur`() {
        val body = wledStateBody(brightness = 100, rgb = null)
        assertEquals(255, body.getInt("bri"))
        assertFalse(body.has("seg"))
    }
}
