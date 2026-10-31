package app.mate.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppAdderTest {

    private val apps = listOf("Slack", "Microsoft Teams", "Calculator", "Visual Studio Code", "Claude", "Notes")

    @Test
    fun `les applis epinglees sur la carte d'abord, puis les autres par ordre alphabetique`() {
        assertEquals(
            listOf("Claude", "Microsoft Teams", "Calculator", "Notes", "Slack", "Visual Studio Code"),
            AppAdder.order(apps, pinned = setOf("Microsoft Teams", "Claude"), query = ""),
        )
    }

    @Test
    fun `la recherche filtre sans tenir compte des majuscules`() {
        assertEquals(listOf("Microsoft Teams", "Notes"), AppAdder.order(apps, pinned = setOf("Microsoft Teams"), query = "te"))
        assertEquals(listOf("Visual Studio Code"), AppAdder.order(apps, pinned = emptySet(), query = "  CODE "))
    }
}
