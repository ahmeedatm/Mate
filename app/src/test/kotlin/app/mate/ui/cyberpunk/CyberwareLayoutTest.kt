package app.mate.ui.cyberpunk

import app.mate.ui.cyberpunk.CyberpunkItems.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CyberwareLayoutTest {
    /** Tiroir Cyber sur la tablette : 1280 × 800 moins le rail et les marges. */
    private val fit = CyberwareLayout.fit(1124f, 736f)

    @Test
    fun zonesSitLeftAndRightOfTheBodyLikeInTheGame() {
        val left = listOf(Zone.FRONTAL, Zone.ARMS, Zone.SKELETON, Zone.NERVOUS, Zone.SKIN)
        val right = listOf(Zone.OS, Zone.EYES, Zone.HANDS, Zone.CARDIO, Zone.LEGS)
        val body = fit.bodyBox(CyberwareLayout.Body.MAN)
        // À gauche : le bloc finit avant le corps ; à droite : il commence après. Les noms ne mordent jamais sur le corps.
        left.forEach { assertTrue("$it à gauche", fit.zone(it, CyberwareLayout.Body.MAN)!!.let { s -> s.side == CyberwareLayout.Side.LEFT && s.x <= body.left }) }
        right.forEach { assertTrue("$it à droite", fit.zone(it, CyberwareLayout.Body.MAN)!!.let { s -> s.side == CyberwareLayout.Side.RIGHT && s.x >= body.right }) }
        // De haut en bas dans l'ordre du jeu, de chaque côté.
        listOf(left, right).forEach { col -> assertEquals(col, col.sortedBy { fit.zone(it, CyberwareLayout.Body.MAN)!!.y }) }
    }

    @Test
    fun columnsHaveRoomForLongZoneNames() {
        // « SYSTÈME CARDIOVASCULAIRE » en 13 sp espacé : environ 260 px.
        val body = fit.bodyBox(CyberwareLayout.Body.MAN)
        assertTrue(body.left >= 260f && 1124f - body.right >= 260f)
    }

    @Test
    fun everythingFitsTheDrawerWithTouchableSlots() {
        Zone.entries.mapNotNull { fit.zone(it, CyberwareLayout.Body.MAN) }.forEach {
            assertTrue("$it dans le tiroir", it.x >= 0f && it.y >= 0f && it.x <= 1124f && it.y <= 736f)
        }
        val body = fit.bodyBox(CyberwareLayout.Body.WOMAN)
        assertTrue("corps dans le tiroir", body.top >= 0f && body.bottom <= 736f)
        assertTrue("case de ${fit.slotPx} px", fit.slotPx >= 48f)
    }

    @Test
    fun immuneHasNoPlaceInTheGameScreen() {
        // Le charcudoc 2.x n'a pas d'ancre pour le système immunitaire : la tablette le met sous la peau, à gauche.
        val skin = fit.zone(Zone.SKIN, CyberwareLayout.Body.MAN)!!
        val immune = fit.zone(Zone.IMMUNE, CyberwareLayout.Body.MAN)!!
        assertEquals(skin.x, immune.x, 0.01f)
        assertTrue(immune.y > skin.y)
    }

    @Test
    fun bodyPartsFollowTheGenderAndLandOnTheBody() {
        val man = CyberwareLayout.part(CyberwareLayout.Body.MAN, Zone.SKELETON)
        val woman = CyberwareLayout.part(CyberwareLayout.Body.WOMAN, Zone.SKELETON)
        assertEquals("ma_full_skeleton.png", man.asset)
        assertEquals("wo_skeleton.png", woman.asset)
        assertEquals("ma_pfrontal_cortex.png", CyberwareLayout.part(CyberwareLayout.Body.MAN, Zone.FRONTAL).asset)
        // Les jambes sous le centre du corps, les yeux au-dessus.
        val legs = fit.partRect(CyberwareLayout.part(CyberwareLayout.Body.MAN, Zone.LEGS))
        val eyes = fit.partRect(CyberwareLayout.part(CyberwareLayout.Body.MAN, Zone.EYES))
        val body = fit.bodyBox(CyberwareLayout.Body.MAN)
        assertTrue(legs.centerY() > body.centerY() && eyes.centerY() < body.centerY())
    }

    @Test
    fun unknownZoneHasNoSpot() {
        assertNull(CyberwareLayout.fit(1124f, 736f).zone(null, CyberwareLayout.Body.MAN))
    }
}
