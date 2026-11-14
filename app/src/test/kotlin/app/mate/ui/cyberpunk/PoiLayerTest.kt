package app.mate.ui.cyberpunk

import app.mate.net.CyberpunkPoi
import app.mate.net.PoiCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PoiLayerTest {
    private fun poi(id: String, x: Double, y: Double, dim: Boolean = false) = CyberpunkPoi(id, x, y, PoiCategory.SHOP, null, null, dim)

    @Test fun `icones de pres, ronds de loin`() {
        assertEquals(PoiStyle.ICON, PoiDensity.style(MapCamera.INITIAL_SCALE))
        assertEquals(PoiStyle.ICON, PoiDensity.style(PoiDensity.ICON_MIN_SCALE))
        assertEquals(PoiStyle.DOT, PoiDensity.style(PoiDensity.ICON_MIN_SCALE - 0.01))
        assertEquals(PoiStyle.DOT, PoiDensity.style(0.04))
    }

    @Test fun `de loin, les points attenues ne sont pas dessines`() {
        assertEquals(true, PoiDensity.shows(poi("a", 0.0, 0.0, dim = true), PoiStyle.ICON))
        assertEquals(false, PoiDensity.shows(poi("a", 0.0, 0.0, dim = true), PoiStyle.DOT))
        assertEquals(true, PoiDensity.shows(poi("a", 0.0, 0.0), PoiStyle.DOT))
    }

    @Test fun `le point sous le doigt est le plus proche dans le rayon`() {
        val points = listOf(poi("a", 100.0, 100.0), poi("b", 130.0, 100.0))
        val project = { p: CyberpunkPoi -> Pt(p.x, p.y) }
        assertEquals("b", pickPoi(points, project, 124.0, 98.0, 24.0)?.id)
        assertNull(pickPoi(points, project, 300.0, 300.0, 24.0))
    }

    @Test fun `affine ecran identique a worldToScreen`() {
        val geo = MapGeometry.of(28672, 28672, 7, 512, listOf(0.5, 0.0, 14336.0, 0.0, -0.5, 14336.0))!!
        val cam = MapCamera(geo).apply {
            setViewport(1280, 800)
            setScale(0.7)
            centerOn(120.0, -340.0)
        }
        val affine = ScreenAffine().also { it.set(cam) }
        for ((x, y) in listOf(0.0 to 0.0, -1397.3 to 1278.1, 2500.0 to -900.0)) {
            val expected = cam.worldToScreen(x, y)
            assertEquals(expected.x, affine.x(x, y).toDouble(), 0.01)
            assertEquals(expected.y, affine.y(x, y).toDouble(), 0.01)
        }
    }

    @Test fun `un repere de Mate passe avant un point d'interet`() {
        assertEquals(TapTarget.PIN, tapTarget(pinHit = true, poi = poi("a", 0.0, 0.0), style = PoiStyle.ICON))
    }

    @Test fun `un point d'interet n'est touche qu'en style icone`() {
        assertEquals(TapTarget.POI, tapTarget(pinHit = false, poi = poi("a", 0.0, 0.0), style = PoiStyle.ICON))
        assertEquals(TapTarget.NEW_PIN, tapTarget(pinHit = false, poi = poi("a", 0.0, 0.0), style = PoiStyle.DOT))
    }

    @Test fun `sans repere ni point, on pose un repere`() {
        assertEquals(TapTarget.NEW_PIN, tapTarget(pinHit = false, poi = null, style = PoiStyle.ICON))
    }
}
