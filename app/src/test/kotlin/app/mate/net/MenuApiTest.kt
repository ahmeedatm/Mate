package app.mate.net

import org.junit.Assert.assertEquals
import org.junit.Test

class MenuApiTest {
    @Test
    fun readsSevenDaysInOrder() {
        val days = MenuApi.parse(
            """{"days":[{"lunch":"Pâtes","dinner":"Soupe"},{"lunch":"","dinner":"Pizza"},{},{},{},{},{"lunch":"Rôti","dinner":""}]}""",
        )
        assertEquals(7, days.size)
        assertEquals(MenuDay("Pâtes", "Soupe"), days[0])
        assertEquals(MenuDay("", "Pizza"), days[1])
        assertEquals(MenuDay("Rôti", ""), days[6])
    }

    @Test
    fun shortOrMissingListIsPaddedToSevenEmptyDays() {
        val short = MenuApi.parse("""{"days":[{"lunch":"Pâtes"}]}""")
        assertEquals(listOf(MenuDay("Pâtes", "")) + List(6) { MenuDay("", "") }, short)
        assertEquals(List(7) { MenuDay("", "") }, MenuApi.parse("{}"))
    }
}
