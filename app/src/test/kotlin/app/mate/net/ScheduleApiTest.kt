package app.mate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleApiTest {
    @Test
    fun readsEachProgrammingWithItsTileNameAndReadableSchedule() {
        val items = ScheduleApi.parse(
            """{"items":[{"id":"a1","tileId":"t-mute","label":"Muet","enabled":true,"schedule":"Lun–ven · 18:00"},""" +
                """{"id":"b2","tileId":"t-pause","label":null,"enabled":false,"schedule":"Toutes les 45 min"}]}""",
        )
        assertEquals(listOf("a1", "b2"), items.map { it.id })
        assertEquals("Muet", items[0].label)
        assertEquals("Lun–ven · 18:00", items[0].schedule)
        assertTrue(items[0].enabled)
        // Touche supprimée du deck depuis : pas de nom, la programmation reste visible pour être supprimée.
        assertNull(items[1].label)
        assertFalse(items[1].enabled)
    }

    @Test
    fun nothingProgrammedMeansEmptyList() {
        assertTrue(ScheduleApi.parse("""{"items":[]}""").isEmpty())
    }

    @Test
    fun fixedTimeBodyCarriesTimeAndDays() {
        val body = ScheduleApi.body("t-mute", at = "18:00", days = listOf(1, 2, 3, 4, 5))
        assertEquals("t-mute", body.getString("tileId"))
        assertEquals("18:00", body.getString("at"))
        assertEquals(5, body.getJSONArray("days").length())
        assertFalse(body.has("everyMinutes"))
    }

    @Test
    fun intervalBodyHasNoTimeNorDays() {
        val body = ScheduleApi.body("t-pause", everyMinutes = 45)
        assertEquals(45, body.getInt("everyMinutes"))
        assertFalse(body.has("at"))
        assertFalse(body.has("days"))
    }

    @Test
    fun timeIsPaddedToTwoDigits() {
        assertEquals("07:05", ScheduleApi.hhmm(7, 5))
        assertEquals("18:00", ScheduleApi.hhmm(18, 0))
    }
}
