package app.mate.ui.cyberpunk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteLayerTest {
    private val k = 28672.0 / 111104.0
    private val geo = MapGeometry(28672, 28672, 4, 512, listOf(10.788 * k, 0.0, 61008 * k, 0.0, -10.788 * k, 46934 * k))

    private fun camera() = MapCamera(geo).apply { setViewport(1280, 800) }

    private fun mapPoints(vararg world: Double) = routeToMap(world, geo)

    private fun project(cam: MapCamera, map: DoubleArray): List<Triple<Double, Double, Boolean>> {
        val out = FloatArray(routeBufferSize(map))
        val n = projectRoute(map, cam.matrix(), cam.centerX, cam.centerY, out)
        return (0 until n).map { Triple(out[3 * it].toDouble(), out[3 * it + 1].toDouble(), out[3 * it + 2] == 1f) }
    }

    @Test fun `a plat chaque sommet tombe ou le met la camera, en un seul morceau`() {
        val c = camera()
        c.centerOn(10.0, 20.0)
        c.zoomAt(2.0, 300f, 200f)
        val world = doubleArrayOf(0.0, 0.0, 40.0, 10.0, 40.0, 90.0, -30.0, 120.0)
        val pts = project(c, mapPoints(*world))
        assertEquals(4, pts.size)
        assertEquals(listOf(true, false, false, false), pts.map { it.third })
        for (i in pts.indices) {
            val s = c.worldToScreen(world[2 * i], world[2 * i + 1])
            assertEquals(s.x, pts[i].first, 0.05)
            assertEquals(s.y, pts[i].second, 0.05)
        }
    }

    @Test fun `en conduite le chemin qui passe loin derriere est coupe comme segmentToScreen, puis reprend`() {
        val c = camera()
        c.centerOn(0.0, 0.0)
        c.setPose(0.0, 1.0, MapCamera.DRIVE_ANCHOR)
        // Devant (nord), très loin derrière, puis de nouveau devant : deux morceaux, aucun point en miroir.
        val world = doubleArrayOf(0.0, 100.0, 0.0, -100000.0, 50.0, 100.0)
        val pts = project(c, mapPoints(*world))
        assertEquals(listOf(true, false, true, false), pts.map { it.third })
        val first = c.segmentToScreen(0.0, 100.0, 0.0, -100000.0)!!
        assertEquals(first.second.y, pts[1].second, 0.5)
        val back = c.segmentToScreen(0.0, -100000.0, 50.0, 100.0)!!
        assertEquals(back.first.y, pts[2].second, 0.5)
        assertEquals(c.worldToScreen(50.0, 100.0).x, pts[3].first, 0.05)
        assertTrue(pts.all { it.first.isFinite() && it.second.isFinite() })
    }

    @Test fun `chemin entierement derriere ou trop court, rien a dessiner`() {
        val c = camera()
        c.centerOn(0.0, 0.0)
        c.setPose(0.0, 1.0, MapCamera.DRIVE_ANCHOR)
        assertEquals(0, project(c, mapPoints(0.0, -100000.0, 10.0, -100000.0)).size)
        assertEquals(0, project(c, mapPoints(0.0, 10.0)).size)
    }
}
