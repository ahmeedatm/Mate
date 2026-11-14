package app.mate.ui.cyberpunk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun `rectangle d'une tuile en pixels du niveau max`() {
        val c = camera()
        val r = c.tileMapRect(TileKey(3, 2, 2))
        assertEquals(1024.0, r.left, 1e-6)
        assertEquals(1536.0, r.right, 1e-6)
        val coarse = c.tileMapRect(TileKey(2, 1, 1)) // même zone au niveau 2 : 1024..2048
        assertEquals(1024.0, coarse.left, 1e-6)
        assertEquals(2048.0, coarse.bottom, 1e-6)
        val edge = c.tileMapRect(TileKey(0, 6, 6)) // dernière tuile du niveau 0 : coupée au bord de la carte
        assertEquals(28672.0, edge.right, 1e-6)
    }

    @Test fun `quad et rectangle, touches ou separes`() {
        val quad = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(6.0, 10.0), Pt(4.0, 10.0))
        assertTrue(intersects(quad, MapRect(5.0, 5.0, 20.0, 20.0)))
        assertFalse(intersects(quad, MapRect(11.0, 0.0, 20.0, 5.0)))
        // Dans la boîte englobante, mais hors du trapèze (coin coupé) : séparés.
        assertFalse(intersects(quad, MapRect(8.5, 8.5, 10.0, 10.0)))
    }

    @Test fun `la matrice donne le meme point que worldToScreen, a plat comme en conduite`() {
        val c = camera()
        c.centerOn(50.0, 60.0)
        c.setScale(0.8)
        for (pose in listOf(Triple(0.0, 0.0, 0.5), Triple(37.0, 1.0, MapCamera.DRIVE_ANCHOR))) {
            c.setPose(pose.first, pose.second, pose.third)
            val m = c.matrix()
            // Matrice relative au centre, en float (comme le Canvas) : au millième de pixel près.
            val p = geo.worldToMap(-80.0, 140.0).let { Pt(it.x - c.centerX, it.y - c.centerY) }
            val w = m[6] * p.x + m[7] * p.y + m[8]
            val s = c.worldToScreen(-80.0, 140.0)
            assertEquals(s.x, (m[0] * p.x + m[1] * p.y + m[2]) / w, 1e-3)
            assertEquals(s.y, (m[3] * p.x + m[4] * p.y + m[5]) / w, 1e-3)
        }
    }

    @Test fun `en conduite le centre est au tiers bas et l'avant du vehicule pointe vers le haut`() {
        val c = camera()
        c.centerOn(0.0, 0.0)
        c.setPose(90.0, 1.0, MapCamera.DRIVE_ANCHOR)
        val me = c.worldToScreen(0.0, 0.0)
        assertEquals(640.0, me.x, 1e-6)
        assertEquals(800 * MapCamera.DRIVE_ANCHOR, me.y, 1e-6)
        // yaw 90 : la flèche du joueur pointe à l'ouest (gauche de la carte à plat) ; en conduite, l'ouest est en haut.
        val west = c.worldToScreen(-100.0, 0.0)
        assertEquals(640.0, west.x, 1e-6)
        assertTrue(west.y < me.y)
    }

    @Test fun `en conduite le loin est ecrase par la perspective, pas le pres`() {
        val c = camera()
        c.centerOn(0.0, 0.0)
        c.setPose(0.0, 1.0, MapCamera.DRIVE_ANCHOR)
        val me = c.worldToScreen(0.0, 0.0).y
        val near = me - c.worldToScreen(0.0, 50.0).y
        val far = me - c.worldToScreen(0.0, 100.0).y
        assertTrue(near > 0)
        assertTrue(far < 2 * near)
        c.setPose(0.0, 0.0, MapCamera.DRIVE_ANCHOR)
        assertEquals(2 * (me - c.worldToScreen(0.0, 50.0).y), me - c.worldToScreen(0.0, 100.0).y, 1e-6)
    }

    @Test fun `en conduite ecran vers monde puis retour, zoom autour du doigt et glisser`() {
        val c = camera()
        c.centerOn(50.0, 60.0)
        c.setPose(-123.0, 1.0, MapCamera.DRIVE_ANCHOR)
        val s = c.worldToScreen(-80.0, 140.0)
        val w = c.screenToWorld(s.x, s.y)
        assertEquals(-80.0, w.x, 1e-6)
        assertEquals(140.0, w.y, 1e-6)
        val before = c.screenToWorld(900.0, 300.0)
        c.zoomAt(1.7, 900f, 300f)
        val after = c.screenToWorld(900.0, 300.0)
        assertEquals(before.x, after.x, 1e-6)
        assertEquals(before.y, after.y, 1e-6)
        val anchor = c.screenToWorld(640.0, 800 * MapCamera.DRIVE_ANCHOR - 40)
        c.panBy(0f, 40f)
        val moved = c.worldToScreen(anchor.x, anchor.y)
        assertEquals(640.0, moved.x, 1e-6)
        assertEquals(800 * MapCamera.DRIVE_ANCHOR, moved.y, 1e-6)
    }

    @Test fun `en conduite les tuiles visibles couvrent tout l'ecran sous le fondu`() {
        val c = camera()
        c.setScale(MapCamera.DRIVE_SCALE_FAR)
        c.centerOn(geo.mapToWorld(14000.0, 9000.0).x, geo.mapToWorld(14000.0, 9000.0).y)
        c.setPose(33.0, 1.0, MapCamera.DRIVE_ANCHOR)
        val z = c.level()
        val tiles = c.visibleTiles(z).toSet()
        val span = geo.span(z) * geo.tile
        for (sx in 0..1280 step 40) for (sy in c.visibleTop.toInt()..800 step 40) {
            val w = c.screenToWorld(sx.toDouble(), sy.toDouble())
            val m = geo.worldToMap(w.x, w.y)
            assertTrue("($sx, $sy)", TileKey(z, (m.x / span).toInt(), (m.y / span).toInt()) in tiles)
        }
        // Le rectangle englobant d'un trapèze tourné en compte bien plus : seules celles qui le touchent restent.
        assertTrue(tiles.size < 40)
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

    @Test fun `le cache tient les tuiles visibles et leurs parentes a tous les zooms`() {
        val budget = MapCamera.tileCacheBytes(1280, 800, 512)
        assertEquals(42 * 512 * 512 * 2, budget)
        val c = camera()
        var s = c.minScale
        while (s <= MapCamera.MAX_SCALE) {
            c.setScale(s)
            for (p in listOf(0.0, 3333.3, 14000.7, 28672.0)) {
                c.centerOn(geo.mapToWorld(p, p * 0.7).x, geo.mapToWorld(p, p * 0.7).y)
                val z = c.level()
                val tiles = c.visibleTiles(z).size + if (z > 0) c.visibleTiles(z - 1).size else 0
                assertTrue("scale $s", tiles * 512 * 512 * 2 <= budget)
            }
            s *= 1.013
        }
    }

    @Test fun `le cache tient aussi la vue conduite, a tous les caps et vitesses`() {
        val budget = MapCamera.tileCacheBytes(1280, 800, 512)
        val c = camera()
        c.centerOn(geo.mapToWorld(14000.0, 9000.0).x, geo.mapToWorld(14000.0, 9000.0).y)
        var s = MapCamera.DRIVE_SCALE_FAR
        while (s <= MapCamera.DRIVE_SCALE_NEAR) {
            c.setScale(s)
            for (b in 0 until 360 step 7) {
                c.setPose(b.toDouble(), 1.0, MapCamera.DRIVE_ANCHOR)
                val z = c.level()
                val tiles = c.visibleTiles(z).size + if (z > 0) c.visibleTiles(z - 1).size else 0
                assertTrue("scale $s cap $b : $tiles tuiles", tiles * 512 * 512 * 2 <= budget)
            }
            s *= 1.05
        }
    }

    @Test fun `cache plafonne pour une tuile demesuree ou minuscule`() {
        assertEquals(MapCamera.MAX_CACHE_BYTES, MapCamera.tileCacheBytes(1280, 800, 4096))
        assertTrue(MapCamera.tileCacheBytes(1280, 800, 1) in 1..MapCamera.MAX_CACHE_BYTES)
    }

    @Test fun `en conduite un point loin derriere n'est pas dessine en miroir`() {
        val c = camera()
        c.centerOn(0.0, 0.0)
        c.setPose(0.0, 1.0, MapCamera.DRIVE_ANCHOR)
        assertTrue(c.worldToScreenOrNull(0.0, 50.0) != null)
        assertNull(c.worldToScreenOrNull(0.0, -100000.0))
        c.setPose(0.0, 0.0, MapCamera.FLAT_ANCHOR)
        assertTrue(c.worldToScreenOrNull(0.0, -100000.0) != null)
    }

    @Test fun `en conduite un trajet vers l'arriere est coupe avant le miroir`() {
        val c = camera()
        c.centerOn(0.0, 0.0)
        c.setPose(0.0, 1.0, MapCamera.DRIVE_ANCHOR)
        val seg = c.segmentToScreen(0.0, 0.0, 0.0, -100000.0)!!
        assertEquals(640.0, seg.first.x, 1e-6)
        assertEquals(800 * MapCamera.DRIVE_ANCHOR, seg.first.y, 1e-6)
        assertTrue("${seg.second.y}", seg.second.y > 800)
        assertTrue(seg.second.y.isFinite())
        val ahead = c.segmentToScreen(0.0, 0.0, 0.0, 100.0)!!
        assertEquals(c.worldToScreen(0.0, 100.0).y, ahead.second.y, 1e-6)
    }
}
