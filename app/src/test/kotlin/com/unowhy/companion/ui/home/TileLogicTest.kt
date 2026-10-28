package com.unowhy.companion.ui.home

import com.unowhy.companion.net.DeckPage
import com.unowhy.companion.net.DeckTile
import com.unowhy.companion.net.VoiceState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileLogicTest {

    private fun tile(whenState: String? = null, lit: String? = null) =
        DeckTile("t", "Tuile", "keys", null, null, whenState = whenState, lit = lit)

    private fun voice(
        connected: Boolean = true,
        muted: Boolean? = null,
        deafened: Boolean? = null,
        streaming: Boolean = false,
        lobby: Boolean = false,
        cameraOff: Boolean? = null,
    ) = VoiceState(connected, null, emptyList(), muted, deafened, streaming, lobby, cameraOff)

    @Test
    fun `une touche call n'apparait qu'en appel`() {
        assertTrue(tileShown(tile("call"), voice()))
        assertFalse(tileShown(tile("call"), null))
        assertFalse(tileShown(tile("call"), voice(connected = false)))
    }

    @Test
    fun `une touche idle disparait en appel`() {
        assertFalse(tileShown(tile("idle"), voice()))
        assertTrue(tileShown(tile("idle"), null))
        assertTrue(tileShown(tile("idle"), voice(connected = false)))
    }

    @Test
    fun `streaming, share et lobby suivent l'etat de l'appel`() {
        assertTrue(tileShown(tile("streaming"), voice(streaming = true)))
        assertFalse(tileShown(tile("streaming"), voice()))
        assertFalse(tileShown(tile("streaming"), null))

        assertTrue(tileShown(tile("share"), voice()))
        assertFalse(tileShown(tile("share"), voice(streaming = true)))
        assertFalse(tileShown(tile("share"), null))

        assertTrue(tileShown(tile("lobby"), voice(connected = false, lobby = true)))
        assertFalse(tileShown(tile("lobby"), voice()))
        assertFalse(tileShown(tile("lobby"), null))
    }

    @Test
    fun `sans condition une touche est toujours montree`() {
        assertTrue(tileShown(tile(), null))
        assertTrue(tileShown(tile(), voice()))
        assertTrue(tileShown(tile("inconnue"), null))
    }

    @Test
    fun `micro allume si coupe ou en sourdine, jamais hors appel`() {
        assertTrue(tileLit(tile(lit = "muted"), voice(muted = true)))
        assertTrue(tileLit(tile(lit = "muted"), voice(deafened = true)))
        assertFalse(tileLit(tile(lit = "muted"), voice(muted = false, deafened = false)))
        assertFalse(tileLit(tile(lit = "muted"), voice(connected = false, muted = true)))
        assertFalse(tileLit(tile(lit = "muted"), null))
    }

    @Test
    fun `sourdine seulement pour deafened, camera pour cameraOff`() {
        assertFalse(tileLit(tile(lit = "deafened"), voice(muted = true)))
        assertTrue(tileLit(tile(lit = "deafened"), voice(deafened = true)))
        assertTrue(tileLit(tile(lit = "camera"), voice(cameraOff = true)))
        assertFalse(tileLit(tile(lit = "camera"), voice(cameraOff = false)))
        assertFalse(tileLit(tile(), voice(muted = true)))
    }

    @Test
    fun `KeyRef relit l'ancien format de configuration`() {
        val key = KeyRef.of(JSONObject("""{"id":"k-3","label":"Mute","glyph":"","color":""}"""))
        assertEquals(KeyRef("k-3", "Mute", null, null), key)
        val json = key.toJson()
        assertEquals(setOf("id", "label", "glyph", "color"), json.keys().asSequence().toSet())
        assertEquals("", json.getString("glyph"))
        assertEquals("", json.getString("color"))
    }

    @Test
    fun `KeyRef relit une tuile du deck`() {
        val tile = DeckTile("k-1", "Muet", "keys", "M", "#ff0000")
        assertEquals(KeyRef("k-1", "Muet", "M", "#ff0000"), KeyRef.of(tile))
    }

    @Test
    fun `KeyRef put et list font l'aller-retour`() {
        val keys = listOf(KeyRef("k-0", "A", "a", "#00ff00"), KeyRef("2-4", "B"))
        val config = JSONObject()
        KeyRef.put(config, keys)
        assertEquals(keys, KeyRef.list(config))
        assertEquals(emptyList<KeyRef>(), KeyRef.list(JSONObject()))
    }

    @Test
    fun `slotOf lit les touches perso seulement`() {
        assertEquals(12, slotOf("k-12"))
        assertEquals(0, slotOf("k-0"))
        assertNull(slotOf("2-4"))
        assertNull(slotOf("k-"))
        assertNull(slotOf("k-x"))
    }

    private fun slot(n: Int, label: String? = null) =
        if (label == null) DeckTile("k-$n", "", "empty", null, null) else DeckTile("k-$n", label, "keys", null, null)

    private fun perso(vararg tiles: DeckTile) = DeckPage("Perso", tiles.toList(), editable = true)

    private fun fixed(vararg tiles: DeckTile) = DeckPage("Fixe", tiles.toList())

    @Test
    fun `nextFreeSlot rend le premier vide de la page perso`() {
        assertEquals(1, nextFreeSlot(listOf(perso(slot(0, "A"), slot(1), slot(2)))))
        assertNull(nextFreeSlot(listOf(fixed(slot(1)))))
        assertNull(nextFreeSlot(listOf(perso(slot(0, "A"), slot(1, "B")))))
    }

    @Test
    fun `reconcile met a jour un libelle change`() {
        val pages = listOf(perso(slot(0, "Nouveau")))
        val result = reconcileKeys(listOf(KeyRef("k-0", "Ancien")), pages)
        assertEquals(listOf(KeyRef("k-0", "Nouveau")), result)
    }

    @Test
    fun `reconcile retire une touche perso videe ou absente`() {
        val pages = listOf(perso(slot(0, "A"), slot(1)))
        val chosen = listOf(KeyRef("k-0", "A"), KeyRef("k-1", "B"), KeyRef("k-9", "C"))
        assertEquals(listOf(KeyRef("k-0", "A")), reconcileKeys(chosen, pages))
    }

    @Test
    fun `reconcile garde une touche d'une page fixe absente`() {
        val chosen = listOf(KeyRef("2-4", "Fantome"))
        assertNull(reconcileKeys(chosen, listOf(perso(slot(0, "A")))))
    }

    @Test
    fun `reconcile garde l'ordre et renvoie null quand rien ne change`() {
        val pages = listOf(fixed(DeckTile("2-4", "X", "keys", null, null)), perso(slot(0, "A")))
        val chosen = listOf(KeyRef("k-0", "A"), KeyRef("2-4", "X"))
        assertNull(reconcileKeys(chosen, pages))
        val renamed = listOf(KeyRef("2-4", "Y"), KeyRef("k-0", "A"))
        assertEquals(listOf(KeyRef("2-4", "X"), KeyRef("k-0", "A")), reconcileKeys(renamed, pages))
    }
}
