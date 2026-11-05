package app.mate.ui.home

import app.mate.net.DeckTile
import app.mate.net.VoiceMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLayoutTest {

    private fun tile(id: String, whenState: String? = null) = DeckTile(id, id, "keys", glyph = null, color = null, whenState = whenState)

    private val open = tile("ouvrir")
    private val mic = tile("micro", "call")
    private val deaf = tile("sourdine", "call")
    private val leave = tile("quitter", "call")
    private val inCall = listOf(open, mic, deaf, leave)

    private fun member(id: String, speaking: Boolean = false) = VoiceMember(id, id, avatar = false, speaking = speaking)

    @Test
    fun `pastille, une seule touche, celle de l'etat de l'appel`() {
        for (island in listOf(false, true)) {
            val plan = planCall(ShapeFamily.PILL, island, inCall)
            assertEquals(mic, plan.main)
            assertEquals(emptyList<DeckTile>(), plan.keys)
            assertFalse(plan.header)
            assertFalse(plan.members)
            assertFalse(plan.audio)
        }
    }

    @Test
    fun `pastille hors appel, la touche qui reste, sinon le titre`() {
        assertEquals(open, planCall(ShapeFamily.PILL, island = false, listOf(open)).main)
        val empty = planCall(ShapeFamily.PILL, island = false, emptyList())
        assertNull(empty.main)
        assertTrue("sans touche, le titre dit ce qu'est le widget", empty.header)
    }

    @Test
    fun `telecommande, toutes les touches dans la grille, de plus en plus grandes`() {
        for (family in ShapeFamily.entries - ShapeFamily.PILL) {
            val plan = planCall(family, island = false, inCall)
            assertNull(family.name, plan.main)
            assertEquals(family.name, inCall, plan.keys)
            assertTrue(family.name, plan.header)
        }
        assertFalse(planCall(ShapeFamily.BLOCK, island = false, inCall).bigKeys)
        assertTrue(planCall(ShapeFamily.LARGE, island = false, inCall).bigKeys)
        assertTrue(planCall(ShapeFamily.FULL, island = false, inCall).bigKeys)
    }

    @Test
    fun `participants et son de l'appli a partir du bloc`() {
        for (island in listOf(false, true)) {
            assertFalse(planCall(ShapeFamily.ROW, island, inCall).audio)
            assertFalse(planCall(ShapeFamily.COLUMN, island, inCall).audio)
            for (family in listOf(ShapeFamily.BLOCK, ShapeFamily.LARGE, ShapeFamily.FULL)) {
                val plan = planCall(family, island, inCall)
                assertTrue("$family $island", plan.members)
                assertTrue("$family $island", plan.audio)
            }
        }
        assertFalse(planCall(ShapeFamily.COLUMN, island = false, inCall).members)
        assertTrue("l'îlot montre qui parle dès la colonne", planCall(ShapeFamily.COLUMN, island = true, inCall).members)
    }

    @Test
    fun `ilot, le gros rond pour l'etat, les touches de l'appel avant ouvrir`() {
        val plan = planCall(ShapeFamily.BLOCK, island = true, inCall)
        assertEquals(mic, plan.main)
        assertEquals(listOf(deaf, leave, open), plan.keys)
    }

    @Test
    fun `ilot, les petits ronds se limitent a la place de la famille`() {
        assertEquals(2, planCall(ShapeFamily.COLUMN, island = true, inCall).keys.size)
        assertEquals(listOf(deaf, leave), planCall(ShapeFamily.COLUMN, island = true, inCall).keys)
        assertEquals(3, planCall(ShapeFamily.ROW, island = true, inCall).keys.size)
    }

    @Test
    fun `ilot hors appel, ouvrir devient le gros rond`() {
        val plan = planCall(ShapeFamily.BLOCK, island = true, listOf(open))
        assertEquals(open, plan.main)
        assertEquals(emptyList<DeckTile>(), plan.keys)
    }

    @Test
    fun `en avant, qui parle, sinon le dernier a avoir parle, sinon le premier`() {
        val a = member("a")
        val b = member("b")
        val c = member("c")
        assertEquals(b, featuredMember(listOf(a, b, c), speaking = setOf("b"), lastSpoke = mapOf("c" to 900L)))
        assertEquals(c, featuredMember(listOf(a, b, c), speaking = emptySet(), lastSpoke = mapOf("a" to 100L, "c" to 900L)))
        assertEquals(a, featuredMember(listOf(a, b, c), speaking = emptySet(), lastSpoke = emptyMap()))
        assertNull(featuredMember(emptyList(), emptySet(), emptyMap()))
    }
}
