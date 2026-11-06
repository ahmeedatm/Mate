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
    fun `ligne trop courte, la derniere touche de la page (quitter) reste`() {
        assertEquals(listOf(open, mic, leave), fitKeys(inCall, 3))
        assertEquals(inCall, fitKeys(inCall, 4))
        assertEquals(inCall, fitKeys(inCall, 9))
        assertEquals(listOf(leave), fitKeys(inCall, 1))
    }

    /** Ligne de libellé de 17 dp (13 sp à l'échelle 1) : il faut 6 + 2 × 17 = 40 dp sous la plaque. */
    private val line = 17

    @Test
    fun `pastille de la telecommande, libelle sous la touche s'il tient, sinon la touche seule`() {
        val roomy = callKeySizes(ShapeFamily.PILL, island = false, innerW = 270, innerH = 150, lineDp = line, keyCount = 0)
        assertTrue(roomy.mainLabel)
        assertEquals(80, roomy.main)
        val tight = callKeySizes(ShapeFamily.PILL, island = false, innerW = 270, innerH = 68, lineDp = line, keyCount = 0)
        assertFalse("68 dp : pas la place d'une plaque de 48 et de deux lignes", tight.mainLabel)
        assertEquals(68, tight.main)
    }

    @Test
    fun `pastille de l'ilot, le rond laisse la place a la legende`() {
        assertEquals(96, callKeySizes(ShapeFamily.PILL, island = true, innerW = 270, innerH = 150, lineDp = line, keyCount = 0).main)
        assertEquals(60, callKeySizes(ShapeFamily.PILL, island = true, innerW = 270, innerH = 88, lineDp = line, keyCount = 0).main)
    }

    @Test
    fun `jamais sous 48 dp, la cible tactile`() {
        for (family in ShapeFamily.entries) {
            for (island in listOf(false, true)) {
                val sizes = callKeySizes(family, island, innerW = 40, innerH = 30, lineDp = line, keyCount = 3)
                if (sizes.main > 0) assertTrue("$family $island", sizes.main >= 48)
                if (sizes.small > 0) assertTrue("$family $island", sizes.small >= 48)
            }
        }
    }

    @Test
    fun `ligne de la telecommande, autant de touches que la largeur en tient, une au moins`() {
        val wide = callKeySizes(ShapeFamily.ROW, island = false, innerW = 1180, innerH = 150, lineDp = line, keyCount = 5)
        assertEquals(7, wide.count)
        assertTrue(wide.smallLabel)
        assertEquals(1, callKeySizes(ShapeFamily.ROW, island = false, innerW = 60, innerH = 150, lineDp = line, keyCount = 5).count)
    }

    @Test
    fun `ilot, les petits ronds plus petits que le gros, tous montres`() {
        val sizes = callKeySizes(ShapeFamily.LARGE, island = true, innerW = 580, innerH = 340, lineDp = line, keyCount = 3)
        assertEquals(104, sizes.main)
        assertEquals(64, sizes.small)
        assertEquals(3, sizes.count)
    }

    /** Grandes (148 × 148), normales (96 × 110), petites (72 × 80) : de la plus grande à la plus petite. */
    private val cuts = listOf(GridCut(148, 148), GridCut(96, 110), GridCut(72, 80))

    @Test
    fun `grille, la plus grande cote ou toutes les touches tiennent`() {
        assertEquals(GridFit(0, 5), fitGrid(5, w = 580, h = 320, cuts, gap = 8))
        assertEquals("colonne étroite et basse : les normales", GridFit(1, 5), fitGrid(5, w = 270, h = 360, cuts, gap = 8))
    }

    @Test
    fun `grille trop petite meme en petites cotes, celles qui tiennent`() {
        assertEquals(GridFit(2, 3), fitGrid(5, w = 270, h = 150, cuts, gap = 8))
        assertEquals("une touche au moins", GridFit(2, 1), fitGrid(5, w = 60, h = 40, cuts, gap = 8))
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
