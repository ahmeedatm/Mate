package app.mate.ui.home

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TileSpanTest {
    private fun fill(cell: Int) = SpanSlot(cell, filler = true)

    @Test
    fun `sans touche large chaque case garde sa place`() {
        assertEquals(listOf(SpanSlot(0), SpanSlot(1), SpanSlot(2)), spanSlots(listOf(1, 1, 1), columns = 3))
    }

    @Test
    fun `une touche large prend sa case et la suivante`() {
        assertEquals(listOf(SpanSlot(0), SpanSlot(1), fill(1), SpanSlot(2)), spanSlots(listOf(1, 2, 1), columns = 4))
    }

    @Test
    fun `une touche large en derniere colonne passe a la rangee suivante, une case vide la devance`() {
        val slots = spanSlots(listOf(1, 1, 2), columns = 3)
        assertEquals(listOf(SpanSlot(0), SpanSlot(1), SpanSlot(PAD), SpanSlot(2), fill(2)), slots)
    }

    @Test
    fun `sur une seule colonne la touche large devient simple`() {
        assertEquals(listOf(SpanSlot(0), SpanSlot(1)), spanSlots(listOf(2, 1), columns = 1))
    }

    @Test
    fun `deux touches larges de suite remplissent une rangee de quatre`() {
        assertEquals(listOf(SpanSlot(0), fill(0), SpanSlot(1), fill(1)), spanSlots(listOf(2, 2), columns = 4))
    }

    @Test
    fun `la touche large survit au JSON du widget, la simple n'ecrit rien`() {
        val wide = KeyRef("k-1", "Large", span = 2)
        assertEquals(2, KeyRef.of(wide.toJson())?.span)
        assertFalse(KeyRef("k-2", "Simple").toJson().has("span"))
        assertEquals(2, KeyRef.of(JSONObject().put("id", "k-3").put("span", 7).put("label", "x"))?.span)
    }

    @Test
    fun `la largeur d'une touche du deck passe a la touche choisie`() {
        val tile = app.mate.net.DeckTile("k-1", "Large", "keys", null, null, span = 2)
        assertEquals(2, KeyRef.of(tile).span)
        assertEquals(1, KeyRef.of(app.mate.net.DeckTile("k-2", "Simple", "keys", null, null)).span)
    }
}
