package app.mate.ui.cyberpunk

import app.mate.FakeContext
import app.mate.net.CyberpunkPoi
import app.mate.net.PoiCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoiFiltersTest {
    private fun poi(id: String, cat: PoiCategory) = CyberpunkPoi(id, 0.0, 0.0, cat, null, null, false)

    private val points = listOf(poi("a", PoiCategory.QUEST), poi("b", PoiCategory.SHOP), poi("c", PoiCategory.SHOP))

    @Test fun `tout est visible par defaut`() {
        assertTrue(PoiFilters.hidden(FakeContext()).isEmpty())
    }

    @Test fun `les categories cachees sont gardees dans les preferences`() {
        val context = FakeContext()
        PoiFilters.setHidden(context, setOf(PoiCategory.SHOP, PoiCategory.NCPD))
        assertEquals(setOf(PoiCategory.SHOP, PoiCategory.NCPD), PoiFilters.hidden(context))
    }

    @Test fun `une cle inconnue des preferences est ignoree`() {
        val context = FakeContext()
        app.mate.Prefs.setCyberpunkPoiHidden(context, setOf("shop", "n_importe_quoi"))
        assertEquals(setOf(PoiCategory.SHOP), PoiFilters.hidden(context))
    }

    @Test fun `cacher ou remontrer une categorie`() {
        val hidden = PoiFilters.toggled(emptySet(), PoiCategory.GIG, visible = false)
        assertEquals(setOf(PoiCategory.GIG), hidden)
        assertTrue(PoiFilters.toggled(hidden, PoiCategory.GIG, visible = true).isEmpty())
    }

    @Test fun `comptes par categorie, zero compris`() {
        val counts = PoiFilters.counts(points)
        assertEquals(2, counts.getValue(PoiCategory.SHOP))
        assertEquals(1, counts.getValue(PoiCategory.QUEST))
        assertEquals(0, counts.getValue(PoiCategory.TRAVEL))
        assertEquals(PoiCategory.entries.toList(), counts.keys.toList())
    }

    @Test fun `points visibles`() {
        assertEquals(listOf("a"), PoiFilters.visible(points, setOf(PoiCategory.SHOP)).map { it.id })
        assertEquals(3, PoiFilters.visible(points, emptySet()).size)
    }
}
