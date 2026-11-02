package app.mate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskItemTest {

    private fun task(assignee: String? = null, detail: String? = null, schedule: String? = null) =
        TaskItem("t", "Poubelles", true, false, false, detail, false, schedule, assignee = assignee)

    @Test
    fun `une corvee a tour de role affiche qui doit la faire avant la frequence`() {
        assertEquals("Tour de Léa · Lun–ven", task("Léa", schedule = "Lun–ven").subtitle("Tour de %1\$s"))
    }

    @Test
    fun `sans personne la ligne reste celle d'avant`() {
        assertEquals("Lun–ven", task(schedule = "Lun–ven").subtitle("Tour de %1\$s"))
        assertEquals("2 h / 7 h", task(detail = "2 h / 7 h", schedule = "Lun–ven").subtitle("Tour de %1\$s"))
        assertNull(task().subtitle("Tour de %1\$s"))
    }
}
