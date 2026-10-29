package com.unowhy.companion.ui

import com.unowhy.companion.FakeContext
import com.unowhy.companion.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TourModelTest {

    @Test
    fun `visite en quatre etapes, dans l'ordre de la barre`() {
        assertEquals(
            listOf(TourModel.Step.HOME, TourModel.Step.SCREENS, TourModel.Step.TALK, TourModel.Step.CUSTOMIZE),
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
}
