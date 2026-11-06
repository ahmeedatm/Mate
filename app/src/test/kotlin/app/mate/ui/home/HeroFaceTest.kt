package app.mate.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroFaceTest {

    @Test
    fun `en ligne, le heros et la journee gardent leur bandeau`() {
        for (size in listOf(WidgetSize.M, WidgetSize.M3, WidgetSize.W)) {
            assertTrue(size.name, HeroFace.of(size).band)
            assertTrue(size.name, DayFace.of(size).band)
        }
        for (size in WidgetSize.entries.filter { it.family != ShapeFamily.ROW }) {
            assertFalse(size.name, HeroFace.of(size).band)
            assertFalse(size.name, DayFace.of(size).band)
        }
    }

    @Test
    fun `heros pastille = l'heure et la journee en ligne, colonne = le cadran, la meteo et l'eau empilees`() {
        val pill = HeroFace.of(WidgetSize.S)
        assertTrue(pill.track)
        assertFalse(pill.date)
        assertFalse(pill.compl)
        for (size in listOf(WidgetSize.V, WidgetSize.V3, WidgetSize.V4)) {
            val face = HeroFace.of(size)
            assertTrue(size.name, face.dial)
            assertTrue(size.name, face.compl)
            assertTrue(size.name, face.complDown)
        }
    }

    @Test
    fun `heros plus grand, plus de choses, le prochain rendez-vous en grand en plein ecran`() {
        assertTrue(HeroFace.of(WidgetSize.L).next)
        assertFalse(HeroFace.of(WidgetSize.L).trackTitles)
        assertTrue(HeroFace.of(WidgetSize.XL).trackTitles)
        assertTrue(HeroFace.of(WidgetSize.FULL).ring)
        assertFalse(HeroFace.of(WidgetSize.XL).ring)
    }

    @Test
    fun `journee pastille = l'anneau seul, colonne et plus = salutation et actions, la journee en ligne des le grand`() {
        val pill = DayFace.of(WidgetSize.S)
        assertTrue(pill.ring)
        assertFalse(pill.greeting)
        assertFalse(pill.actions)
        for (size in listOf(WidgetSize.V, WidgetSize.L, WidgetSize.L33)) {
            assertTrue(size.name, DayFace.of(size).greeting)
            assertTrue(size.name, DayFace.of(size).actions)
            assertFalse(size.name, DayFace.of(size).track)
        }
        assertTrue(DayFace.of(WidgetSize.XL).track)
        assertTrue(DayFace.of(WidgetSize.FULL).compl)
        assertFalse(DayFace.of(WidgetSize.V).ringBeside)
        assertTrue(DayFace.of(WidgetSize.L).ringBeside)
    }
}
