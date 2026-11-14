package app.mate.ui.cyberpunk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCameraTest {
    // Calage mesuré : pixel pleine résolution = (61008 + 10,788·x ; 46934 − 10,788·y), ramené au niveau max (28672 px).
    private val k = 28672.0 / 111104.0
    private val geo = MapGeometry(28672, 28672, 4, 512, listOf(10.788 * k, 0.0, 61008 * k, 0.0, -10.788 * k, 46934 * k))

    private fun camera(w: Int = 1280, h: Int = 800) = MapCamera(geo).apply { setViewport(w, h) }

    @Test fun `monde vers pixels de la carte puis retour`() {
        val p = geo.worldToMap(120.0, -340.0)
        assertEquals(61008 * k + 10.788 * k * 120.0, p.x, 1e-6)
        assertEquals(46934 * k + 10.788 * k * 340.0, p.y, 1e-6)
        val w = geo.mapToWorld(p.x, p.y)
        assertEquals(120.0, w.x, 1e-6)
        assertEquals(-340.0, w.y, 1e-6)
    }

    @Test fun `monde vers ecran puis retour apres zoom et deplacement`() {
        val c = camera()
        c.centerOn(50.0, 60.0)
        c.zoomAt(2.0, 300f, 200f)
        c.panBy(-35f, 12f)
        val s = c.worldToScreen(-80.0, 140.0)
        val w = c.screenToWorld(s.x, s.y)
        assertEquals(-80.0, w.x, 1e-6)
        assertEquals(140.0, w.y, 1e-6)
    }

    @Test fun `centrer met le point au milieu de la vue`() {
        val c = camera()
        c.centerOn(10.0, 20.0)
        val s = c.worldToScreen(10.0, 20.0)
        assertEquals(640.0, s.x, 1e-6)
        assertEquals(400.0, s.y, 1e-6)
    }

    @Test fun `zoom autour d'un point garde ce point sous le doigt`() {
        val c = camera()
        c.centerOn(0.0, 0.0)
        val before = c.screenToWorld(900.0, 300.0)
        c.zoomAt(1.7, 900f, 300f)
        val after = c.screenToWorld(900.0, 300.0)
        assertEquals(before.x, after.x, 1e-6)
        assertEquals(before.y, after.y, 1e-6)
    }

    @Test fun `bornes du zoom de la ville entiere au niveau max fois 1,5`() {
        val c = camera()
        c.zoomAt(1e-6, 0f, 0f)
        assertEquals(800.0 / 28672.0, c.scale, 1e-9)
        c.zoomAt(1e6, 0f, 0f)
        assertEquals(1.5, c.scale, 1e-9)
    }

    @Test fun `niveau de tuile selon le zoom`() {
        val c = camera()
        c.zoomAt(1e6, 0f, 0f)
        assertEquals(3, c.level())
        c.setScale(0.6)
        assertEquals(3, c.level())
        c.setScale(0.5)
        assertEquals(2, c.level())
        c.setScale(0.3)
        assertEquals(2, c.level())
        c.setScale(0.2)
        assertEquals(1, c.level())
        c.setScale(0.1)
        assertEquals(0, c.level())
        c.setScale(800.0 / 28672.0)
        assertEquals(0, c.level())
    }

    @Test fun `ville entiere visible en tuiles du niveau 0`() {
        val c = camera()
        c.setScale(0.0)
        val tiles = c.visibleTiles()
        assertTrue(tiles.all { it.z == 0 })
        // 28672 / 8 = 3584 px au niveau 0 = 7 × 7 tuiles de 512
        assertEquals(49, tiles.size)
        assertTrue(tiles.contains(TileKey(0, 6, 6)))
        assertTrue(tiles.none { it.x > 6 || it.y > 6 })
    }

    @Test fun `tuiles visibles aux bords sans sortir de la grille`() {
        val c = camera()
        c.setScale(1.0)
        c.centerOn(geo.mapToWorld(0.0, 0.0).x, geo.mapToWorld(0.0, 0.0).y)
        val tiles = c.visibleTiles()
        assertTrue(tiles.all { it.z == 3 && it.x >= 0 && it.y >= 0 })
        assertTrue(tiles.contains(TileKey(3, 0, 0)))
        assertEquals(0, tiles.minOf { it.x })
        c.centerOn(geo.mapToWorld(28672.0, 28672.0).x, geo.mapToWorld(28672.0, 28672.0).y)
        val far = c.visibleTiles()
        assertTrue(far.all { it.x <= 55 && it.y <= 55 })
        assertTrue(far.contains(TileKey(3, 55, 55)))
    }

    @Test fun `rectangle ecran d'une tuile`() {
        val c = camera()
        c.setScale(0.5)
        c.centerOn(geo.mapToWorld(1024.0, 1024.0).x, geo.mapToWorld(1024.0, 1024.0).y)
        val r = c.tileRect(TileKey(3, 2, 2)) // pixels 1024..1536 : démarre au centre de la vue
        assertEquals(640.0, r.left, 1e-6)
        assertEquals(400.0, r.top, 1e-6)
        assertEquals(256.0, r.right - r.left, 1e-6)
        val coarse = c.tileRect(TileKey(2, 1, 1)) // même zone au niveau 2 : 1024..2048
        assertEquals(640.0, coarse.left, 1e-6)
        assertEquals(512.0, coarse.right - coarse.left, 1e-6)
    }

    @Test fun `centre borne dans la carte`() {
        val c = camera()
        c.setScale(1.0)
        c.panBy(1e9f, 1e9f)
        assertTrue(c.centerX in 0.0..28672.0)
        assertTrue(c.centerY in 0.0..28672.0)
    }

    @Test fun `repere le plus proche dans le rayon, sinon rien`() {
        val pts = listOf("a" to Pt(100.0, 100.0), "b" to Pt(130.0, 100.0))
        assertEquals("b", nearestPoint(pts, 124.0, 98.0, 24.0))
        assertEquals("a", nearestPoint(pts, 112.0, 100.0, 24.0))
        assertNull(nearestPoint(pts, 300.0, 300.0, 24.0))
    }
}
