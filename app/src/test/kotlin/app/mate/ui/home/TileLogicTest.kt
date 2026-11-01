package app.mate.ui.home

import app.mate.net.DeckPage
import app.mate.net.DeckTile
import app.mate.net.VoiceState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        val json = key!!.toJson()
        assertEquals(setOf("id", "label", "glyph", "color"), json.keys().asSequence().toSet())
        assertEquals("", json.getString("glyph"))
        assertEquals("", json.getString("color"))
    }

    @Test
    fun `un element sans id est ignore au lieu de faire planter la lecture`() {
        assertNull(KeyRef.of(JSONObject("""{"label":"Sans id"}""")))
        val config = JSONObject("""{"keys":[{"label":"Sans id"},{"id":"k-1","label":"A"},"abime",null]}""")
        assertEquals(listOf(KeyRef("k-1", "A")), KeyRef.list(config))
        assertEquals(listOf(KeyRef("k-1", "A")), KeyRef.listFor(config, "pc-a"))
        val own = JSONObject("""{"byComputer":{"pc-a":{"keys":[{"id":"","label":"Vide"},{"id":"2-4","label":"B"}]}}}""")
        assertEquals(listOf(KeyRef("2-4", "B")), KeyRef.listFor(own, "pc-a"))
    }

    @Test
    fun `KeyRef relit une tuile du deck`() {
        val tile = DeckTile("k-1", "Muet", "keys", "M", "#ff0000")
        assertEquals(KeyRef("k-1", "Muet", "M", "#ff0000"), KeyRef.of(tile))
    }

    @Test
    fun `l'ancien format est rattache a l'ordinateur actif puis retire`() {
        val config = JSONObject()
        val old = listOf(KeyRef("k-0", "A"), KeyRef("2-4", "B"))
        KeyRef.put(config, old)
        assertEquals(old, KeyRef.listFor(config, "pc-a"))
        assertTrue(KeyRef.adopt(config, "pc-a"))
        assertFalse(config.has("keys"))
        assertEquals(old, KeyRef.listFor(config, "pc-a"))
        assertEquals(emptyList<KeyRef>(), KeyRef.listFor(config, "pc-b"))
        assertFalse(KeyRef.adopt(config, "pc-a"))
    }

    @Test
    fun `deux ordinateurs gardent chacun leurs touches`() {
        val config = JSONObject()
        val a = listOf(KeyRef("k-0", "A"))
        val b = listOf(KeyRef("k-5", "B"), KeyRef("3-1", "C"))
        assertTrue(KeyRef.putFor(config, "pc-a", a))
        assertTrue(KeyRef.putFor(config, "pc-b", b))
        assertEquals(a, KeyRef.listFor(config, "pc-a"))
        assertEquals(b, KeyRef.listFor(config, "pc-b"))
    }

    @Test
    fun `ecrire pour A ne touche pas B`() {
        val config = JSONObject()
        val b = listOf(KeyRef("k-5", "B"))
        KeyRef.putFor(config, "pc-b", b)
        KeyRef.putFor(config, "pc-a", listOf(KeyRef("k-0", "A")))
        KeyRef.putFor(config, "pc-a", emptyList())
        assertEquals(b, KeyRef.listFor(config, "pc-b"))
        assertEquals(emptyList<KeyRef>(), KeyRef.listFor(config, "pc-a"))
    }

    @Test
    fun `sans ordinateur actif on n'ecrit rien`() {
        val config = JSONObject()
        val old = listOf(KeyRef("k-0", "A"))
        KeyRef.put(config, old)
        val before = config.toString()
        assertFalse(KeyRef.putFor(config, null, listOf(KeyRef("k-1", "Z"))))
        assertFalse(KeyRef.adopt(config, null))
        assertEquals(before, config.toString())
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

    @Test
    fun `pageSlotOf lit les touches des pages de touches nommees`() {
        assertEquals(0 to 3, pageSlotOf("k1-3"))
        assertEquals(2 to 12, pageSlotOf("k3-12"))
        assertNull(pageSlotOf("k-3"))
        assertNull(pageSlotOf("k0-3"))
        assertNull(pageSlotOf("2-4"))
        assertNull(pageSlotOf("k2-"))
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

    @Test
    fun `reconcile ne touche a rien quand les pages ne sont pas lues`() {
        assertNull(reconcileKeys(listOf(KeyRef("k-0", "A"), KeyRef("k-3", "B")), emptyList()))
    }

    // Deck V2 : symboles des tuiles et touches de départ du profil Deck.

    private fun page(title: String, n: Int) =
        DeckPage(title, (1..n).map { DeckTile("r-$it", "Touche $it", "system", "🔒", null, symbol = "lock") }, false, false, false, null, false, true)

    @Test
    fun `le symbole d'une touche suit la tuile et survit au JSON`() {
        val key = KeyRef.of(DeckTile("r-1", "Verrouiller", "system", "🔒", null, symbol = "lock"))
        assertEquals("lock", key.symbol)
        assertEquals(key, KeyRef.of(key.toJson()))
        assertNull(KeyRef.of(JSONObject().put("id", "x").put("label", "X"))!!.symbol)
    }

    @Test
    fun `touches de depart, la page Raccourcis, une seule fois par ordinateur`() {
        val config = JSONObject().put(KeyRef.DEFAULT_PAGE, "Raccourcis")
        val pages = listOf(page("Apps", 3), page("Raccourcis", 12))
        val seeded = KeyRef.seed(config, "mac", pages)!!
        assertEquals(KeyRef.SEED_MAX, seeded.size)
        assertEquals("r-1", seeded.first().id)
        KeyRef.putFor(config, "mac", emptyList())
        assertNull("déjà posées, même vidées ensuite", KeyRef.seed(config, "mac", pages))
        assertEquals(KeyRef.SEED_MAX, KeyRef.seed(config, "pc", pages)!!.size)
    }

    @Test
    fun `une touche de depart se retire du widget et ne revient pas`() {
        val config = JSONObject().put(KeyRef.DEFAULT_PAGE, "Raccourcis")
        val pages = listOf(page("Raccourcis", 4))
        val seeded = KeyRef.seed(config, "mac", pages)!!
        KeyRef.putFor(config, "mac", seeded)
        val removed = KeyRef.without(KeyRef.listFor(config, "mac"), "r-2")
        assertEquals(seeded.size - 1, removed.size)
        assertFalse(removed.any { it.id == "r-2" })
        KeyRef.putFor(config, "mac", removed)
        // « Après redémarrage » : la config relue, le deck relu, rien ne remet la touche.
        val reloaded = KeyRef.listFor(config, "mac")
        assertNull(KeyRef.seed(config, "mac", pages))
        assertNull(reconcileKeys(reloaded, pages))
        assertFalse(reloaded.any { it.id == "r-2" })
    }

    @Test
    fun `une touche perso ou d'une page nommee se retire aussi du widget`() {
        val keys = listOf(KeyRef("k-0", "Perso"), KeyRef("2-4", "Nommée"), KeyRef("r-1", "Départ"))
        assertEquals(listOf(KeyRef("2-4", "Nommée"), KeyRef("r-1", "Départ")), KeyRef.without(keys, "k-0"))
        assertEquals(listOf(KeyRef("k-0", "Perso"), KeyRef("r-1", "Départ")), KeyRef.without(keys, "2-4"))
        assertEquals("id inconnu : rien ne change", keys, KeyRef.without(keys, "x"))
    }

    @Test
    fun `pas de touches de depart sans page choisie, sans la page ou sans ordinateur`() {
        val pages = listOf(page("Raccourcis", 4))
        assertNull(KeyRef.seed(JSONObject(), "mac", pages))
        assertNull(KeyRef.seed(JSONObject().put(KeyRef.DEFAULT_PAGE, "Absente"), "mac", pages))
        assertNull(KeyRef.seed(JSONObject().put(KeyRef.DEFAULT_PAGE, "Raccourcis"), null, pages))
    }

    @Test
    fun `chaque symbole du backend a son icone`() {
        for (name in TileIcons.NAMES) assertTrue(name, TileIcons.drawable(name) != null)
        // La liste du compagnon (TILE_SYMBOLS de backend/src/screens.ts) et celle de la tablette sont les mêmes.
        val backend = Regex("""TILE_SYMBOLS = \[(.*?)] as const""", RegexOption.DOT_MATCHES_ALL)
            .find(File("../backend/src/screens.ts").readText())!!.groupValues[1]
            .let { list -> Regex(""""([a-z_]+)"""").findAll(list).map { it.groupValues[1] }.toSet() }
        assertTrue(backend.size > 40)
        assertEquals(backend, TileIcons.NAMES)
        assertNull(TileIcons.drawable("fusee"))
        assertNull(TileIcons.drawable(null))
    }

    @Test
    fun `touches de depart, les essentielles bureau d'abord, quel que soit l'ordre de la page`() {
        val tiles = listOf(
            DeckTile("r-0", "Play / Pause", "media", null, null, symbol = "play_pause"),
            DeckTile("r-1", "Capture zone", "keys", null, null, symbol = "screenshot"),
            DeckTile("r-2", "Verrouiller", "system", null, null, symbol = "lock"),
            DeckTile("r-3", "Couper le micro", "system", null, null, symbol = "mic_off"),
            DeckTile("r-4", "Ne pas déranger", "system", null, null, symbol = "dnd"),
        )
        val pages = listOf(DeckPage("Raccourcis", tiles, false, false, false, null, false, true))
        val seeded = KeyRef.seed(JSONObject().put(KeyRef.DEFAULT_PAGE, "Raccourcis"), "mac", pages)!!
        assertEquals(listOf("r-2", "r-3", "r-4", "r-1", "r-0"), seeded.map { it.id })
    }

    @Test
    fun `une touche a une seconde action seulement si le deck le dit`() {
        val pages = listOf(
            DeckPage("A", listOf(DeckTile("a-0", "Capture zone", "keys", null, null, hold = true), DeckTile("a-1", "Verrouiller", "system", null, null))),
        )
        assertTrue(tileHasHold(pages, "a-0"))
        assertFalse(tileHasHold(pages, "a-1"))
        assertFalse(tileHasHold(pages, "inconnue"))
    }
}
