package app.mate.ui.home

import app.mate.net.AppWarn
import app.mate.net.DiskPerf
import app.mate.net.PcApp
import app.mate.net.PcPerf
import app.mate.net.parsePerfApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfAppsTest {

    @Test
    fun `parsePerfApps lit les applis et ce qu'elles prennent en trop`() {
        val apps = parsePerfApps(
            """{"apps":[{"name":"Google Chrome","cpu":78,"memMb":3174,"memPercent":10,"warn":"cpu","hogSeconds":125},""" +
                """{"name":"Teams","cpu":4,"memMb":5300,"memPercent":33,"warn":"ram"},{"name":"Code","cpu":9,"memMb":1434,"memPercent":4}]}""",
        )!!
        assertEquals(3, apps.size)
        assertEquals(PcApp("Google Chrome", 78, 3174, 10, AppWarn.CPU, 125), apps[0])
        assertEquals(AppWarn.RAM, apps[1].warn)
        assertNull(apps[1].hogSeconds)
        assertNull(apps[2].warn)
    }

    @Test
    fun `parsePerfApps rend null quand la liste est illisible, et ignore un avertissement inconnu`() {
        assertNull(parsePerfApps("""{"apps":null}"""))
        val apps = parsePerfApps("""{"apps":[{"name":"X","cpu":1,"memMb":2,"memPercent":0,"warn":"gpu"}]}""")!!
        assertNull(apps[0].warn)
    }

    private val calm = PcPerf(cpu = 20, ramPercent = 50, ramUsedGb = 8.0, ramTotalGb = 16.0, gpu = null)
    private val chrome = PcApp("Google Chrome", 78, 3174, 10, AppWarn.CPU, 125)
    private val teams = PcApp("Teams", 4, 5300, 33, AppWarn.RAM, null)
    private val code = PcApp("Code", 9, 1434, 4, null, null)

    @Test
    fun `rien a signaler`() {
        assertNull(perfIssue(calm, listOf(code)))
        assertNull(perfIssue(calm, null))
    }

    @Test
    fun `une appli qui abuse du processeur passe avant la memoire et le disque`() {
        val full = calm.copy(disk = DiskPerf(freeGb = 10.0, totalGb = 500.0, activity = null))
        assertEquals(PerfIssue.AppCpu("Google Chrome", 78, 125), perfIssue(full, listOf(teams, chrome)))
        assertEquals(PerfIssue.AppRam("Teams", 5300), perfIssue(full, listOf(code, teams)))
        assertEquals(PerfIssue.DiskFull(10.0), perfIssue(full, listOf(code)))
    }

    @Test
    fun `disque presque plein sous 10 pour cent libres`() {
        assertNull(perfIssue(calm.copy(disk = DiskPerf(50.0, 500.0, null)), null))
        assertEquals(PerfIssue.DiskFull(49.9), perfIssue(calm.copy(disk = DiskPerf(49.9, 500.0, null)), null))
    }

    @Test
    fun `la liste des applis vit dans les grandes formes, a cote des chiffres sur 4 colonnes, dessous sinon`() {
        for (size in WidgetSize.entries) {
            val big = size.family == ShapeFamily.LARGE || size.family == ShapeFamily.FULL
            assertEquals("$size", big, perfShowsApps(size))
        }
        assertTrue(perfAppsBeside(WidgetSize.FULL))
        assertTrue(perfAppsBeside(WidgetSize.XL))
        assertTrue(perfAppsBeside(WidgetSize.XL3))
        assertFalse(perfAppsBeside(WidgetSize.T))
        assertFalse(perfAppsBeside(WidgetSize.T3))
    }

    @Test
    fun `memoire en Mo sous 1 Go, en Go au-dela`() {
        assertEquals(PerfMem(614.0, false), perfMem(614))
        assertEquals(PerfMem(3.1, true), perfMem(3174))
        assertEquals(PerfMem(1.0, true), perfMem(1024))
    }

    @Test
    fun `duree d'abus en secondes sous la minute, en minutes au-dela`() {
        assertEquals(PerfDuration(45, false), perfDuration(45))
        assertEquals(PerfDuration(2, true), perfDuration(125))
    }

    @Test
    fun `l'apercu montre une appli qui abuse`() {
        assertTrue(PerfSample.apps.any { it.warn != null })
    }
}
