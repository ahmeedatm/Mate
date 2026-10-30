package app.mate.ui.home

import app.mate.R
import app.mate.ui.home.PerfLevel.CRITICAL
import app.mate.ui.home.PerfLevel.NORMAL
import app.mate.ui.home.PerfLevel.WARN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** L'alerte du widget Performances est aussi écrite : pas seulement le rouge des chiffres. */
class PerfAlertTextTest {
    private val words = mapOf(R.string.perf_level_warn to "en alerte", R.string.perf_level_critical to "critique")
    private fun text(vararg readings: Pair<String, PerfLevel>) = perfAlertText(readings.toList()) { words.getValue(it) }

    @Test
    fun `un mot par gravite au-dela de normal`() {
        assertNull(perfLevelWord(NORMAL))
        assertEquals(R.string.perf_level_warn, perfLevelWord(WARN))
        assertEquals(R.string.perf_level_critical, perfLevelWord(CRITICAL))
    }

    @Test
    fun `tout va bien rien a dire`() {
        assertEquals("", text("CPU" to NORMAL, "RAM" to NORMAL))
    }

    @Test
    fun `seules les mesures en alerte dans l ordre recu`() {
        assertEquals("CPU critique · RAM en alerte", text("CPU" to CRITICAL, "GPU" to NORMAL, "RAM" to WARN))
    }

    @Test
    fun `seuils de l utilisation et de la temperature`() {
        assertEquals("GPU °C en alerte", text("GPU °C" to tempLevel(80), "CPU" to usageLevel(84)))
        assertEquals("CPU critique", text("CPU" to usageLevel(95)))
    }
}
