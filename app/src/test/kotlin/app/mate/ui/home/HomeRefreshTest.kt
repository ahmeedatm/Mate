package app.mate.ui.home

import app.mate.ui.home.HomeRefresh.Fetch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRefreshTest {

    @Test
    fun `sans widget d agenda les fonctions de l ordinateur sont quand meme relues`() {
        assertEquals(Fetch.FEATURES, HomeRefresh.plan(hasComputer = true, due = true, showsCalendar = false))
    }

    @Test
    fun `avec un widget d agenda l agenda est relu avec les fonctions`() {
        assertEquals(Fetch.FEATURES_AND_CALENDAR, HomeRefresh.plan(hasComputer = true, due = true, showsCalendar = true))
    }

    @Test
    fun `sans ordinateur ou hors echeance rien n est relu`() {
        assertEquals(Fetch.NONE, HomeRefresh.plan(hasComputer = false, due = true, showsCalendar = true))
        assertEquals(Fetch.NONE, HomeRefresh.plan(hasComputer = true, due = false, showsCalendar = true))
    }

    @Test
    fun `echeance toutes les cinq minutes ou chaque minute en attendant un lien`() {
        assertTrue(HomeRefresh.due(tick = 0, waitingLink = false))
        assertFalse(HomeRefresh.due(tick = 3, waitingLink = false))
        assertTrue(HomeRefresh.due(tick = 10, waitingLink = false))
        assertTrue(HomeRefresh.due(tick = 3, waitingLink = true))
    }
}
