package app.mate.ui

import app.mate.FakeContext
import app.mate.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TourModelTest {

    @Test
    fun `visite en quatre etapes, dans l'ordre de la barre`() {
        assertEquals(
            listOf(
                TourModel.Step.HOME,
                TourModel.Step.SCREENS,
                TourModel.Step.SWIPE,
                TourModel.Step.TALK,
                TourModel.Step.NOTIFICATIONS,
                TourModel.Step.CUSTOMIZE,
            ),
            TourModel.STEPS,
        )
    }

    @Test
    fun `visite montree une fois, apres l'appairage`() {
        val context = FakeContext()
        assertFalse("pas d'ordinateur : pas de visite", TourModel.shouldShow(context, hasComputer = false))
        assertTrue(TourModel.shouldShow(context, hasComputer = true))
        Prefs.setTourDone(context)
        assertFalse(TourModel.shouldShow(context, hasComputer = true))
    }

    @Test
    fun `bulle a droite de la cible, a gauche si elle sortirait de l'ecran`() {
        // Écran de 1280, bulle de 400, écart 20, marge 16.
        assertEquals("cible de la barre : à droite", 104f, TourModel.bubbleX(4f, 84f, 400f, 1280f, 20f, 16f))
        assertEquals("cloche du bandeau : à gauche", 640f, TourModel.bubbleX(1060f, 1100f, 400f, 1280f, 20f, 16f))
        assertEquals("aucune place des deux côtés : dans l'écran", 16f, TourModel.bubbleX(200f, 1000f, 400f, 1280f, 20f, 16f))
    }
}
