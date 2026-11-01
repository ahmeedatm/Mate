package app.mate.ui.home

import app.mate.R
import app.mate.ui.home.PerfLevel.CRITICAL
import app.mate.ui.home.PerfLevel.NORMAL
import app.mate.ui.home.PerfLevel.WARN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfModelTest {

    @Test
    fun `seuils d'utilisation`() {
        assertEquals(NORMAL, usageLevel(0))
        assertEquals(NORMAL, usageLevel(84))
        assertEquals(WARN, usageLevel(85))
        assertEquals(WARN, usageLevel(94))
        assertEquals(CRITICAL, usageLevel(95))
        assertEquals(CRITICAL, usageLevel(100))
    }

    @Test
    fun `seuils de temperature`() {
        assertEquals(NORMAL, tempLevel(79))
        assertEquals(WARN, tempLevel(80))
        assertEquals(WARN, tempLevel(89))
        assertEquals(CRITICAL, tempLevel(90))
    }

    @Test
    fun `sous les seuils tout est neutre, jamais l'accent`() {
        for (series in PerfSeries.entries) {
            assertEquals(R.attr.c_text, perfColorAttr(series, PerfMark.VALUE, NORMAL))
            assertEquals(R.attr.c_text_dim, perfColorAttr(series, PerfMark.FILL, NORMAL))
            for (mark in PerfMark.entries) assertNotEquals(R.attr.c_accent, perfColorAttr(series, mark, NORMAL))
        }
    }

    @Test
    fun `traits de la courbe distincts pour le processeur, la carte graphique et la memoire`() {
        assertEquals(R.attr.c_text, perfColorAttr(PerfSeries.CPU, PerfMark.LINE, NORMAL))
        assertEquals(R.attr.c_text_faint, perfColorAttr(PerfSeries.GPU, PerfMark.LINE, NORMAL))
        assertEquals(R.attr.c_text_dim, perfColorAttr(PerfSeries.RAM, PerfMark.LINE, NORMAL))
    }

    @Test
    fun `alerte et critique en rouge d'alerte pour tous les elements`() {
        for (series in PerfSeries.entries) {
            for (mark in PerfMark.entries) {
                assertEquals(R.attr.c_accent, perfColorAttr(series, mark, WARN))
                assertEquals(R.attr.c_accent, perfColorAttr(series, mark, CRITICAL))
            }
        }
    }

    @Test
    fun `mesure ancienne grisee, meme en alerte`() {
        for (series in PerfSeries.entries) {
            for (level in PerfLevel.entries) {
                assertEquals(R.attr.c_text_faint, perfColorAttr(series, PerfMark.VALUE, level, stale = true))
                assertEquals(R.attr.c_graphic, perfColorAttr(series, PerfMark.FILL, level, stale = true))
                assertEquals(R.attr.c_graphic, perfColorAttr(series, PerfMark.LINE, level, stale = true))
            }
        }
    }

    @Test
    fun `l'historique garde les 5 dernieres minutes de mesures`() {
        val h = PerfHistory()
        assertEquals(150, h.capacity)
        for (i in 1..151) h.add(i, i * 2, i * 3)
        assertEquals(150, h.cpu.size)
        assertEquals(150, h.gpu.size)
        assertEquals(150, h.ram.size)
        assertEquals(2, h.cpu.first())
        assertEquals(151, h.cpu.last())
        assertEquals(4, h.gpu.first())
        assertEquals(302, h.gpu.last())
        assertEquals(6, h.ram.first())
        assertEquals(453, h.ram.last())
    }

    @Test
    fun `l'historique accepte une mesure gpu absente`() {
        val h = PerfHistory(capacity = 3)
        h.add(10, null, 50)
        h.add(20, 5, 60)
        assertEquals(listOf(10, 20), h.cpu)
        assertEquals(listOf(null, 5), h.gpu)
        assertEquals(listOf(50, 60), h.ram)
        assertTrue(h.cpu.size <= 3)
    }

    @Test
    fun `l'historique vide repart de zero`() {
        val h = PerfHistory(capacity = 3)
        h.add(10, 1, 40)
        h.add(20, null, 41)
        h.clear()
        assertEquals(emptyList<Int>(), h.cpu)
        assertEquals(emptyList<Int?>(), h.gpu)
        assertEquals(emptyList<Int>(), h.ram)
        h.add(30, 4, 42)
        assertEquals(listOf(30), h.cpu)
        assertEquals(listOf<Int?>(4), h.gpu)
        assertEquals(listOf(42), h.ram)
    }
}
